package com.falseenvironment.jmapjolt

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * Two rules keep a failed handshake from silently leaving the device without push:
 *
 *  - the recorded id is never cleared before a replacement is created, so a create that
 *    the server refuses leaves the previous subscription (and its deliveries) intact;
 *  - stale unverified subscriptions are reaped *before* the create. Stalwart verifies the
 *    oldest unverified subscription first (one per account per minute), so a leftover from
 *    an earlier attempt can starve the new one indefinitely.
 */
internal object PushSubscriptionManager {

    private const val TAG = "PushSubscription"
    private const val PREFS = "mail_prefs"
    private const val KEY_ENDPOINT = "last_up_endpoint"
    private const val KEY_DEVICE_CLIENT = "up_device_client_id"
    private const val KEY_SUBSCRIPTION_PREFIX = "up_push_subscription_id_"
    private const val KEY_SUBSCRIPTION_ENDPOINT_PREFIX = "up_push_subscription_endpoint_"
    private const val KEY_VERIFIED_PREFIX = "up_push_verified_id_"

    // Last registration outcome, surfaced in Settings instead of leaving the user with
    // just an "on" switch and no mail. Single slot: the subscription follows the
    // currently connected account.
    private const val KEY_STATE = "up_push_state"
    private const val KEY_STATE_DETAIL = "up_push_state_detail"
    private const val KEY_STATE_EMAIL = "up_push_state_email"
    private const val KEY_STATE_UPDATED_AT = "up_push_state_updated_at"

    const val ACTION_PUSH_STATE_CHANGED =
        "com.falseenvironment.jmapjolt.ACTION_PUSH_STATE_CHANGED"

    /** Coalesces the launch-time pass, onNewEndpoint and verify into one at a time. */
    private val mutex = Mutex()

    enum class PushState { INACTIVE, PENDING, ACTIVE, ERROR }

