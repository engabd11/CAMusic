package com.engabd.sendpin.protocol.noise

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two cipher suites the Sendspin spec defines for its Noise `KKpsk2` handshake.
 * A client picks one and names it in `client/init`; servers must support both.
 *
 * Both are AEADs with a 32-byte key, a 12-byte nonce and a 16-byte tag. What differs
 * beyond the primitive is how Noise lays the 64-bit message counter into the nonce:
 * big-endian for AES-GCM, little-endian for ChaCha20-Poly1305 (Noise §12.3 / §12.4).
 */
enum class NoiseCipherSuite(val wireName: String) {
    /** Hardware-accelerated on every phone; the one the app picks. */
    AESGCM("25519_AESGCM_SHA256"),
    CHACHAPOLY("25519_ChaChaPoly_SHA256");

    val protocolName: String get() = "Noise_KKpsk2_$wireName"

    fun encrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce).run {
            updateAAD(ad)
            doFinal(plaintext)
        }

    /** @throws javax.crypto.AEADBadTagException when the tag does not verify. */
    fun decrypt(key: ByteArray, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce).run {
            updateAAD(ad)
            doFinal(ciphertext)
        }

    private fun cipher(mode: Int, key: ByteArray, nonce: Long): Cipher {
        val iv = ByteArray(12)
        val order = if (this == AESGCM) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        ByteBuffer.wrap(iv, 4, 8).order(order).putLong(nonce)
        return when (this) {
            AESGCM -> Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }
            CHACHAPOLY -> Cipher.getInstance("ChaCha20-Poly1305").apply {
                init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
            }
        }
    }

    companion object {
        const val TAG_SIZE = 16

        fun fromWireName(name: String): NoiseCipherSuite? = entries.firstOrNull { it.wireName == name }
    }
}

internal object NoiseHash {
    const val HASH_LEN = 32

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** Noise's HKDF (§4.3): two or three outputs from a chaining key and input material. */
    fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, outputs: Int): List<ByteArray> {
        require(outputs == 2 || outputs == 3)
        val tempKey = hmac(chainingKey, inputKeyMaterial)
        val out1 = hmac(tempKey, byteArrayOf(0x01))
        val out2 = hmac(tempKey, out1, byteArrayOf(0x02))
        if (outputs == 2) return listOf(out1, out2)
        val out3 = hmac(tempKey, out2, byteArrayOf(0x03))
        return listOf(out1, out2, out3)
    }
}
