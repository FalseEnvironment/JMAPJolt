package com.falseenvironment.jmapjolt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream

/**
 * Contact photos, shared by everything that draws a person: the address book rows, the editor, the
 * email list and the compose recipient picker. Photos live on [Contact.photoBase64]; this object
 * owns the decode (which is expensive enough to want a cache) and the address index that lets the
 * email list ask "is there a contact behind this From address?" without walking the book per row.
 */
object ContactAvatars {

    /** Square side the picked image is downscaled to before it is stored and synced. */
    private const val STORED_SIZE = 512
    private const val JPEG_QUALITY = 85

    private val decoded = LruCache<String, Bitmap>(64)

    /** Lowercased email address -> base64 photo, rebuilt whenever the address book reloads. */
    @Volatile
    private var byAddress: Map<String, String> = emptyMap()

    fun decode(base64: String?): Bitmap? {
        val key = base64?.takeIf { it.isNotBlank() } ?: return null
        decoded.get(key)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(key, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.also { decoded.put(key, it) }
    }

    /** Photo for an email address, or null when no contact owns it. */
    fun photoFor(address: String?): Bitmap? {
        val key = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return decode(byAddress[key])
    }

    fun hasPhotoFor(address: String?): Boolean {
        val key = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return byAddress.containsKey(key)
    }

    /** Rebuilds the address index; call after every address book load. */
    fun index(contacts: List<Contact>) {
        val map = mutableMapOf<String, String>()
        contacts.forEach { contact ->
            val photo = contact.photoBase64?.takeIf { it.isNotBlank() } ?: return@forEach
            contact.emails.forEach { email ->
                val key = email.address.trim().lowercase()
                if (key.isNotEmpty()) map.putIfAbsent(key, photo)
            }
        }
        byAddress = map
    }

    /**
     * Reads a picked image, downscales it to a [STORED_SIZE] square centre crop and returns it as
     * base64 JPEG — small enough to ride along in a JSContact card or a provider blob.
     */
    fun fromPickedImage(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > STORED_SIZE * 2 ||
            bounds.outHeight / sample > STORED_SIZE * 2
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val source = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val square = centreCropSquare(source, STORED_SIZE)
        val out = ByteArrayOutputStream()
        square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun toBytes(base64: String?): ByteArray? =
        base64?.takeIf { it.isNotBlank() }?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }

    fun toBase64(bytes: ByteArray?): String? =
        bytes?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private fun centreCropSquare(source: Bitmap, size: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / side
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (size - source.width * scale) / 2f,
                (size - source.height * scale) / 2f
            )
        }
        Canvas(output).drawBitmap(source, matrix, null)
        return output
    }
}
