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

        val plainBytes = WebPushKeys.decrypt(context, message) ?: message
        val msgStr = plainBytes.toString(Charsets.UTF_8)

        BackgroundEmailSyncReceiver.showPushNotification(context, msgStr)

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )

        context.sendBroadcast(Intent(ACTION_PUSH_MESSAGE_RECEIVED).setPackage(context.packageName))
    }

    override fun onNewEndpoint(context: android.content.Context, endpoint: String, instance: String) {
        Log.d(TAG, "UnifiedPush new endpoint (instance=$instance): $endpoint")
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
