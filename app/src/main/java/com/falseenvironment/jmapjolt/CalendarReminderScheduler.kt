package com.falseenvironment.jmapjolt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

/**
 * Schedules exact alarms for event reminders. Recomputes the upcoming-window of occurrences
 * after any edit (or on app open) so recurring events keep firing. Tracks issued request codes
 * so stale alarms can be cancelled before rescheduling.
 */
object CalendarReminderScheduler {
    const val ACTION_REMIND = "com.falseenvironment.jmapjolt.CALENDAR_REMINDER"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_OCC_START = "occ_start"
    const val EXTRA_TITLE = "title"
    const val EXTRA_LOCATION = "location"

    private const val PREFS = "calendar_alarms"
    private const val KEY_CODES = "request_codes"
    private const val WINDOW_AHEAD_MS = 45L * 86_400_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when exact alarms can be scheduled (always pre-S; gated by user grant on S+). */
    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            alarmManager(context).canScheduleExactAlarms()
        else true

    fun requestExactAlarmIntent(): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)

    private fun requestCode(eventId: String, occStart: Long): Int =
        (eventId.hashCode() * 31 + occStart.hashCode())

    private fun pendingIntent(context: Context, code: Int, occurrence: EventOccurrence): PendingIntent {
        val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_EVENT_ID, occurrence.event.id)
            putExtra(EXTRA_OCC_START, occurrence.start)
            putExtra(EXTRA_TITLE, occurrence.event.title)
            putExtra(EXTRA_LOCATION, occurrence.event.location)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Cancels all previously-scheduled reminders and reschedules from the current store. */
    @Synchronized
    fun reschedule(context: Context) {
        cancelAll(context)
        if (!canScheduleExact(context)) return

        val now = System.currentTimeMillis()
        val occs = CalendarStore.occurrences(context, now, now + WINDOW_AHEAD_MS)
        val am = alarmManager(context)
        val codes = JSONArray()

        for (occ in occs) {
            val mins = occ.event.reminderMinutes ?: continue
            val triggerAt = occ.start - mins * 60_000L
            if (triggerAt <= now) continue
            val code = requestCode(occ.event.id, occ.start)
            val pi = pendingIntent(context, code, occ)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                codes.put(code)
            } catch (_: SecurityException) {
                // Lost exact-alarm permission mid-run; stop quietly.
                break
            }
        }
        prefs(context).edit().putString(KEY_CODES, codes.toString()).apply()
    }

    @Synchronized
    fun cancelAll(context: Context) {
        val raw = prefs(context).getString(KEY_CODES, null) ?: return
        val am = alarmManager(context)
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val code = arr.getInt(i)
                val pi = PendingIntent.getBroadcast(
                    context, code,
                    Intent(context, CalendarReminderReceiver::class.java).setAction(ACTION_REMIND),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                pi?.let { am.cancel(it); it.cancel() }
            }
        }
        prefs(context).edit().remove(KEY_CODES).apply()
    }
}
