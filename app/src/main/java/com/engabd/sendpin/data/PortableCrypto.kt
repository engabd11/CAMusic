package com.engabd.sendpin.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM string encryption with a password-derived key, for settings export.
 *
 * [Crypto] can't be reused for this: its key lives in the Android Keystore and is
 * deliberately non-exportable — that's what makes it safe at rest, and exactly why
 * an export written with it would be unreadable on a different device, or after a
 * reinstall. This derives its key from a user-chosen passphrase with PBKDF2 instead,
 * so the same key can be re-derived anywhere the same passphrase is entered.
 *
 * `java.util.Base64` rather than `android.util.Base64` — nothing else reads this
 * format, so there's no interop reason to prefer the Android one, and the JDK one
 * keeps this class plain-JVM testable (`android.util.Base64` throws "not mocked"
 * off-device, same as [Crypto]).
 */
object PortableCrypto {
    private const val PREFIX = "pc1:"
    private const val IV_LEN = 12
    private const val SALT_LEN = 16
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** @return `pc1:<base64 of salt+iv+ciphertext>`, or null if the platform can't do AES-GCM. */
    fun encrypt(plain: String, password: String): String? = try {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.getEncoder().encodeToString(salt + iv + ct)
    } catch (_: Exception) {
        null
    }

    /** @return the decrypted plaintext, or null on a wrong password or corrupt/foreign input. */
    fun decrypt(blob: String, password: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        return try {
            val data = Base64.getDecoder().decode(blob.removePrefix(PREFIX))
            val salt = data.copyOfRange(0, SALT_LEN)
            val iv = data.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val ct = data.copyOfRange(SALT_LEN + IV_LEN, data.size)
            val key = deriveKey(password, salt)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            // Wrong password surfaces here too: GCM's auth tag check fails the same
            // way corrupt ciphertext does, which is the point — neither should say
            // more than "that didn't work" to whoever's holding the file.
            null
        }
    }
}
