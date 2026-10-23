package com.falseenvironment.jmapjolt

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events from the user's distributor app (ntfy, ...).
 *
 * This is the UnifiedPush 3 API: a non-exported [PushService] declared with the
 * `org.unifiedpush.android.connector.PUSH_EVENT` action. The library's own receiver talks
 * to the distributor and binds this service to deliver events. The old `MessagingReceiver`
 * broadcast API is deprecated and its direct intent filters also disable the library
 * receiver, which makes delivery dependent on the distributor's intent shape.
 */
class UnifiedPushService : PushService() {

    override fun onMessage(message: PushMessage, instance: String) {
        val context = applicationContext
        val payload = message.content
        Log.d(
            TAG,
            "UnifiedPush message received (instance=$instance, bytes=${payload.size}, " +
                "decrypted=${message.decrypted})"
        )

        if (!PushSubscriptionManager.isEnabled(context)) {
            Log.d(TAG, "UnifiedPush is disabled - ignoring message")
            return
        }

        val plaintext = WebPushKeys.decrypt(context, payload)

        // RFC 8620 §7.2.2: right after a PushSubscription is created the server posts a
        // PushVerification here and refuses to send anything else until the code comes
        // back via PushSubscription/set. This is the step that makes push active at all.
        val verification = plaintext?.let { parsePushVerification(it) }
        if (verification != null) {
            val (subscriptionId, code) = verification
            Log.d(TAG, "PushVerification received (subscription=$subscriptionId)")
            receiverScope.launch {
                PushSubscriptionManager.verify(context, subscriptionId, code)
            }
            return
        }

        if (plaintext == null) {
            // Anyone who learns the endpoint URL can POST to it, so an undecryptable payload
            // is unauthenticated data: never render it, and never let it drive a sync.
            if (consumePendingTest(context)) {
                Log.d(TAG, "Undecryptable payload matched a pending Settings test — showing test notification")
                BackgroundEmailSyncReceiver.showPushNotification(
                    context, context.getString(R.string.settings_unifiedpush_test_body)
                )
                return
            }
            if (WebPushKeys.hasKeys(context)) {
                Log.w(TAG, "Dropping push message that failed WebPush decryption (${payload.size} bytes)")
                return
            }
            // No keys yet: the subscription was never registered with encryption, so there is
            // nothing to authenticate against. Treat it as a bare wake-up — sync, show nothing.
            Log.w(TAG, "Push received before WebPush keys exist — syncing without rendering the payload")
        } else {
            // Any authenticated push proves the server accepted the subscription, even if
            // the verification answer was missed while the app was not running.
            receiverScope.launch { PushSubscriptionManager.notePushReceived(context) }
        }

        // draft-ietf-jmap-emailpush: the server pushes the ids of the delivered messages,
        // so the worker notifies exactly those (in any mailbox) instead of diffing INBOX.
        val emailPush = plaintext?.let { parseEmailPushPayload(it) }
        if (emailPush != null) {
            Log.d(TAG, "EmailPush received (${emailPush.emailIds.size} email(s))")
        }

        // A decrypted payload is a JMAP EmailPush/StateChange, not user-facing text. The
        // new-mail notification is raised by EmailSyncWorker once it knows what changed.
        // Expedited: on Android 12+ this runs even in Doze (subject to quota) instead of
        // waiting for a maintenance window, which is what keeps the notification instant.
        val work = OneTimeWorkRequestBuilder<EmailSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (emailPush != null) {
            work.setInputData(
                Data.Builder()
                    .putStringArray(
                        EmailSyncWorker.KEY_EMAIL_IDS,
                        emailPush.emailIds.toTypedArray()
                    )
                    .apply {
                        emailPush.accountId?.let {
                            putString(EmailSyncWorker.KEY_JMAP_ACCOUNT_ID, it)
                        }
                    }
                    .build()
            )
        }
        WorkManager.getInstance(context).enqueue(work.build())

        context.sendBroadcast(Intent(ACTION_PUSH_MESSAGE_RECEIVED).setPackage(context.packageName))
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val context = applicationContext
        Log.d(TAG, "UnifiedPush new endpoint (instance=$instance, host=${LogRedact.host(endpoint.url)})")
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UP_ENDPOINT, endpoint.url)
            .putLong(KEY_UP_ENDPOINT_UPDATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_UP_REGISTERED, true)
            .apply()

