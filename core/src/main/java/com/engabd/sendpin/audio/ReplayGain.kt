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

    /**
     * The linear factor to multiply output by, or 1.0 when nothing should change.
     *
     * @param mode one of [OFF], [TRACK], [ALBUM].
     */
    fun factor(quality: StreamQuality?, mode: String, untaggedDb: Float = 0f): Float {
        val db = decibels(quality, mode, untaggedDb) ?: return 1f
        return 10f.toDouble().pow(db / 20.0).toFloat()
    }

    /**
     * The gain that will actually be applied, in dB, or null when none is.
     *
     * [untaggedDb] is for a track the library describes but carries no level for.
     * A tagged track is typically pulled down 6–10 dB to the ReplayGain reference,
     * and an untagged one used to stay at full level, so shuffling the two made
     * every untagged song jump out. Off (0) by default; see [UNTAGGED_CHOICES].
     * Not applied when nothing is known about the track at all.
     */
    fun decibels(quality: StreamQuality?, mode: String, untaggedDb: Float = 0f): Float? {
        if (quality == null) return null
        if (mode != TRACK && mode != ALBUM) return null
        val raw = when (mode) {
            TRACK -> quality.replayGainTrack ?: quality.replayGainAlbum
            // Album gain is the point of the album mode, but a single with only a
            // track tag should still be levelled rather than left alone.
            else -> quality.replayGainAlbum ?: quality.replayGainTrack
        } ?: return untaggedDb.takeIf { it != 0f && it.isFinite() }?.coerceIn(MIN_DB, 0f)
        if (!raw.isFinite() || raw < MIN_DB) return null
        return raw.coerceAtMost(MAX_BOOST_DB)
    }

    /** What "Untagged files" offers, in dB. */
    val UNTAGGED_CHOICES = listOf(0f, -3f, -6f, -9f)
}
