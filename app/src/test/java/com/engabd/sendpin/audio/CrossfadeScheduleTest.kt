package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When DJ Radio's crossfade starts, and how loud each side of it is.
 *
 * The deck itself needs a decoder and an Android `Context`; the timing does not, and
 * the timing is where a crossfade goes wrong — a hand-over one tick late is a gap,
 * and a fade curve that dips in the middle is the sequential fade this whole feature
 * exists to stop being.
 */
class CrossfadeScheduleTest {

    private val minute = 240_000L    // a four-minute track

    @Test
    fun `the hand-over happens exactly one window before the end`() {
        assertFalse(CrossfadeSchedule.shouldHandOver(minute - 6_100, minute, 6))
        assertTrue(CrossfadeSchedule.shouldHandOver(minute - 6_000, minute, 6))
        assertTrue(CrossfadeSchedule.shouldHandOver(minute - 1, minute, 6))
    }

    @Test
    fun `the deck is armed a pre-roll ahead of that, and only once`() {
        val armAt = minute - 6_000 - CrossfadeDeck.PREROLL_MS
        assertFalse(CrossfadeSchedule.shouldArm(armAt - 100, minute, 6))
        assertTrue(CrossfadeSchedule.shouldArm(armAt, minute, 6))
        // Arming stays true through the hand-over window, because the caller uses it
        // as "should there be a deck" rather than as an edge.
        assertTrue(CrossfadeSchedule.shouldArm(minute - 1_000, minute, 6))
    }

    @Test
    fun `a short track is left alone`() {
        // At six seconds of overlap a fifteen-second interlude would spend most of
        // its life in a transition — the same guard the sequential fade uses.
        val short = 15_000L
        assertFalse(CrossfadeSchedule.shouldHandOver(short - 1_000, short, 6))
        assertFalse(CrossfadeSchedule.shouldArm(short - 1_000, short, 6))
        // Three windows is the line.
        val long = 18_001L
        assertTrue(CrossfadeSchedule.shouldHandOver(long - 1_000, long, 6))
    }

    @Test
    fun `off means off`() {
        assertFalse(CrossfadeSchedule.shouldHandOver(minute - 1_000, minute, 0))
        assertFalse(CrossfadeSchedule.shouldArm(minute - 1_000, minute, 0))
        assertEquals(1f, CrossfadeSchedule.fadeInAt(0, 0))
    }

    @Test
    fun `an unknown duration never triggers one`() {
        // A stream whose length the server has not reported: better no crossfade
        // than one timed against a duration of zero.
        assertFalse(CrossfadeSchedule.shouldHandOver(10_000, 0, 6))
        assertFalse(CrossfadeSchedule.shouldArm(10_000, 0, 6))
    }

    @Test
    fun `the incoming track comes up from silence and reaches full at the window`() {
        assertEquals(0f, CrossfadeSchedule.fadeInAt(0, 6), 0.0001f)
        assertEquals(1f, CrossfadeSchedule.fadeInAt(6_000, 6), 0.0001f)
        assertEquals(1f, CrossfadeSchedule.fadeInAt(60_000, 6), 0.0001f)
        // Monotonic on the way there — a ramp that went backwards would be heard.
        var last = -1f
        for (ms in 0..6_000 step 250) {
            val v = CrossfadeSchedule.fadeInAt(ms.toLong(), 6)
            assertTrue(v >= last, "the fade-in went backwards at ${ms}ms")
            last = v
        }
    }

    @Test
    fun `the two sides sum to constant power, which is the whole point`() {
        // The incoming `sin` against the deck's `cos`. Squares that sum to one is
        // what keeps the middle of a transition as loud as either end; equal
        // *amplitude* dips there, and a dip in the middle is exactly the hole a
        // sequential fade has and this is supposed to remove.
        for (i in 0..24) {
            val x = i / 24f
            val incoming = CrossfadeSchedule.fadeInAt((x * 6_000).toLong(), 6)
            val outgoing = kotlin.math.cos(x * (Math.PI / 2).toFloat())
            val power = incoming * incoming + outgoing * outgoing
            assertTrue(
                abs(power - 1f) < 0.01f,
                "power dipped to $power a fraction $x through the mix",
            )
        }
    }
}
