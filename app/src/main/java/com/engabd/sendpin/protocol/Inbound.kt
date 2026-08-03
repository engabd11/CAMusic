package com.engabd.sendpin.protocol

/**
 * One thing that arrived on the Sendspin WebSocket, in wire order.
 *
 * Control JSON and binary audio share a single socket, and the protocol relies on
 * that: `stream/clear` means "drop everything you have been given up to here", so
 * it only means anything if it is still ordered against the audio around it.
 * Handling text and binary through separate flows loses that ordering — a seek can
 * land after the frames it was supposed to discard, and the user hears a burst of
 * pre-seek audio.
 *
 * Everything the socket produces therefore goes through one queue of these and is
 * drained by one consumer.
 */
internal sealed interface Inbound {
    /** A text frame, with the local receive time (T4) stamped on the reader thread. */
    class Text(val text: String, val rxUs: Long) : Inbound

    /** A binary frame — an audio chunk, still with its Sendspin header attached. */
    class Audio(val frame: ByteArray) : Inbound

    /**
     * The socket dropped. Queued as a message rather than applied directly so the
     * reset happens *after* any frames still ahead of it, not in the middle of them.
     */
    data object Disconnected : Inbound
}

/**
 * The admission rule for the inbound queue.
 *
 * The queue is unbounded so that ordering can never be lost to a full buffer, which
 * means it needs its own guard against a stall turning into unbounded memory. Audio
 * is the only thing worth dropping: a stale chunk is already useless by the time the
 * queue is this deep, and the scheduler would discard it anyway. Control messages are
 * never dropped — losing a `stream/start` or a `server/time` breaks the session in
 * ways that don't recover.
 *
 * Pure so it can be tested without a socket.
 */
internal object Inbox {
    /**
     * ~4k frames. At the ~20 ms per FLAC chunk MA sends, that is over a minute of
     * backlog — far past anything a healthy consumer produces, so hitting it means
     * the decode loop has genuinely stopped rather than fallen briefly behind.
     */
    const val MAX_DEPTH = 4096

    fun accept(depth: Int, isAudio: Boolean, max: Int = MAX_DEPTH): Boolean =
        !isAudio || depth < max
}
