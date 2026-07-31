package com.falseenvironment.jmapjolt

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class JmapEventSourceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Tracks emails of accounts that already have a running loop in this instance.
    private val activeLoops = ConcurrentHashMap.newKeySet<String>()

    // startForeground() must run as early as possible: onCreate fires before
    // onStartCommand, and a busy main thread at app launch can otherwise push the
    // call past the system deadline (ForegroundServiceDidNotStartInTimeException).
    // On Android 15+, the dataSync FGS type has a rolling time budget; once it's
    // exhausted the system kills the service and refuses restart, throwing
    // ForegroundServiceStartNotAllowedException here instead of just failing quietly.
    override fun onCreate() {
        super.onCreate()
        if (!tryStartForeground()) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!tryStartForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        serviceScope.launch { startLoopsForAllAccounts() }
        return START_STICKY
    }

    private fun tryStartForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification())
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground refused (FGS time-limit likely exhausted)", e)
        false
    }

    private suspend fun startLoopsForAllAccounts() {
        val accounts = try {
            BackgroundEmailSyncReceiver.readAllAccounts(this@JmapEventSourceService)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read accounts (keystore may be corrupted)", e)
            emptyList()
        }
        if (accounts.isEmpty()) {
            Log.w(TAG, "No accounts — stopping SSE service")
            stopSelf()
            return
        }
        accounts.forEach { account ->
            if (activeLoops.add(account.email)) {
                serviceScope.launch { connectLoop(account) }
            }
        }
    }

    private suspend fun connectLoop(account: JMapClient.ConnectedAccount) {
        var backoffMs = BACKOFF_INITIAL_MS
        try {
            while (true) {
                try {
                    val sseUrl = JmapSse.resolveEventSourceUrl(account)
                    if (sseUrl == null) {
                        Log.w(TAG, "No eventSourceUrl for ${account.email} — retrying in ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                        continue
                    }
                    backoffMs = BACKOFF_INITIAL_MS
                    Log.d(TAG, "Connecting SSE for ${account.email}: $sseUrl")
                    JmapSse.connectAndListen(account, sseUrl) { type, data ->
                        handleEvent(type, data, account)
                    }
                    backoffMs = BACKOFF_INITIAL_MS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "SSE error for ${account.email}, reconnecting in ${backoffMs}ms", e)
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                }
            }
        } finally {
            activeLoops.remove(account.email)
        }
    }

    private fun handleEvent(type: String, data: String, account: JMapClient.ConnectedAccount) {
        if (!JmapSse.isRelevantStateChange(data)) return
        Log.d(TAG, "StateChange for ${account.email} — triggering sync")
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setInputData(workDataOf(EmailSyncWorker.KEY_ACCOUNT_EMAIL to account.email))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
        sendBroadcast(
            Intent(UnifiedPushService.ACTION_PUSH_MESSAGE_RECEIVED)
                .setPackage(packageName)
        )
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sync status", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                    }
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sse_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isEnabled(this)) {
            val restartIntent = Intent(this, JmapEventSourceService::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            // getService would restart without foreground allowance and crash with
            // ForegroundServiceDidNotStartInTimeException on O+.
            val restart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 1, restartIntent, flags)
            } else {
                PendingIntent.getService(this, 1, restartIntent, flags)
            }
            val alarmManager = getSystemService(AlarmManager::class.java)
            val triggerAt = SystemClock.elapsedRealtime() + 5_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restart)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restart)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JmapEventSourceService"
        private const val NOTIFICATION_ID = 4004
        private const val CHANNEL_ID = "background_email_sync_status"
        private const val BACKOFF_INITIAL_MS = 5_000L
        private const val BACKOFF_MAX_MS = 60_000L
        const val KEY_SSE_ENABLED = "sse_enabled"
        private const val PREFS_NAME = "jmap_service_prefs"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JmapEventSourceService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JmapEventSourceService::class.java))
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SSE_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SSE_ENABLED, enabled).apply()
        }
    }
}
