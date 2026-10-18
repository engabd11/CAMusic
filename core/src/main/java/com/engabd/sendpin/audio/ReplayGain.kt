package com.engabd.sendpin.audio

import kotlin.math.pow

/**
 * Turning a ReplayGain tag into a linear volume factor.
 *
 * The tags were already being parsed off OpenSubsonic and shown on the quality
 * card, but nothing applied them, so the number on screen described something that
 * wasn't happening. That is the difference between an album mastered in 1985 and
 * one mastered in 2015 landing 10 dB apart on the same playlist.
 *
 * Applied as a scalar on the player's volume rather than as a DSP stage: the gain
 * is a constant per track, and a multiply on the output is exactly what a constant
 * gain is. A processor would cost a buffer copy to do the same arithmetic.
 */
object ReplayGain {

    const val OFF = "off"
    const val TRACK = "track"
    const val ALBUM = "album"

    /**
     * ReplayGain is free to ask for a *boost*, and a boost is where clipping comes
     * from: the samples were mastered against full scale, so multiplying them up has
     * nowhere to go but into the ceiling. Attenuation is always safe, so positive
     * gain is capped rather than trusted.
     *
     * Anything under +0 dB passes untouched. Real-world positive values are small —
     * quiet classical and jazz recordings, mostly. A rise is applied in [LocalDsp]
     * behind a [BoostLimiter], so it cannot clip; the cap is about how hard the
     * limiter may be asked to work on a master that was quiet on purpose.
     */
    const val MAX_BOOST_DB = 6f

    /** Below this the track would be inaudible; treat it as a bad tag and ignore it. */
    const val MIN_DB = -30f

    /** The ReplayGain 2 reference loudness, which measured tracks are levelled to. */
    const val TARGET_LUFS = -18f

    /** The highest a levelled track's peak may reach, so the rise never clips an encoder or a DAC. */
    const val TRUE_PEAK_CEILING_DB = -1f

    /** Where a gain came from, for the signal path to say so. */
    enum class Origin { TAGS, MEASURED, FIXED }

    /** A gain that will be applied, and what it was worked out from. */
    data class Applied(val db: Float, val origin: Origin)

    /**
     * The linear factor to multiply output by, or 1.0 when nothing should change.
     *
     * @param mode one of [OFF], [TRACK], [ALBUM].
     */
    fun factor(
        quality: StreamQuality?,
        mode: String,
        untaggedDb: Float = 0f,
        measured: MeasuredLoudness? = null,
    ): Float {
        val db = decibels(quality, mode, untaggedDb, measured) ?: return 1f
        return 10f.toDouble().pow(db / 20.0).toFloat()
    }

    /** The gain that will actually be applied, in dB, or null when none is. See [resolve]. */
    fun decibels(
        quality: StreamQuality?,
        mode: String,
        untaggedDb: Float = 0f,
        measured: MeasuredLoudness? = null,
    ): Float? = resolve(quality, mode, untaggedDb, measured)?.db

    /**
     * The gain to apply and where it came from, in the order of how much to trust it:
     *
     *  1. **The file's own tags**, as the mode asks (track or album).
     *  2. **Our own measurement** of the track (BS.1770 loudness from the background
     *     scan) levelled to [TARGET_LUFS], held back so the peak stays under
     *     [TRUE_PEAK_CEILING_DB]. Per track, whatever the mode: an album gain cannot be
     *     worked out from one track.
     *  3. **The fixed level** for untagged files, [untaggedDb]: what a track gets until
     *     its scan has run.
     *
     * Null when the mode is off or none of those applies. A tag below [MIN_DB] is
     * treated as a bad one and skipped, not trusted.
     *
     * [untaggedDb] is for a track the library describes but carries no level for.
     * A tagged track is typically pulled down 6-10 dB to the ReplayGain reference,
     * and an untagged one used to stay at full level, so shuffling the two made
     * every untagged song jump out. Off (0) by default; see [UNTAGGED_CHOICES].
     */
    fun resolve(
        quality: StreamQuality?,
        mode: String,
        untaggedDb: Float = 0f,
        measured: MeasuredLoudness? = null,
    ): Applied? {
        if (mode != TRACK && mode != ALBUM) return null
        val raw = when (mode) {
            TRACK -> quality?.replayGainTrack ?: quality?.replayGainAlbum
            // Album gain is the point of the album mode, but a single with only a
            // track tag should still be levelled rather than left alone.
            else -> quality?.replayGainAlbum ?: quality?.replayGainTrack
        }
        if (raw != null && raw.isFinite() && raw >= MIN_DB) {
            return Applied(raw.coerceAtMost(MAX_BOOST_DB), Origin.TAGS)
        }
        measuredDecibels(measured)?.let { return Applied(it, Origin.MEASURED) }
        // A bad tag is dropped, never replaced by the fixed level: that is a guess
        // for files that say nothing, not for ones that say something unusable.
        if (raw != null) return null
        // Not applied when nothing is known about the track at all.
        if (quality == null) return null
        return untaggedDb.takeIf { it != 0f && it.isFinite() }
            ?.let { Applied(it.coerceIn(MIN_DB, 0f), Origin.FIXED) }
    }

    /**
     * The gain that brings [measured] to [TARGET_LUFS], limited so its true peak ends
     * no higher than [TRUE_PEAK_CEILING_DB], and by the same cap as a tag. Null when
     * there is no usable measurement.
     */
    fun measuredDecibels(measured: MeasuredLoudness?): Float? {
        if (measured == null) return null
        val lufs = measured.integratedLufs
        val peak = measured.truePeakDb
        if (!lufs.isFinite() || !peak.isFinite()) return null
        val toTarget = TARGET_LUFS - lufs
        val peakRoom = TRUE_PEAK_CEILING_DB - peak
        return minOf(toTarget, peakRoom).coerceIn(MIN_DB, MAX_BOOST_DB)
    }

    /** What "Untagged files" offers, in dB. */
    val UNTAGGED_CHOICES = listOf(0f, -3f, -6f, -9f)
}

/**
 * What the background scan measured of a track: integrated loudness (BS.1770-4 / EBU
 * R128, in LUFS) and the highest true peak (dBTP).
 */
data class MeasuredLoudness(val integratedLufs: Float, val truePeakDb: Float)
