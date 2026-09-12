package com.falseenvironment.jmapjolt

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt

// Semantic colour tokens for one app theme.
// Single source of truth for every surface, text and divider colour. Before this
// existed the same palette was re-declared as raw hex in `applyTheme`, in
// `styleOutlinedField`, in the snackbar builder, in three `getTheme*Color`
// helpers and again in the layout XML — so a colour added to one of them silently
// kept the Legacy value in Snow/OLED/Iris.
internal data class ThemeTokens(
    // App window and screen containers.
    val background: Int,
    // Toolbar, status strip, detail header — the surface that sits on [background].
    val surface: Int,
    // Inset strips (compose formatting bar) — recedes below [background].
    val surfaceVariant: Int,
    // Settings grouped-list cards. Legacy used to leave this null and fall back to
    // `bg_settings_card`, whose `?attr/colorSurfaceVariant` follows the system
    // dark-mode setting rather than the theme picked in Settings — on a light-mode
    // phone that painted a light card under Legacy's light text.
    val surfaceCard: Int,
    val surfaceDialog: Int,
    val surfaceSnackbar: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    // Third text level: timestamps and previews of read mail, idle icons.
    val textMuted: Int,
    // Hairline separators between rows.
    val divider: Int,
    // Outlined text field box fill / idle stroke / idle floating label / input text.
    val inputBox: Int,
    val inputStroke: Int,
    val inputLabel: Int,
    val inputText: Int,
    // Loading-shimmer pair for the email detail skeleton: the bar and the band
    // that sweeps across it.
    val skeletonBase: Int,
    val skeletonShine: Int,
    // Light text on a dark ground, or the reverse. Single answer to "is this theme
    // dark?" — it used to be re-derived by listing theme keys at each call site,
    // so a new theme had to be added to every one of those lists.
    val isDark: Boolean,
)

/** ARGB int as a CSS "#RRGGBB" literal, for the colours handed to the WebView. */
internal fun Int.toCssHex(): String = String.format("#%06X", this and 0xFFFFFF)

private fun tokens(
    background: String,
    surface: String,
    surfaceVariant: String,
    surfaceCard: String,
    surfaceDialog: String,
    surfaceSnackbar: String,
    textPrimary: String,
    textSecondary: String,
    textMuted: String,
    divider: String,
    inputBox: String,
    inputStroke: String,
    inputLabel: String,
    inputText: String,
    skeletonBase: String,
    skeletonShine: String,
    isDark: Boolean,
) = ThemeTokens(
    background = background.toColorInt(),
    surface = surface.toColorInt(),
    surfaceVariant = surfaceVariant.toColorInt(),
    surfaceCard = surfaceCard.toColorInt(),
    surfaceDialog = surfaceDialog.toColorInt(),
    surfaceSnackbar = surfaceSnackbar.toColorInt(),
    textPrimary = textPrimary.toColorInt(),
    textSecondary = textSecondary.toColorInt(),
    textMuted = textMuted.toColorInt(),
    divider = divider.toColorInt(),
    inputBox = inputBox.toColorInt(),
    inputStroke = inputStroke.toColorInt(),
    inputLabel = inputLabel.toColorInt(),
    inputText = inputText.toColorInt(),
    skeletonBase = skeletonBase.toColorInt(),
    skeletonShine = skeletonShine.toColorInt(),
    isDark = isDark,
)