        if (!endpoint.url.startsWith("https://")) {
            Log.w(TAG, "Rejecting non-HTTPS push endpoint (host=${LogRedact.host(endpoint.url)})")
            return
        }
        receiverScope.launch {
            // Creates/refreshes the JMAP PushSubscription; the verification that follows
            // arrives on this same endpoint and is answered in onMessage.
            if (PushSubscriptionManager.ensure(context)) {
                Log.d(TAG, "PushSubscription registered - new mail is pushed instantly")
            } else {
                Log.w(TAG, "PushSubscription not active - periodic fallback (15 min) is the active path")
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "UnifiedPush registration failed (instance=$instance, reason=$reason)")
    }

    override fun onUnregistered(instance: String) {
        val context = applicationContext
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_UP_ENDPOINT)
            .putBoolean(KEY_UP_REGISTERED, false)
            .apply()
    }

    companion object {
        const val ACTION_PUSH_MESSAGE_RECEIVED = "com.falseenvironment.jmapjolt.ACTION_PUSH_MESSAGE_RECEIVED"
        private const val TAG = "UnifiedPushService"
        private const val PREFS_NAME = "mail_prefs"
        private const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
        private const val KEY_UP_REGISTERED = "up_registered"

        private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private const val KEY_PUSH_TEST_PENDING_UNTIL = "up_test_pending_until"
        private const val PUSH_TEST_WINDOW_MS = 2 * 60 * 1000L

        /**
         * Marks that Settings just sent a test push, so the plaintext payload that comes back
         * is allowed to raise a notification. Without this window an undecryptable message is
         * always dropped, and a stranger POSTing to the endpoint cannot make the app buzz.
         */
        fun markPendingTest(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_PUSH_TEST_PENDING_UNTIL, System.currentTimeMillis() + PUSH_TEST_WINDOW_MS)
                .apply()
        }

        /** True until the test payload has been delivered and shown. */
        fun isTestPending(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_PUSH_TEST_PENDING_UNTIL, 0L) > System.currentTimeMillis()

        /** Single-use: the window closes on the first message that claims it. */
        private fun consumePendingTest(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val until = prefs.getLong(KEY_PUSH_TEST_PENDING_UNTIL, 0L)
            if (until <= 0L) return false
            prefs.edit().remove(KEY_PUSH_TEST_PENDING_UNTIL).apply()
            return System.currentTimeMillis() <= until
        }

        /**
         * {"@type":"PushVerification","pushSubscriptionId":"...","verificationCode":"..."}
         * A decrypted StateChange carries a different @type, so it falls through to the sync.
         */
        private fun parsePushVerification(payload: ByteArray): Pair<String, String>? = try {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.optString("@type") != "PushVerification") {
                null
            } else {
                val id = json.optString("pushSubscriptionId").takeIf { it.isNotBlank() }
                val code = json.optString("verificationCode").takeIf { it.isNotBlank() }
                if (id != null && code != null) id to code else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal data class EmailPushPayload(val accountId: String?, val emailIds: List<String>)

/**
 * draft-ietf-jmap-emailpush:
 * {"@type":"EmailPush","accountId":"...","emails":[{"id":"..."}],"state":"..."}
 * Only the ids are used: the worker fetches full summaries for exactly those.
 */
internal fun parseEmailPushPayload(payload: ByteArray): EmailPushPayload? = try {
    val json = JSONObject(String(payload, Charsets.UTF_8))
    if (json.optString("@type") != "EmailPush") {
        null
    } else {
        val emails = json.optJSONArray("emails")
        val ids = buildList {
            if (emails != null) {
                for (i in 0 until emails.length()) {
                    emails.optJSONObject(i)
                        ?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                }
            }
        }.distinct()
        if (ids.isEmpty()) {
            null
        } else {
            EmailPushPayload(
                accountId = json.optString("accountId").takeIf { it.isNotBlank() },
                emailIds = ids
            )
        }
    }
} catch (_: Exception) {
    null
}
