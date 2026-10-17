package com.falseenvironment.jmapjolt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Timeline blocks must be positioned by wall-clock time, not by the raw number of
 * milliseconds elapsed since midnight: a DST day is 23 or 25 hours long, so the naive
 * division shifted every block of that column by an hour.
 */
class WallClockMinutesTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun midnight(year: Int, month: Int, day: Int): Long = local(year, month, day, 0)

    @Test
    fun `an evening event keeps its wall-clock position on the spring forward day`() {
        // 29 March 2026: clocks jump forward, the day is only 23 hours long.
        val day = midnight(2026, Calendar.MARCH, 29)
        assertEquals(1200, wallClockMinutes(day, local(2026, Calendar.MARCH, 29, 20), zone))
    }

    @Test
    fun `an evening event keeps its wall-clock position on the fall back day`() {
        // 25 October 2026: clocks fall back, the day is 25 hours long.
        val day = midnight(2026, Calendar.OCTOBER, 25)
        assertEquals(1200, wallClockMinutes(day, local(2026, Calendar.OCTOBER, 25, 20), zone))
    }

    @Test
    fun `the naive elapsed-milliseconds math is what made Sunday one hour longer`() {
        val day = midnight(2026, Calendar.OCTOBER, 25)
        val at20 = local(2026, Calendar.OCTOBER, 25, 20)
        // 20:00 lands 21 real hours after midnight, which used to draw it at 21:00.
        assertEquals(1260, ((at20 - day) / 60_000L).toInt())
        assertEquals(1200, wallClockMinutes(day, at20, zone))
    }

    @Test
    fun `an instant on a later local day clamps to the grid end`() {
        val day = midnight(2026, Calendar.JUNE, 1)
        assertEquals(1440, wallClockMinutes(day, midnight(2026, Calendar.JUNE, 2), zone))
    }

    @Test
    fun `next midnight is 25 hours away on the fall back day`() {
        val day = midnight(2026, Calendar.OCTOBER, 25)
        assertEquals(25L * 3_600_000L, nextMidnight(day, zone) - day)
    }

    @Test
    fun `next midnight is 23 hours away on the spring forward day`() {
        val day = midnight(2026, Calendar.MARCH, 29)
        assertEquals(23L * 3_600_000L, nextMidnight(day, zone) - day)
    }

    @Test
    fun `the day index rolls over at local midnight`() {
        assertEquals(
            localDayIndex(local(2026, Calendar.JUNE, 1, 23, 59), zone) + 1,
            localDayIndex(local(2026, Calendar.JUNE, 2, 0, 1), zone)
        )
    }

    @Test
    fun `instants either side of the fall back stay on the same local day`() {
        val before = localDayIndex(local(2026, Calendar.OCTOBER, 25, 1, 30), zone)
        val after = localDayIndex(local(2026, Calendar.OCTOBER, 25, 4, 30), zone)
        assertEquals(before, after)
    }

    @Test
    fun `instants on the same local day share an index across a DST change`() {
        val dayStart = midnight(2026, Calendar.MARCH, 29)
        assertEquals(
            localDayIndex(dayStart, zone),
            localDayIndex(local(2026, Calendar.MARCH, 29, 20), zone)
        )
    }
}
