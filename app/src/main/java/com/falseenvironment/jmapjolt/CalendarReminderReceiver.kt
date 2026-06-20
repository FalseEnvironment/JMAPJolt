package com.falseenvironment.jmapjolt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Posts a notification when an event reminder alarm fires. */
class CalendarReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarReminderScheduler.ACTION_REMIND) return
        val eventId = intent.getStringExtra(CalendarReminderScheduler.EXTRA_EVENT_ID) ?: return
        val occStart = intent.getLongExtra(CalendarReminderScheduler.EXTRA_OCC_START, 0L)
        val title = intent.getStringExtra(CalendarReminderScheduler.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Event"
        val location = intent.getStringExtra(CalendarReminderScheduler.EXTRA_LOCATION).orEmpty()

        ensureChannel(context)

        val timeStr = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ENGLISH).format(Date(occStart))
        val text = buildString {
            append(timeStr)
            if (location.isNotBlank()) append(" · ").append(location)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
        }
        val contentPi = PendingIntent.getActivity(
            context, eventId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((eventId + occStart).hashCode(), notification)

        // Recurring events: re-arm the next window now that one has fired.
        CalendarReminderScheduler.reschedule(context)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Calendar event reminders"
                    enableVibration(true)
                    enableLights(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "calendar_reminders"
    }
}
