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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class JmapEventSourceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // No read timeout on the base client; each SSE call overrides it per stream.
    private val sharedSseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
    // Tracks emails of accounts that already have a running loop in this instance.
    private val activeLoops = ConcurrentHashMap.newKeySet<String>()

    // startForeground() must run as early as possible: onCreate fires before
    // onStartCommand, and a busy main thread at app launch can otherwise push the
    // call past the system deadline (ForegroundServiceDidNotStartInTimeException).
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch { startLoopsForAllAccounts() }
        return START_STICKY
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
                    val sseUrl = resolveEventSourceUrl(account)
                    if (sseUrl == null) {
                        Log.w(TAG, "No eventSourceUrl for ${account.email} — retrying in ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, BACKOFF_MAX_MS)
                        continue
                    }
                    backoffMs = BACKOFF_INITIAL_MS
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
                val resolved = template
                    .replace("{types}", "Email")
                    .replace("{+types}", "Email")
                    .replace("{closeafter}", "no")
                    .replace("{ping}", PING_SECONDS.toString())
                if (!JMapClient.isTrustedServerUrl(resolved, account.sessionUrl)) {
                    Log.w(TAG, "Refusing eventSourceUrl outside session origin")
                    return@withContext null
                }
                resolved
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to fetch JMAP session for ${account.email}", e)
                null
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }

    private suspend fun connectAndListen(account: JMapClient.ConnectedAccount, url: String) =
        withContext(Dispatchers.IO) {
            // HttpURLConnection does not reliably stream a chunked SSE body (its
            // transparent gzip buffers and readLine blocks). OkHttp streams the
            // response source line-by-line. readTimeout is set just above the
            // server ping interval so a stale half-open connection is detected
            // within seconds and the outer loop reconnects.
            val client = sharedSseClient.newBuilder()
                .readTimeout((PING_SECONDS + 30).toLong(), TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", basicAuth(account))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity")
                .build()
            val call = client.newCall(request)
            val response = call.execute()
            try {
                if (!response.isSuccessful) {
                    throw java.io.IOException("SSE HTTP ${response.code}")
                }
                val source = response.body?.source()
                    ?: throw java.io.IOException("SSE empty body")
                var data = StringBuilder()
                var eventType = ""
                while (true) {
                    val line = source.readUtf8Line() ?: break
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
                try { response.close() } catch (_: Throwable) {}
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
        private const val PING_SECONDS = 90
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

        private fun basicAuth(account: JMapClient.ConnectedAccount): String {
            val credentials = "${account.email}:${account.password}"
            return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
    }
}
