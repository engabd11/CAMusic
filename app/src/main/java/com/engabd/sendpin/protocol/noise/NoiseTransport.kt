package com.engabd.sendpin.protocol.noise

/**
 * Transport-mode encryption: one key per direction, counters advancing per frame.
 *
 * Every WebSocket binary frame after the handshake is one Noise transport ciphertext,
 * and the counters are the replay protection, so frames must be encrypted and decrypted
 * in wire order — one thread per direction, no reordering. Not thread-safe by design.
 */
class NoiseTransport internal constructor(
    private val send: CipherState,
    private val receive: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray = send.encryptWithAd(EMPTY, plaintext)

    /** @throws javax.crypto.AEADBadTagException on a forged, replayed or reordered frame. */
    fun decrypt(ciphertext: ByteArray): ByteArray = receive.decryptWithAd(EMPTY, ciphertext)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * The type-byte framing inside each decrypted transport message, and the
 * fragmentation that splits messages bigger than one Noise frame (spec §Communication,
 * §Fragmentation).
 *
 * After decryption the first byte says what the rest is: `0` a UTF-8 JSON body, `4` an
 * audio chunk, `2`/`3` a piece of a larger message. A Noise transport message is capped
 * at 65535 bytes including the 16-byte tag, so the plaintext per frame — type byte
 * included — is at most [MAX_PLAINTEXT]; a 24/192 PCM chunk is bigger than that and
 * arrives in pieces.
 *
 * Pure functions over byte arrays, so they can be tested without a socket.
 */
object NoiseFraming {
    const val TYPE_JSON = 0
    const val TYPE_FRAGMENT_MORE = 2
    const val TYPE_FRAGMENT_END = 3
    const val TYPE_AUDIO = 4

    /** 65535 − 16-byte tag: the most plaintext (type byte included) one frame carries. */
    const val MAX_PLAINTEXT = 65535 - NoiseCipherSuite.TAG_SIZE

    /**
     * Bounds one message's reassembly buffer, the same figure the reference uses; a
     * peer streaming endless fragments is a protocol error, not a memory leak.
     */
    const val MAX_REASSEMBLED = 64 * 1024 * 1024

    /** A JSON body as a type-prefixed plaintext. */
    fun json(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).also { System.arraycopy(body, 0, it, 1, body.size) }
    }

    /**
     * The frames one type-prefixed plaintext goes out as: itself when it fits, otherwise
     * an opening `[2][orig_type][data]`, continuations `[2][data]`, and a closing
     * `[3][data]`.
     */
    fun fragment(plaintext: ByteArray): List<ByteArray> {
        if (plaintext.size <= MAX_PLAINTEXT) return listOf(plaintext)
        val origType = plaintext[0]
        val frames = ArrayList<ByteArray>()
        val firstCap = MAX_PLAINTEXT - 2
        val contCap = MAX_PLAINTEXT - 1
        var pos = 1
        frames += byteArrayOf(TYPE_FRAGMENT_MORE.toByte(), origType) +
            plaintext.copyOfRange(pos, minOf(pos + firstCap, plaintext.size))
        pos = minOf(pos + firstCap, plaintext.size)
        // Anything over one frame leaves at least two bytes for the closing frame, so
        // there is always one, and the fragment count is never zero.
        val rest = plaintext.size - pos
        val chunks = (rest + contCap - 1) / contCap
        for (index in 0 until chunks) {
            val end = minOf(pos + contCap, plaintext.size)
            val tag = if (index == chunks - 1) TYPE_FRAGMENT_END else TYPE_FRAGMENT_MORE
            frames += byteArrayOf(tag.toByte()) + plaintext.copyOfRange(pos, end)
            pos = end
        }
        return frames
    }

    /**
     * Receiver-side reassembly: feed every decrypted frame in order; get back the
     * complete type-prefixed message, or null while more fragments are due.
     *
     * The malformed sequences the spec names are protocol errors that must close the
     * connection, and surface here as [ProtocolError].
     */
    class Reassembler {
        private var buffer: java.io.ByteArrayOutputStream? = null
        private var origType = 0

        class ProtocolError(message: String) : Exception(message)

        fun accept(frame: ByteArray): ByteArray? {
            if (frame.isEmpty()) throw ProtocolError("empty plaintext after Noise decrypt")
            return when (frame[0].toInt()) {
                TYPE_FRAGMENT_MORE -> {
                    val buf = buffer
                    if (buf == null) {
                        if (frame.size < 2) throw ProtocolError("fragment-more start frame missing orig_type")
                        val type = frame[1].toInt()
                        if (type == TYPE_FRAGMENT_MORE || type == TYPE_FRAGMENT_END) {
                            throw ProtocolError("fragment orig_type $type is itself a fragment type")
                        }
                        origType = type
                        buffer = java.io.ByteArrayOutputStream().also { it.write(frame, 2, frame.size - 2) }
                    } else {
                        append(buf, frame)
                    }
                    null
                }
                TYPE_FRAGMENT_END -> {
                    val buf = buffer ?: throw ProtocolError("fragment-end frame with no fragmented message in flight")
                    append(buf, frame)
                    buffer = null
                    val body = buf.toByteArray()
                    ByteArray(body.size + 1).also {
                        it[0] = origType.toByte()
                        System.arraycopy(body, 0, it, 1, body.size)
                    }
                }
                else -> {
                    if (buffer != null) throw ProtocolError("non-fragment frame while a fragmented message is in flight")
                    frame
                }
            }
        }

        private fun append(buf: java.io.ByteArrayOutputStream, frame: ByteArray) {
            if (buf.size() + frame.size - 1 > MAX_REASSEMBLED) {
                buffer = null
                throw ProtocolError("fragmented message exceeds maximum reassembly size")
            }
            buf.write(frame, 1, frame.size - 1)
        }
    }
}
