package com.engabd.sendpin.usb

/**
 * Which of a DAC's playback formats a track goes out in.
 *
 * The file's own bit depth when the DAC has it — a 16-bit album goes to a 16-bit
 * alternate setting, not padded into a 24-bit one. Padding is lossless (a 16-bit sample
 * in a 24-bit slot is the same number with zeros below it) but it is still a change of
 * container the DAC then has to undo, and a Bluetooth DAC re-encoding to a 16-bit codec
 * such as aptX Lossless has one less step to take. Failing an exact match, the smallest
 * format that holds every bit; failing that, the deepest there is, which loses bits and
 * is reported as not bit-perfect. The rate is never changed: a format without the file's
 * rate is not a candidate.
 */
object UsbFormatChoice {

    fun choose(outputs: List<StreamingAlt>, rate: Int, channels: Int, sourceBits: Int?): StreamingAlt? {
        val candidates = outputs
            .filter { it.channels == channels && it.endpoint != null }
            .filter { a ->
                rate in a.sampleRates ||
                    a.sampleRateRange?.contains(rate) == true ||
                    // UAC2 lists no rates in the descriptors; the clock read-back decides.
                    (a.sampleRates.isEmpty() && a.sampleRateRange == null)
            }
        if (candidates.isEmpty()) return null
        val deepest = candidates.maxWith(compareBy<StreamingAlt>({ it.bitResolution }, { it.subslotBytes }))
        if (sourceBits == null) return deepest
        candidates.filter { it.bitResolution == sourceBits }
            .minByOrNull { it.subslotBytes }
            ?.let { return it }
        return candidates.filter { it.bitResolution >= sourceBits }
            .minWithOrNull(compareBy<StreamingAlt>({ it.bitResolution }, { it.subslotBytes }))
            ?: deepest
    }
}
