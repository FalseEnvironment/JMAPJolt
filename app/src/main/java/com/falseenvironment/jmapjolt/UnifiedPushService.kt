package com.falseenvironment.jmapjolt

import android.content.Intent
import android.util.Log
import android.content.Context.MODE_PRIVATE
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.MessagingReceiver
import java.util.UUID

class UnifiedPushService : MessagingReceiver() {

    override fun onMessage(context: android.content.Context, message: ByteArray, instance: String) {
        Log.d(TAG, "UnifiedPush message received (instance=$instance, bytes=${message.size})")

        if (WebPushKeys.decrypt(context, message) == null) {
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
                Log.w(TAG, "Dropping push message that failed WebPush decryption (${message.size} bytes)")
                return
            }
            // No keys yet: the subscription was never registered with encryption, so there is
            // nothing to authenticate against. Treat it as a bare wake-up — sync, show nothing.
            Log.w(TAG, "Push received before WebPush keys exist — syncing without rendering the payload")
        }

        // A decrypted payload is a JMAP StateChange, not user-facing text. The new-mail
        // notification is raised by EmailSyncWorker once it knows what actually changed.
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )

        context.sendBroadcast(Intent(ACTION_PUSH_MESSAGE_RECEIVED).setPackage(context.packageName))
    }

    override fun onNewEndpoint(context: android.content.Context, endpoint: String, instance: String) {
        Log.d(TAG, "UnifiedPush new endpoint (instance=$instance, host=${LogRedact.host(endpoint)})")
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UP_ENDPOINT, endpoint)
            .apply()

        EmailSyncWorker.schedule(context)

        receiverScope.launch {
            val account = BackgroundEmailSyncReceiver.readCurrentAccount(context)
            if (account == null) {
                Log.w(TAG, "No account configured — relying on WorkManager fallback only")
                return@launch
            }
            if (!endpoint.startsWith("https://")) {
                Log.w(TAG, "Rejecting non-HTTPS push endpoint (host=${LogRedact.host(endpoint)})")
                return@launch
            }
            val deviceClientId = getOrCreateDeviceClientId(context)
            val ok = try {
                JMapClient(context).registerPushSubscription(context, account, endpoint, deviceClientId)
            } catch (e: Throwable) {
                Log.e(TAG, "registerPushSubscription threw", e)
                false
            }
            if (ok) {
                Log.d(TAG, "PushSubscription/set succeeded — periodic fallback kept active as safety net")
            } else {
                Log.w(TAG, "PushSubscription/set failed — periodic fallback (15 min) is the active path")
            }
        }
    }

    override fun onRegistrationFailed(context: android.content.Context, instance: String) {
        Log.w(TAG, "UnifiedPush registration failed (instance=$instance)")
    }

    override fun onUnregistered(context: android.content.Context, instance: String) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_UP_ENDPOINT)
            .apply()
    }

    companion object {
        const val ACTION_PUSH_MESSAGE_RECEIVED = "com.falseenvironment.jmapjolt.ACTION_PUSH_MESSAGE_RECEIVED"
        private const val TAG = "UnifiedPushService"
        private const val PREFS_NAME = "mail_prefs"
        private const val KEY_LAST_UP_ENDPOINT = "last_up_endpoint"
        private const val KEY_DEVICE_CLIENT_ID = "up_device_client_id"

        private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private const val KEY_PUSH_TEST_PENDING_UNTIL = "up_test_pending_until"
        private const val PUSH_TEST_WINDOW_MS = 2 * 60 * 1000L

        /**
         * Marks that Settings just sent a test push, so the plaintext payload that comes back
         * is allowed to raise a notification. Without this window an undecryptable message is
         * always dropped, and a stranger POSTing to the endpoint cannot make the app buzz.
         */
        fun markPendingTest(context: android.content.Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_PUSH_TEST_PENDING_UNTIL, System.currentTimeMillis() + PUSH_TEST_WINDOW_MS)
                .apply()
        }

        /** Single-use: the window closes on the first message that claims it. */
        private fun consumePendingTest(context: android.content.Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val until = prefs.getLong(KEY_PUSH_TEST_PENDING_UNTIL, 0L)
            if (until <= 0L) return false
            prefs.edit().remove(KEY_PUSH_TEST_PENDING_UNTIL).apply()
            return System.currentTimeMillis() <= until
        }

        private fun getOrCreateDeviceClientId(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_CLIENT_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_CLIENT_ID, generated).apply()
            return generated
        }
    }
}
