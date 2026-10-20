package com.falseenvironment.jmapjolt

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Keeps the JMAP PushSubscription that gives UnifiedPush its "instant" behaviour.
 *
 * UnifiedPush only carries the wake-up: the mail server has to be told where to post it,
 * and RFC 8620 makes that registration a two-step handshake:
 *
 *  1. `PushSubscription/set create` with our endpoint, Web Push keys, `types` and `expires`;
 *  2. the server posts a `PushVerification` to the endpoint, and we answer it with
 *     `PushSubscription/set update {verificationCode}`. Until then the server must not
 *     send anything else, so without step 2 no mail push ever arrives.
 *
 * Subscriptions also expire (Stalwart caps them at 7 days), so every app launch renews the
 * recorded one, recreating it when the server no longer knows it or the endpoint rotated.
 *
 * Without this the app still receives mail, but only from the 15-minute WorkManager
 * fallback - which is Android's minimum periodic interval, not an instant notification.
 */
internal object PushSubscriptionManager {

    private const val TAG = "PushSubscription"
    private const val PREFS = "mail_prefs"
    private const val KEY_ENDPOINT = "last_up_endpoint"
    private const val KEY_DEVICE_CLIENT = "up_device_client_id"
    private const val KEY_SUBSCRIPTION_PREFIX = "up_push_subscription_id_"
    private const val KEY_SUBSCRIPTION_ENDPOINT_PREFIX = "up_push_subscription_endpoint_"
    private const val KEY_VERIFIED_PREFIX = "up_push_verified_id_"

