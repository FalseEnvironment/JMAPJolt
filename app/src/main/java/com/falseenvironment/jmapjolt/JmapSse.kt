package com.falseenvironment.jmapjolt

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Shared JMAP EventSource (SSE) plumbing, used by both the background
 * [JmapEventSourceService] and the in-activity foreground listener: resolves
 * the eventSourceUrl from the session, holds the streaming connection, and
 * classifies incoming StateChange payloads.
 */
object JmapSse {

    private const val TAG = "JmapSse"
    const val PING_SECONDS = 90

    // No read timeout on the base client; each SSE call overrides it per stream.
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun resolveEventSourceUrl(account: JMapClient.ConnectedAccount): String? =
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
                Log.e(TAG, "Failed to fetch JMAP session for ${LogRedact.email(account.email)}", e)
                null
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }
        }

    /**
     * Blocks on the SSE stream, invoking [onEvent] for each complete event.
     * Returns when the server closes the stream; throws on IO errors — the
     * caller owns the reconnect/backoff loop.
     */
    suspend fun connectAndListen(
        account: JMapClient.ConnectedAccount,
        url: String,
        onEvent: (type: String, data: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // HttpURLConnection does not reliably stream a chunked SSE body (its
        // transparent gzip buffers and readLine blocks). OkHttp streams the
        // response source line-by-line. readTimeout is set just above the
        // server ping interval so a stale half-open connection is detected
        // within seconds and the outer loop reconnects.
        val client = baseClient.newBuilder()
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
                        if (data.isNotEmpty()) onEvent(eventType, data.toString())
                        data = StringBuilder()
                        eventType = ""
                    }
                }
            }
        } finally {
            try { response.close() } catch (_: Throwable) {}
        }
    }

    /** True when the SSE payload is a StateChange touching Email/Thread/Mailbox. */
    fun isRelevantStateChange(data: String): Boolean {
        return try {
            val json = JSONObject(data)
            if (json.optString("@type") != "StateChange") return false
            val changed = json.optJSONObject("changed") ?: return false
            val keys = changed.keys()
            while (keys.hasNext()) {
                val types = changed.optJSONObject(keys.next()) ?: continue
                if (types.has("Email") || types.has("Thread") || types.has("Mailbox")) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun basicAuth(account: JMapClient.ConnectedAccount): String {
        val credentials = "${account.email}:${account.password}"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
}
