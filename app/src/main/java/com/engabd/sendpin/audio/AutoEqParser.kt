package com.engabd.sendpin.audio

/**
 * Reads an AutoEQ `ParametricEQ.txt` (the Equalizer APO format AutoEQ publishes a
 * correction for each headphone in) into a parametric curve.
 *
 * ```
 * Preamp: -6.4 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.3 dB Q 0.70
 * Filter 2: ON PK Fc 2399 Hz Gain -2.1 dB Q 1.74
 * Filter 10: ON HSC Fc 10000 Hz Gain -1.2 dB Q 0.70
 * ```
 *
 * Forgiving where the files in the wild differ: comma decimals (written by
 * European locales), `kHz`, filters numbered or not, `LS`/`HS` without a Q, and the
 * pass filters with or without one. A filter switched `OFF` is kept, disabled, so
 * the curve reads the way the file does. A type this equaliser has no section for
 * (a notch, an all-pass) is counted and left out rather than guessed at.
 */
object AutoEqParser {

    data class Result(
        val config: LocalDsp.Config,
        /** Filters the file had that this equaliser cannot run. */
        val skipped: Int,
    )

    /** Enough for any AutoEQ correction (they use ten) with room for a hand-made file. */
    const val MAX_BANDS = 31

    private const val NUM = """[-+]?\d+(?:[.,]\d+)?"""
    private val preampLine = Regex("""^\s*Preamp\s*:\s*($NUM)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterLine = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)(?:\s+\d+\s*dB)?\s+Fc\s+($NUM)\s*(k?Hz)?""" +
            """(?:\s+Gain\s+($NUM)\s*dB)?(?:\s+Q\s+($NUM))?""",
        RegexOption.IGNORE_CASE,
    )

    /** The curve in [text], or null when it holds no filter this equaliser can run. */
    fun parse(text: String): Result? {
        var preamp = 0f
        var skipped = 0
        val bands = mutableListOf<LocalDsp.Band>()
        for (line in text.lineSequence()) {
            preampLine.find(line)?.let { preamp = number(it.groupValues[1]) ?: preamp }
            val m = filterLine.find(line) ?: continue
            val type = typeOf(m.groupValues[2])
            val hz = number(m.groupValues[3])?.let { if (m.groupValues[4].equals("kHz", true)) it * 1_000f else it }
            if (type == null || hz == null || hz <= 0f) {
                skipped++
                continue
            }
            if (bands.size >= MAX_BANDS) {
                skipped++
                continue
            }
            bands += LocalDsp.Band(
                type = type,
                frequency = hz.coerceIn(10f, 22_000f),
                gainDb = number(m.groupValues[5])?.coerceIn(-30f, 30f) ?: 0f,
                q = number(m.groupValues[6])?.takeIf { it > 0f }?.coerceIn(0.05f, 20f) ?: DEFAULT_Q,
                enabled = m.groupValues[1].equals("ON", ignoreCase = true),
            )
        }
        if (bands.none { it.enabled }) return null
        return Result(
            config = LocalDsp.Config(
                enabled = true,
                bands = bands,
                // The file's own preamp is the correction's headroom, worked out for
                // that headphone; the automatic one would second-guess it.
                preampDb = preamp.coerceIn(-30f, 12f),
                autoPreamp = false,
                parametric = true,
            ),
            skipped = skipped,
        )
    }

    /** A shelf's or pass filter's Q when the file leaves it out: Butterworth. */
    private const val DEFAULT_Q = 0.71f

    private fun typeOf(code: String): LocalDsp.Band.Type? = when (code.uppercase()) {
        "PK", "PEQ", "MODAL" -> LocalDsp.Band.Type.PEAKING
        "LS", "LSC", "LSQ" -> LocalDsp.Band.Type.LOW_SHELF
        "HS", "HSC", "HSQ" -> LocalDsp.Band.Type.HIGH_SHELF
        "HP", "HPQ" -> LocalDsp.Band.Type.HIGH_PASS
        "LP", "LPQ" -> LocalDsp.Band.Type.LOW_PASS
        else -> null
    }

    private fun number(raw: String): Float? = raw.replace(',', '.').toFloatOrNull()
}
