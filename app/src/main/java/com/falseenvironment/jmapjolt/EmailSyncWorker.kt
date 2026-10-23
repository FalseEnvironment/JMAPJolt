package com.falseenvironment.jmapjolt

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class EmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * Needed for the expedited flavour enqueued by UnifiedPush: below Android 12
     * WorkManager runs it as a foreground service, which the platform only allows with
     * a notification and a declared type.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = BackgroundEmailSyncReceiver.buildSyncInProgressNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                BackgroundEmailSyncReceiver.SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(BackgroundEmailSyncReceiver.SYNC_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val emailIds = inputData.getNullableStringArray(KEY_EMAIL_IDS)
            if (!emailIds.isNullOrEmpty()) {
                // emailpush payload: notify exactly the delivered ids, wherever they landed.
                BackgroundEmailSyncReceiver.notifyForIds(
                    context = applicationContext,
                    emailIds = emailIds.filterNotNull(),
                    jmapAccountId = inputData.getString(KEY_JMAP_ACCOUNT_ID)
                )
                return Result.success()
            }
            val accountEmail = inputData.getString(KEY_ACCOUNT_EMAIL)
            if (accountEmail != null) {
                val account = BackgroundEmailSyncReceiver.readAllAccounts(applicationContext)
                    .find { it.email == accountEmail }
                if (account != null) {
                    BackgroundEmailSyncReceiver.fetchAndNotify(applicationContext, account)
                } else {
                    Log.w(TAG, "Account $accountEmail not found, syncing all")
                    BackgroundEmailSyncReceiver.fetchAndNotify(applicationContext)
                }
            } else {
                BackgroundEmailSyncReceiver.fetchAndNotify(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Email sync worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "EmailSyncWorker"
        private const val WORK_NAME = "email_sync_periodic"
        /**
         * Removed: the app is UnifiedPush-only, there is no 15-minute periodic
         * fallback. Push messages and SSE events enqueue one-time expedited
         * [EmailSyncWorker] runs instead. `cancel` still exists to stop legacy
         * periodic jobs scheduled by older builds.
         */
        const val KEY_ACCOUNT_EMAIL = "account_email"
        /** Email ids carried by an emailpush push (see UnifiedPushService.parseEmailPush). */
        const val KEY_EMAIL_IDS = "email_ids"
        /** JMAP primary account id the push was generated for. */
        const val KEY_JMAP_ACCOUNT_ID = "jmap_account_id"

        /**
         * Kept after the 15-minute fallback was removed: WorkManager persists jobs
         * across reinstalls and app updates, so this is both the toggle-off path
         * ("no background sync when UnifiedPush is off") and the migration path that
         * cancels periodic jobs scheduled by older builds.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic email sync (UnifiedPush-only mode)")
        }
    }
}
