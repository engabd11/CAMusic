package com.engabd.sendpin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An audio format advertised to / negotiated with the server. The wire keys are
 * `sample_rate` / `bit_depth` (Music Assistant's Sendspin naming). Field ORDER is
 * kept (codec, channels, sampleRate, bitDepth) so positional constructor calls in
 * the audio layer keep working.
 */
@Serializable
data class AudioFormatSpec(
    val codec: String,                                    // "flac", "opus", or "pcm"
    val channels: Int,
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("bit_depth") val bitDepth: Int,
)
