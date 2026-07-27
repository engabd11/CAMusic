package com.engabd.sendpin.audio

import com.engabd.sendpin.protocol.AudioFormatSpec
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

data class NegotiatedFormat(
    val codec: String,    // "flac", "opus", or "pcm"
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
)

object FormatNegotiator {
    /**
     * Formats we advertise to the server (`player@v1_support.supported_formats`).
     * The server streams one of *these*, so it always matches. FLAC-first at
     * 48 kHz / 16-bit — matches Music Assistant's default and keeps grouped sync
     * stable (it is the server's fallback order; opus-first breaks grouped sync).
     * The 24-bit entries are for the later hi-res phase.
     */
    val supportedFormats: List<AudioFormatSpec> = listOf(
        AudioFormatSpec("flac", 2, 48000, 16),
        AudioFormatSpec("opus", 2, 48000, 16),
        AudioFormatSpec("pcm", 2, 48000, 16),
        AudioFormatSpec("flac", 2, 48000, 24),
        AudioFormatSpec("flac", 2, 96000, 24),
    )

    /**
     * The server always streams a format we advertised, so accept the `stream/start`
     * player info directly.
     */
    fun resolve(player: StreamStartPlayerInfo): NegotiatedFormat =
        NegotiatedFormat(player.codec, player.sampleRate, player.bitDepth, player.channels)

    val preferredFormats: String
        get() = supportedFormats.joinToString(", ") { "${it.codec}:${it.sampleRate}:${it.bitDepth}:${it.channels}" }
}
