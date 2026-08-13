package com.falseenvironment.jmapjolt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Rolls the home-screen widgets over to the new day.
 *
 * `updatePeriodMillis` only guarantees a refresh roughly every 30 minutes and is
 * suspended in doze, so "Today"/"Tomorrow" labels, the agenda window and the week grid
 * could stay on yesterday's date for hours. This arms one alarm at the next local
 * midnight, redraws every widget when it fires, and immediately arms the following one.
 *
 * Midnight is computed in the calendar's own zone ([CalendarPrefs.zone] — the Settings
 * override when set, the device zone otherwise), so the rollover matches the dates the
 * app itself renders. A zone or clock change re-arms the alarm through the manifest
 * intent filters on this receiver.
 */
class WidgetDayRollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DAY_ROLL, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> {
                Log.d(TAG, "Day roll (${intent.action}) — refreshing widgets")
                CalendarPrefs.warmTimeZone(context)
                refreshWidgets(context)
                schedule(context)
            }
        }
    }

    companion object {
        private const val TAG = "WidgetDayRoll"
        const val ACTION_DAY_ROLL = "com.falseenvironment.jmapjolt.WIDGET_DAY_ROLL"
        private const val REQUEST_CODE = 0x5AFE0002
        /** A few seconds past midnight, so "today" has definitely changed. */
        private const val MIDNIGHT_SLACK_MS = 5_000L

        fun refreshWidgets(context: Context) {
            runCatching { CalendarWidgetProvider.refreshAll(context) }
            runCatching { CalendarWeekWidgetProvider.refreshAll(context) }
            runCatching { InboxWidgetProvider.refreshAll(context) }
        }

        /** (Re)arms the alarm for the next local midnight. Safe to call repeatedly. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                app, REQUEST_CODE,
                Intent(app, WidgetDayRollReceiver::class.java).setAction(ACTION_DAY_ROLL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = nextMidnight()
            try {
                // Exact when the user granted it (the app already uses exact alarms for
                // reminders); otherwise an inexact alarm still rolls the day over, just
                // with some doze slack.
                if (CalendarReminderScheduler.canScheduleExact(app)) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
                }
            } catch (_: SecurityException) {
                alarm.set(AlarmManager.RTC, at, pi)
            }
        }

        /** Next 00:00 in the calendar time zone, as an epoch instant. */
        private fun nextMidnight(): Long = CalendarPrefs.calendar().apply {
            timeInMillis = System.currentTimeMillis()
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis + MIDNIGHT_SLACK_MS
    }
}
