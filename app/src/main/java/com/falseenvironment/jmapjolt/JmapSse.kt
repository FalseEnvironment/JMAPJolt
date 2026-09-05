package com.falseenvironment.jmapjolt

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
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

    private const val SESSION_TIMEOUT_SECONDS = 10L

    suspend fun resolveEventSourceUrl(account: JMapClient.ConnectedAccount): String? =
        withContext(Dispatchers.IO) {
            // The session fetch carries Basic auth, so it runs on the no-redirect
            // client: a 30x must not replay the credentials somewhere else.
            val http = AppHttp.noRedirects.newBuilder()
                .readTimeout(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(account.sessionUrl)
                .header("Authorization", basicAuth(account))
                .get()
                .build()
            try {
                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string() ?: return@withContext null
                }
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
        val client = AppHttp.noRedirects.newBuilder()
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
