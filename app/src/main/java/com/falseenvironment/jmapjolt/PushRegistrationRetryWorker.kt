package com.falseenvironment.jmapjolt

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Retries a PushSubscription create that the server refused (network hiccup, a throttled
 * push manager, a rejected create). Before this the failure was a silent dead end: the
 * toggle stayed on, no verification was ever attempted again, and no mail push arrived
 * until the user happened to reopen the app.
 *
 * The verification handshake that follows a successful create has its own retry in
 * [PushVerificationRetryWorker]; this worker only closes the "create never happened" hole.
 */
class PushRegistrationRetryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PushSubscriptionManager.isEnabled(applicationContext)) return Result.success()

        PushSubscriptionManager.ensure(applicationContext)
        return when (PushSubscriptionManager.status(applicationContext)?.state) {
            // Created (and possibly still verifying) or already running: done here.
            PushSubscriptionManager.PushState.ACTIVE,
            PushSubscriptionManager.PushState.PENDING -> Result.success()

            else -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                Log.w(TAG, "PushSubscription still not registered after $MAX_ATTEMPTS attempts")
                Result.success()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "PushRegisterRetry"
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_DELAY_MINUTES = 2L
        private const val RETRY_DELAY_MINUTES = 5L

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val WORK_NAME = "push-register-retry"
    }
}
