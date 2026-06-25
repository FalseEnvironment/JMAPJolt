package com.falseenvironment.jmapjolt.cache

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.falseenvironment.jmapjolt.DisplayEmail
import com.falseenvironment.jmapjolt.EmailAttachmentInfo
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Offline email cache. Wraps a Room database stored on an encrypted (SQLCipher)
 * file; the passphrase lives in EncryptedSharedPreferences (Android Keystore
 * backed), never hardcoded. All FOSS: Room (AndroidX) + SQLCipher (BSD).
 */
object EmailCacheStore {

    private const val DB_NAME = "email_cache.db"
    private const val KEY_PREFS = "email_cache_keys"
    private const val KEY_PASSPHRASE = "db_passphrase_b64"
    private const val PASSPHRASE_BYTES = 32

    @Volatile private var db: EmailCacheDatabase? = null

    private fun database(context: Context): EmailCacheDatabase {
        return db ?: synchronized(this) {
            db ?: build(context.applicationContext).also { db = it }
        }
    }

    private fun build(context: Context): EmailCacheDatabase {
        // The sqlcipher-android artifact does not auto-load its JNI library.
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(loadOrCreatePassphrase(context))
        return Room.databaseBuilder(context, EmailCacheDatabase::class.java, DB_NAME)
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    /** 32 random bytes, persisted (base64) in an encrypted prefs file. */
    private fun loadOrCreatePassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            KEY_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString(KEY_PASSPHRASE, null)?.let {
            return Base64.decode(it, Base64.NO_WRAP)
        }
        val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }

    /** Snapshot key for an account + folder pair. */
    fun bucket(accountEmail: String, folderId: Int): String = "$accountEmail#$folderId"

    suspend fun load(context: Context, bucket: String): List<DisplayEmail> =
        database(context).cachedEmailDao().loadBucket(bucket).map { it.toDisplayEmail() }

    /** Replace a folder snapshot with the freshly fetched list (atomic). */
    suspend fun save(context: Context, bucket: String, emails: List<DisplayEmail>) {
        val rows = emails.map { it.toRow(bucket) }
        val database = database(context)
        database.withTransaction {
            val dao = database.cachedEmailDao()
            dao.clearBucket(bucket)
            dao.upsert(rows)
        }
    }

    // --- mapping -----------------------------------------------------------

    private fun DisplayEmail.toRow(bucket: String) = CachedEmailRow(
        bucket = bucket,
        id = id,
        subject = subject,
        from = from,
        fromEmail = fromEmail,
        preview = preview,
        fullBody = fullBody,
        seen = seen,
        isFavorite = isFavorite,
        receivedAt = receivedAt,
        toEmail = toEmail,
        accountEmail = accountEmail,
        attachmentsJson = encodeAttachments(attachments),
        labelsJson = JSONArray(labels).toString(),
        threadId = threadId
    )

    private fun CachedEmailRow.toDisplayEmail() = DisplayEmail(
        id = id,
        subject = subject,
        from = from,
        fromEmail = fromEmail,
        preview = preview,
        fullBody = fullBody,
        seen = seen,
        isFavorite = isFavorite,
        receivedAt = receivedAt,
        toEmail = toEmail,
        attachments = decodeAttachments(attachmentsJson),
        accountEmail = accountEmail,
        labels = decodeStringList(labelsJson),
        threadId = threadId
    )

    private fun encodeAttachments(list: List<EmailAttachmentInfo>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("blobId", it.blobId)
                    .put("name", it.name)
                    .put("mimeType", it.mimeType)
                    .put("size", it.size)
            )
        }
        return arr.toString()
    }

    private fun decodeAttachments(json: String): List<EmailAttachmentInfo> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EmailAttachmentInfo(
                    blobId = o.optString("blobId"),
                    name = o.optString("name"),
                    mimeType = o.optString("mimeType"),
                    size = o.optLong("size")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeStringList(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}
