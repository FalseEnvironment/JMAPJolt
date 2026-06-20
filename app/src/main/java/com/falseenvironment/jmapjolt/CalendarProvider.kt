package com.falseenvironment.jmapjolt

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Reads and writes events through the system [CalendarContract] — the Etar/DAVx5 model.
 * Calendars synced by DAVx5 (or any sync adapter) appear here automatically; we never speak
 * CalDAV ourselves. Recurrence expansion is delegated to the provider's Instances table.
 */
object CalendarProvider {

    /** Marks a [CalendarEvent.id] as backed by a system-provider row: "cp:<eventId>". */
    private const val ID_PREFIX = "cp:"

    fun isProviderEventId(id: String): Boolean = id.startsWith(ID_PREFIX)
    private fun rowId(eventId: String): Long? = eventId.removePrefix(ID_PREFIX).toLongOrNull()
    private fun eventId(rowId: Long): String = "$ID_PREFIX$rowId"

    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** A calendar exposed by the system provider (one per DAVx5 collection / local account). */
    data class ProviderCalendar(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val color: Int,
        val writable: Boolean
    )

    fun calendars(context: Context): List<ProviderCalendar> {
        if (!hasReadPermission(context)) return emptyList()
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val out = mutableListOf<ProviderCalendar>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, cols, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val access = c.getInt(4)
                    out += ProviderCalendar(
                        id = c.getLong(0),
                        displayName = c.getString(1) ?: "Calendar",
                        accountName = c.getString(2) ?: "",
                        color = c.getInt(3),
                        writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                    )
                }
            }
        }
        return out
    }

    /** First writable calendar, preferring a synced (non-LOCAL) account over a device-local one. */
    fun defaultWritableCalendarId(context: Context): Long? {
        val writable = calendars(context).filter { it.writable }
        return writable.firstOrNull { !it.accountName.equals("LOCAL", ignoreCase = true) }?.id
            ?: writable.firstOrNull()?.id
    }

    /** Expanded occurrences overlapping [from, to), pulled from the Instances table. */
    fun occurrences(context: Context, from: Long, to: Long): List<EventOccurrence> {
        if (!hasReadPermission(context)) return emptyList()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_ID
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from)
            ContentUris.appendId(this, to)
        }.build()
        val out = mutableListOf<EventOccurrence>()
        runCatching {
            context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        val begin = c.getLong(1)
                        val end = c.getLong(2)
                        val allDay = c.getInt(4) == 1
                        val dur = ((end - begin) / 60_000L).toInt().coerceAtLeast(0)
                        val color = c.getInt(7)
                        val event = CalendarEvent(
                            id = eventId(c.getLong(0)),
                            calendarId = c.getLong(9).toString(),
                            title = c.getString(3) ?: "(no title)",
                            description = c.getString(6) ?: "",
                            location = c.getString(5) ?: "",
                            start = begin,
                            durationMinutes = dur,
                            allDay = allDay,
                            color = if (color != 0) String.format("#%06X", 0xFFFFFF and color) else null,
                            recurrence = RecurrenceRule.fromRRule(c.getString(8))
                        )
                        out += EventOccurrence(event, begin, end)
                    }
                }
        }
        return out
    }

    /** Insert or update an event row. Returns the provider-backed [CalendarEvent.id], or null. */
    fun upsert(context: Context, event: CalendarEvent, calendarId: Long): String? {
        if (!hasWritePermission(context)) return null
        val tz = TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, event.start)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            val rrule = event.recurrence?.toRRule()
            if (rrule != null) {
                put(CalendarContract.Events.RRULE, rrule)
                put(CalendarContract.Events.DURATION, "P${event.durationMinutes}M")
                putNull(CalendarContract.Events.DTEND)
            } else {
                put(CalendarContract.Events.DTEND, event.end)
                putNull(CalendarContract.Events.DURATION)
            }
        }
        return runCatching {
            val existing = rowId(event.id)
            if (existing != null) {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing)
                context.contentResolver.update(uri, values, null, null)
                replaceReminder(context, existing, event.reminderMinutes)
                event.id
            } else {
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                val newId = uri?.let { ContentUris.parseId(it) } ?: return null
                replaceReminder(context, newId, event.reminderMinutes)
                eventId(newId)
            }
        }.getOrNull()
    }

    fun delete(context: Context, event: CalendarEvent): Boolean {
        if (!hasWritePermission(context)) return false
        val id = rowId(event.id) ?: return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun replaceReminder(context: Context, eventRowId: Long, minutes: Int?) {
        runCatching {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventRowId.toString())
            )
            if (minutes != null) {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventRowId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
        }
    }
}
