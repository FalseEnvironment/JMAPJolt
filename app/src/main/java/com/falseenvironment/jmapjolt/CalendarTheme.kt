package com.falseenvironment.jmapjolt

import android.content.Context
import androidx.core.graphics.toColorInt

/**
 * Standalone theme reader for the calendar and contacts screens. Reads the same
 * [THEME_TOKENS] as ThemeHelper, so both panels follow the app's main theme and accent
 * without depending on MainActivity's view-bound extensions.
 */
object CalendarTheme {

    data class Palette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val onAccent: Int,
        val isDark: Boolean,
        // Search field, popup menus and unselected chips: one step above [background].
        val card: Int,
        val divider: Int,
        // Accent tint behind a selected chip, tab or row.
        val accentSoft: Int,
        // Accent used as text or icon colour on [background]; lifted on dark themes.
        val accentText: Int,
    )

    fun palette(context: Context): Palette {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getString("app_theme", "gray") ?: "gray"
        val storedAccent = prefs.getString(MainActivity.KEY_ACCENT_COLOR, "#3D8BFD") ?: "#3D8BFD"
        val accentHex = MainActivity.LEGACY_ACCENT_MAP[storedAccent.uppercase()] ?: storedAccent

        val tokens = THEME_TOKENS[theme] ?: THEME_TOKENS.getValue("gray")
        val accent = runCatching { accentHex.toColorInt() }.getOrDefault("#3D8BFD".toColorInt())
        return Palette(
            background = tokens.background,
            surface = tokens.surface,
            text = tokens.textPrimary,
            secondaryText = tokens.textSecondary,
            accent = accent,
            onAccent = onAccentFor(accent),
            isDark = tokens.isDark,
            card = tokens.surfaceCard,
            divider = tokens.divider,
            accentSoft = tokens.accentSoft(accent),
            accentText = tokens.accentOnGround(accent),
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
    fun current(context: Context): JMapClient.ConnectedAccount? = SecureStorage.currentAccount(context)
}
