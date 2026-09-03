package com.falseenvironment.jmapjolt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exercises [WebPushKeys.decryptWithKeys] against messages built by a minimal RFC 8291
 * sender implemented below. The point of the tamper cases: an attacker who learns the push
 * endpoint can POST anything, and every such payload has to come back as null so
 * UnifiedPushService drops it instead of rendering it.
 */
class WebPushDecryptTest {

    private val plaintext = """{"@type":"StateChange","changed":{"a":{"Email":"s1"}}}""".toByteArray()

    @Test
    fun `genuine message round trips`() {
        val r = Receiver()

        val decrypted = WebPushKeys.decryptWithKeys(r.encrypt(plaintext), r.privateBytes, r.auth, r.publicBytes)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `flipped ciphertext byte is rejected`() {
        val r = Receiver()
        val message = r.encrypt(plaintext)
        // Last body byte: inside the GCM tag, so the tag check is what must fail.
        message[message.size - 1] = (message[message.size - 1].toInt() xor 0x01).toByte()

        assertNull(WebPushKeys.decryptWithKeys(message, r.privateBytes, r.auth, r.publicBytes))
    }

    @Test
    fun `flipped plaintext-carrying byte is rejected`() {
        val r = Receiver()
        val message = r.encrypt(plaintext)
        val bodyStart = 16 + 4 + 1 + 65
        message[bodyStart] = (message[bodyStart].toInt() xor 0x40).toByte()

        assertNull(WebPushKeys.decryptWithKeys(message, r.privateBytes, r.auth, r.publicBytes))
    }

    @Test
    fun `tampered salt is rejected`() {
        val r = Receiver()
        val message = r.encrypt(plaintext)
        message[0] = (message[0].toInt() xor 0xFF).toByte()

        assertNull(WebPushKeys.decryptWithKeys(message, r.privateBytes, r.auth, r.publicBytes))
    }

    @Test
    fun `substituted sender key is rejected`() {
        val r = Receiver()
        val message = r.encrypt(plaintext)
        // A different ephemeral key derives a different CEK — this is the "someone else
        // POSTed to my endpoint" case.
        val other = uncompressed(generateKeyPair().public as ECPublicKey)
        System.arraycopy(other, 0, message, 16 + 4 + 1, other.size)

        assertNull(WebPushKeys.decryptWithKeys(message, r.privateBytes, r.auth, r.publicBytes))
    }

    @Test
    fun `message for another subscription is rejected`() {
        val sender = Receiver()
        val victim = Receiver()

        val decrypted = WebPushKeys.decryptWithKeys(
            sender.encrypt(plaintext), victim.privateBytes, victim.auth, victim.publicBytes
        )

        assertNull(decrypted)
    }

    @Test
    fun `plaintext payload is not accepted as a message`() {
        // What the Settings test notification actually POSTs: unencrypted text.
        val r = Receiver()

        val decrypted = WebPushKeys.decryptWithKeys(
            "JMAPJolt test notification".toByteArray(), r.privateBytes, r.auth, r.publicBytes
        )

        assertNull(decrypted)
    }

    @Test
    fun `truncated message is rejected`() {
        val r = Receiver()
        val message = r.encrypt(plaintext)

        assertNull(WebPushKeys.decryptWithKeys(message.copyOfRange(0, 20), r.privateBytes, r.auth, r.publicBytes))
        assertNull(WebPushKeys.decryptWithKeys(message.copyOfRange(0, 60), r.privateBytes, r.auth, r.publicBytes))
    }

    // --- minimal RFC 8291 / RFC 8188 sender -------------------------------------------

    /** A subscription's key material, plus the sender side that encrypts to it. */
    private class Receiver {
        private val keyPair: KeyPair = generateKeyPair()
        val privateBytes: ByteArray = keyPair.private.encoded
        val publicBytes: ByteArray = uncompressed(keyPair.public as ECPublicKey)
        val auth: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        fun encrypt(payload: ByteArray): ByteArray {
            val ephemeral = generateKeyPair()
            val ephemeralPublic = uncompressed(ephemeral.public as ECPublicKey)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(ephemeral.private)
            ka.doPhase(keyPair.public, true)
            val ecdh = ka.generateSecret()

            val ikm = hkdf(
                salt = auth, ikm = ecdh,
                info = "WebPush: info\u0000".toByteArray() + publicBytes + ephemeralPublic, length = 32
            )
            val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
            val nonce = hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), 12)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            val body = cipher.doFinal(payload + 0x02)  // 0x02 = last-record delimiter

            val rs = body.size + 16
            return salt +
                byteArrayOf((rs ushr 24).toByte(), (rs ushr 16).toByte(), (rs ushr 8).toByte(), rs.toByte()) +
                byteArrayOf(ephemeralPublic.size.toByte()) +
                ephemeralPublic +
                body
        }
    }

    private companion object {
        // SunEC names the curve secp256r1; Android names the same curve prime256v1.
        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        fun uncompressed(key: ECPublicKey): ByteArray =
            byteArrayOf(0x04) + key.w.affineX.to32Bytes() + key.w.affineY.to32Bytes()

        fun BigInteger.to32Bytes(): ByteArray {
            val raw = toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }

        fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
            val prk = extract.doFinal(ikm)
            val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            expand.update(info)
            expand.update(1.toByte())
            return expand.doFinal().copyOfRange(0, length)
        }

    }
}
