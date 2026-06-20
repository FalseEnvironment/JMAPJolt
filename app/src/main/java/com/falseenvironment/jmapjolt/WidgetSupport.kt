package com.falseenvironment.jmapjolt

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject

/**
 * Shared helpers for the inbox home-screen widget: theme palette resolution,
 * accent color, per-account color, the saved account list, and per-widget config.
 *
 * Kept self-contained (not tied to MainActivity) so the widget process can run
 * without the Activity being alive.
 */
object WidgetSupport {

    const val UNIFIED = "__unified__"

    private const val WIDGET_PREFS = "widget_prefs"
    private const val KEY_ACCOUNTS_JSON = "accounts_json"

    /** Palette: [bg, header, text, secondaryText] mirroring ThemeHelper.applyTheme. */
    fun palette(theme: String): IntArray {
        val hex = when (theme) {
            "light" -> arrayOf("#F6F6F8", "#FFFFFF", "#1B1B1F", "#5F5F66")
            "oled" -> arrayOf("#000000", "#0B0B0D", "#ECECF1", "#90909A")
            "violet" -> arrayOf("#160E24", "#1E1430", "#ECECF1", "#9B7DC8")
            else -> arrayOf("#212126", "#2A2A30", "#ECECF1", "#90909A")
        }
        return IntArray(4) { hex[it].toColorInt() }
    }

    fun currentTheme(context: Context): String =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("app_theme", "gray") ?: "gray"

    fun accentColor(context: Context): Int {
        val stored = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_ACCENT_COLOR, "#3D8BFD") ?: "#3D8BFD"
        val migrated = MainActivity.LEGACY_ACCENT_MAP[stored.uppercase()] ?: stored
        return runCatching { migrated.toColorInt() }.getOrDefault("#3D8BFD".toColorInt())
    }

    /** Same rule as MainActivity.getAccountColor: saved override, else stable hue from email. */
    fun accountColor(context: Context, email: String): Int {
        val saved = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("account_color_$email", Int.MIN_VALUE)
        if (saved != Int.MIN_VALUE) return saved
        val hue = kotlin.math.abs(email.hashCode() % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
    }

    /** Account emails saved in encrypted storage, in stored order. */
    fun savedAccountEmails(context: Context): List<String> {
        val raw = SecureStorage.prefs(context).getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        val accounts = runCatching { JSONObject(raw).optJSONArray("accounts") }.getOrNull() ?: return emptyList()
        return (0 until accounts.length()).mapNotNull {
            accounts.optJSONObject(it)?.optString("email")?.takeIf { e -> e.isNotBlank() }
        }
    }

    // --- per-widget configuration ---

    fun saveSelection(context: Context, appWidgetId: Int, account: String) {
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit().putString("widget_account_$appWidgetId", account).apply()
    }

    /** Returns configured account email, [UNIFIED], or null if unconfigured. */
    fun selection(context: Context, appWidgetId: Int): String? =
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString("widget_account_$appWidgetId", null)

    /**
     * Selection to actually render. Some launchers (e.g. Kvaesitso) add widgets
     * without ever launching the APPWIDGET_CONFIGURE activity, so [selection] stays
     * null and the widget would show "no messages". Fall back to a sensible default:
     * the only account if there's one, the unified inbox if there are several.
     */
    fun effectiveSelection(context: Context, appWidgetId: Int): String? {
        selection(context, appWidgetId)?.let { return it }
        val accounts = savedAccountEmails(context)
        return when {
            accounts.isEmpty() -> null
            accounts.size == 1 -> accounts[0]
            else -> UNIFIED
        }
    }

    fun clearSelection(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit().remove("widget_account_$appWidgetId").apply()
    }
}
