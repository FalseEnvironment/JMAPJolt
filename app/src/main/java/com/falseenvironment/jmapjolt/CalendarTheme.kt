package com.falseenvironment.jmapjolt

import android.content.Context
import androidx.core.graphics.toColorInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standalone theme reader for the calendar screens, mirroring the palette logic in
 * ThemeHelper so the calendar honours the app's main + accent colours without depending
 * on MainActivity's view-bound extensions.
 */
object CalendarTheme {

    data class Palette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val onAccent: Int,
        val isDark: Boolean
    )

    fun palette(context: Context): Palette {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getString("app_theme", "gray") ?: "gray"
        val storedAccent = prefs.getString(MainActivity.KEY_ACCENT_COLOR, "#3D8BFD") ?: "#3D8BFD"
        val accentHex = MainActivity.LEGACY_ACCENT_MAP[storedAccent.uppercase()] ?: storedAccent

        val colors = when (theme) {
            "light"  -> arrayOf("#F6F6F8", "#FFFFFF", "#1B1B1F", "#5F5F66")
            "oled"   -> arrayOf("#000000", "#0B0B0D", "#ECECF1", "#90909A")
            "violet" -> arrayOf("#160E24", "#1E1430", "#ECECF1", "#9B7DC8")
            else     -> arrayOf("#212126", "#2A2A30", "#ECECF1", "#90909A")
        }
        val accent = runCatching { accentHex.toColorInt() }.getOrDefault("#3D8BFD".toColorInt())
        return Palette(
            background = colors[0].toColorInt(),
            surface = colors[1].toColorInt(),
            text = colors[2].toColorInt(),
            secondaryText = colors[3].toColorInt(),
            accent = accent,
            onAccent = onAccentFor(accent),
            isDark = theme != "light"
        )
    }

    /** White or near-black text for legibility on the accent colour. */
    private fun onAccentFor(accent: Int): Int {
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.6) "#1B1B1F".toColorInt() else 0xFFFFFFFF.toInt()
    }
}

/** Reads the active JMAP account from secure storage for calendar sync. */
object CalendarAccount {
    fun current(context: Context): JMapClient.ConnectedAccount? {
        val raw = SecureStorage.prefs(context).getString("accounts_json", null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val list = root.optJSONArray("accounts") ?: JSONArray()
            if (list.length() == 0) return null
            val current = root.optString("current", "")
            var chosen: JSONObject? = null
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (current.isNotBlank() && item.optString("email").equals(current, true)) {
                    chosen = item; break
                }
                if (chosen == null) chosen = item
            }
            chosen?.let {
                JMapClient.ConnectedAccount(
                    email = it.optString("email"),
                    password = it.optString("password"),
                    sessionUrl = it.optString("sessionUrl"),
                    apiUrl = it.optString("apiUrl"),
                    accountId = it.optString("accountId")
                )
            }
        }.getOrNull()
    }
}
