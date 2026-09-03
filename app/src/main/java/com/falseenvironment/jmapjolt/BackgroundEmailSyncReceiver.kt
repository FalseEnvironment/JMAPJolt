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
        private const val KEY_LAST_EMAIL_IDS = "background_last_email_ids"   // legacy, migrated
        private const val KEY_SEEN_EMAIL_IDS = "background_seen_email_ids"
        // Cap on remembered ids. Stored newest-first, so the oldest fall off. Well above
        // the inbox page size, otherwise an email moved out and back in after a long
        // absence would look new again.
        private const val MAX_SEEN_IDS = 2000

        suspend fun fetchAndNotify(context: Context) {
            val accounts = readAllAccounts(context)
            if (accounts.isEmpty()) Log.w(TAG, "fetchAndNotify: no accounts")
            accounts.forEach { fetchAndNotify(context, it) }
        }

        suspend fun fetchAndNotify(context: Context, account: JMapClient.ConnectedAccount) {
            Log.d(TAG, "fetchAndNotify: fetching for ${account.email}")
            val emails = JMapClient(context).fetchEmails(account)
            Log.d(TAG, "fetchAndNotify: got ${emails.size} emails for ${account.email}")
            if (emails.isEmpty()) return
            val currentIds = emails.map { it.id }

            val seenIds = readSeenIds(context, account.email)
            if (seenIds == null) {
                Log.d(TAG, "fetchAndNotify: first run, saving baseline (${currentIds.size} ids)")
                writeSeenIds(context, account.email, currentIds)
                return
            }

            // "New" means never observed before — not merely absent from the inbox last
            // time. An email the user moved to Archive/Trash and later moved back keeps
            // its id in the seen set, so it does not notify a second time.
            val newEmails = emails.filter { it.id !in seenIds }
            Log.d(TAG, "fetchAndNotify: ${newEmails.size} new emails (seen=${seenIds.size})")
            // Newest first so the cap in writeSeenIds drops the oldest ids, never these.
            writeSeenIds(context, account.email, currentIds + seenIds)
            if (newEmails.isEmpty()) return

            showNewEmailNotification(context, newEmails)
        }

        // Ids already observed for this account, newest first, or null when this account
        // has never synced (the caller then records a baseline and notifies nothing).
        // Reads the legacy unordered background_last_email_ids set once, so an upgrade
        // does not treat the whole inbox as new mail.
        private fun readSeenIds(context: Context, accountEmail: String): LinkedHashSet<String>? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString("$KEY_SEEN_EMAIL_IDS:$accountEmail", null)?.let { stored ->
                return LinkedHashSet(stored.split('\n').filter { it.isNotBlank() })
            }
            val legacy = prefs.getStringSet("$KEY_LAST_EMAIL_IDS:$accountEmail", null) ?: return null
            return LinkedHashSet(legacy)
        }

        // Persist newest-first, de-duplicated and capped at MAX_SEEN_IDS.
        private fun writeSeenIds(context: Context, accountEmail: String, ids: Collection<String>) {
            val capped = LinkedHashSet(ids).take(MAX_SEEN_IDS)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("$KEY_SEEN_EMAIL_IDS:$accountEmail", capped.joinToString("\n"))
                    .remove("$KEY_LAST_EMAIL_IDS:$accountEmail")
                    .apply()
        }

        fun updateBaseline(context: Context, accountEmail: String, emailIds: Set<String>) {
            if (accountEmail.isBlank() || emailIds.isEmpty()) return
            // Union, never replace: dropping ids here is what made a moved-back email
            // look new again.
            val existing = readSeenIds(context, accountEmail).orEmpty()
            writeSeenIds(context, accountEmail, emailIds + existing)
        }

        fun addToBaseline(context: Context, accountEmail: String, emailIds: Collection<String>) {
            if (accountEmail.isBlank() || emailIds.isEmpty()) return
            val existing = readSeenIds(context, accountEmail) ?: return
            writeSeenIds(context, accountEmail, emailIds + existing)
        }

        fun readAllAccounts(context: Context): List<JMapClient.ConnectedAccount> =
            SecureStorage.connectedAccounts(context)

        internal fun readCurrentAccount(context: Context): JMapClient.ConnectedAccount? =
            SecureStorage.currentAccount(context)

        // Generic local-parts that carry no sender identity — fall back to the
        // domain's main label (noreply@bethesda.net -> Bethesda).
        private val GENERIC_LOCALPARTS = setOf(
            "noreply", "no-reply", "donotreply", "do-not-reply", "info", "mail",
            "mailer", "contact", "hello", "support", "notifications", "notification",
            "news", "newsletter", "team", "account", "accounts", "service", "admin"
        )

        // Best-effort human-friendly sender name: prefer the display name, else
        // derive from the email (local-part, or the domain label if generic).
        private fun senderName(displayName: String, emailAddr: String): String {
            displayName.trim().takeIf { it.isNotBlank() }?.let { return it }
            val at = emailAddr.indexOf('@')
            if (at <= 0) return emailAddr
            val local = emailAddr.substring(0, at).lowercase()
            val domain = emailAddr.substring(at + 1)
            val label = if (local in GENERIC_LOCALPARTS) {
                domain.split('.').let { p -> if (p.size >= 2) p[p.size - 2] else p.firstOrNull() ?: domain }
            } else {
                local
            }
            return label.replace(Regex("[._-]+"), " ")
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                .ifBlank { emailAddr }
        }

        // Strip repeated "Fwd:" / "Re:" / "Fw:" prefixes from a subject.
        private val SUBJECT_PREFIX = Regex("^\\s*(fwd?|re|aw|wg|r|i)\\s*:\\s*", RegexOption.IGNORE_CASE)

        private fun cleanSubject(raw: String): String {
            var s = raw.trim()
            while (SUBJECT_PREFIX.containsMatchIn(s)) {
                s = SUBJECT_PREFIX.replaceFirst(s, "").trim()
            }
            return s.ifBlank { raw.trim() }
        }

        // Expanded notification body: subject line, then the message, slightly
        // separated. Collapsed body just shows the message.
        private fun notificationBody(email: JMapClient.EmailSummary): String {
            val subject = cleanSubject(email.subject)
            val message = PreviewText.clean(email.preview)
            return when {
                subject.isBlank() -> message
                message.isBlank() -> subject
                else -> "$subject\n\n$message"
            }
        }

        private fun showNewEmailNotification(
            context: Context,
            newEmails: List<JMapClient.EmailSummary>
        ) {
            createNotificationChannels(context)
            val nm = NotificationManagerCompat.from(context)
            val appIntent = openAppIntent(context, 0)

            try {
                // Each email gets its own stable notification ID derived from its
                // server ID so re-syncs don't create duplicates.
                newEmails.forEach { email ->
                    val notifId = EMAIL_INDIVIDUAL_BASE + (email.id.hashCode() and 0x7FFFFFFF) % 10_000
                    val n = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(senderName(email.from, email.fromEmail))
                        .setContentText(cleanSubject(email.subject))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(notificationBody(email)))
                        .setContentIntent(appIntent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setAllowSystemGeneratedContextualActions(false)
                        .setGroup(EMAIL_GROUP_KEY)
                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                        .setWhen(email.receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                        .setShowWhen(true)
                        .build()
                    nm.notify(notifId, n)
                }

                // Group summary — always required on Android 7+ to bundle the group;
                // carries the sound/vibration.
                val inboxStyle = NotificationCompat.InboxStyle()
                    .setSummaryText(context.getString(
                        R.string.background_sync_notification_group, newEmails.size))
                newEmails.take(6).forEach { email ->
                    val sender = senderName(email.from, email.fromEmail)
                    val line = SpannableString("$sender  ${cleanSubject(email.subject)}")
                    line.setSpan(StyleSpan(Typeface.BOLD), 0, sender.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    inboxStyle.addLine(line)
                }
                val summary = NotificationCompat.Builder(context, EMAIL_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(
                        R.string.background_sync_notification_group, newEmails.size))
                    .setContentText(newEmails.joinToString(", ") {
                        senderName(it.from, it.fromEmail) })
                    .setStyle(inboxStyle)
                    .setContentIntent(appIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAllowSystemGeneratedContextualActions(false)
                    .setGroup(EMAIL_GROUP_KEY)
                    .setGroupSummary(true)
                    .build()
                nm.notify(EMAIL_NOTIFICATION_ID, summary)
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
            .setContentTitle(senderName(email.from, email.fromEmail))
            .setContentText(cleanSubject(email.subject))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationBody(email)))
            .setContentIntent(appIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAllowSystemGeneratedContextualActions(false)
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
