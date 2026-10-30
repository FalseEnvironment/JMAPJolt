package com.falseenvironment.jmapjolt

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a [JMapClient.ConnectedAccount] authenticates its JMAP requests.
 *
 * BASIC is the historical path: email + password (or Stalwart app password) sent as
 * HTTP Basic. OAUTH is the 2FA path: the user signs in on the server's own login page
 * (password + TOTP) and the app keeps a Bearer access token plus a rotating refresh
 * token instead of ever storing the password.
 */
enum class AuthType { BASIC, OAUTH }

/** The OAuth grant is gone (revoked, expired, password changed): the user must sign in again. */
internal class OAuthReauthRequired(message: String) : IOException(message)

/** Access + refresh token pair for one account, persisted in EncryptedSharedPreferences. */
internal data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val tokenEndpoint: String,
    val clientId: String,
    val revocationEndpoint: String? = null
) {
    fun isExpiring(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis <= now + REFRESH_MARGIN_MS

    companion object {
        /** Refresh a little before expiry so a request never races the clock. */
        const val REFRESH_MARGIN_MS = 60_000L
    }
}

/**
 * Encrypted token storage keyed by account email. Tokens are written with commit() on
 * the IO dispatcher: a rotated refresh token lost to an async apply() would invalidate
 * the whole grant on the next launch.
 */
internal object OAuthTokenStore {
    private const val KEY = "oauth_tokens_json"
    private val lock = Any()

    private fun keyFor(email: String): String = email.trim().lowercase(Locale.ROOT)

    fun load(context: Context, email: String): OAuthTokens? {
        val raw = SecureStorage.prefs(context).getString(KEY, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw).optJSONObject(keyFor(email)) ?: return null
            val access = obj.optString("accessToken")
            val endpoint = obj.optString("tokenEndpoint")
            val clientId = obj.optString("clientId")
            if (access.isBlank() || endpoint.isBlank() || clientId.isBlank()) return null
            OAuthTokens(
                accessToken = access,
                refreshToken = obj.optString("refreshToken").takeIf { it.isNotBlank() },
                expiresAtMillis = obj.optLong("expiresAt", 0L),
                tokenEndpoint = endpoint,
                clientId = clientId,
                revocationEndpoint = obj.optString("revocationEndpoint").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    fun save(context: Context, email: String, tokens: OAuthTokens) {
        synchronized(lock) {
            val prefs = SecureStorage.prefs(context)
            val root = prefs.getString(KEY, null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            root.put(
                keyFor(email),
                JSONObject()
                    .put("accessToken", tokens.accessToken)
                    .put("refreshToken", tokens.refreshToken ?: "")
                    .put("expiresAt", tokens.expiresAtMillis)
                    .put("tokenEndpoint", tokens.tokenEndpoint)
                    .put("clientId", tokens.clientId)
                    .put("revocationEndpoint", tokens.revocationEndpoint ?: "")
            )
            prefs.edit().putString(KEY, root.toString()).commit()
        }
    }

    fun clear(context: Context, email: String) {
        synchronized(lock) {
            val prefs = SecureStorage.prefs(context)
            val root = prefs.getString(KEY, null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return
            if (root.remove(keyFor(email)) != null) {
                prefs.edit().putString(KEY, root.toString()).commit()
            }
        }
    }
}

/** In-flight authorization request, kept across the browser round-trip (and process death). */
internal data class PendingOAuth(
    val state: String,
    val codeVerifier: String,
    val clientId: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val serverOrigin: String,
    val email: String,
    val createdAtMillis: Long
)

internal object OAuthPendingStore {
    private const val KEY = "oauth_pending_json"

    fun save(context: Context, pending: PendingOAuth) {
        val json = JSONObject()
            .put("state", pending.state)
            .put("codeVerifier", pending.codeVerifier)
            .put("clientId", pending.clientId)
            .put("tokenEndpoint", pending.tokenEndpoint)
            .put("revocationEndpoint", pending.revocationEndpoint ?: "")
            .put("serverOrigin", pending.serverOrigin)
            .put("email", pending.email)
            .put("createdAt", pending.createdAtMillis)
        SecureStorage.prefs(context).edit().putString(KEY, json.toString()).commit()
    }

    fun load(context: Context): PendingOAuth? {
        val raw = SecureStorage.prefs(context).getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PendingOAuth(
                state = json.optString("state"),
                codeVerifier = json.optString("codeVerifier"),
                clientId = json.optString("clientId"),
                tokenEndpoint = json.optString("tokenEndpoint"),
                revocationEndpoint = json.optString("revocationEndpoint").takeIf { it.isNotBlank() },
                serverOrigin = json.optString("serverOrigin"),
                email = json.optString("email"),
                createdAtMillis = json.optLong("createdAt", 0L)
            ).takeIf { it.state.isNotBlank() && it.codeVerifier.isNotBlank() }
        }.getOrNull()
    }

    fun clear(context: Context) {
        SecureStorage.prefs(context).edit().remove(KEY).commit()
    }
}

/**
 * Stalwart OAuth 2.0 for public native clients: RFC 8414 discovery, RFC 7591 dynamic
 * registration, Authorization Code + PKCE (S256), rotating refresh tokens, and
 * RFC 7009 revocation.
 *
 * The browser login is the only place a TOTP code is ever entered; the app itself
 * never asks for it, which is exactly what makes the flow real 2FA.
 */
internal object OAuthClient {
    private const val TAG = "OAuthClient"

    const val REDIRECT_SCHEME = "com.falseenvironment.jmapjolt"
    const val REDIRECT_PATH = "/oauth2redirect"
    const val REDIRECT_URI = "$REDIRECT_SCHEME:$REDIRECT_PATH"

    private val SCOPES = listOf(
        "urn:ietf:params:oauth:scope:mail",
        "urn:ietf:params:oauth:scope:contacts",
        "urn:ietf:params:oauth:scope:calendars"
    )

    private const val CLIENT_NAME = "JMAPJolt"
    private const val PENDING_MAX_AGE_MS = 10 * 60_000L

    private val jsonMedia = "application/json".toMediaType()
    private val formMedia = "application/x-www-form-urlencoded".toMediaType()

    /** Serializes refreshes: a rotated refresh token may only be redeemed once. */
    private val refreshLock = Mutex()

    data class Metadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val revocationEndpoint: String?
    )

    data class CompletedAuth(
        val email: String,
        val serverOrigin: String,
        val tokens: OAuthTokens
    )

    /**
     * Normalises what the user typed into the server's HTTPS origin. Reuses the JMAP
     * candidate parser so `mail.example.com`, `https://mail.example.com/jmap` and
     * `https://mail.example.com/` all end up as `https://mail.example.com`.
     */
    fun serverOrigin(rawInput: String): String? {
        val url = JMapClient.buildSessionCandidates(rawInput).firstOrNull()
            ?.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    /** RFC 8414 metadata, falling back to Stalwart's fixed endpoints when absent. */
    suspend fun discover(origin: String): Metadata = withContext(Dispatchers.IO) {
        val fallback = Metadata(
            authorizationEndpoint = "$origin/login",
            tokenEndpoint = "$origin/auth/token",
            registrationEndpoint = "$origin/auth/register",
            revocationEndpoint = "$origin/auth/revoke"
        )
        try {
            val request = Request.Builder()
                .url("$origin/.well-known/oauth-authorization-server")
                .header("Accept", "application/json")
                .get()
                .build()
            AppHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext fallback
                val json = JSONObject(response.body?.string().orEmpty())
                Metadata(
                    authorizationEndpoint =
                        resolveEndpoint(origin, json.optString("authorization_endpoint"))
                            ?: fallback.authorizationEndpoint,
                    tokenEndpoint =
                        resolveEndpoint(origin, json.optString("token_endpoint"))
                            ?: fallback.tokenEndpoint,
                    registrationEndpoint =
                        resolveEndpoint(origin, json.optString("registration_endpoint"))
                            ?: fallback.registrationEndpoint,
                    revocationEndpoint =
                        resolveEndpoint(origin, json.optString("revocation_endpoint"))
                            ?: fallback.revocationEndpoint
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "OAuth discovery failed at ${LogRedact.host(origin)}: ${e.message}")
            fallback
        }
    }

    private fun resolveEndpoint(origin: String, raw: String): String? {
        if (raw.isBlank()) return null
        val absolute = raw.toHttpUrlOrNull()
        if (absolute != null) return absolute.toString()
        return origin.toHttpUrlOrNull()?.resolve(raw)?.toString()
    }

    /** RFC 7591 dynamic registration of a public client bound to our custom scheme. */
    private suspend fun registerClient(metadata: Metadata, redirectUri: String): String? =
        withContext(Dispatchers.IO) {
            val endpoint = metadata.registrationEndpoint ?: return@withContext null
            val payload = JSONObject()
                .put("client_name", CLIENT_NAME)
                .put("redirect_uris", JSONArray().put(redirectUri))
                .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none")
                // A dynamically registered client may only request the scopes it
                // names here; without this the authorization request fails with
                // invalid_scope even though the scopes are valid on the server.
                .put("scope", SCOPES.joinToString(" "))
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
                AppHttp.client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "OAuth client registration failed: HTTP ${response.code}")
                        return@withContext null
                    }
                    JSONObject(body).optString("client_id").takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth client registration failed: ${e.message}")
                null
            }
        }

    /**
     * Registers a client, builds the authorization URL and stores the PKCE verifier +
     * state for the redirect. Returns null when the server does not support OAuth.
     */
    suspend fun beginAuthorization(
        context: Context,
        email: String,
        serverInput: String
    ): String? {
        val origin = serverOrigin(serverInput) ?: return null
        // Drop any half-finished attempt: its verifier must not be reused, and a late
        // redirect from it must fail the state check rather than revive an old request.
        OAuthPendingStore.clear(context)
        val metadata = discover(origin)
        val clientId = registerClient(metadata, REDIRECT_URI) ?: return null
        val state = randomToken(24)
        val verifier = randomToken(48)
        OAuthPendingStore.save(
            context,
            PendingOAuth(
                state = state,
                codeVerifier = verifier,
                clientId = clientId,
                tokenEndpoint = metadata.tokenEndpoint,
                revocationEndpoint = metadata.revocationEndpoint,
                serverOrigin = origin,
                email = email,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        return Uri.parse(metadata.authorizationEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("login_hint", email)
            .build()
            .toString()
    }

    /**
     * Validates the redirect, exchanges the authorization code and persists the tokens.
     * Returns null when there is no matching pending request or the server refused.
     */
    suspend fun completeAuthorization(context: Context, redirect: Uri): CompletedAuth? =
        withContext(Dispatchers.IO) {
            val pending = OAuthPendingStore.load(context) ?: return@withContext null
            try {
                if (System.currentTimeMillis() - pending.createdAtMillis > PENDING_MAX_AGE_MS) {
                    Log.w(TAG, "OAuth redirect arrived after the pending request expired")
                    return@withContext null
                }
                // The private-use redirect URI is opaque (scheme:/path, no authority),
                // so read the query by hand instead of relying on Uri.getQueryParameter.
                val params = queryParameters(redirect)
                if (params["state"] != pending.state) {
                    Log.w(TAG, "OAuth redirect state mismatch")
                    return@withContext null
                }
                val error = params["error"]
                if (!error.isNullOrBlank()) {
                    Log.w(TAG, "OAuth authorization refused: $error")
                    return@withContext null
                }
                val code = params["code"] ?: return@withContext null
                val tokens = postToken(
                    tokenEndpoint = pending.tokenEndpoint,
                    form = mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to REDIRECT_URI,
                        "client_id" to pending.clientId,
                        "code_verifier" to pending.codeVerifier
                    ),
                    previous = null,
                    revocationEndpoint = pending.revocationEndpoint
                )
                OAuthTokenStore.save(context, pending.email, tokens)
                CompletedAuth(pending.email, pending.serverOrigin, tokens)
            } catch (e: OAuthReauthRequired) {
                Log.w(TAG, "OAuth code exchange rejected: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "OAuth code exchange failed: ${e.message}")
                null
            } finally {
                OAuthPendingStore.clear(context)
            }
        }

    /** A valid Bearer token for [email], refreshing it first when it is about to expire. */
    suspend fun accessToken(context: Context, email: String): String {
        val stored = OAuthTokenStore.load(context, email)
            ?: throw OAuthReauthRequired("No OAuth tokens stored for this account")
        if (!stored.isExpiring()) return stored.accessToken
        return refreshLock.withLock {
            // Another request may have refreshed while this one waited for the lock.
            val current = OAuthTokenStore.load(context, email)
                ?: throw OAuthReauthRequired("No OAuth tokens stored for this account")
            if (!current.isExpiring()) return@withLock current.accessToken
            refreshLocked(context, email, current)
        }
    }

    private suspend fun refreshLocked(
        context: Context,
        email: String,
        current: OAuthTokens
    ): String = withContext(Dispatchers.IO) {
        val refresh = current.refreshToken
        if (refresh == null) {
            OAuthTokenStore.clear(context, email)
            throw OAuthReauthRequired("No OAuth refresh token stored")
        }
        try {
            val refreshed = postToken(
                tokenEndpoint = current.tokenEndpoint,
                form = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to current.clientId
                ),
                previous = current
            )
            OAuthTokenStore.save(context, email, refreshed)
            refreshed.accessToken
        } catch (e: OAuthReauthRequired) {
            // invalid_grant means the grant is dead (revoked, password changed, idle
            // timeout). Drop the tokens so the UI routes back to the login screen.
            OAuthTokenStore.clear(context, email)
            throw e
        }
    }

    /** Best-effort revocation on account removal; tokens are cleared even if the call fails. */
    suspend fun revoke(context: Context, email: String) = withContext(Dispatchers.IO) {
        val tokens = OAuthTokenStore.load(context, email) ?: return@withContext
        val endpoint = tokens.revocationEndpoint
        val refresh = tokens.refreshToken
        val token = refresh ?: tokens.accessToken
        if (endpoint != null) {
            try {
                val body = formEncode(
                    mapOf(
                        "token" to token,
                        "token_type_hint" to if (refresh != null) "refresh_token" else "access_token",
                        "client_id" to tokens.clientId
                    )
                )
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body.toRequestBody(formMedia))
                    .build()
                AppHttp.client.newCall(request).execute().use { /* best effort */ }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth revocation failed: ${e.message}")
            }
        }
        OAuthTokenStore.clear(context, email)
    }

    private fun postToken(
        tokenEndpoint: String,
        form: Map<String, String>,
        previous: OAuthTokens?,
        revocationEndpoint: String? = previous?.revocationEndpoint
    ): OAuthTokens {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(formEncode(form).toRequestBody(formMedia))
            .build()
        AppHttp.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val error = json?.optString("error").orEmpty()
                if (error == "invalid_grant" || error == "invalid_client") {
                    throw OAuthReauthRequired("OAuth token rejected: $error")
                }
                val detail = json?.optString("error_description").orEmpty()
                throw IOException("OAuth token endpoint HTTP ${response.code}: $detail")
            }
            val access = json?.optString("access_token").orEmpty()
            if (access.isBlank()) throw IOException("OAuth token response has no access_token")
            val expiresIn = json?.optLong("expires_in", 3600L)?.coerceAtLeast(60L) ?: 3600L
            val refresh = json?.optString("refresh_token")?.takeIf { it.isNotBlank() }
                ?: previous?.refreshToken
            return OAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
                tokenEndpoint = tokenEndpoint,
                clientId = previous?.clientId ?: form["client_id"].orEmpty(),
                revocationEndpoint = revocationEndpoint
            )
        }
    }

    /** Query parameters of an opaque redirect URI, decoded once. */
    private fun queryParameters(uri: Uri): Map<String, String> {
        val raw = uri.encodedQuery
            ?: uri.toString().substringAfter('?', "").substringBefore('#')
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            URLDecoder.decode(part.substring(0, separator), "UTF-8") to
                URLDecoder.decode(part.substring(separator + 1), "UTF-8")
        }.toMap()
    }

    private fun formEncode(values: Map<String, String>): String =
        values.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }

    private fun randomToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}

/** The Authorization header for [this] account, refreshing an OAuth token if needed. */
internal suspend fun JMapClient.ConnectedAccount.authorizationHeader(context: Context): String =
    when (authType) {
        AuthType.BASIC -> Credentials.basic(email, password)
        AuthType.OAUTH -> "Bearer " + OAuthClient.accessToken(context, email)
    }
