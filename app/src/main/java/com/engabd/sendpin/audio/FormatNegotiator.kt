package com.engabd.sendpin.audio

import com.engabd.sendpin.protocol.AudioFormatSpec

data class NegotiatedFormat(
    val codec: String,    // "pcm" or "flac"
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
)

object FormatNegotiator {
    val supportedFormats: List<AudioFormatSpec> = listOf(
        AudioFormatSpec("flac", 2, 48000, 24),
        AudioFormatSpec("flac", 2, 96000, 24),
        AudioFormatSpec("pcm",  2, 48000, 24),
        AudioFormatSpec("pcm",  2, 96000, 24),
        AudioFormatSpec("pcm",  2, 48000, 16),
    )

    fun resolve(serverFormat: AudioFormatSpec): NegotiatedFormat? {
        val match = supportedFormats.firstOrNull {
            it.codec == serverFormat.codec &&
            it.channels == serverFormat.channels &&
            it.sampleRate == serverFormat.sampleRate &&
            it.bitDepth == serverFormat.bitDepth
        }
        return match?.let {
            NegotiatedFormat(it.codec, it.sampleRate, it.bitDepth, it.channels)
        }
    }

    val preferredFormats: String
        get() = supportedFormats.joinToString(", ") { "${it.codec}:${it.sampleRate}:${it.bitDepth}:${it.channels}" }
}
