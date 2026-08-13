package com.falseenvironment.jmapjolt

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget showing the week as a calendar grid — the widget counterpart of the
 * in-app WEEK view, next to the agenda-style [CalendarWidgetProvider].
 *
 * RemoteViews has no custom views, so [CalendarWeekRenderer] paints the grid into a bitmap
 * sized from the widget's own dimensions. Each widget keeps its own week offset so ‹ / ›
 * page through weeks; tapping the title opens the app on the displayed week and resets the
 * offset back to the current one.
 */
class CalendarWeekWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
        WidgetDayRollReceiver.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, mgr, appWidgetId, newOptions)
        // Resized: the bitmap must be re-rendered at the new dimensions.
        renderWidget(context, mgr, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { prefs.remove(keyOffset(it)) }
        prefs.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.action) {
            ACTION_PREV -> shiftWeek(context, id, -1)
            ACTION_NEXT -> shiftWeek(context, id, +1)
            ACTION_REFRESH -> {
                setOffset(context, id, 0)
                renderWidget(context, AppWidgetManager.getInstance(context), id)
                WidgetSupport.spinWhileRefreshing(
                    context, id, R.layout.widget_calendar_week, goAsync()
                )
            }
        }
    }

    private fun shiftWeek(context: Context, appWidgetId: Int, delta: Int) {
        setOffset(context, appWidgetId, offset(context, appWidgetId) + delta)
        renderWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
    }

    companion object {
        const val ACTION_REFRESH = "com.falseenvironment.jmapjolt.WEEK_WIDGET_REFRESH"
        private const val ACTION_PREV = "com.falseenvironment.jmapjolt.WEEK_WIDGET_PREV"
        private const val ACTION_NEXT = "com.falseenvironment.jmapjolt.WEEK_WIDGET_NEXT"

        private const val PREFS = "week_widget_prefs"
        private const val WEEK_MS = 7L * 86_400_000L
        // Distinct PendingIntent request-code spaces per action.
        private const val OPEN_OFFSET = 5_000_000
        private const val ADD_OFFSET = 6_000_000
        private const val PREV_OFFSET = 7_000_000
        private const val NEXT_OFFSET = 8_000_000
        private const val REFRESH_OFFSET = 9_000_000
        /** Fallback size when the launcher reports no dimensions yet. */
        private const val FALLBACK_WIDTH_DP = 320
        private const val FALLBACK_HEIGHT_DP = 180

        private fun keyOffset(appWidgetId: Int) = "week_offset_$appWidgetId"

        private fun offset(context: Context, appWidgetId: Int): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(keyOffset(appWidgetId), 0)

        private fun setOffset(context: Context, appWidgetId: Int, value: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(keyOffset(appWidgetId), value).apply()
        }

        /** Re-renders every week widget after a sync or a theme/accent change. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, CalendarWeekWidgetProvider::class.java)
            )
            for (id in ids) renderWidget(context, mgr, id)
        }

        fun renderWidget(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            CalendarPrefs.warmTimeZone(context)
            val views = RemoteViews(context.packageName, R.layout.widget_calendar_week)
            val palette = WidgetSupport.palette(WidgetSupport.currentTheme(context))
            val accent = WidgetSupport.accentColor(context)

            views.setInt(R.id.widgetRoot, "setBackgroundColor", palette[0])
            views.setInt(R.id.widgetHeader, "setBackgroundColor", palette[1])
            views.setTextColor(R.id.widgetTitle, palette[2])
            views.setInt(R.id.widgetHeaderStrip, "setBackgroundColor", accent)
            views.setInt(R.id.widgetPrev, "setColorFilter", accent)
            views.setInt(R.id.widgetNext, "setColorFilter", accent)
            views.setInt(R.id.widgetAdd, "setColorFilter", accent)
            views.setInt(R.id.widgetRefresh, "setColorFilter", accent)
            views.setViewVisibility(R.id.widgetRefresh, View.VISIBLE)
            views.setViewVisibility(R.id.widgetRefreshProgress, View.GONE)

            val anchor = System.currentTimeMillis() + offset(context, appWidgetId) * WEEK_MS
            val week = CalendarWeekRenderer.week(context, anchor)
            views.setTextViewText(R.id.widgetTitle, weekLabel(week.days))

            val (widthPx, heightPx) = gridSizePx(context, mgr, appWidgetId)
            val bitmap = CalendarWeekRenderer.render(context, week, widthPx, heightPx)
            if (bitmap != null) views.setImageViewBitmap(R.id.widgetWeekGrid, bitmap)

            views.setOnClickPendingIntent(
                R.id.widgetTitle, openCalendarIntent(context, appWidgetId, week.days.first())
            )
            views.setOnClickPendingIntent(
                R.id.widgetWeekGrid, openCalendarIntent(context, appWidgetId, week.days.first())
            )
            views.setOnClickPendingIntent(R.id.widgetAdd, newEventIntent(context, appWidgetId))
            views.setOnClickPendingIntent(
                R.id.widgetPrev, broadcast(context, appWidgetId, ACTION_PREV, PREV_OFFSET)
            )
            views.setOnClickPendingIntent(
                R.id.widgetNext, broadcast(context, appWidgetId, ACTION_NEXT, NEXT_OFFSET)
            )
            views.setOnClickPendingIntent(
                R.id.widgetRefresh, broadcast(context, appWidgetId, ACTION_REFRESH, REFRESH_OFFSET)
            )

            mgr.updateAppWidget(appWidgetId, views)
        }

        /** "11 – 17 Aug" / "28 Jul – 3 Aug" for the displayed week. */
        private fun weekLabel(days: List<Long>): String {
            val zone = CalendarPrefs.zone()
            val first = days.first()
            val last = days.last()
            val month = SimpleDateFormat("MMM", Locale.ENGLISH).apply { timeZone = zone }
            val dayOnly = SimpleDateFormat("d", Locale.ENGLISH).apply { timeZone = zone }
            val dayMonth = SimpleDateFormat("d MMM", Locale.ENGLISH).apply { timeZone = zone }
            return if (month.format(Date(first)) == month.format(Date(last)))
                "${dayOnly.format(Date(first))} – ${dayMonth.format(Date(last))}"
            else "${dayMonth.format(Date(first))} – ${dayMonth.format(Date(last))}"
        }

        /** Widget size minus the header row, in pixels. */
        private fun gridSizePx(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int
        ): Pair<Int, Int> {
            val options = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
            // The launcher reports both orientations at once: portrait is the narrow/tall
            // pair (minWidth, maxHeight), landscape the wide/short one (maxWidth, minHeight).
            val landscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            val widthDp = options?.getInt(widthKey, 0)?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            val heightDp = options?.getInt(heightKey, 0)?.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
            val density = context.resources.displayMetrics.density
            val headerDp = 42
            return ((widthDp * density).toInt() to
                ((heightDp - headerDp).coerceAtLeast(60) * density).toInt())
        }

        private fun openCalendarIntent(context: Context, appWidgetId: Int, weekStart: Long) =
            android.app.PendingIntent.getActivity(
                context, appWidgetId + OPEN_OFFSET,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
                    // Noon keeps the target inside the day in every time zone.
                    .putExtra(MainActivity.EXTRA_OPEN_EVENT_START, weekStart + 43_200_000L)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

        private fun newEventIntent(context: Context, appWidgetId: Int) =
            android.app.PendingIntent.getActivity(
                context, appWidgetId + ADD_OFFSET,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
                    .putExtra(MainActivity.EXTRA_NEW_EVENT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

        private fun broadcast(
            context: Context,
            appWidgetId: Int,
            action: String,
            requestOffset: Int
        ) = android.app.PendingIntent.getBroadcast(
            context, appWidgetId + requestOffset,
            Intent(context, CalendarWeekWidgetProvider::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
}
