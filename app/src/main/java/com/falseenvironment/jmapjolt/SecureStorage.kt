package com.falseenvironment.jmapjolt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

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
        legacy.edit().remove(KEY_ACCOUNTS_JSON).apply()
        Log.i(TAG, "Migrated account credentials from plaintext to encrypted storage")
    }
}
