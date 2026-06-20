package com.falseenvironment.jmapjolt

import android.content.Context

/**
 * Dual-source calendar read/write. The system [CalendarProvider] (events synced by DAVx5 or
 * any sync adapter) is the primary source; [CalendarStore] holds events created in-app when no
 * writable provider calendar is available (offline / no DAVx5). Reads merge both sources.
 */
object CalendarRepository {

    /** Sentinel calendar id meaning "store locally in [CalendarStore]". */
    const val LOCAL_CALENDAR_ID = "local"

    private fun useProvider(context: Context): Boolean =
        CalendarPrefs.provider(context) == CalendarPrefs.Provider.DAVX5

    fun occurrences(context: Context, from: Long, to: Long): List<EventOccurrence> {
        val provider = if (useProvider(context)) CalendarProvider.occurrences(context, from, to)
            else emptyList()
        val local = CalendarStore.occurrences(context, from, to)
        return (provider + local).sortedBy { it.start }
    }

    /** Calendar to create new events in: a writable provider calendar, else the local store. */
    fun defaultCalendarId(context: Context): String =
        (if (useProvider(context)) CalendarProvider.defaultWritableCalendarId(context) else null)
            ?.toString() ?: LOCAL_CALENDAR_ID

    fun upsert(context: Context, event: CalendarEvent) {
        val providerCalId = event.calendarId.toLongOrNull()
        val goesToProvider = useProvider(context) &&
            (CalendarProvider.isProviderEventId(event.id) || providerCalId != null)
        if (goesToProvider && CalendarProvider.hasWritePermission(context)) {
            val calId = providerCalId
                ?: event.calendarId.toLongOrNull()
                ?: CalendarProvider.defaultWritableCalendarId(context)
            if (calId != null && CalendarProvider.upsert(context, event, calId) != null) return
        }
        CalendarStore.upsert(context, event)
    }

    fun delete(context: Context, event: CalendarEvent) {
        if (CalendarProvider.isProviderEventId(event.id)) {
            if (CalendarProvider.delete(context, event)) return
        }
        CalendarStore.softDelete(context, event.id)
    }
}
