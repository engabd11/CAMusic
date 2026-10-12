package com.engabd.sendpin.audio

/**
 * Music Assistant's quality tiers, as its own pipeline view and the official app grade
 * a stream: LQ, SQ, HQ, HR, worst first.
 *
 * The server sends the tier itself for the input and each output
 * (`audio_processing.input_fidelity` / `outputs[].fidelity.quality`), and that is
 * preferred. [of] applies MA's documented rule to a bare format for everything the
 * server did not grade, such as a library this phone streams itself.
 */
enum class QualityTier(val code: String, val label: String) {
    LOW("LQ", "Low quality"),
    STANDARD("SQ", "Standard quality"),
    LOSSLESS("HQ", "Lossless"),
    HI_RES("HR", "Hi-res"),
    ;

    companion object {
        /** MA's `AudioQuality` wire value (`low`, `standard`, `lossless`, `hi_res`), or null. */
        fun fromWire(value: String?): QualityTier? = when (value?.lowercase()) {
            "low" -> LOW
            "standard" -> STANDARD
            "lossless" -> LOSSLESS
            "hi_res" -> HI_RES
            else -> null
        }

        /**
         * MA's rule: lossy below 256 kbps is LQ, lossy at 256 kbps or more SQ, lossless
         * HQ, lossless above 48 kHz or 16 bits HR. Null when a lossy format gives no
         * bitrate to judge by.
         */
        fun of(q: StreamQuality?): QualityTier? = when {
            q == null -> null
            q.lossless && (q.sampleRateHz > 48_000 || q.bitDepth > 16) -> HI_RES
            q.lossless -> LOSSLESS
            q.bitrateKbps >= 256 -> STANDARD
            q.bitrateKbps > 0 -> LOW
            else -> null
        }
    }
}
