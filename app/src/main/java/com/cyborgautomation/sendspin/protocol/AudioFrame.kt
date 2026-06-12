package com.cyborgautomation.sendspin.protocol

sealed class AudioFrame {
    abstract val timestamp: Long  // microseconds, server time

    data class Pcm(
        override val timestamp: Long,
        val format: AudioFormatSpec,
        val rawPcmBytes: ByteArray,  // Packed 3-byte i24 LE, interleaved
    ) : AudioFrame()

    data class Flac(
        override val timestamp: Long,
        val format: AudioFormatSpec,
        val compressedData: ByteArray,  // Raw FLAC frame
    ) : AudioFrame()
}

class AudioFrameParser {
    private var currentFormat: AudioFormatSpec? = null

    fun setFormat(format: AudioFormatSpec) {
        currentFormat = format
    }

    fun parse(bytes: ByteArray): AudioFrame {
        require(bytes.size >= 9) { "Audio frame too short: ${bytes.size} bytes" }

        // Byte 0: Type ID
        val typeId = bytes[0].toInt() and 0xFF
        require(typeId == 0x04) { "Unexpected audio frame type: 0x${typeId.toString(16)}" }

        // Bytes 1-8: Timestamp (big-endian i64, microseconds)
        var timestamp = 0L
        for (i in 1..8) {
            timestamp = (timestamp shl 8) or (bytes[i].toLong() and 0xFF)
        }

        // Bytes 9+: Codec payload
        val payload = bytes.copyOfRange(9, bytes.size)
        val format = currentFormat

        return when (format?.codec) {
            "flac" -> AudioFrame.Flac(timestamp, format!!, payload)
            "pcm", null -> AudioFrame.Pcm(
                timestamp,
                format ?: AudioFormatSpec("pcm", 2, 48000, 24),
                payload  // Raw packed 3-byte i24 LE — identical to AAudio I24
            )
            else -> AudioFrame.Pcm(timestamp, format, payload)
        }
    }
}
