package com.falseenvironment.jmapjolt

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Home-screen widget showing an Etar-style agenda list of upcoming calendar
 * events grouped by day. Reads from the local-first [CalendarStore], so it works
 * offline without the Activity being alive.
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetSupport.spinWhileRefreshing(context, id, R.layout.widget_calendar, goAsync())
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.falseenvironment.jmapjolt.CALENDAR_WIDGET_REFRESH"
        private const val TEMPLATE_OFFSET = 2_000_000
        private const val NEW_EVENT_OFFSET = 3_000_000

        /** Re-applies theme/accent colors after a sync or a theme/accent change. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, CalendarWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_calendar)
                applyChrome(context, views)
                mgr.partiallyUpdateAppWidget(id, views)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
        }

        /** Theme/accent styling shared by full render and partial refresh. */
        private fun applyChrome(context: Context, views: RemoteViews) {
            val palette = WidgetSupport.palette(WidgetSupport.currentTheme(context))
            val bg = palette[0]
            val header = palette[1]
            val text = palette[2]
            val secondary = palette[3]
            val accent = WidgetSupport.accentColor(context)

            views.setInt(R.id.widgetRoot, "setBackgroundColor", bg)
            views.setInt(R.id.widgetHeader, "setBackgroundColor", header)
            views.setTextColor(R.id.widgetTitle, text)
            views.setTextColor(R.id.widgetEmpty, secondary)
            views.setInt(R.id.widgetHeaderStrip, "setBackgroundColor", accent)
            views.setInt(R.id.widgetAdd, "setColorFilter", accent)
            views.setInt(R.id.widgetRefresh, "setColorFilter", accent)
        }

        fun renderWidget(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            applyChrome(context, views)

            // Collection adapter — unique data uri so each widget has its own factory.
            val serviceIntent = Intent(context, CalendarWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("jmapjolt://calwidget/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.widgetList, serviceIntent)
            views.setEmptyView(R.id.widgetList, R.id.widgetEmpty)

            // Tapping the title or a row opens the calendar screen.
            val openCalendar = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, openCalendar)

            // The + opens the calendar with the new-event editor at the current hour.
            val newEvent = PendingIntent.getActivity(
                context, appWidgetId + NEW_EVENT_OFFSET,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
                    .putExtra(MainActivity.EXTRA_NEW_EVENT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetAdd, newEvent)

            // Mutable template so per-row fill-in intents (event id) merge in.
            val rowTemplate = PendingIntent.getActivity(
                context, appWidgetId + TEMPLATE_OFFSET,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widgetList, rowTemplate)

            val refreshPI = PendingIntent.getBroadcast(
                context, appWidgetId,
                Intent(context, CalendarWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPI)

            mgr.updateAppWidget(appWidgetId, views)
        }
    }
}
