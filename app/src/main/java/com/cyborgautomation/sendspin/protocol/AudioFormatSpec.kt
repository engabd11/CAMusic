package com.cyborgautomation.sendspin.protocol

import kotlinx.serialization.Serializable

@Serializable
data class AudioFormatSpec(
    val codec: String,    // "pcm" or "flac"
    val channels: Int,
    val sampleRate: Int,
    val bitDepth: Int,
)
