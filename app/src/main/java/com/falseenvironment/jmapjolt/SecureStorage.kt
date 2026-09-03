package com.falseenvironment.jmapjolt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import org.json.JSONObject

object SecureStorage {
    private const val SECURE_PREFS_NAME = "secure_accounts"
    private const val LEGACY_PREFS_NAME = "mail_prefs"
    private const val KEY_ACCOUNTS_JSON = "accounts_json"
    private const val TAG = "SecureStorage"

    @Volatile
    private var cached: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appContext = context.applicationContext
            val prefs = create(appContext)
            migrateFromLegacyIfNeeded(appContext, prefs)
            cached = prefs
            return prefs
        }
    }

    /** Every saved account, in stored order. Single parser for the accounts JSON blob. */
    fun connectedAccounts(context: Context): List<JMapClient.ConnectedAccount> {
        val raw = prefs(context).getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        val list = runCatching { JSONObject(raw).optJSONArray("accounts") }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let {
                JMapClient.ConnectedAccount(
                    email = it.optString("email"),
                    password = it.optString("password"),
                    sessionUrl = it.optString("sessionUrl"),
                    apiUrl = it.optString("apiUrl"),
                    accountId = it.optString("accountId")
                )
            }
        }
    }

    /** The account marked as current, else the first saved one. */
    fun currentAccount(context: Context): JMapClient.ConnectedAccount? {
        val accounts = connectedAccounts(context)
        val current = prefs(context).getString(KEY_ACCOUNTS_JSON, null)
            ?.let { runCatching { JSONObject(it).optString("current", "") }.getOrNull() }.orEmpty()
        return accounts.firstOrNull { it.email.equals(current, ignoreCase = true) } ?: accounts.firstOrNull()
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            build(context, masterKey)
        } catch (e: GeneralSecurityException) {
            // Keyset/Keystore corruption (e.g. after a restore on new hardware):
            // wipe the encrypted file and recreate so the app stays usable.
            // The credentials are lost and the user re-authenticates.
            Log.e(TAG, "EncryptedSharedPreferences init failed, recreating", e)
            context.deleteSharedPreferences(SECURE_PREFS_NAME)
            build(context, masterKey)
        } catch (e: IOException) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, recreating", e)
            context.deleteSharedPreferences(SECURE_PREFS_NAME)
            build(context, masterKey)
        }
    }

    private fun build(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun migrateFromLegacyIfNeeded(context: Context, securePrefs: SharedPreferences) {
        if (securePrefs.contains(KEY_ACCOUNTS_JSON)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val plaintext = legacy.getString(KEY_ACCOUNTS_JSON, null) ?: return
        securePrefs.edit().putString(KEY_ACCOUNTS_JSON, plaintext).apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "Migrated account credentials from plaintext to encrypted storage")
    }
}
