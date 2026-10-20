package com.falseenvironment.jmapjolt

import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal fun MainActivity.loadUnifiedPushPreferences() {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    unifiedPushSwitch.isChecked = prefs.getBoolean(KEY_UP_ENABLED, false)
    sseSwitch.isChecked = JmapEventSourceService.isEnabled(this)
}

internal fun MainActivity.saveUnifiedPushEnabled(enabled: Boolean) {
    getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_UP_ENABLED, enabled)
        .apply()
}

internal fun MainActivity.normalizeUnifiedPushLink(value: String): String? {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)) trimmed else "https://$trimmed"
    return try {
        val url = URL(withScheme)
        if (url.protocol != "https" || url.host.isNullOrBlank()) return null
        val topic = url.path.trim('/').ifBlank { getOrCreateUnifiedPushTopic() }
        URL("https", url.host, url.port, "/$topic").toString()
    } catch (_: Throwable) {
        null
    }
}

internal fun MainActivity.getOrCreateUnifiedPushTopic(): String {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_UP_AUTO_TOPIC, null)
    if (!saved.isNullOrBlank()) return saved
    val generated = "jmapjolt-${UUID.randomUUID().toString().take(8)}"
    prefs.edit().putString(KEY_UP_AUTO_TOPIC, generated).apply()
    return generated
}

/**
 * Registers/refreshes the JMAP PushSubscription behind UnifiedPush. Called on app start,
 * after sign-in, on account switch and when the distributor rotates the endpoint.
 *
 * [register] re-announces the app to the saved distributor, which is recommended on every
 * start so a reinstalled/updated distributor is picked up. The connector persists the
 * registration token, so this keeps the same endpoint/topic instead of rotating it.
 */
internal fun MainActivity.ensurePushSubscription(register: Boolean = false) {
    if (!PushSubscriptionManager.isEnabled(this)) return
    if (register) {
        try {
            val distributors = UnifiedPush.getDistributors(this)
            val saved = UnifiedPush.getSavedDistributor(this)
            val target = saved?.takeIf { distributors.contains(it) }
                ?: distributors.firstOrNull { it.contains("ntfy", ignoreCase = true) }
                ?: distributors.firstOrNull()
            if (target != null) {
                UnifiedPush.saveDistributor(this, target)
                getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_UP_REGISTER_REQUESTED_AT, System.currentTimeMillis())
                    .apply()
                UnifiedPush.register(this, INSTANCE_DEFAULT, packageName)
            }
        } catch (e: Throwable) {
            Log.w(MainActivity.TAG, "UnifiedPush re-registration on start failed", e)
        }
    }
    lifecycleScope.launch {
        PushSubscriptionManager.ensure(applicationContext)
    }
}

internal fun MainActivity.sendUnifiedPushTestNotification() {
    lifecycleScope.launch {
        val endpoint = withContext(Dispatchers.IO) { awaitTestEndpoint() }
        if (endpoint == null) {
            showThemedSnackbar(getString(R.string.settings_unifiedpush_waiting_endpoint))
            return@launch
        }
        // The test push travels unencrypted, so onMessage would otherwise drop it:
        // open the short window that lets exactly this payload be shown.
        UnifiedPushService.markPendingTest(this@sendUnifiedPushTestNotification)
        val first = withContext(Dispatchers.IO) { postTestPush(endpoint) }
        // Honest UX: HTTP 200 only means the POST reached the distributor — the banner
        // shows when the distributor's (possibly reconnecting) WebSocket delivers it.
        showThemedSnackbar(getString(R.string.settings_unifiedpush_test_sending))
        // TLS handshakes to the endpoint are frequently stalled or refused for the first
        // seconds (and the distributor's WebSocket can be down at the same time): repeat
        // the (idempotent) test push while the window is open, failing fast per attempt,
        // so the banner appears as soon as any attempt succeeds instead of ~10s later.
        var success = first.success
        var failure: UnifiedPushTestResult = first
        Log.d(MainActivity.TAG, "UnifiedPush test POST ${if (first.success) "accepted" else "failed"} (http=${first.httpCode ?: "no-connection"})")
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (attempt < 9) {
                Thread.sleep(3500)
                if (!UnifiedPushService.isTestPending(this@sendUnifiedPushTestNotification)) {
                    return@withContext // banner shown — done
                }
                val retry = postTestPush(endpoint)
                if (retry.success) {
                    success = true
                } else if (failure.httpCode == null && retry.httpCode != null) {
                    failure = retry // keep the most informative failure for the final message
                } else {
                    attempt++
                }
                Log.d(MainActivity.TAG, "UnifiedPush test retry ${attempt + 1}/9 (http=${retry.httpCode ?: "no-connection"})")
            }
        }
        showThemedSnackbar(
            when {
                success -> getString(R.string.settings_unifiedpush_test_sent)
                failure.httpCode != null -> getString(R.string.settings_unifiedpush_error_with_code, failure.httpCode)
                else -> getString(R.string.settings_unifiedpush_error)
            }
        )
    }
}

/**
 * Returns the endpoint the test push must target. A registration that just started may
 * rotate the topic, so wait briefly for the fresh NEW_ENDPOINT; otherwise use the stored
 * one immediately (the connector keeps its token, so repeated registrations keep the topic).
 */
private fun MainActivity.awaitTestEndpoint(): String? {
    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val requestedAt = prefs.getLong(KEY_UP_REGISTER_REQUESTED_AT, 0L)
    var attempt = 0
    while (attempt < 30) {
        val endpoint = prefs.getString(KEY_LAST_UP_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
        val updatedAt = prefs.getLong(KEY_UP_ENDPOINT_UPDATED_AT, 0L)
        if (endpoint != null) {
            // Freshly broadcast endpoint: use it. Otherwise give the distributor ~3s in
            // case it is still about to rotate, then keep the current (still valid) one.
            if (updatedAt >= requestedAt || attempt >= 6) return endpoint
        }
        Thread.sleep(500)
        attempt++
    }
    return prefs.getString(KEY_LAST_UP_ENDPOINT, null)?.takeIf { it.isNotBlank() }
}

private fun MainActivity.postTestPush(endpoint: String): UnifiedPushTestResult {
    return try {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        // Short timeouts: a stalled handshake must free the coroutine for the retry
        // loop quickly instead of burning the user's 10-second perceived wait.
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.outputStream.use {
            it.write(getString(R.string.settings_unifiedpush_test_body).toByteArray())
        }
        val code = connection.responseCode
        connection.disconnect()
        UnifiedPushTestResult(code in 200..299, code)
    } catch (e: Throwable) {
        Log.e(MainActivity.TAG, "UnifiedPush test notification failed", e)
        UnifiedPushTestResult(false, null)
    }
}

// ---------------------------------------------------------------------------
// Constants (moved from MainActivity companion to allow cross-file access)
// ---------------------------------------------------------------------------
internal const val KEY_UP_ENABLED = "up_enabled"
internal const val KEY_UP_AUTO_TOPIC = "up_auto_topic"
internal const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
/** Wall-clock of the last NEW_ENDPOINT broadcast; lets the test wait out a topic rotation. */
internal const val KEY_UP_ENDPOINT_UPDATED_AT = "up_endpoint_updated_at"
/** Wall-clock of the last UnifiedPush.register call, to reject endpoints older than it. */
internal const val KEY_UP_REGISTER_REQUESTED_AT = "up_register_requested_at"

internal data class UnifiedPushTestResult(val success: Boolean, val httpCode: Int?)
