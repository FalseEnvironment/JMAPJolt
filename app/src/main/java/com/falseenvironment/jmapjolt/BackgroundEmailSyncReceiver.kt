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

        // Server-generated previews of forwarded/replied mail are cluttered with
        // boilerplate: dashed "Forwarded message" / "Original Message" separators,
        // reply intros, quoted ">" lines, and the forwarded header block
        // (From:/To:/Date:/Subject:/...). Drop those lines and keep the real body.
        private val FORWARD_SEPARATOR = Regex("^-+\\s*(forwarded|original)\\b.*", RegexOption.IGNORE_CASE)
        private val REPLY_INTRO = Regex("^On .+wrote:\\s*$", RegexOption.IGNORE_CASE)
        private val BEGIN_FORWARD = Regex("^begin forwarded message:?\\s*$", RegexOption.IGNORE_CASE)
        private val HEADER_LINE = Regex(
            "^(from|to|cc|bcc|date|sent|subject|reply-to)\\s*:.*",
            RegexOption.IGNORE_CASE
        )

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
            val message = cleanPreview(email.preview)
            return when {
                subject.isBlank() -> message
                message.isBlank() -> subject
                else -> "$subject\n\n$message"
            }
        }

        private fun cleanPreview(raw: String): String {
            val cleaned = raw.lineSequence()
                .map { it.trim() }
                .filterNot { t ->
                    t.isEmpty() ||
                        t.startsWith(">") ||
                        FORWARD_SEPARATOR.matches(t) ||
                        BEGIN_FORWARD.matches(t) ||
                        REPLY_INTRO.matches(t) ||
                        HEADER_LINE.matches(t)
                }
                .joinToString(" ")
                // Strip leaked CSS: brace blocks ({ margin:0; padding:0; }) and any
                // leftover "property: value;" declarations.
                .replace(Regex("\\{[^{}]*\\}"), " ")
                .replace(Regex("[.#@]?[\\w-]+\\s*\\{[^{}]*", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("[a-zA-Z-]+\\s*:\\s*[^;{}\\n]+;"), " ")
                .replace(Regex("<https?://[^>]*>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return cleaned.ifBlank { raw.trim() }
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
