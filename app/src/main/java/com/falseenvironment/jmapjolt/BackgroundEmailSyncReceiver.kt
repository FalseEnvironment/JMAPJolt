package com.falseenvironment.jmapjolt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

class BackgroundEmailSyncReceiver {

    companion object {
        private const val TAG = "BackgroundEmailSync"
        private const val EMAIL_CHANNEL_ID = "background_email_sync"
        private const val SYNC_CHANNEL_ID = "background_email_sync_status"
        private const val EMAIL_NOTIFICATION_ID = 4001   // group summary
        private const val EMAIL_INDIVIDUAL_BASE = 4010   // 4010, 4011, ... per-email
        private const val SYNC_NOTIFICATION_ID = 4003
        private const val EMAIL_GROUP_KEY = "com.falseenvironment.jmapjolt.email_group"
        private const val PREFS_NAME = "mail_prefs"
        private const val KEY_ACCOUNTS_JSON = "accounts_json"
        private const val KEY_LAST_EMAIL_IDS = "background_last_email_ids"

        suspend fun fetchAndNotify(context: Context) {
            val accounts = readAllAccounts(context)
            if (accounts.isEmpty()) Log.w(TAG, "fetchAndNotify: no accounts")
            accounts.forEach { fetchAndNotify(context, it) }
        }

        suspend fun fetchAndNotify(context: Context, account: JMapClient.ConnectedAccount) {
            Log.d(TAG, "fetchAndNotify: fetching for ${account.email}")
            val emails = JMapClient(context).fetchEmails(account)
            val currentIds = emails.map { it.id }.toSet()
            Log.d(TAG, "fetchAndNotify: got ${emails.size} emails for ${account.email}")
            if (currentIds.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "$KEY_LAST_EMAIL_IDS:${account.email}"
            val previousIds = prefs.getStringSet(key, null)
            if (previousIds == null) {
                Log.d(TAG, "fetchAndNotify: first run, saving baseline (${currentIds.size} ids)")
                prefs.edit().putStringSet(key, HashSet(currentIds)).apply()
                return
            }

            val newEmails = emails.filter { it.id !in previousIds }
            Log.d(TAG, "fetchAndNotify: ${newEmails.size} new emails (prev baseline=${previousIds.size})")
            prefs.edit().putStringSet(key, HashSet(currentIds)).apply()
            if (newEmails.isEmpty()) return

            showNewEmailNotification(context, newEmails)
        }

        fun updateBaseline(context: Context, accountEmail: String, emailIds: Set<String>) {
            if (accountEmail.isBlank() || emailIds.isEmpty()) return
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet("$KEY_LAST_EMAIL_IDS:$accountEmail", HashSet(emailIds))
                    .apply()
        }

        fun addToBaseline(context: Context, accountEmail: String, emailIds: Collection<String>) {
            if (accountEmail.isBlank() || emailIds.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "$KEY_LAST_EMAIL_IDS:$accountEmail"
            val existing = prefs.getStringSet(key, null) ?: return
            prefs.edit().putStringSet(key, HashSet(existing + emailIds)).apply()
        }

        fun readAllAccounts(context: Context): List<JMapClient.ConnectedAccount> {
            val raw = SecureStorage.prefs(context).getString(KEY_ACCOUNTS_JSON, null)
                ?: return emptyList()
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
            val accounts = root.optJSONArray("accounts") ?: return emptyList()
            return (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it)?.toConnectedAccount() }
        }

        internal fun readCurrentAccount(context: Context): JMapClient.ConnectedAccount? {
            val raw =
                    SecureStorage.prefs(context)
                            .getString(KEY_ACCOUNTS_JSON, null)
                            ?: return null
            val root = JSONObject(raw)
            val accounts = root.optJSONArray("accounts") ?: JSONArray()
            val current = root.optString("current", "")
            var fallback: JSONObject? = null
            for (i in 0 until accounts.length()) {
                val account = accounts.optJSONObject(i) ?: continue
                if (fallback == null) fallback = account
                if (account.optString("email").equals(current, ignoreCase = true)) {
                    return account.toConnectedAccount()
                }
            }
            return fallback?.toConnectedAccount()
        }

        private fun JSONObject.toConnectedAccount(): JMapClient.ConnectedAccount {
            return JMapClient.ConnectedAccount(
                    email = optString("email"),
                    password = optString("password"),
                    sessionUrl = optString("sessionUrl"),
                    apiUrl = optString("apiUrl"),
                    accountId = optString("accountId")
            )
        }

        private fun showNewEmailNotification(
            context: Context,
            newEmails: List<JMapClient.EmailSummary>
        ) {
            createNotificationChannels(context)
            val nm = NotificationManagerCompat.from(context)
            val appIntent = openAppIntent(context, 0)

            try {
                if (newEmails.size == 1) {
                    val email = newEmails[0]
                    nm.notify(EMAIL_NOTIFICATION_ID, buildEmailNotification(context, email, appIntent))
                } else {
                    // Individual notifications (silent — summary carries the sound)
                    newEmails.forEachIndexed { index, email ->
                        val n = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(email.from.ifBlank { email.fromEmail })
                            .setContentText(email.subject)
                            .setStyle(NotificationCompat.BigTextStyle()
                                .bigText(email.preview.ifBlank { email.subject }))
                            .setContentIntent(appIntent)
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setGroup(EMAIL_GROUP_KEY)
                            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                            .setWhen(email.receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                            .setShowWhen(true)
                            .build()
                        nm.notify(EMAIL_INDIVIDUAL_BASE + index, n)
                    }

                    // Summary — carries sound/vibration and shows InboxStyle when expanded
                    val inboxStyle = NotificationCompat.InboxStyle()
                        .setSummaryText(context.getString(
                            R.string.background_sync_notification_group, newEmails.size))
                    newEmails.take(6).forEach { email ->
                        val sender = email.from.ifBlank { email.fromEmail }
                        val line = SpannableString("$sender  ${email.subject}")
                        line.setSpan(StyleSpan(Typeface.BOLD), 0, sender.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        inboxStyle.addLine(line)
                    }

                    val summary = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(
                            R.string.background_sync_notification_group, newEmails.size))
                        .setContentText(newEmails.joinToString(", ") {
                            it.from.ifBlank { it.fromEmail } })
                        .setStyle(inboxStyle)
                        .setContentIntent(appIntent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setGroup(EMAIL_GROUP_KEY)
                        .setGroupSummary(true)
                        .build()
                    nm.notify(EMAIL_NOTIFICATION_ID, summary)
                }
            } catch (securityError: SecurityException) {
                Log.w(TAG, "Notification permission missing", securityError)
            }
        }

        private fun buildEmailNotification(
            context: Context,
            email: JMapClient.EmailSummary,
            appIntent: android.app.PendingIntent
        ) = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(email.from.ifBlank { email.fromEmail })
            .setContentText(email.subject)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(email.preview.take(160).ifBlank { email.subject }))
            .setContentIntent(appIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(email.receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .build()

        fun showPushNotification(context: Context, message: String) {
            createNotificationChannels(context)
            val notificationText = message.ifBlank {
                context.getString(R.string.settings_unifiedpush_test_body)
            }
            val notification =
                    NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(context.getString(R.string.settings_unifiedpush_test_title))
                            .setContentText(notificationText)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                            .setContentIntent(openAppIntent(context, 1))
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build()
            try {
                NotificationManagerCompat.from(context).notify(EMAIL_NOTIFICATION_ID + 1, notification)
            } catch (securityError: SecurityException) {
                Log.w(TAG, "Notification permission missing", securityError)
            }
        }

        fun buildSyncInProgressNotification(context: Context): Notification {
            createNotificationChannels(context)
            return NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.background_sync_updating_title))
                    .setContentText(context.getString(R.string.background_sync_updating_text))
                    .setContentIntent(openAppIntent(context, 2))
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
        }

        fun showSyncInProgressNotification(context: Context) {
            val notification = buildSyncInProgressNotification(context)
            try {
                NotificationManagerCompat.from(context).notify(SYNC_NOTIFICATION_ID, notification)
            } catch (securityError: SecurityException) {
                Log.w(TAG, "Notification permission missing", securityError)
            }
        }

        private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
            return PendingIntent.getActivity(
                    context,
                    requestCode,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val emailChannel =
                    NotificationChannel(
                            EMAIL_CHANNEL_ID,
                            context.getString(R.string.background_sync_notification_channel),
                            NotificationManager.IMPORTANCE_DEFAULT
                    )
            val syncChannel =
                    NotificationChannel(
                            SYNC_CHANNEL_ID,
                            context.getString(R.string.background_sync_status_channel),
                            NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
            manager.createNotificationChannel(emailChannel)
            manager.createNotificationChannel(syncChannel)
        }
    }
}