// Palette per theme key, keyed by the value stored in `app_theme`.
// Grounds are neutral (grey, white, black) so the accent reads as a highlight instead
// of a wash; only Iris keeps a violet cast. Each theme separates its layers —
// background < surface < card — by a visible step rather than a near-identical shade.
internal val THEME_TOKENS: Map<String, ThemeTokens> = mapOf(
    "gray" to tokens(
        background = "#141416", surface = "#1C1C1F", surfaceVariant = "#101012",
        surfaceCard = "#232327", surfaceDialog = "#1F1F23", surfaceSnackbar = "#2C2C31",
        textPrimary = "#EDEDF0", textSecondary = "#A0A0A8", textMuted = "#6E6E77",
        divider = "#2A2A2F",
        inputBox = "#1C1C1F", inputStroke = "#3A3A41", inputLabel = "#A8A8B0",
        inputText = "#FFFFFF",
        skeletonBase = "#232327", skeletonShine = "#2F2F34", isDark = true,
    ),
    "light" to tokens(
        background = "#FFFFFF", surface = "#F7F7F8", surfaceVariant = "#EEEEF1",
        surfaceCard = "#F1F1F4", surfaceDialog = "#FFFFFF", surfaceSnackbar = "#FFFFFF",
        textPrimary = "#18181B", textSecondary = "#5E5E66", textMuted = "#8E8E96",
        divider = "#E5E5EA",
        inputBox = "#FFFFFF", inputStroke = "#D2D2D8", inputLabel = "#8A8A90",
        inputText = "#18181B",
        skeletonBase = "#ECECEF", skeletonShine = "#F6F6F8", isDark = false,
    ),
    "oled" to tokens(
        background = "#000000", surface = "#0C0C0E", surfaceVariant = "#000000",
        surfaceCard = "#151518", surfaceDialog = "#111113", surfaceSnackbar = "#1C1C1F",
        textPrimary = "#EDEDF0", textSecondary = "#9A9AA2", textMuted = "#66666E",
        divider = "#1E1E22",
        inputBox = "#111113", inputStroke = "#3A3A41", inputLabel = "#A8A8B0",
        inputText = "#FFFFFF",
        skeletonBase = "#111113", skeletonShine = "#1E1E21", isDark = true,
    ),
    "violet" to tokens(
        background = "#120C1C", surface = "#1A1328", surfaceVariant = "#0C0814",
        surfaceCard = "#231A35", surfaceDialog = "#1A1328", surfaceSnackbar = "#2A2040",
        textPrimary = "#EEEAF4", textSecondary = "#A89CBE", textMuted = "#74698A",
        divider = "#2B2140",
        inputBox = "#1A1328", inputStroke = "#3F3456", inputLabel = "#B0A6C2",
        inputText = "#FFFFFF",
        skeletonBase = "#231A35", skeletonShine = "#2F2446", isDark = true,
    ),
)

/** [base] moved towards [other] by [ratio] (0 = base, 1 = other), per RGB channel. */
internal fun blendColors(base: Int, other: Int, ratio: Float): Int {
    val r = ratio.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (base shr shift) and 0xFF
        val b = (other shr shift) and 0xFF
        return (a + (b - a) * r).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Accent tint for selected rows, active nav items and chips: accent laid over the background. */
internal fun ThemeTokens.accentSoft(accent: Int): Int =
    blendColors(background, accent, if (isDark) 0.22f else 0.14f)

/**
 * Tonal container for a floating action button: clearly tinted by the accent but far
 * quieter than the raw accent, so the button stands out without shouting.
 */
internal fun ThemeTokens.accentContainer(accent: Int): Int =
    blendColors(surfaceCard, accent, if (isDark) 0.34f else 0.24f)

/**
 * Accent used as text or icon colour on the theme ground. Dark themes lift it towards
 * white so a deep accent (navy, dark purple) keeps contrast on a near-black background.
 */
internal fun ThemeTokens.accentOnGround(accent: Int): Int =
    if (isDark) blendColors(accent, textPrimary, 0.25f) else accent

// Tokens of the theme currently selected in Settings.
internal val MainActivity.tokens: ThemeTokens
    get() = THEME_TOKENS[currentTheme] ?: THEME_TOKENS.getValue("gray")

// ---------------------------------------------------------------------------
// Tagged views
// ---------------------------------------------------------------------------

// `android:tag` values a layout can carry so [applyTokenTags] repaints the view on
// every theme change. Used for the elements a generic pass would otherwise get
// wrong: hairlines (no text, no id worth wiring) and labels that must stay
// secondary after `updateContainerTextColors` paints every TextView primary.
internal object ViewTokenTag {
    const val DIVIDER = "token:divider"
    const val TEXT_SECONDARY = "token:textSecondary"
    const val SURFACE = "token:surface"
    const val SURFACE_VARIANT = "token:surfaceVariant"
}

// Repaint every view tagged with a [ViewTokenTag] under [root], recursively.
internal fun MainActivity.applyTokenTags(root: View) {
    when (root.tag as? String) {
        ViewTokenTag.DIVIDER -> root.setBackgroundColor(tokens.divider)
        ViewTokenTag.SURFACE -> root.setBackgroundColor(tokens.surface)
        ViewTokenTag.SURFACE_VARIANT -> root.setBackgroundColor(tokens.surfaceVariant)
        ViewTokenTag.TEXT_SECONDARY ->
            (root as? TextView)?.setTextColor(tokens.textSecondary)
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) applyTokenTags(root.getChildAt(i))
    }
}
