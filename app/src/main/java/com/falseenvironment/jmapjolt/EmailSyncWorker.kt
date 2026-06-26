package com.falseenvironment.jmapjolt

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class EmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
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
            Log.e(TAG, "Email sync worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "EmailSyncWorker"
        private const val WORK_NAME = "email_sync_periodic"
        const val KEY_ACCOUNT_EMAIL = "account_email"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            Log.d(TAG, "Scheduled periodic email sync (15 min fallback)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic email sync")
        }
    }
}
