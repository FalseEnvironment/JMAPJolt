package com.falseenvironment.jmapjolt

import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Minimal iCalendar (RFC 5545) reader/writer for [CalendarEvent].
 * Round-trips UID, SUMMARY, DESCRIPTION, LOCATION, DTSTART/DTEND (timed & all-day),
 * RRULE (via [RecurrenceRule]) and a single display VALARM (reminder minutes).
 */
object CalendarIcs {

    private const val CRLF = "\r\n"

    // ---- export ---------------------------------------------------------------------------

    /** Serialize [events] into a single VCALENDAR document. */
    fun toIcs(events: List<CalendarEvent>): String = buildString {
        append("BEGIN:VCALENDAR").append(CRLF)
        append("VERSION:2.0").append(CRLF)
        append("PRODID:-//JMAPJolt//Calendar//EN").append(CRLF)
        append("CALSCALE:GREGORIAN").append(CRLF)
        events.forEach { append(toVEvent(it)) }
        append("END:VCALENDAR").append(CRLF)
    }

    /** Serialize a single event as a VEVENT block (used for CalDAV PUT too). */
    fun toVEvent(e: CalendarEvent): String = buildString {
        append("BEGIN:VEVENT").append(CRLF)
        append("UID:").append(e.id).append(CRLF)
        append("DTSTAMP:").append(utcStamp(System.currentTimeMillis())).append(CRLF)
        if (e.allDay) {
            append("DTSTART;VALUE=DATE:").append(dateStamp(e.start)).append(CRLF)
            append("DTEND;VALUE=DATE:").append(dateStamp(e.start + maxOf(1, e.durationMinutes / 1440) * 86_400_000L)).append(CRLF)
        } else {
            append("DTSTART:").append(utcStamp(e.start)).append(CRLF)
            append("DTEND:").append(utcStamp(e.end)).append(CRLF)
        }
        append(fold("SUMMARY:" + escape(e.title))).append(CRLF)
        if (e.description.isNotBlank()) append(fold("DESCRIPTION:" + escape(e.description))).append(CRLF)
        if (e.location.isNotBlank()) append(fold("LOCATION:" + escape(e.location))).append(CRLF)
        e.recurrence?.let { append("RRULE:").append(it.toRRule()).append(CRLF) }
        e.reminderMinutes?.let {
            append("BEGIN:VALARM").append(CRLF)
            append("ACTION:DISPLAY").append(CRLF)
            append("DESCRIPTION:").append(escape(e.title.ifBlank { "Reminder" })).append(CRLF)
            append("TRIGGER:-PT").append(it).append("M").append(CRLF)
            append("END:VALARM").append(CRLF)
        }
        append("END:VEVENT").append(CRLF)
    }

    // ---- import ---------------------------------------------------------------------------

