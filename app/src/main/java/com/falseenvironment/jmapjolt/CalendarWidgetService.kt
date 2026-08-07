package com.falseenvironment.jmapjolt

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.toColorInt
import java.util.Calendar

/** Provides the agenda rows for [CalendarWidgetProvider]'s ListView. */
class CalendarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CalendarWidgetFactory(applicationContext)
}

private class CalendarWidgetFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    /** A flat agenda entry: either a day header or an event row. */
    private sealed interface Item {
        data class Header(val label: String) : Item
        data class Event(
            val start: Long,
            val title: String,
            val location: String,
            val time: String,
            val color: Int
        ) : Item
    }

    private var items: List<Item> = emptyList()
    private var textColor = 0
    private var secondaryColor = 0
    private var accentColor = 0

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // Widgets can be rebuilt in a fresh process where the static zone cache is cold.
        CalendarPrefs.warmTimeZone(context)
        val palette = WidgetSupport.palette(WidgetSupport.currentTheme(context))
        textColor = palette[2]
        secondaryColor = palette[3]
        accentColor = WidgetSupport.accentColor(context)

        // Window: start of today through the next AGENDA_DAYS days.
        val from = startOfToday()
        val to = from + AGENDA_DAYS * DAY_MS
        val occurrences = runCatching { CalendarRepository.occurrences(context, from, to) }
            .getOrDefault(emptyList())

        val use24h = CalendarPrefs.use24Hour(context)
        val built = mutableListOf<Item>()
        var lastDay = Long.MIN_VALUE
        for (occ in occurrences) {
            val day = dayStart(occ.start)
            if (day != lastDay) {
                built += Item.Header(dayLabel(occ.start))
                lastDay = day
            }
            built += Item.Event(
                start = occ.start,
                title = occ.event.title.ifBlank { context.getString(R.string.calendar_no_title) },
                location = occ.event.location,
                time = timeLabel(occ, use24h),
                color = eventColor(occ.event)
            )
        }
        items = built.take(MAX_ITEMS)
    }

    override fun onDestroy() { items = emptyList() }

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews = when (val item = items[position]) {
        is Item.Header -> headerView(item)
        is Item.Event -> eventView(item)
    }

    private fun headerView(header: Item.Header): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar_header)
        views.setTextViewText(R.id.headerText, header.label)
        views.setTextColor(R.id.headerText, accentColor)
        return views
    }

    private fun eventView(event: Item.Event): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar_item)
        views.setInt(R.id.itemColorStrip, "setBackgroundColor", event.color)
        views.setTextViewText(R.id.itemTime, event.time)
        views.setTextViewText(R.id.itemTitle, event.title)
        views.setTextColor(R.id.itemTime, secondaryColor)
        views.setTextColor(R.id.itemTitle, textColor)

        if (event.location.isNotBlank()) {
            views.setTextViewText(R.id.itemLocation, event.location)
            views.setTextColor(R.id.itemLocation, secondaryColor)
            views.setViewVisibility(R.id.itemLocation, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.itemLocation, View.GONE)
        }

        // Tint the press ripple with the app accent (API 31+).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            views.setColorStateList(
                R.id.itemRoot, "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(accentColor)
            )
        }

        val fillIn = Intent()
            .putExtra(MainActivity.EXTRA_OPEN_EVENT_START, event.start)
        views.setOnClickFillInIntent(R.id.itemRoot, fillIn)
        return views
    }

    private fun eventColor(event: CalendarEvent): Int =
        event.color?.let { runCatching { it.toColorInt() }.getOrNull() } ?: accentColor

    private fun timeLabel(occ: EventOccurrence, use24h: Boolean): String {
        if (occ.event.allDay) return context.getString(R.string.calendar_all_day)
        // SimpleDateFormat rather than DateUtils: the latter always renders in the
        // device zone and ignores the calendar's time-zone override.
        val fmt = java.text.SimpleDateFormat(
            if (use24h) "HH:mm" else "h:mm a", java.util.Locale.ENGLISH
        ).apply { timeZone = CalendarPrefs.zone() }
        return "${fmt.format(java.util.Date(occ.start))}\n${fmt.format(java.util.Date(occ.end))}"
    }

    private val englishContext: Context by lazy {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.ENGLISH)
        context.createConfigurationContext(config)
    }

    private fun dayLabel(ts: Long): String {
        val today = startOfToday()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
            DateUtils.FORMAT_ABBREV_ALL
        val date = DateUtils.formatDateTime(
            englishContext, ts, flags or DateUtils.FORMAT_ABBREV_ALL
        ).let {
            // formatDateTime has no zone parameter; when an override is active the day
            // label is derived from the overridden calendar instead.
            if (CalendarPrefs.zone() == java.util.TimeZone.getDefault()) it
            else java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.ENGLISH)
                .apply { timeZone = CalendarPrefs.zone() }
                .format(java.util.Date(ts))
        }
        return when (dayStart(ts)) {
            today -> context.getString(R.string.calendar_today_prefix, date)
            today + DAY_MS -> context.getString(R.string.calendar_tomorrow_prefix, date)
            else -> date
        }
    }

    private fun startOfToday(): Long = dayStart(System.currentTimeMillis())

    private fun dayStart(ts: Long): Long = CalendarPrefs.calendar().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 2
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false

    companion object {
        private const val AGENDA_DAYS = 30
        private const val MAX_ITEMS = 100
        private const val DAY_MS = 86_400_000L
    }
}
