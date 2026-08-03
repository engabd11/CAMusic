package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ReplayGain was parsed off OpenSubsonic and printed on the quality card while
 * nothing applied it, so the number on screen described something that wasn't
 * happening.
 */
class ReplayGainTest {

    private fun q(track: Float? = null, album: Float? = null) =
        StreamQuality("FLAC", 44_100, 16, replayGainTrack = track, replayGainAlbum = album)

    @Test
    fun `off changes nothing`() {
        assertEquals(1f, ReplayGain.factor(q(track = -6f, album = -6f), ReplayGain.OFF))
        assertNull(ReplayGain.decibels(q(track = -6f), ReplayGain.OFF))
    }

    @Test
    fun `no tags, no change`() {
        assertEquals(1f, ReplayGain.factor(q(), ReplayGain.ALBUM))
        assertEquals(1f, ReplayGain.factor(null, ReplayGain.ALBUM))
    }

    /** Each mode prefers its own tag. */
    @Test
    fun `album and track modes pick their own`() {
        val both = q(track = -9f, album = -3f)
        assertEquals(-3f, ReplayGain.decibels(both, ReplayGain.ALBUM))
        assertEquals(-9f, ReplayGain.decibels(both, ReplayGain.TRACK))
    }

    /**
     * A single tagged only per-track should still be levelled in album mode —
     * refusing to touch it would leave exactly the loud outlier the mode exists to
     * tame.
     */
    @Test
    fun `each mode falls back to the other tag`() {
        assertEquals(-9f, ReplayGain.decibels(q(track = -9f), ReplayGain.ALBUM))
        assertEquals(-3f, ReplayGain.decibels(q(album = -3f), ReplayGain.TRACK))
    }

    /** -6 dB is half the amplitude, near enough. */
    @Test
    fun `decibels convert to a linear factor`() {
        val f = ReplayGain.factor(q(album = -6.02f), ReplayGain.ALBUM)
        assertTrue(f in 0.49f..0.51f, "expected ~0.5, got $f")
        assertEquals(1f, ReplayGain.factor(q(album = 0f), ReplayGain.ALBUM))
    }

    /**
     * The one that protects the audio: a positive gain multiplies samples that were
     * already mastered against full scale, so an unclamped boost clips.
     */
    @Test
    fun `a boost is capped`() {
        assertEquals(ReplayGain.MAX_BOOST_DB, ReplayGain.decibels(q(album = 12f), ReplayGain.ALBUM))
        // A small boost is under the cap and passes through untouched.
        assertEquals(1.5f, ReplayGain.decibels(q(album = 1.5f), ReplayGain.ALBUM))
    }

    /** Attenuation is always safe, so it is never capped. */
    @Test
    fun `attenuation is trusted`() {
        assertEquals(-20f, ReplayGain.decibels(q(album = -20f), ReplayGain.ALBUM))
    }

    @Test
    fun `an absurd tag is ignored rather than silencing the track`() {
        assertNull(ReplayGain.decibels(q(album = -99f), ReplayGain.ALBUM))
        assertNull(ReplayGain.decibels(q(album = Float.NaN), ReplayGain.ALBUM))
        assertNull(ReplayGain.decibels(q(album = Float.NEGATIVE_INFINITY), ReplayGain.ALBUM))
    }
}
