package com.falseenvironment.jmapjolt

import android.content.Context

/**
 * Stores which calendar backend the user selected. DAVx5 reads/writes the system
 * [CalendarProvider]; JMAP uses [CalendarSync] + the local [CalendarStore].
 */
object CalendarPrefs {
    private const val PREFS = "calendar_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIME_FORMAT = "time_format"
    private const val KEY_TIME_ZONE = "time_zone"

    enum class Provider { DAVX5, JMAP }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the calendar feature is enabled (shown in the drawer). Default on. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun provider(context: Context): Provider =
        runCatching { Provider.valueOf(prefs(context).getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(Provider.JMAP)

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    /**
     * Clock style for every calendar surface (agenda, timeline grid, event editor,
     * reminders and the widget). SYSTEM follows the device setting, which is what the
     * app did before this became user-selectable.
     */
    enum class TimeFormat { SYSTEM, H24, H12 }

    fun timeFormat(context: Context): TimeFormat =
        runCatching { TimeFormat.valueOf(prefs(context).getString(KEY_TIME_FORMAT, null) ?: "") }
            .getOrDefault(TimeFormat.SYSTEM)

    fun setTimeFormat(context: Context, format: TimeFormat) {
        prefs(context).edit().putString(KEY_TIME_FORMAT, format.name).apply()
    }

    /** True when times should render as 00-23 rather than 12-hour with AM/PM. */
    fun use24Hour(context: Context): Boolean = when (timeFormat(context)) {
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
        TimeFormat.SYSTEM -> android.text.format.DateFormat.is24HourFormat(context)
    }

    /** `HH:mm` or `h:mm a` depending on the current preference. */
    fun timePattern(context: Context): String =
        if (use24Hour(context)) "HH:mm" else "h:mm a"

    fun timeFormatter(context: Context): java.text.SimpleDateFormat =
        java.text.SimpleDateFormat(timePattern(context), java.util.Locale.ENGLISH)
            .apply { timeZone = zone() }

    // ---- Time zone ------------------------------------------------------

    /**
     * Selected zone id, or null for "Automatic" (the device zone).
     *
     * [zone] is also read from static helpers such as
     * [CalendarTimelineView.midnight] that have no Context, so the resolved value is
     * cached here and refreshed by [setTimeZone] and [warmTimeZone].
     */
    fun timeZoneId(context: Context): String? =
        prefs(context).getString(KEY_TIME_ZONE, null)?.takeIf { it.isNotBlank() }

    fun setTimeZone(context: Context, zoneId: String?) {
        prefs(context).edit().apply {
            if (zoneId.isNullOrBlank()) remove(KEY_TIME_ZONE) else putString(KEY_TIME_ZONE, zoneId)
        }.apply()
        cachedZone = zoneId?.let { java.util.TimeZone.getTimeZone(it) }
    }

    /** Loads the persisted override into the cache; call once at startup. */
    fun warmTimeZone(context: Context) {
        cachedZone = timeZoneId(context)?.let { java.util.TimeZone.getTimeZone(it) }
    }

    @Volatile private var cachedZone: java.util.TimeZone? = null

    /** The zone every calendar surface should use: the override, else the device zone. */
    fun zone(): java.util.TimeZone = cachedZone ?: java.util.TimeZone.getDefault()

    /** A [java.util.Calendar] already pinned to [zone]. */
    fun calendar(): java.util.Calendar = java.util.Calendar.getInstance(zone())

    /** "UTC+02:00 · Europe/Rome"-style label for the settings row. */
    fun zoneLabel(context: Context): String {
        val id = timeZoneId(context)
            ?: return context.getString(R.string.settings_cal_timezone_auto)
        return TimeZones.labelFor(id)
    }
}
