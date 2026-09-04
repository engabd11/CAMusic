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

    private fun secretKey(): SecretKey {
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
            c.init(Cipher.ENCRYPT_MODE, secretKey())
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
        return try {
            val data = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = data.copyOfRange(0, IV_LEN)
            val ct = data.copyOfRange(IV_LEN, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
