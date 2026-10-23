package com.falseenvironment.jmapjolt

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Makes sure a freshly created PushSubscription actually gets verified.
 *
 * Stalwart only sends a `PushVerification` while processing a subscription change, and it
 * throttles those to one per account every `pushVerifyTimeout` (default 1 minute). A
 * subscription created while that throttle is warm is skipped silently and then never
 * retried, because nothing else mutates the subscription: the app looks registered but no
 * mail push is ever delivered. Rotating the UnifiedPush endpoint and recreating it
 * immediately after the previous one makes this easy to hit.
 *
 * Each attempt performs a no-op `expires` refresh, which is a `PushSubscription/set`
 * mutation and therefore wakes the server's push manager again. Once the verification
 * arrives, [PushSubscriptionManager.verify] records it and the next attempt exits.
 */
class PushVerificationRetryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val subscriptionId = inputData.getString(KEY_SUBSCRIPTION_ID) ?: return Result.success()
        val account = BackgroundEmailSyncReceiver.readCurrentAccount(applicationContext)
            ?: return Result.success()

        if (PushSubscriptionManager.isVerified(applicationContext, account.email, subscriptionId)) {
            return Result.success()
        }
        // Endpoint rotated or subscription recreated: this worker refers to a dead id.
        if (!PushSubscriptionManager.isCurrent(applicationContext, account.email, subscriptionId)) {
            return Result.success()
        }

        val renewed = JMapClient(applicationContext)
            .renewPushSubscription(account, subscriptionId)
        if (!renewed) {
            // Expired/destroyed server-side: rebuild it; the new create schedules its own retry.
            PushSubscriptionManager.ensure(applicationContext)
            return Result.success()
        }
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.w(TAG, "PushSubscription $subscriptionId still unverified after $MAX_ATTEMPTS nudges")
            // Keep the subscription (a later launch retries it) but stop pretending the
            // push is active: Settings now explains that the server never confirmed it.
            PushSubscriptionManager.markVerificationTimedOut(applicationContext, account.email)
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        private const val TAG = "PushVerifyRetry"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        /**
         * Stalwart verifies one subscription per account per minute and queues new ones
         * behind leftovers. A handful of nudges was not enough when another unverified
         * record was ahead of ours, so keep trying for roughly three quarters of an hour.
         */
        private const val MAX_ATTEMPTS = 10
        private const val INITIAL_DELAY_SECONDS = 75L
        private const val RETRY_DELAY_SECONDS = 60L

        fun schedule(context: Context, subscriptionId: String) {
            val request = OneTimeWorkRequestBuilder<PushVerificationRetryWorker>()
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_SUBSCRIPTION_ID to subscriptionId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("push-verify-$subscriptionId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
