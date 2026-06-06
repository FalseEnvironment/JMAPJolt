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
import android.util.Base64
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class JmapEventSourceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Tracks emails of accounts that already have a running loop in this instance.
    private val activeLoops = ConcurrentHashMap.newKeySet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch { startLoopsForAllAccounts() }
        return START_STICKY
    }

    private suspend fun startLoopsForAllAccounts() {
        val accounts = BackgroundEmailSyncReceiver.readAllAccounts(this@JmapEventSourceService)
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
                    val sseUrl = resolveEventSourceUrl(account)
                    if (sseUrl == null) {
                        Log.w(TAG, "No eventSourceUrl for ${account.email} — loop stopped")
                        break
                    }
                    Log.d(TAG, "Connecting SSE for ${account.email}: $sseUrl")
                    connectAndListen(account, sseUrl)
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

    private suspend fun resolveEventSourceUrl(account: JMapClient.ConnectedAccount): String? =
        withContext(Dispatchers.IO) {
            val conn = URL(account.sessionUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", basicAuth(account))
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val body = conn.inputStream.bufferedReader().readText()
                val template = JSONObject(body).optString("eventSourceUrl").takeIf { it.isNotBlank() }
                    ?: return@withContext null
                template
                    .replace("{types}", "Email")
                    .replace("{+types}", "Email")
                    .replace("{closeafter}", "no")
                    .replace("{ping}", PING_SECONDS.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to fetch JMAP session for ${account.email}", e)
                null
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }

    private suspend fun connectAndListen(account: JMapClient.ConnectedAccount, url: String) =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", basicAuth(account))
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connectTimeout = 15_000
            conn.readTimeout = (PING_SECONDS * 2500)
            try {
                val reader = conn.inputStream.bufferedReader()
                var data = StringBuilder()
                var eventType = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                        line.startsWith("data:")  -> data.append(line.removePrefix("data:").trim())
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) handleEvent(eventType, data.toString(), account)
                            data = StringBuilder()
                            eventType = ""
                        }
                    }
                }
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }

    private fun handleEvent(type: String, data: String, account: JMapClient.ConnectedAccount) {
        try {
            val json = JSONObject(data)
            if (json.optString("@type") != "StateChange") return
            val changed = json.optJSONObject("changed") ?: return
            val keys = changed.keys()
            while (keys.hasNext()) {
                val types = changed.optJSONObject(keys.next()) ?: continue
                if (types.has("Email") || types.has("Thread") || types.has("Mailbox")) {
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
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event (type=$type)", e)
        }
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
            val restart = PendingIntent.getService(
                this, 1,
                Intent(this, JmapEventSourceService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 5_000L, restart)
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
        private const val PING_SECONDS = 90
        private const val BACKOFF_INITIAL_MS = 5_000L
        private const val BACKOFF_MAX_MS = 300_000L
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

        private fun basicAuth(account: JMapClient.ConnectedAccount): String {
            val credentials = "${account.email}:${account.password}"
            return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
    }
}