    /** Coalesces the launch-time pass and the onNewEndpoint pass into one. */
    private val mutex = Mutex()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UP_ENABLED, false)

    /**
     * Makes sure the current account has a live, verified-capable subscription on this
     * device's current UnifiedPush endpoint. Safe to call on every launch and on every
     * endpoint rotation; failures are logged, never thrown.
     */
    suspend fun ensure(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { ensureLocked(context) }
                .onFailure { Log.e(TAG, "ensure failed", it) }
                .getOrDefault(false)
        }
    }

    /**
     * Turns push off without unregistering from the distributor. Keeping the connector
     * registration keeps the distributor token (and therefore the ntfy topic) stable, so
     * toggling back on does not rotate the endpoint and the test push is delivered at once.
     * The server-side subscription is destroyed so Stalwart stops sending anything.
     */
    suspend fun disable(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val account = BackgroundEmailSyncReceiver.readCurrentAccount(context)
                val storedId = account?.let {
                    prefs(context).getString(subscriptionKey(it.email), null)
                }
                if (account != null && storedId != null) {
                    JMapClient(context).destroyPushSubscription(account, storedId)
                    WorkManager.getInstance(context)
                        .cancelUniqueWork("push-verify-$storedId")
                }
                prefs(context).edit().apply {
                    if (account != null) {
                        remove(subscriptionKey(account.email))
                        remove(subscriptionEndpointKey(account.email))
                        remove(verifiedKey(account.email))
                    }
                }.apply()
                Log.d(TAG, "PushSubscription disabled (distributor registration kept)")
            }.onFailure { Log.w(TAG, "disable failed", it) }
        }
    }

    private suspend fun ensureLocked(context: Context): Boolean {
        if (!isEnabled(context)) {
            Log.d(TAG, "UnifiedPush disabled - not registering a PushSubscription")
            return false
        }
        val endpoint = prefs(context).getString(KEY_ENDPOINT, null)
            ?.takeIf { it.startsWith("https://") }
            ?: run {
                Log.w(TAG, "No UnifiedPush endpoint yet - subscription deferred")
                return false
            }
        val account = BackgroundEmailSyncReceiver.readCurrentAccount(context) ?: run {
            Log.w(TAG, "No connected account - subscription deferred")
            return false
        }

        val deviceClientId = getOrCreateDeviceClientId(context)
        val client = JMapClient(context)
        val storedId = prefs(context).getString(subscriptionKey(account.email), null)
        val storedEndpoint = prefs(context).getString(subscriptionEndpointKey(account.email), null)

        // url and keys are immutable in JMAP: once the distributor rotates the endpoint the
        // old subscription can only be replaced, not patched.
        if (storedId != null && storedEndpoint == endpoint) {
            if (client.renewPushSubscription(account, storedId)) {
                Log.d(TAG, "PushSubscription renewed: id=$storedId")
                // A previous build (or a lost verification post) may have left it unverified.
                if (!isVerified(context, account.email, storedId)) {
                    PushVerificationRetryWorker.schedule(context, storedId)
                }
                return true
            }
            Log.w(TAG, "Stored subscription $storedId is gone - creating a new one")
        }

        // Clear the record before creating: Stalwart posts the verification before the
        // create response reaches us, and [verify] must not mistake it for a stale one.
        prefs(context).edit()
            .remove(subscriptionKey(account.email))
            .remove(subscriptionEndpointKey(account.email))
            .remove(verifiedKey(account.email))
            .apply()

        val newId = client.registerPushSubscription(
            context = context,
            connectedAccount = account,
            pushEndpointUrl = endpoint,
            deviceClientId = deviceClientId
        ) ?: run {
            Log.w(TAG, "PushSubscription create failed - periodic fallback stays active")
            return false
        }

        prefs(context).edit()
            .putString(subscriptionKey(account.email), newId)
            .putString(subscriptionEndpointKey(account.email), endpoint)
            .apply()
        Log.d(TAG, "PushSubscription created: id=$newId (awaiting verification)")
        // Stalwart may have skipped the verification because of its per-account throttle:
        // nudge it until the code arrives instead of leaving push dead until the next launch.
        PushVerificationRetryWorker.schedule(context, newId)

        reapStaleSubscriptions(client, account, deviceClientId, newId)
        return true
    }

    /**
     * Destroys the other subscriptions this device left behind (rotated endpoints, failed
     * attempts, records from builds that never stored ids). Stalwart sends one
     * PushVerification per account per minute to the oldest unverified subscription, so a
     * leftover can hold up the new one and then deliver duplicate pushes.
     * Other deviceClientIds (another phone, the PWA) are never touched.
     */
    private suspend fun reapStaleSubscriptions(
        client: JMapClient,
        account: JMapClient.ConnectedAccount,
        deviceClientId: String,
        keepId: String
    ) {
        val stale = client.listPushSubscriptions(account)
            .filter { it.deviceClientId == deviceClientId && it.id != keepId }
        if (stale.isEmpty()) return
        Log.d(TAG, "Reaping ${stale.size} stale push subscription(s) for this device")
        stale.forEach { client.destroyPushSubscription(account, it.id) }
    }

    /**
     * Answers the PushVerification the server posted to the UnifiedPush endpoint.
     * Returns true when the server accepted the code.
     */
    suspend fun verify(
        context: Context,
        subscriptionId: String,
        verificationCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val account = BackgroundEmailSyncReceiver.readCurrentAccount(context) ?: run {
                Log.w(TAG, "PushVerification for $subscriptionId but no connected account")
                return@withContext false
            }
            val storedId = prefs(context).getString(subscriptionKey(account.email), null)
            // A verification for a subscription we are not tracking is a leftover (or a
            // stale retry): answering it would make a dead subscription start pushing too.
            if (storedId != null && storedId != subscriptionId) {
                Log.w(TAG, "Ignoring PushVerification for untracked subscription $subscriptionId")
                return@withContext false
            }
            val ok = JMapClient(context).verifyPushSubscription(
                connectedAccount = account,
                subscriptionId = subscriptionId,
                verificationCode = verificationCode
            )
            if (ok) {
                val prefs = prefs(context)
                prefs.edit()
                    .putString(subscriptionKey(account.email), subscriptionId)
                    .putString(verifiedKey(account.email), subscriptionId)
                    .putString(
                        subscriptionEndpointKey(account.email),
                        prefs.getString(KEY_ENDPOINT, null)
                    )
                    .apply()
            }
            ok
        }.onFailure { Log.e(TAG, "verify failed for $subscriptionId", it) }
            .getOrDefault(false)
    }

    /** True when this exact subscription id has already answered its PushVerification. */
    fun isVerified(context: Context, email: String, subscriptionId: String): Boolean =
        prefs(context).getString(verifiedKey(email), null) == subscriptionId

    /** True when the account still points at this subscription (endpoint did not rotate). */
    fun isCurrent(context: Context, email: String, subscriptionId: String): Boolean =
        prefs(context).getString(subscriptionKey(email), null) == subscriptionId

    /** The endpoint a subscription was created for; the server-side url is immutable. */
    private fun subscriptionKey(email: String): String =
        KEY_SUBSCRIPTION_PREFIX + email.lowercase()

    private fun subscriptionEndpointKey(email: String): String =
        KEY_SUBSCRIPTION_ENDPOINT_PREFIX + email.lowercase()

    private fun verifiedKey(email: String): String =
        KEY_VERIFIED_PREFIX + email.lowercase()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateDeviceClientId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_DEVICE_CLIENT, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_CLIENT, generated).apply()
        return generated
    }
}
