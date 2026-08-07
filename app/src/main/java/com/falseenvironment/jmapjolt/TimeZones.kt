package com.falseenvironment.jmapjolt

import java.util.TimeZone

/**
 * Curated UTC-offset list for the calendar time-zone picker.
 *
 * The full tzdb has ~600 ids, which is unusable in a dropdown, so each whole/half
 * offset is represented by one well-known city. Ordered as the settings row reads
 * top-to-bottom: negative offsets first, UTC in the middle, positive offsets last.
 */
object TimeZones {

    data class Entry(val zoneId: String, val city: String)

    /** One representative city per offset, from UTC-12 up to UTC+14. */
    val entries: List<Entry> = listOf(
        Entry("Etc/GMT+12", "Baker Island"),
        Entry("Pacific/Pago_Pago", "Pago Pago"),
        Entry("Pacific/Honolulu", "Honolulu"),
        Entry("Pacific/Marquesas", "Marquesas"),
        Entry("America/Anchorage", "Anchorage"),
        Entry("America/Los_Angeles", "Los Angeles"),
        Entry("America/Denver", "Denver"),
        Entry("America/Chicago", "Chicago"),
        Entry("America/New_York", "New York"),
        Entry("America/Halifax", "Halifax"),
        Entry("America/St_Johns", "St. John's"),
        Entry("America/Sao_Paulo", "Sao Paulo"),
        Entry("Atlantic/South_Georgia", "South Georgia"),
        Entry("Atlantic/Azores", "Azores"),
        Entry("UTC", "UTC"),
        Entry("Europe/London", "London"),
        Entry("Europe/Rome", "Italy/Rome"),
        Entry("Europe/Athens", "Athens"),
        Entry("Europe/Moscow", "Moscow"),
        Entry("Asia/Tehran", "Tehran"),
        Entry("Asia/Dubai", "Dubai"),
        Entry("Asia/Kabul", "Kabul"),
        Entry("Asia/Karachi", "Karachi"),
        Entry("Asia/Kolkata", "Kolkata"),
        Entry("Asia/Kathmandu", "Kathmandu"),
        Entry("Asia/Dhaka", "Dhaka"),
        Entry("Asia/Yangon", "Yangon"),
        Entry("Asia/Bangkok", "Bangkok"),
        Entry("Asia/Shanghai", "Shanghai"),
        Entry("Asia/Tokyo", "Tokyo"),
        Entry("Australia/Adelaide", "Adelaide"),
        Entry("Australia/Sydney", "Sydney"),
        Entry("Pacific/Noumea", "Noumea"),
        Entry("Pacific/Auckland", "Auckland"),
        Entry("Pacific/Kiritimati", "Kiritimati"),
    )

    /**
     * Current offset in minutes, DST included, so the labels match what the clock
     * actually shows today (Rome reads +2 in summer and +1 in winter).
     */
    private fun offsetMinutes(zoneId: String): Int {
        val tz = TimeZone.getTimeZone(zoneId)
        return tz.getOffset(System.currentTimeMillis()) / 60000
    }

    /** "+2" / "-3:30" / "+0", the prefix used in the dropdown rows. */
    fun offsetLabel(zoneId: String): String {
        val total = offsetMinutes(zoneId)
        val sign = if (total < 0) "-" else "+"
        val abs = kotlin.math.abs(total)
        val hours = abs / 60
        val minutes = abs % 60
        return if (minutes == 0) "$sign$hours" else "$sign$hours:%02d".format(minutes)
    }

    /** "+2 Italy/Rome" — the row text and the collapsed settings value. */
    fun labelFor(zoneId: String): String {
        val entry = entries.firstOrNull { it.zoneId == zoneId }
        val city = entry?.city ?: zoneId.substringAfterLast('/').replace('_', ' ')
        return if (zoneId == "UTC") "+0 (UTC)" else "${offsetLabel(zoneId)} $city"
    }

    /** Dropdown rows, in list order. */
    fun labels(): List<String> = entries.map { labelFor(it.zoneId) }
}