    data class PushStatus(
        val state: PushState,
        val detail: String?,
        val updatedAt: Long
    )

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UP_ENABLED, false)

    /**
     * Makes sure the current account has a live, verified-capable subscription on this
     * device's current UnifiedPush endpoint. Safe to call on every launch and on every
     * endpoint rotation; failures are logged and recorded in the push state, never thrown.
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
                if (account != null) {
                    setState(context, account.email, PushState.INACTIVE, null)
                }
                Log.d(TAG, "PushSubscription disabled (distributor registration kept)")
            }.onFailure { Log.w(TAG, "disable failed", it) }
        }
    }

    private suspend fun ensureLocked(context: Context): Boolean {
        if (!isEnabled(context)) {
            Log.d(TAG, "UnifiedPush disabled - not registering a PushSubscription")
            return false
        }
        val account = BackgroundEmailSyncReceiver.readCurrentAccount(context) ?: run {
            Log.w(TAG, "No connected account - subscription deferred")
            return false
        }
        val email = account.email
        val endpoint = prefs(context).getString(KEY_ENDPOINT, null)
            ?.takeIf { it.startsWith("https://") }
            ?: run {
                Log.w(TAG, "No UnifiedPush endpoint yet - subscription deferred")
                setState(context, email, PushState.PENDING, "no_endpoint")
                return false
            }

        // Record what is already in the inbox *before* the server can push anything.
        // Otherwise the first push-triggered sync finds no baseline, records the whole
        // inbox as seen and silently swallows the new email (and its notification).
        BackgroundEmailSyncReceiver.seedBaselineIfMissing(context, account)

        val deviceClientId = getOrCreateDeviceClientId(context)
        val client = JMapClient(context)
        val storedId = prefs(context).getString(subscriptionKey(email), null)
        val storedEndpoint = prefs(context).getString(subscriptionEndpointKey(email), null)

        // draft-ietf-jmap-emailpush: the push can then carry the delivered ids, which
        // makes the notification independent of any inbox baseline. Servers without the
        // capability keep the StateChange + receivedAt fallback.
        val emailPush = if (client.supportsEmailPush(account)) {
            JMapClient.EmailPushConfig(
                accountId = account.accountId,
                filter = emailPushFilter()
            )
        } else {
            null
        }

        // url and keys are immutable in JMAP: once the distributor rotates the endpoint the
        // old subscription can only be replaced, not patched.
        if (storedId != null && storedEndpoint == endpoint) {
            if (client.renewPushSubscription(account, storedId, emailPush = emailPush)) {
                Log.d(TAG, "PushSubscription renewed: id=$storedId emailPush=${emailPush != null}")
                // A previous build (or a lost verification post) may have left it unverified.
                if (isVerified(context, email, storedId)) {
                    setState(context, email, PushState.ACTIVE, null)
                } else {
                    setState(context, email, PushState.PENDING, "verifying")
                    PushVerificationRetryWorker.schedule(context, storedId)
                }
                return true
            }
            Log.w(TAG, "Stored subscription $storedId is gone - creating a new one")
        } else if (storedId != null) {
            Log.d(TAG, "Push endpoint rotated - replacing subscription $storedId")
        }

        // Reap this device's leftovers *before* the create: Stalwart verifies the oldest
        // unverified subscription per account first, so one left behind by an earlier
        // failed attempt would delay (or starve) the verification of the new one. The
        // current record is kept until its replacement is verified.
        reapStaleSubscriptions(client, account, deviceClientId, keepId = storedId)

        setState(context, email, PushState.PENDING, "creating")
        val newId = client.registerPushSubscription(
            context = context,
            connectedAccount = account,
            pushEndpointUrl = endpoint,
            deviceClientId = deviceClientId,
            emailPush = emailPush
        ) ?: run {
            // The previous subscription (if any) is still stored and may still deliver:
            // never drop the record on a failed create.
            Log.w(TAG, "PushSubscription create failed - keeping ${storedId ?: "no"} record")
            setState(context, email, PushState.ERROR, "create_failed")
            // A transient failure used to leave push dead until the next manual launch.
            PushRegistrationRetryWorker.schedule(context)
            return false
        }

        prefs(context).edit()
            .putString(subscriptionKey(email), newId)
            .putString(subscriptionEndpointKey(email), endpoint)
            .remove(verifiedKey(email))
            .apply()
        Log.d(TAG, "PushSubscription created: id=$newId (awaiting verification)")
        setState(context, email, PushState.PENDING, "verifying")
        // Stalwart may have skipped the verification because of its per-account throttle:
        // nudge it until the code arrives instead of leaving push dead until the next launch.
        PushVerificationRetryWorker.schedule(context, newId)
        return true
    }

    /**
     * Junk is excluded server-side so a spam delivery does not wake the device at all.
     * Everything else is delivered: the per-message keywords still filter junk, seen and
     * sent copies client-side, and mail filtered into custom folders now notifies too.
     */
    private fun emailPushFilter(): JSONObject = JSONObject()
        .put("operator", "AND")
        .put(
            "conditions",
            JSONArray().put(JSONObject().put("notKeyword", "\$junk"))
        )

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
        keepId: String?
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
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val account = BackgroundEmailSyncReceiver.readCurrentAccount(context) ?: run {
                    Log.w(TAG, "PushVerification for $subscriptionId but no connected account")
                    return@runCatching false
                }
                val storedId = prefs(context).getString(subscriptionKey(account.email), null)
                // A verification for a subscription we are not tracking is a leftover (or a
                // stale retry): answering it would make a dead subscription start pushing too.
                if (storedId != null && storedId != subscriptionId) {
                    Log.w(TAG, "Ignoring PushVerification for untracked subscription $subscriptionId")
                    return@runCatching false
                }
                val client = JMapClient(context)
                val ok = client.verifyPushSubscription(
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
                    setState(context, account.email, PushState.ACTIVE, null)
                    // Only now is the replacement live: destroy the superseded record (and
                    // any other same-device leftovers) without ever leaving a gap.
                    reapStaleSubscriptions(
                        client = client,
                        account = account,
                        deviceClientId = getOrCreateDeviceClientId(context),
                        keepId = subscriptionId
                    )
                } else {
                    setState(context, account.email, PushState.ERROR, "verify_rejected")
                }
                ok
            }.onFailure { Log.e(TAG, "verify failed for $subscriptionId", it) }
                .getOrDefault(false)
        }
    }

    /**
     * A decrypted mail push proves the server accepted the subscription even when the
     * verification answer was missed (the app can be dead exactly when the server posts
     * it). Reflect that in Settings instead of showing "pending" forever.
     */
    fun notePushReceived(context: Context) {
        runCatching {
            if (!isEnabled(context)) return
            val account = BackgroundEmailSyncReceiver.readCurrentAccount(context) ?: return
            if (prefs(context).getString(subscriptionKey(account.email), null) == null) return
            setState(context, account.email, PushState.ACTIVE, null)
        }.onFailure { Log.w(TAG, "notePushReceived failed", it) }
    }

    /**
     * The retry worker exhausted its nudges without a verification: keep the record (a
     * later launch retries the handshake) but stop reporting the push as pending, so the
     * user sees that the server never confirmed it.
     */
    fun markVerificationTimedOut(context: Context, email: String) {
        runCatching {
            if (!isEnabled(context)) return
            if (prefs(context).getString(subscriptionKey(email), null) == null) return
            if (prefs(context).getString(verifiedKey(email), null) != null) return
            setState(context, email, PushState.ERROR, "verify_timeout")
        }.onFailure { Log.w(TAG, "markVerificationTimedOut failed", it) }
    }

    /** True when this exact subscription id has already answered its PushVerification. */
    fun isVerified(context: Context, email: String, subscriptionId: String): Boolean =
        prefs(context).getString(verifiedKey(email), null) == subscriptionId

    /** True when the account still points at this subscription (endpoint did not rotate). */
    fun isCurrent(context: Context, email: String, subscriptionId: String): Boolean =
        prefs(context).getString(subscriptionKey(email), null) == subscriptionId

    /** Last registration outcome, or null when nothing was attempted yet. */
    fun status(context: Context): PushStatus? {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        val state = PushState.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: return null
        return PushStatus(
            state = state,
            detail = prefs.getString(KEY_STATE_DETAIL, null),
            updatedAt = prefs.getLong(KEY_STATE_UPDATED_AT, 0L)
        )
    }

    /** Email the recorded state belongs to; the UI only shows it for that account. */
    fun statusEmail(context: Context): String? =
        prefs(context).getString(KEY_STATE_EMAIL, null)

    private fun setState(
        context: Context,
        email: String,
        state: PushState,
        detail: String?
    ) {
        val editor = prefs(context).edit()
            .putString(KEY_STATE, state.name.lowercase())
            .putString(KEY_STATE_EMAIL, email)
            .putLong(KEY_STATE_UPDATED_AT, System.currentTimeMillis())
        if (detail != null) {
            editor.putString(KEY_STATE_DETAIL, detail)
        } else {
            editor.remove(KEY_STATE_DETAIL)
        }
        editor.apply()
        context.sendBroadcast(Intent(ACTION_PUSH_STATE_CHANGED).setPackage(context.packageName))
    }

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
