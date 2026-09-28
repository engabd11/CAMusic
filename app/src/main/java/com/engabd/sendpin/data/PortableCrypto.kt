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
    /**
     * Two formats, told apart by prefix: the iteration count is part of what the
     * file *is*, so an old backup keeps opening after the count goes up.
     *
     * `pc2` is OWASP's current PBKDF2-HMAC-SHA256 figure, 600,000. `pc1` was 210,000
     * and is read, never written. The file holds every server login, and whoever
     * has a copy can guess at it offline for as long as they like, so this is the
     * one place where a second of work on export is worth paying.
     */
    private const val PREFIX_V1 = "pc1:"
    private const val PREFIX = "pc2:"
    private const val IV_LEN = 12
    private const val SALT_LEN = 16
    private const val PBKDF2_ITERATIONS_V1 = 210_000
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256

    /**
     * The shortest passphrase an export accepts.
     *
     * Ten characters, because the iteration count only multiplies the cost of each
     * guess — it cannot make a four-digit PIN expensive to try all of.
     */
    const val MIN_PASSWORD_LENGTH = 10

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** @return `pc2:<base64 of salt+iv+ciphertext>`, or null if the platform can't do AES-GCM. */
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
        val (prefix, iterations) = when {
            blob.startsWith(PREFIX) -> PREFIX to PBKDF2_ITERATIONS
            blob.startsWith(PREFIX_V1) -> PREFIX_V1 to PBKDF2_ITERATIONS_V1
            else -> return null
        }
        return try {
            val data = Base64.getDecoder().decode(blob.removePrefix(prefix))
            val salt = data.copyOfRange(0, SALT_LEN)
            val iv = data.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val ct = data.copyOfRange(SALT_LEN + IV_LEN, data.size)
            val key = deriveKey(password, salt, iterations)
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
