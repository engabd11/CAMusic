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
     * quiet classical and jazz recordings, mostly — so this rarely bites, and when
     * it does, a slightly-too-quiet track is a far better outcome than a distorted one.
     */
    const val MAX_BOOST_DB = 3f

    /** Below this the track would be inaudible; treat it as a bad tag and ignore it. */
    const val MIN_DB = -30f

    /**
     * The linear factor to multiply output by, or 1.0 when nothing should change.
     *
     * @param mode one of [OFF], [TRACK], [ALBUM].
     */
    fun factor(quality: StreamQuality?, mode: String): Float {
        val db = decibels(quality, mode) ?: return 1f
        return 10f.toDouble().pow(db / 20.0).toFloat()
    }

    /** The gain that will actually be applied, in dB, or null when none is. */
    fun decibels(quality: StreamQuality?, mode: String): Float? {
        if (quality == null) return null
        val raw = when (mode) {
            TRACK -> quality.replayGainTrack ?: quality.replayGainAlbum
            // Album gain is the point of the album mode, but a single with only a
            // track tag should still be levelled rather than left alone.
            ALBUM -> quality.replayGainAlbum ?: quality.replayGainTrack
            else -> null
        } ?: return null
        if (!raw.isFinite() || raw < MIN_DB) return null
        return raw.coerceAtMost(MAX_BOOST_DB)
    }
}
