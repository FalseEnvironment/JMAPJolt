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
import androidx.annotation.RequiresApi
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
    // Set when the system revokes the dataSync FGS budget: the service must then stay
    // down instead of being restarted by onTaskRemoved, which would only time out again.
    @Volatile
    private var stoppedByTimeout = false
    // Cancel the periodic WorkManager fallback the first time SSE proves it can
    // reach the server, so the two sync paths don't run doubled up forever —
    // EmailSyncWorker.schedule() can be triggered independently of UnifiedPush
    // (see tryStartForeground/handleForegroundTimeout) and previously had no
    // corresponding cancel once SSE recovered.
    @Volatile
    private var fallbackWorkerCancelled = false

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

    // Android 14+ gives dataSync foreground services a rolling time budget (~6h per 24h).
    // When it runs out the system calls onTimeout() and expects the service to stop right
    // away; failing to do so crashes the process with
    // ForegroundServiceDidNotStopInTimeException. The SSE loop is unbounded by design, so
    // it always reaches this point eventually: hand over to the periodic WorkManager
    // fallback so mail keeps syncing until the budget resets.
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout()
    }

    private fun handleForegroundTimeout() {
        if (stoppedByTimeout) return
        stoppedByTimeout = true
        Log.w(TAG, "dataSync FGS budget exhausted — stopping SSE, falling back to periodic sync")
        try {
            EmailSyncWorker.schedule(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule periodic sync fallback", e)
        }
        stopSelf()
    }

    private fun tryStartForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification())
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground refused (FGS time-limit likely exhausted)", e)
        // Same budget exhaustion as onTimeout(), just observed on the start path: keep the
        // service down and let the periodic worker carry the sync.
        stoppedByTimeout = true
        try {
            EmailSyncWorker.schedule(this)
        } catch (scheduleError: Exception) {
            Log.e(TAG, "Failed to schedule periodic sync fallback", scheduleError)
        }
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
        var consecutiveFailures = 0
        try {
            while (true) {
                try {
                    val sseUrl = JmapSse.resolveEventSourceUrl(account)
                    if (sseUrl == null) {
                        Log.w(TAG, "No eventSourceUrl for ${LogRedact.email(account.email)} — retrying in ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                        continue
                    }
                    backoffMs = BACKOFF_INITIAL_MS
                    Log.d(TAG, "Connecting SSE for ${LogRedact.email(account.email)} (host=${LogRedact.host(sseUrl)})")
                    if (fallbackWorkerCancelled.not()) {
                        fallbackWorkerCancelled = true
                        EmailSyncWorker.cancel(this@JmapEventSourceService)
                    }
                    JmapSse.connectAndListen(account, sseUrl) { type, data ->
                        handleEvent(type, data, account)
                    }
                    backoffMs = BACKOFF_INITIAL_MS
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "SSE error for ${LogRedact.email(account.email)}, reconnecting in ${backoffMs}ms", e)
                    consecutiveFailures++
                    if (consecutiveFailures >= SSE_FAILURES_BEFORE_FALLBACK && fallbackWorkerCancelled) {
                        Log.w(TAG, "SSE unstable for ${LogRedact.email(account.email)} — re-enabling periodic fallback")
                        fallbackWorkerCancelled = false
                        EmailSyncWorker.schedule(this@JmapEventSourceService)
                    }
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
        Log.d(TAG, "StateChange for ${LogRedact.email(account.email)} — triggering sync")
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
        if (isEnabled(this) && !stoppedByTimeout) {
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
        private const val SSE_FAILURES_BEFORE_FALLBACK = 3
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
