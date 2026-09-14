package com.engabd.sendpin.protocol.noise

import java.security.SecureRandom

/**
 * One AEAD key with its message counter — Noise's `CipherState` (§5.1).
 *
 * `k == null` is the "no key yet" state, in which the handshake's payloads pass in the
 * clear and are only mixed into the transcript hash.
 */
internal class CipherState(private val suite: NoiseCipherSuite, private var k: ByteArray? = null) {
    private var n = 0L

    val hasKey: Boolean get() = k != null

    fun initializeKey(key: ByteArray) {
        k = key
        n = 0L
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext
        check(n != -1L) { "Noise nonce exhausted" }
        return suite.encrypt(key, n++, ad, plaintext)
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext
        check(n != -1L) { "Noise nonce exhausted" }
        // The counter only advances on a verified tag: Noise §5.1 forbids consuming a
        // nonce on a failed decrypt, and the connection is dropped on one anyway.
        val plaintext = suite.decrypt(key, n, ad, ciphertext)
        n++
        return plaintext
    }
}

/** The chaining key + transcript hash pair — Noise's `SymmetricState` (§5.2). */
internal class SymmetricState(private val suite: NoiseCipherSuite) {
    val cipher = CipherState(suite)
    private var ck: ByteArray
    var h: ByteArray
        private set

    init {
        val name = suite.protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= NoiseHash.HASH_LEN) name.copyOf(NoiseHash.HASH_LEN) else NoiseHash.sha256(name)
        ck = h
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = NoiseHash.hkdf(ck, inputKeyMaterial, 2)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = NoiseHash.sha256(h, data)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (newCk, tempH, tempK) = NoiseHash.hkdf(ck, inputKeyMaterial, 3)
        ck = newCk
        mixHash(tempH)
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /** `Split()`: the two transport keys, initiator-to-responder first. */
    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = NoiseHash.hkdf(ck, ByteArray(0), 2)
        return CipherState(suite, k1) to CipherState(suite, k2)
    }
}

/** Thrown when a handshake message fails to parse or authenticate. The socket is then closed silently, per the spec. */
class NoiseHandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The client's half of Sendspin's Noise handshake: the **responder** of
 *
 * ```
 * KKpsk2:
 *   -> s
 *   <- s
 *   ...
 *   -> e, es, ss            (message 1, from the server; carries the psk_id)
 *   <- e, ee, se, psk       (message 2, from us; carries "{}")
 * ```
 *
 * The server is always the Noise initiator, whoever opened the WebSocket. Both static
 * keys are known in advance (`client_id` and `server_id` *are* the public keys), and the
 * PSK is only mixed in on message 2 — which is what lets [readMessage1] run without one:
 * message 1's payload names the `psk_id`, the caller looks the PSK up, and hands it to
 * [writeMessage2].
 *
 * Transport-agnostic on purpose: the first handshake's messages travel as cleartext
 * text frames, an in-band re-handshake's as JSON inside the existing encrypted channel,
 * and the state machine is the same either way.
 */
class NoiseHandshake(
    val suite: NoiseCipherSuite,
    localStaticPrivate: ByteArray,
    remoteStaticPublic: ByteArray,
    prologue: ByteArray,
    /** Injectable so a test can replay a transcript; production callers leave it random. */
    private val ephemeralPrivate: ByteArray = X25519.generatePrivateKey(SecureRandom()),
) {
    private val s = localStaticPrivate
    private val rs = remoteStaticPublic
    private val state = SymmetricState(suite)
    private var re: ByteArray? = null
    private var stage = 0

    /** The transport keys and the handshake hash `h`, available after [writeMessage2]. */
    class Result(val transport: NoiseTransport, val handshakeHash: ByteArray)

    init {
        state.mixHash(prologue)
        // Pre-messages, initiator's first: the server's static key, then ours.
        state.mixHash(rs)
        state.mixHash(X25519.publicKey(s))
    }

    /** Consume Noise message 1 and return its decrypted payload (the `psk_id` JSON). */
    fun readMessage1(message: ByteArray): ByteArray {
        check(stage == 0) { "message 1 already read" }
        if (message.size < X25519.KEY_SIZE) throw NoiseHandshakeException("Noise message 1 too short")
        val re = message.copyOfRange(0, X25519.KEY_SIZE)
        this.re = re
        // e — in a psk pattern the ephemeral is also mixed into the key.
        state.mixHash(re)
        state.mixKey(re)
        // es (responder side: DH(s, re)), then ss.
        state.mixKey(X25519.scalarMult(s, re))
        state.mixKey(X25519.scalarMult(s, rs))
        val payload = try {
            state.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
        } catch (e: Exception) {
            throw NoiseHandshakeException("Noise message 1 failed authentication", e)
        }
        stage = 1
        return payload
    }

    /** Produce Noise message 2 carrying [payload], mixing [psk] in; completes the handshake. */
    fun writeMessage2(psk: ByteArray, payload: ByteArray): Pair<ByteArray, Result> {
        check(stage == 1) { "message 1 must be read first" }
        require(psk.size == 32) { "PSK must be 32 bytes" }
        val re = checkNotNull(re)
        val e = ephemeralPrivate
        val ePub = X25519.publicKey(e)
        // e
        state.mixHash(ePub)
        state.mixKey(ePub)
        // ee, se (responder side: DH(e, rs)), psk
        state.mixKey(X25519.scalarMult(e, re))
        state.mixKey(X25519.scalarMult(e, rs))
        state.mixKeyAndHash(psk)
        val ciphertext = state.encryptAndHash(payload)
        val (initiatorToResponder, responderToInitiator) = state.split()
        stage = 2
        val message = ePub + ciphertext
        return message to Result(
            NoiseTransport(send = responderToInitiator, receive = initiatorToResponder),
            state.h,
        )
    }
}
