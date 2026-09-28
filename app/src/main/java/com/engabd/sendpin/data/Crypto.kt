package com.engabd.sendpin.data

import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM string encryption backed by the Android Keystore (key never leaves
 * secure hardware). Used to protect the stored MA password + HA token at rest.
 * Values are tagged with a prefix so legacy plaintext still reads back (and gets
 * re-encrypted on the next write).
 */
object Crypto {
    private const val KEY_ALIAS = "sendpin_creds_v1"
    private const val PREFIX = "enc1:"
    private const val IV_LEN = 12
    private const val TAG = "SendpinCrypto"

    /**
     * The key handle, once found. Opening the Keystore is a binder round trip to
     * keystore2, and it was paid on *every* encrypt and decrypt — dozens per settings
     * write, since every flow that reads a secret re-runs on any write at all. The
     * handle is only a reference; the key material never leaves the Keystore either
     * way. Dropped on any failure so a key that was invalidated is looked up afresh.
     */
    @Volatile private var cachedKey: SecretKey? = null

    /**
     * Decrypted values by ciphertext. Every write uses a fresh random IV, so a
     * changed value is always a new blob and a stale entry can never be returned;
     * the only cost is memory the app is already spending, since the same plaintext
     * sits in the flows that asked for it. Bounded so a busy session cannot grow it.
     */
    private val plainByBlob = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > DECRYPT_CACHE
    }
    private const val DECRYPT_CACHE = 64

    private fun secretKey(): SecretKey = cachedKey ?: loadKey().also { cachedKey = it }

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            initWithFreshKeyOnFailure { c.init(Cipher.ENCRYPT_MODE, it) }
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // No plaintext fallback: a broken Keystore must never cause a password
            // or token to be written to DataStore in the clear. Losing the value is
            // recoverable (the user re-enters it); storing it unencrypted is not.
            Log.w(TAG, "encrypt failed; credential not persisted", e)
            ""
        }
    }

    fun decrypt(blob: String): String {
        if (blob.isEmpty() || !blob.startsWith(PREFIX)) return blob  // legacy plaintext
        synchronized(plainByBlob) { plainByBlob[blob] }?.let { return it }
        return try {
            val data = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            initWithFreshKeyOnFailure { c.init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, iv)) }
            String(c.doFinal(ct), Charsets.UTF_8).also { plain ->
                synchronized(plainByBlob) { plainByBlob[blob] = plain }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Run a cipher init with the cached key, and once more with a key looked up
     * afresh if that fails — a cached handle to a key the system has since
     * invalidated must cost one retry, not every secret in the app.
     */
    private inline fun initWithFreshKeyOnFailure(init: (SecretKey) -> Unit) {
        try {
            init(secretKey())
        } catch (e: java.security.GeneralSecurityException) {
            cachedKey = null
            init(secretKey())
        }
    }
}