    /** Parse every VEVENT in an ICS document into events bound to [calendarId]. */
    fun parse(ics: String, calendarId: String): List<CalendarEvent> {
        val lines = unfold(ics)
        val events = mutableListOf<CalendarEvent>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().equals("BEGIN:VEVENT", true)) {
                val end = lines.indexOfFirst2(i + 1) { it.trim().equals("END:VEVENT", true) }
                val block = lines.subList(i, if (end < 0) lines.size else end)
                parseVEvent(block, calendarId)?.let { events += it }
                i = if (end < 0) lines.size else end + 1
            } else i++
        }
        return events
    }

    private fun parseVEvent(block: List<String>, calendarId: String): CalendarEvent? {
        var uid: String? = null
        var title = ""
        var description = ""
        var location = ""
        var start: Long? = null
        var end: Long? = null
        var allDay = false
        var rrule: String? = null
        var reminder: Int? = null
        var inAlarm = false

        for (raw in block) {
            val line = raw.trim()
            if (line.equals("BEGIN:VALARM", true)) { inAlarm = true; continue }
            if (line.equals("END:VALARM", true)) { inAlarm = false; continue }
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val nameWithParams = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val name = nameWithParams.substringBefore(';').uppercase()
            val params = nameWithParams.substringAfter(';', "")
            when {
                inAlarm && name == "TRIGGER" -> reminder = parseTriggerMinutes(value)
                name == "UID" -> uid = value
                name == "SUMMARY" -> title = unescape(value)
                name == "DESCRIPTION" -> description = unescape(value)
                name == "LOCATION" -> location = unescape(value)
                name == "RRULE" -> rrule = value
                name == "DTSTART" -> {
                    allDay = params.contains("VALUE=DATE", true) && !value.contains("T")
                    start = parseStamp(value)
                }
                name == "DTEND" -> end = parseStamp(value)
            }
        }
        val s = start ?: return null
        val durationMinutes = when {
            end != null -> ((end!! - s) / 60_000L).toInt().coerceAtLeast(if (allDay) 1440 else 30)
            allDay -> 1440
            else -> 60
        }
        return CalendarEvent(
            id = uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            calendarId = calendarId,
            title = title,
            description = description,
            location = location,
            start = if (allDay) CalendarTimelineView.midnight(s) else s,
            durationMinutes = durationMinutes,
            allDay = allDay,
            recurrence = RecurrenceRule.fromRRule(rrule),
            reminderMinutes = reminder
        )
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun parseTriggerMinutes(value: String): Int? {
        // e.g. -PT15M, -PT1H, -P1D  (negative = before start)
        val v = value.trim().uppercase()
        if (!v.startsWith("-P")) return null
        val body = v.removePrefix("-P")
        val num = Regex("(\\d+)([WDHM])").findAll(body)
        var minutes = 0
        for (m in num) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            minutes += when (m.groupValues[2]) {
                "W" -> n * 7 * 24 * 60; "D" -> n * 24 * 60; "H" -> n * 60; else -> n
            }
        }
        return minutes.takeIf { it > 0 }
    }

    private fun utcStamp(epoch: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epoch }
        return "%04d%02d%02dT%02d%02d%02dZ".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun dateStamp(epoch: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = epoch }
        return "%04d%02d%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun parseStamp(s: String): Long? = runCatching {
        val t = s.trim()
        if (!t.contains("T")) {
            // DATE only (all-day): local midnight
            val c = Calendar.getInstance()
            c.clear()
            c.set(t.substring(0, 4).toInt(), t.substring(4, 6).toInt() - 1, t.substring(6, 8).toInt())
            return@runCatching c.timeInMillis
        }
        val utc = t.endsWith("Z")
        val tz = if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val b = t.removeSuffix("Z")
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(
            b.substring(0, 4).toInt(), b.substring(4, 6).toInt() - 1, b.substring(6, 8).toInt(),
            b.substring(9, 11).toInt(), b.substring(11, 13).toInt(),
            if (b.length >= 15) b.substring(13, 15).toInt() else 0)
        c.timeInMillis
    }.getOrNull()

    /** RFC5545 line folding at 75 octets (approx by chars — safe for our ASCII-ish content). */
    private fun fold(line: String): String {
        if (line.length <= 75) return line
        val sb = StringBuilder()
        var idx = 0
        while (idx < line.length) {
            val take = if (idx == 0) 75 else 74
            val endChar = minOf(idx + take, line.length)
            if (idx > 0) sb.append(CRLF).append(' ')
            sb.append(line, idx, endChar)
            idx = endChar
        }
        return sb.toString()
    }

    /** Reverse line folding: continuation lines start with space/tab. */
    private fun unfold(ics: String): List<String> {
        val out = mutableListOf<String>()
        for (line in ics.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + line.substring(1)
            } else out += line
        }
        return out
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    private fun unescape(s: String): String = s
        .replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",")
        .replace("\\;", ";").replace("\\\\", "\\")

    private inline fun List<String>.indexOfFirst2(from: Int, pred: (String) -> Boolean): Int {
        for (j in from until size) if (pred(this[j])) return j
        return -1
    }
}
