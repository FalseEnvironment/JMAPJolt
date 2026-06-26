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

internal fun MainActivity.sendUnifiedPushTestNotification() {
    lifecycleScope.launch {
        val endpoint = withContext(Dispatchers.IO) {
            var attempt = 0
            var found: String? = null
            while (attempt < 24) {
                found = getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(KEY_LAST_UP_ENDPOINT, null)
                    ?.takeIf { it.isNotBlank() }
                if (found != null) break
                Thread.sleep(500)
                attempt++
            }
            found
        }
        if (endpoint == null) {
            showThemedSnackbar(getString(R.string.settings_unifiedpush_waiting_endpoint))
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
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
        showThemedSnackbar(
            when {
                result.success -> getString(R.string.settings_unifiedpush_test_sent)
                result.httpCode != null -> getString(R.string.settings_unifiedpush_error_with_code, result.httpCode)
                else -> getString(R.string.settings_unifiedpush_error)
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Constants (moved from MainActivity companion to allow cross-file access)
// ---------------------------------------------------------------------------
internal const val KEY_UP_ENABLED = "up_enabled"
internal const val KEY_UP_AUTO_TOPIC = "up_auto_topic"
internal const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"

internal data class UnifiedPushTestResult(val success: Boolean, val httpCode: Int?)
