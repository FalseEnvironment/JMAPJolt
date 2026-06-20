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
 * Home-screen widget showing the inbox of a single account or the unified inbox.
 * The per-widget selection is captured by [InboxWidgetConfigActivity] on placement.
 */
class InboxWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) renderWidget(context, mgr, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetSupport.clearSelection(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                AppWidgetManager.getInstance(context)
                    .notifyAppWidgetViewDataChanged(id, R.id.widgetList)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.falseenvironment.jmapjolt.WIDGET_REFRESH"
        const val EXTRA_OPEN_EMAIL_ID = "widget_open_email_id"
        const val EXTRA_OPEN_ACCOUNT = "widget_open_account"
        private const val TEMPLATE_OFFSET = 1_000_000

        /**
         * Re-applies theme/accent colors after a sync or a theme/accent change.
         *
         * Uses [AppWidgetManager.partiallyUpdateAppWidget] with a chrome-only
         * RemoteViews so the existing RemoteAdapter is left untouched — re-calling
         * setRemoteAdapter here would tear down and rebind the ListView, flashing
         * the stale rows before the new colors load. The row recolor is handled by
         * the factory's onDataSetChanged via notifyAppWidgetViewDataChanged.
         */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, InboxWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_inbox)
                applyChrome(context, views, id)
                mgr.partiallyUpdateAppWidget(id, views)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
        }

        /** Theme/accent/title styling shared by full render and partial refresh. */
        private fun applyChrome(context: Context, views: RemoteViews, appWidgetId: Int) {
            val selection = WidgetSupport.effectiveSelection(context, appWidgetId)
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

            val isUnified = selection == WidgetSupport.UNIFIED
            val title = when {
                selection == null -> context.getString(R.string.widget_inbox_title)
                isUnified -> context.getString(R.string.widget_unified_inbox)
                else -> selection
            }
            views.setTextViewText(R.id.widgetTitle, title)
            // Header strip: accent for unified, account color for a single account.
            val stripColor = if (isUnified || selection == null) accent
                else WidgetSupport.accountColor(context, selection)
            views.setInt(R.id.widgetHeaderStrip, "setBackgroundColor", stripColor)
            views.setInt(R.id.widgetCompose, "setColorFilter", accent)
            views.setInt(R.id.widgetRefresh, "setColorFilter", accent)
        }

        fun renderWidget(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_inbox)
            applyChrome(context, views, appWidgetId)

            // Collection adapter — unique data uri so each widget has its own factory.
            val serviceIntent = Intent(context, InboxWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("jmapjolt://widget/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.widgetList, serviceIntent)
            views.setEmptyView(R.id.widgetList, R.id.widgetEmpty)

            // Tapping a row or the title opens the app.
            val openApp = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, openApp)
            // Mutable template so per-row fill-in intents (email id / account) merge in.
            val rowTemplate = PendingIntent.getActivity(
                context, appWidgetId + TEMPLATE_OFFSET,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widgetList, rowTemplate)

            // Compose via the existing mailto: deep-link path.
            val composePI = PendingIntent.getActivity(
                context, appWidgetId,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_SENDTO)
                    .setData(Uri.parse("mailto:"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetCompose, composePI)

            val refreshPI = PendingIntent.getBroadcast(
                context, appWidgetId,
                Intent(context, InboxWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPI)

            mgr.updateAppWidget(appWidgetId, views)
        }
    }
}
