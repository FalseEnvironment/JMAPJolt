package com.falseenvironment.jmapjolt

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt

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

    /**
     * Palette: [bg, header, text, secondaryText] from the app's [THEME_TOKENS]. The header
     * shares the background, like the app's neutral top bars.
     */
    fun palette(theme: String): IntArray {
        val t = themeTokens(theme)
        return intArrayOf(t.background, t.background, t.textPrimary, t.textSecondary)
    }

    private fun themeTokens(theme: String): ThemeTokens =
        THEME_TOKENS[theme] ?: THEME_TOKENS.getValue("gray")

    /** Accent for text, icons and dots on the widget background (lifted on dark themes). */
    fun accentText(context: Context): Int =
        themeTokens(currentTheme(context)).accentOnGround(accentColor(context))

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
    fun savedAccountEmails(context: Context): List<String> =
        SecureStorage.connectedAccounts(context).map { it.email }.filter { it.isNotBlank() }

    /**
     * Shows a spinning [android.widget.ProgressBar] in place of the refresh icon while the
     * list reloads, then swaps back. Shared by both widgets — their headers use the same
     * `widgetRefresh` / `widgetRefreshProgress` ids. [pending] is the receiver's goAsync()
     * result, finished once the spin completes so the process stays alive meanwhile.
     */
    fun spinWhileRefreshing(
        context: Context,
        appWidgetId: Int,
        layoutRes: Int,
        pending: BroadcastReceiver.PendingResult?
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        val show = RemoteViews(context.packageName, layoutRes).apply {
            setViewVisibility(R.id.widgetRefresh, View.GONE)
            setViewVisibility(R.id.widgetRefreshProgress, View.VISIBLE)
        }
        mgr.partiallyUpdateAppWidget(appWidgetId, show)
        mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetList)
        Thread {
            runCatching { Thread.sleep(REFRESH_SPIN_MS) }
            val hide = RemoteViews(context.packageName, layoutRes).apply {
                setViewVisibility(R.id.widgetRefreshProgress, View.GONE)
                setViewVisibility(R.id.widgetRefresh, View.VISIBLE)
            }
            mgr.partiallyUpdateAppWidget(appWidgetId, hide)
            pending?.finish()
        }.start()
    }

    private const val REFRESH_SPIN_MS = 900L

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
    fun effectiveSelection(context: Context, appWidgetId: Int): String? =
        resolveWidgetSelection(selection(context, appWidgetId), savedAccountEmails(context))

    fun clearSelection(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit().remove("widget_account_$appWidgetId").apply()
    }
}

/**
 * What an inbox widget renders, from its saved [saved] selection and the signed-in
 * [accounts]. The unified inbox exists only with two or more accounts: a unified widget
 * left with one account shows that account.
 */
internal fun resolveWidgetSelection(saved: String?, accounts: List<String>): String? = when {
    accounts.isEmpty() -> null
    saved == WidgetSupport.UNIFIED || saved == null ->
        if (accounts.size == 1) accounts[0] else WidgetSupport.UNIFIED
    else -> saved
}

/** Per-row account colour strips tell accounts apart, so only a multi-account unified inbox has them. */
internal fun showsAccountStrips(selection: String?, accountCount: Int): Boolean =
    selection == WidgetSupport.UNIFIED && accountCount > 1
