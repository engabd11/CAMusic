package com.engabd.sendpin.audio

/**
 * What is actually coming out of the pipe, however it got here: negotiated by
 * the Sendspin protocol when this phone is the player, or read off the Music
 * Assistant queue's stream details when a remote speaker is.
 *
 * Kept codec-agnostic — the UI decides how loudly to shout about it.
 */
data class StreamQuality(
    val codec: String,          // "FLAC", "OPUS", "MP3", …
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
) {
    val lossless: Boolean get() = codec.lowercase() in LOSSLESS

    /** Better than CD: the case worth lighting the badge up for. */
    val hiRes: Boolean get() = lossless && (sampleRateHz > 48_000 || bitDepth > 16)

    /** The badge text, e.g. `FLAC · 96/24 · 1411k` or `OPUS · 320k`. */
    val label: String
        get() {
            val name = codec.uppercase()
            val detail = when {
                lossless && bitDepth > 0 && sampleRateHz > 0 -> "${khz(sampleRateHz)}/$bitDepth"
                sampleRateHz > 0 && bitrateKbps > 0 && !lossless -> "${bitrateKbps}k"
                sampleRateHz > 0 -> "${khz(sampleRateHz)}kHz"
                bitrateKbps > 0 -> "${bitrateKbps}k"
                else -> null
            }
            val br = if (bitrateKbps > 0 && lossless) " · ${bitrateKbps}k" else ""
            return if (detail == null) name else "$name · $detail$br"
        }

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "pcm", "dsf", "dff", "ape", "wavpack")

        /** 44100 → "44.1", 96000 → "96". */
        private fun khz(hz: Int): String {
            val k = hz / 1000.0
            return if (k == Math.floor(k)) k.toInt().toString() else String.format("%.1f", k)
        }
    }
}
