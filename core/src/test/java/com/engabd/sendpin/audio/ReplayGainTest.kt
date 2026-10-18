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

    @Test
    fun `untagged tracks take the chosen level only when levelling is on`() {
        assertEquals(-6f, ReplayGain.decibels(q(), ReplayGain.ALBUM, untaggedDb = -6f))
        assertEquals(-6f, ReplayGain.decibels(q(), ReplayGain.TRACK, untaggedDb = -6f))
        assertNull(ReplayGain.decibels(q(), ReplayGain.OFF, untaggedDb = -6f))
        // A tag always wins over the untagged level.
        assertEquals(-2f, ReplayGain.decibels(q(album = -2f), ReplayGain.ALBUM, untaggedDb = -9f))
        // Nothing known about the track at all: leave it alone.
        assertNull(ReplayGain.decibels(null, ReplayGain.ALBUM, untaggedDb = -6f))
        // Default is off.
        assertNull(ReplayGain.decibels(q(), ReplayGain.ALBUM))
    }

    // -- Measured loudness for files with no tags ---------------------------------

    private fun m(lufs: Float, peak: Float = -6f) = MeasuredLoudness(lufs, peak)

    @Test
    fun `an untagged track is levelled to minus 18 LUFS from its measurement`() {
        val applied = ReplayGain.resolve(q(), ReplayGain.TRACK, measured = m(-9f, peak = -3f))!!
        assertEquals(-9f, applied.db, 0.001f)
        assertEquals(ReplayGain.Origin.MEASURED, applied.origin)
        assertEquals(-9f, ReplayGain.decibels(q(), ReplayGain.ALBUM, measured = m(-9f, peak = -3f))!!, 0.001f)
    }

    @Test
    fun `a quiet untagged track is raised, held back by its true peak`() {
        // -26 LUFS wants +8 dB, capped at the boost limit anyway.
        assertEquals(ReplayGain.MAX_BOOST_DB, ReplayGain.decibels(q(), ReplayGain.TRACK, measured = m(-26f, peak = -20f))!!, 0.001f)
        // -22 LUFS wants +4 dB but a -4 dBTP peak only has 3 dB to the -1 dBTP ceiling.
        val limited = ReplayGain.resolve(q(), ReplayGain.TRACK, measured = m(-22f, peak = -4f))!!
        assertEquals(3f, limited.db, 0.001f)
        assertEquals(ReplayGain.Origin.MEASURED, limited.origin)
    }

    @Test
    fun `a loud master near full scale is only ever pulled down`() {
        val db = ReplayGain.decibels(q(), ReplayGain.TRACK, measured = m(-6f, peak = 0.5f))!!
        assertEquals(-12f, db, 0.001f)
        assertTrue(db < 0f)
    }

    @Test
    fun `tags win over a measurement`() {
        val applied = ReplayGain.resolve(q(track = -7f), ReplayGain.TRACK, measured = m(-9f))!!
        assertEquals(-7f, applied.db)
        assertEquals(ReplayGain.Origin.TAGS, applied.origin)
    }

    @Test
    fun `without a measurement an untagged track gets the fixed level`() {
        val applied = ReplayGain.resolve(q(), ReplayGain.TRACK, untaggedDb = -6f, measured = null)!!
        assertEquals(-6f, applied.db)
        assertEquals(ReplayGain.Origin.FIXED, applied.origin)
    }

    @Test
    fun `a measurement wins over the fixed level`() {
        val applied = ReplayGain.resolve(q(), ReplayGain.TRACK, untaggedDb = -6f, measured = m(-12f))!!
        assertEquals(-6f, applied.db, 0.001f)
        assertEquals(ReplayGain.Origin.MEASURED, applied.origin)
        val other = ReplayGain.resolve(q(), ReplayGain.TRACK, untaggedDb = -3f, measured = m(-14f))!!
        assertEquals(-4f, other.db, 0.001f)
    }

    @Test
    fun `off applies nothing, measured or not`() {
        assertNull(ReplayGain.resolve(q(), ReplayGain.OFF, untaggedDb = -6f, measured = m(-9f)))
        assertEquals(1f, ReplayGain.factor(q(), ReplayGain.OFF, measured = m(-9f)))
    }

    @Test
    fun `a nonsense measurement is ignored`() {
        assertNull(ReplayGain.resolve(q(), ReplayGain.TRACK, measured = m(Float.NaN)))
        assertNull(ReplayGain.resolve(q(), ReplayGain.TRACK, measured = MeasuredLoudness(-10f, Float.POSITIVE_INFINITY)))
        val fallback = ReplayGain.resolve(q(), ReplayGain.TRACK, untaggedDb = -3f, measured = m(Float.NaN))!!
        assertEquals(ReplayGain.Origin.FIXED, fallback.origin)
    }

    @Test
    fun `a bad tag is skipped for a measurement but not for the fixed level`() {
        val bad = q(track = -99f)
        assertEquals(ReplayGain.Origin.MEASURED, ReplayGain.resolve(bad, ReplayGain.TRACK, measured = m(-12f))!!.origin)
        assertNull(ReplayGain.resolve(bad, ReplayGain.TRACK, untaggedDb = -6f))
    }

    @Test
    fun `a measurement works even when the library gave no quality`() {
        val applied = ReplayGain.resolve(null, ReplayGain.TRACK, measured = m(-12f))!!
        assertEquals(ReplayGain.Origin.MEASURED, applied.origin)
    }

    @Test
    fun `the factor follows the measured decibels`() {
        assertEquals(0.5012f, ReplayGain.factor(q(), ReplayGain.TRACK, measured = m(-12f, peak = -3f)), 0.001f)
    }
}
