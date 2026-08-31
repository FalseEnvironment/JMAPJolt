package com.falseenvironment.jmapjolt

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * Semantic colour tokens for one app theme.
 *
 * Single source of truth for every surface, text and divider colour. Before this
 * existed the same palette was re-declared as raw hex in `applyTheme`, in
 * `styleOutlinedField`, in the snackbar builder, in three `getTheme*Color`
 * helpers and again in the layout XML — so a colour added to one of them silently
 * kept the Legacy value in Snow/OLED/Iris.
 */
internal data class ThemeTokens(
    /** App window and screen containers. */
    val background: Int,
    /** Toolbar, status strip, detail header — the surface that sits on [background]. */
    val surface: Int,
    /** Inset strips (compose formatting bar) — recedes below [background]. */
    val surfaceVariant: Int,
    /** Settings grouped-list cards; null keeps the Legacy `bg_settings_card` drawable. */
    val surfaceCard: Int?,
    val surfaceDialog: Int,
    val surfaceSnackbar: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    /** Hairline separators between rows. */
    val divider: Int,
    /** Outlined text field box fill / idle stroke / idle floating label / input text. */
    val inputBox: Int,
    val inputStroke: Int,
    val inputLabel: Int,
    val inputText: Int,
)

private fun tokens(
    background: String,
    surface: String,
    surfaceVariant: String,
    surfaceCard: String?,
    surfaceDialog: String,
    surfaceSnackbar: String,
    textPrimary: String,
    textSecondary: String,
    divider: String,
    inputBox: String,
    inputStroke: String,
    inputLabel: String,
    inputText: String,
) = ThemeTokens(
    background = background.toColorInt(),
    surface = surface.toColorInt(),
    surfaceVariant = surfaceVariant.toColorInt(),
    surfaceCard = surfaceCard?.toColorInt(),
    surfaceDialog = surfaceDialog.toColorInt(),
    surfaceSnackbar = surfaceSnackbar.toColorInt(),
    textPrimary = textPrimary.toColorInt(),
    textSecondary = textSecondary.toColorInt(),
    divider = divider.toColorInt(),
    inputBox = inputBox.toColorInt(),
    inputStroke = inputStroke.toColorInt(),
    inputLabel = inputLabel.toColorInt(),
    inputText = inputText.toColorInt(),
)

/** Palette per theme key, keyed by the value stored in `app_theme`. */
internal val THEME_TOKENS: Map<String, ThemeTokens> = mapOf(
    "gray" to tokens(
        background = "#212126", surface = "#2A2A30", surfaceVariant = "#1C1C22",
        surfaceCard = null, surfaceDialog = "#242429", surfaceSnackbar = "#333338",
        textPrimary = "#ECECF1", textSecondary = "#90909A", divider = "#38383F",
        inputBox = "#2E2E34", inputStroke = "#454552", inputLabel = "#B0B0BA",
        inputText = "#FFFFFF",
    ),
    "light" to tokens(
        background = "#F6F6F8", surface = "#FFFFFF", surfaceVariant = "#E8E8EC",
        surfaceCard = "#EAEAEF", surfaceDialog = "#F0EEEE", surfaceSnackbar = "#FFFFFF",
        textPrimary = "#1B1B1F", textSecondary = "#5F5F66", divider = "#DCDCE3",
        inputBox = "#FFFFFF", inputStroke = "#D0D0D4", inputLabel = "#8A8A90",
        inputText = "#212121",
    ),
    "oled" to tokens(
        background = "#000000", surface = "#0B0B0D", surfaceVariant = "#080808",
        surfaceCard = "#141416", surfaceDialog = "#0A0A0A", surfaceSnackbar = "#1C1C1E",
        textPrimary = "#ECECF1", textSecondary = "#90909A", divider = "#232327",
        inputBox = "#141414", inputStroke = "#454552", inputLabel = "#B0B0BA",
        inputText = "#FFFFFF",
    ),
    "violet" to tokens(
        background = "#160E24", surface = "#1E1430", surfaceVariant = "#0E0A1A",
        surfaceCard = "#271C3E", surfaceDialog = "#140B22", surfaceSnackbar = "#2C1F46",
        textPrimary = "#ECECF1", textSecondary = "#9B7DC8", divider = "#33254F",
        inputBox = "#241634", inputStroke = "#454552", inputLabel = "#B0B0BA",
        inputText = "#FFFFFF",
    ),
)

/** Tokens of the theme currently selected in Settings. */
internal val MainActivity.tokens: ThemeTokens
    get() = THEME_TOKENS[currentTheme] ?: THEME_TOKENS.getValue("gray")

// ---------------------------------------------------------------------------
// Tagged views
// ---------------------------------------------------------------------------

/**
 * `android:tag` values a layout can carry so [applyTokenTags] repaints the view on
 * every theme change. Used for the elements a generic pass would otherwise get
 * wrong: hairlines (no text, no id worth wiring) and labels that must stay
 * secondary after `updateContainerTextColors` paints every TextView primary.
 */
internal object ViewTokenTag {
    const val DIVIDER = "token:divider"
    const val TEXT_SECONDARY = "token:textSecondary"
    const val SURFACE = "token:surface"
    const val SURFACE_VARIANT = "token:surfaceVariant"
}

/** Repaint every view tagged with a [ViewTokenTag] under [root], recursively. */
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
