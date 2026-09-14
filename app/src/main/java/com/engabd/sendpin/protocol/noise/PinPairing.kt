package com.engabd.sendpin.protocol.noise

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * The pieces of the PIN pairing flows that are pure computation (spec §Dynamic PIN
 * Pairing Flow, §Static PIN Pairing Flow, §PAKE, §PSK Wrapping): the CPace session id,
 * the dynamic PIN's commitment and derivation, and the wrapping of the new long-term
 * PSK under the PAKE output. All labels are the spec's literal bytes.
 */
object PinPairing {
    const val NONCE_SIZE = 32
    const val MIN_PIN_DIGITS = 4
    const val MAX_PIN_DIGITS = 12
    /** Below this many digits a dynamic-PIN attempt is gesture-gated (spec §Pairing Window). */
    const val SHORT_PIN_DIGITS = 6
    const val STATIC_PIN_DIGITS = 8
    /** The spec's recommended initial `min_pin_length`. */
    const val DEFAULT_MIN_PIN_DIGITS = 6
    /** Failures of `server_kc` verification at which dynamic PIN escalates to gesture-gating. */
    const val ESCALATION_FAILURES = 10

    private val SID_LABEL = "sendspin-pair-pake-v1".toByteArray(Charsets.US_ASCII)
    private val PIN_DERIVE_LABEL = "sendspin-pin-derive-v1".toByteArray(Charsets.US_ASCII)
    private val COMMIT_LABEL = "sendspin-pair-commit-v1".toByteArray(Charsets.US_ASCII)
    private val WRAP_LABEL = "sendspin-pair-psk-wrap-v1".toByteArray(Charsets.US_ASCII)
    val AD_SERVER: ByteArray = "server".toByteArray(Charsets.US_ASCII)
    val AD_CLIENT: ByteArray = "client".toByteArray(Charsets.US_ASCII)

    fun generateNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

    /** `"sendspin-pair-pake-v1" || h || counter` — the CPace session id for one attempt. */
    fun sid(handshakeHash: ByteArray, pairingIndex: Int): ByteArray =
        SID_LABEL + handshakeHash + ByteBuffer.allocate(4).putInt(pairingIndex).array()

    /** `commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B)`. */
    fun commit(nonceB: ByteArray): ByteArray {
        require(nonceB.size == NONCE_SIZE)
        return NoiseHash.sha256(COMMIT_LABEL, nonceB)
    }

    /** The dynamic PIN: SHA-256 over the label, `h` and both nonces, taken mod 10^L, zero-padded. */
    fun derivePin(handshakeHash: ByteArray, nonceA: ByteArray, nonceB: ByteArray, pinLength: Int): String {
        require(pinLength in MIN_PIN_DIGITS..MAX_PIN_DIGITS) { "pin_length must be in [$MIN_PIN_DIGITS, $MAX_PIN_DIGITS]" }
        require(nonceA.size == NONCE_SIZE && nonceB.size == NONCE_SIZE)
        val digest = NoiseHash.sha256(PIN_DERIVE_LABEL, handshakeHash, nonceA, nonceB)
        val pinInt = BigInteger(1, digest).mod(BigInteger.TEN.pow(pinLength))
        return pinInt.toString().padStart(pinLength, '0')
    }

    fun isValidStaticPin(pin: String): Boolean = pin.length == STATIC_PIN_DIGITS && pin.all { it in '0'..'9' }

    fun generateStaticPin(random: SecureRandom = SecureRandom()): String =
        (1..STATIC_PIN_DIGITS).joinToString("") { random.nextInt(10).toString() }

    /** `K_wrap = SHA-256("sendspin-pair-psk-wrap-v1" || sid || ISK)`. */
    fun wrapKey(sid: ByteArray, isk: ByteArray): ByteArray = NoiseHash.sha256(WRAP_LABEL, sid, isk)

    /**
     * The new PSK sealed under [wrapKey] with the connection's AEAD, an all-zero 12-byte
     * nonce and no associated data: 48 bytes of ciphertext plus tag (spec §PSK Wrapping).
     */
    fun wrapPsk(suite: NoiseCipherSuite, wrapKey: ByteArray, psk: ByteArray): ByteArray {
        require(psk.size == SendspinPsk.PSK_SIZE)
        // A zero nonce in either suite's byte order is the same twelve zero bytes.
        return suite.encrypt(wrapKey, 0L, ByteArray(0), psk)
    }
}
