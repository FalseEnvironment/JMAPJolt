package com.falseenvironment.jmapjolt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** Recurrence frequency, mirrors the iCalendar FREQ part used by JMAP JSCalendar. */
enum class RecurrenceFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * Subset of an iCalendar RRULE that the editor can round-trip:
 * FREQ, INTERVAL, COUNT, UNTIL and (for weekly) BYDAY.
 */
data class RecurrenceRule(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val count: Int? = null,
    val until: Long? = null,
    /** Days of week as [Calendar.SUNDAY]..[Calendar.SATURDAY]; only used for weekly. */
    val byDay: List<Int> = emptyList()
) {
    fun toRRule(): String {
        val parts = mutableListOf("FREQ=${freq.name}")
        if (interval > 1) parts += "INTERVAL=$interval"
        count?.let { parts += "COUNT=$it" }
        until?.let { parts += "UNTIL=${formatUtc(it)}" }
        if (byDay.isNotEmpty()) parts += "BYDAY=${byDay.joinToString(",") { dayToken(it) }}"
        return parts.joinToString(";")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("freq", freq.name)
        put("interval", interval)
        count?.let { put("count", it) }
        until?.let { put("until", it) }
        if (byDay.isNotEmpty()) put("byDay", JSONArray(byDay))
    }

    companion object {
        private val TOKEN_TO_DAY = mapOf(
            "SU" to Calendar.SUNDAY, "MO" to Calendar.MONDAY, "TU" to Calendar.TUESDAY,
            "WE" to Calendar.WEDNESDAY, "TH" to Calendar.THURSDAY, "FR" to Calendar.FRIDAY,
            "SA" to Calendar.SATURDAY
        )
        private val DAY_TO_TOKEN = TOKEN_TO_DAY.entries.associate { (k, v) -> v to k }

        private fun dayToken(day: Int): String = DAY_TO_TOKEN[day] ?: "MO"

        private fun formatUtc(epoch: Long): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epoch }
            return "%04d%02d%02dT%02d%02d%02dZ".format(
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
            )
        }

        private fun parseUtc(s: String): Long? = runCatching {
            val t = s.trim().removeSuffix("Z")
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(
                t.substring(0, 4).toInt(), t.substring(4, 6).toInt() - 1, t.substring(6, 8).toInt(),
                if (t.length >= 11) t.substring(9, 11).toInt() else 0,
                if (t.length >= 13) t.substring(11, 13).toInt() else 0,
                if (t.length >= 15) t.substring(13, 15).toInt() else 0
            )
            c.timeInMillis
        }.getOrNull()

        fun fromRRule(rrule: String?): RecurrenceRule? {
            if (rrule.isNullOrBlank()) return null
            val map = rrule.split(";").mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
            }.toMap()
            val freq = runCatching { RecurrenceFreq.valueOf(map["FREQ"]?.uppercase() ?: return null) }
                .getOrNull() ?: return null
            return RecurrenceRule(
                freq = freq,
                interval = map["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                count = map["COUNT"]?.toIntOrNull(),
                until = map["UNTIL"]?.let { parseUtc(it) },
                byDay = map["BYDAY"]?.split(",")?.mapNotNull { TOKEN_TO_DAY[it.trim().uppercase()] }
                    ?: emptyList()
            )
        }

        fun fromJson(json: JSONObject?): RecurrenceRule? {
            if (json == null) return null
            val freq = runCatching { RecurrenceFreq.valueOf(json.optString("freq")) }.getOrNull()
                ?: return null
            val byDay = json.optJSONArray("byDay")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            } ?: emptyList()
            return RecurrenceRule(
                freq = freq,
                interval = json.optInt("interval", 1).coerceAtLeast(1),
                count = if (json.has("count")) json.getInt("count") else null,
                until = if (json.has("until")) json.getLong("until") else null,
                byDay = byDay
            )
        }
    }
}

/**
 * A calendar event. Times are absolute epoch-millis instants; [allDay] events use the
 * local midnight of the day. [reminderMinutes] is minutes-before-start (null = no reminder).
 */
data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: Long,
    val durationMinutes: Int,
    val allDay: Boolean = false,
    val color: String? = null,
    val recurrence: RecurrenceRule? = null,
    val reminderMinutes: Int? = null,
    /** Server-side JMAP id, null until first synced. */
    val jmapId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Local soft-delete tombstone awaiting server destroy. */
    val deleted: Boolean = false
) {
    val end: Long get() = start + durationMinutes * 60_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("calendarId", calendarId)
        put("title", title)
        put("description", description)
        put("location", location)
        put("start", start)
        put("durationMinutes", durationMinutes)
        put("allDay", allDay)
        color?.let { put("color", it) }
        recurrence?.let { put("recurrence", it.toJson()) }
        reminderMinutes?.let { put("reminderMinutes", it) }
        jmapId?.let { put("jmapId", it) }
        put("updatedAt", updatedAt)
        put("deleted", deleted)
    }

    companion object {
        fun fromJson(json: JSONObject): CalendarEvent = CalendarEvent(
            id = json.getString("id"),
            calendarId = json.optString("calendarId", "local"),
            title = json.optString("title"),
            description = json.optString("description"),
            location = json.optString("location"),
            start = json.getLong("start"),
            durationMinutes = json.optInt("durationMinutes", 60),
            allDay = json.optBoolean("allDay", false),
            color = if (json.has("color")) json.getString("color") else null,
            recurrence = RecurrenceRule.fromJson(json.optJSONObject("recurrence")),
            reminderMinutes = if (json.has("reminderMinutes")) json.getInt("reminderMinutes") else null,
            jmapId = if (json.has("jmapId")) json.getString("jmapId") else null,
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            deleted = json.optBoolean("deleted", false)
        )
    }
}

/** A single concrete occurrence of an event (recurrence already expanded). */
data class EventOccurrence(
    val event: CalendarEvent,
    val start: Long,
    val end: Long
) {
    val durationMinutes: Int get() = ((end - start) / 60_000L).toInt()
}
