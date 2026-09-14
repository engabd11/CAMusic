package com.engabd.sendpin.protocol.noise

import java.security.SecureRandom
import java.util.Base64

/** The base64url (no padding) alphabet every Sendspin identifier and key travels in. */
object B64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Tolerates missing padding, like the reference; throws on anything else. */
    fun decode(text: String): ByteArray = decoder.decode(text)
}

/** Sendspin's fixed PSK-layer constants (spec §Pre-Shared Key). */
object SendspinPsk {
    const val PSK_SIZE = 32

    // Declared first: object properties initialise in order, and the two constants
    // below are derived through [idFor], which reads this.
    private val ID_LABEL = "sendspin-psk-id-v1".toByteArray(Charsets.US_ASCII)

    /** Published constant used when no other PSK applies. Authenticates nothing. */
    val SENTINEL: ByteArray = NoiseHash.sha256("sendspin-sentinel-psk-v1".toByteArray(Charsets.US_ASCII))
    val SENTINEL_ID: String = idFor(SENTINEL)

    /** `psk_id = base64url(SHA-256("sendspin-psk-id-v1" || PSK))`. */
    fun idFor(psk: ByteArray): String {
        require(psk.size == PSK_SIZE) { "PSK must be $PSK_SIZE bytes" }
        return B64Url.encode(NoiseHash.sha256(ID_LABEL, psk))
    }

    fun generate(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(PSK_SIZE).also { random.nextBytes(it) }
}

/**
 * A Sendspin identity: a long-lived X25519 keypair whose base64url public key is the
 * `client_id`. Rotating it makes this phone a new player everywhere.
 */
class SendspinIdentity(val privateKey: ByteArray) {
    init {
        require(privateKey.size == X25519.KEY_SIZE) { "X25519 private key must be 32 bytes" }
    }

    val publicKey: ByteArray by lazy { X25519.publicKey(privateKey) }

    /** The 43-character base64url public key — the wire `client_id`. */
    val peerId: String by lazy { B64Url.encode(publicKey) }

    companion object {
        const val PEER_ID_LENGTH = 43

        fun generate(random: SecureRandom = SecureRandom()) = SendspinIdentity(X25519.generatePrivateKey(random))

        /** Decode a 43-character peer id to its 32-byte public key, or null if it is not one. */
        fun decodePeerId(peerId: String): ByteArray? {
            if (peerId.length != PEER_ID_LENGTH) return null
            val bytes = try { B64Url.decode(peerId) } catch (_: IllegalArgumentException) { return null }
            return bytes.takeIf { it.size == X25519.KEY_SIZE }
        }
    }
}

/**
 * The pairing token an operator pastes or scans into a server to run the Pairing PSK
 * flow (spec §Pairing Token): `SP:0` + base32(client_key || pairing_psk) with the
 * padding stripped and every `2` written as `9`, so the whole thing sits in the QR
 * alphanumeric alphabet.
 */
object PairingToken {
    private const val PREFIX = "SP:"
    private const val VERSION = '0'
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(clientPublicKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientPublicKey.size == 32 && pairingPsk.size == 32)
        val body = base32(clientPublicKey + pairingPsk).trimEnd('=').replace('2', '9')
        return "$PREFIX$VERSION$body"
    }

    class Decoded(val clientPublicKey: ByteArray, val pairingPsk: ByteArray)

    /** Lenient with operator input as the spec asks; null for anything malformed. */
    fun decode(input: String): Decoded? {
        var s = input.trim().uppercase()
        if (s.startsWith(PREFIX)) s = s.removePrefix(PREFIX)
        if (s.isEmpty() || s[0] != VERSION) return null
        val body = s.substring(1).replace('9', '2')
        val padded = body + "=".repeat((8 - body.length % 8) % 8)
        val bytes = unbase32(padded) ?: return null
        if (bytes.size != 64) return null
        return Decoded(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(value shl (5 - bits)) and 31])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    private fun unbase32(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (c in text) {
            if (c == '=') break
            val idx = ALPHABET.indexOf(c)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.write((value ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
