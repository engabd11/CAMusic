package com.engabd.sendpin.audio

/**
 * The wire format of one Sendspin binary audio message:
 * `[type:uint8 = 4][server_ts_us: int64 big-endian][payload...]`. Shared between
 * [SendspinAudioEngine] and [SendspinExoEngine] - the two playback engines that
 * consume the same frames through different pipelines - so the header layout has
 * exactly one definition to drift out of sync.
 */
object SendspinAudioFrame {
    const val HEADER_SIZE = 9
    const val TYPE_PLAYER_AUDIO = 4

    /** The server timestamp and the payload (header stripped), or null if this isn't a player-audio frame. */
    fun parse(frame: ByteArray): Pair<Long, ByteArray>? {
        if (frame.size <= HEADER_SIZE || (frame[0].toInt() and 0xFF) != TYPE_PLAYER_AUDIO) return null
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (frame[i].toLong() and 0xffL)
        return ts to frame.copyOfRange(HEADER_SIZE, frame.size)
    }
}
