package com.falseenvironment.jmapjolt

import android.content.Context
import org.json.JSONArray
import java.util.Calendar

/**
 * Local-first calendar cache. Events are persisted as JSON in a private prefs file and
 * survive offline; JMAP sync reconciles against this store. Recurrence is expanded on read.
 */
object CalendarStore {
    private const val PREFS = "calendar_store"
    private const val KEY_EVENTS = "events"
    private const val MAX_OCCURRENCES = 2000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<CalendarEvent> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CalendarEvent.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Live (non-deleted) events only. */
    fun active(context: Context): List<CalendarEvent> = all(context).filter { !it.deleted }

    fun find(context: Context, id: String): CalendarEvent? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun saveAll(context: Context, events: List<CalendarEvent>) {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_EVENTS, arr.toString()).apply()
        CalendarWidgetProvider.refreshAll(context)
    }

    @Synchronized
    fun upsert(context: Context, event: CalendarEvent) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list += event
        saveAll(context, list)
    }

    /** Soft-delete: keep a tombstone so the next sync can destroy it server-side. */
    @Synchronized
    fun softDelete(context: Context, id: String) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val ev = list[idx]
        if (ev.jmapId == null) {
            list.removeAt(idx) // never synced — drop outright
        } else {
            list[idx] = ev.copy(deleted = true, updatedAt = System.currentTimeMillis())
        }
        saveAll(context, list)
    }

    /** Hard-remove (used after server confirms destroy). */
    @Synchronized
    fun purge(context: Context, id: String) {
        saveAll(context, all(context).filterNot { it.id == id })
    }

    /**
     * Expand all active events into concrete occurrences overlapping [from, to).
     * Sorted by start time.
     */
    fun occurrences(context: Context, from: Long, to: Long): List<EventOccurrence> {
        val out = mutableListOf<EventOccurrence>()
        for (event in active(context)) {
            expand(event, from, to, out)
        }
        return out.sortedBy { it.start }
    }

    private fun expand(event: CalendarEvent, from: Long, to: Long, out: MutableList<EventOccurrence>) {
        val rule = event.recurrence
        val durMs = event.durationMinutes * 60_000L
        if (rule == null) {
            if (event.start < to && event.end > from) {
                out += EventOccurrence(event, event.start, event.end)
            }
            return
        }

        val cal = Calendar.getInstance().apply { timeInMillis = event.start }
        var emitted = 0
        var guard = 0
        val untilCap = rule.until ?: Long.MAX_VALUE

        while (guard++ < MAX_OCCURRENCES) {
            // Candidate start instants for this interval step, chronological.
            val occStarts = occurrencesForStep(event, rule, cal).sorted()
            for (s in occStarts) {
                if (s > untilCap) return
                if (rule.count != null && emitted >= rule.count) return
                emitted++
                if (s >= to) return // monotonic increasing — nothing further is in window
                if (s + durMs > from) out += EventOccurrence(event, s, s + durMs)
            }
            advance(rule, cal)
            if (cal.timeInMillis > to && rule.byDay.isEmpty()) return
            if (cal.timeInMillis > to + 7L * 86_400_000L) return
            if (rule.until != null && cal.timeInMillis > untilCap) return
        }
    }

    /** Concrete start instants generated for the current step of the cursor. */
    private fun occurrencesForStep(
        event: CalendarEvent,
        rule: RecurrenceRule,
        cursor: Calendar
    ): List<Long> {
        if (rule.freq == RecurrenceFreq.WEEKLY && rule.byDay.isNotEmpty()) {
            // Emit each selected weekday within the cursor's week.
            val weekStart = (cursor.clone() as Calendar).apply {
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            }
            val origin = Calendar.getInstance().apply { timeInMillis = event.start }
            return rule.byDay.sorted().map { dow ->
                (weekStart.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_WEEK, dow)
                    set(Calendar.HOUR_OF_DAY, origin.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, origin.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.filter { it >= event.start }
        }
        return listOf(cursor.timeInMillis)
    }

    private fun advance(rule: RecurrenceRule, cal: Calendar) {
        when (rule.freq) {
            RecurrenceFreq.DAILY -> cal.add(Calendar.DAY_OF_MONTH, rule.interval)
            RecurrenceFreq.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, rule.interval)
            RecurrenceFreq.MONTHLY -> cal.add(Calendar.MONTH, rule.interval)
            RecurrenceFreq.YEARLY -> cal.add(Calendar.YEAR, rule.interval)
        }
    }
}
