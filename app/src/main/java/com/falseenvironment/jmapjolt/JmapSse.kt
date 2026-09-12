package com.falseenvironment.jmapjolt

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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
    private const val MAX_SESSION_REDIRECTS = 3

    suspend fun resolveEventSourceUrl(account: JMapClient.ConnectedAccount): String? =
        withContext(Dispatchers.IO) {
            // The session fetch carries Basic auth, so it runs on the no-redirect
            // client: a 30x must not replay the credentials somewhere else. The
            // redirect is followed by hand below, only towards the same origin.
            val http = AppHttp.noRedirects.newBuilder()
                .readTimeout(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            // The stored sessionUrl is whatever candidate the JMAP library accepted,
            // and that can be the bare origin it resolved through /.well-known: a raw
            // GET on it answers with the site root, not the session object. Probe the
            // usual session paths instead of giving up on the first non-session body.
            for (candidate in sessionProbeUrls(account.sessionUrl)) {
                val session = fetchSessionJson(http, account, candidate) ?: continue
                val template = session.optString("eventSourceUrl").takeIf { it.isNotBlank() }
                if (template == null) {
                    Log.w(TAG, "Session at ${LogRedact.host(candidate)} carries no eventSourceUrl")
                    continue
                }
                val resolved = template
                    .replace("{types}", "Email")
                    .replace("{+types}", "Email")
                    .replace("{closeafter}", "no")
                    .replace("{ping}", PING_SECONDS.toString())
                if (!JMapClient.isTrustedServerUrl(resolved, account.sessionUrl)) {
                    Log.w(TAG, "Refusing eventSourceUrl outside session origin")
                    return@withContext null
                }
                return@withContext resolved
            }
            null
        }

    /** The stored session URL first, then the standard JMAP session paths on the same origin. */
    internal fun sessionProbeUrls(sessionUrl: String): List<String> {
        val base = sessionUrl.toHttpUrlOrNull() ?: return listOf(sessionUrl)
        val paths = listOf("/.well-known/jmap", "/jmap/session", "/jmap")
        return (listOf(sessionUrl) + paths.map { path ->
            base.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
        }).distinct()
    }

    /**
     * GETs one session candidate and returns its JSON body, following same-origin
     * redirects by hand. Returns null — with the reason logged — when the candidate
     * is not a JMAP session.
     */
    private fun fetchSessionJson(
        http: OkHttpClient,
        account: JMapClient.ConnectedAccount,
        url: String
    ): JSONObject? {
        var current = url
        var hops = 0
        while (hops <= MAX_SESSION_REDIRECTS) {
            val request = Request.Builder()
                .url(current)
                .header("Authorization", basicAuth(account))
                .header("Accept", "application/json")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location")
                        if (location == null) {
                            Log.w(TAG, "Session ${LogRedact.host(current)} redirected without Location")
                            return null
                        }
                        val next = current.toHttpUrlOrNull()?.resolve(location)?.toString()
                        if (next == null || !JMapClient.isTrustedServerUrl(next, account.sessionUrl)) {
                            Log.w(TAG, "Refusing session redirect outside session origin")
                            return null
                        }
                        current = next
                        hops++
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Session ${LogRedact.host(current)} answered HTTP ${response.code}")
                        return null
                    }
                    val text = response.body?.string()
                    if (text == null) {
                        Log.w(TAG, "Session ${LogRedact.host(current)} answered with an empty body")
                        return null
                    }
                    val json = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        Log.w(TAG, "Session ${LogRedact.host(current)} answered with non-JSON body")
                        return null
                    }
                    // The site root answers 200 with JSON on some hosts: only a body that
                    // carries the session fields is a session.
                    if (!json.has("eventSourceUrl") && !json.has("apiUrl")) {
                        Log.w(TAG, "Body at ${LogRedact.host(current)} is not a JMAP session")
                        return null
                    }
                    return json
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to fetch JMAP session for ${LogRedact.email(account.email)}", e)
                return null
            }
        }
        Log.w(TAG, "Session ${LogRedact.host(url)} exceeded $MAX_SESSION_REDIRECTS redirects")
        return null
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
