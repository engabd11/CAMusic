package com.engabd.sendpin.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection and the freeze, exercised off-device with a hand-cranked clock.
 *
 * These pin the behaviours the old scalar engine got wrong: a stale server reading
 * dragging the bar backwards, and a skip briefly rendering the outgoing track's
 * position.
 */
class PlayerPositionTrackerTest {

    private var now = 1_000L
    private val tracker = PlayerPositionTracker { now }
    private val q = "queue-1"

    @Test
    fun `projects forward from the anchor while playing`() {
        tracker.setAnchor(q, elapsedMs = 10_000, isPlaying = true, durationMs = 300_000)
        now += 2_500
        assertEquals(12_500L, tracker.effectiveMs(q))
    }

    @Test
    fun `does not advance while paused`() {
        tracker.setAnchor(q, elapsedMs = 10_000, isPlaying = false, durationMs = 300_000)
        now += 5_000
        assertEquals(10_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `scales the projection by playback speed`() {
        tracker.setAnchor(q, elapsedMs = 0, isPlaying = true, durationMs = 300_000, speed = 1.5f)
        now += 10_000
        assertEquals(15_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `pausing snapshots the position instead of folding in the paused time`() {
        tracker.setAnchor(q, elapsedMs = 10_000, isPlaying = true, durationMs = 300_000)
        now += 3_000
        tracker.setPlaying(q, false)
        now += 60_000                      // a minute spent paused
        assertEquals(13_000L, tracker.effectiveMs(q))

        tracker.setPlaying(q, true)
        now += 1_000
        assertEquals(14_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `caps at the duration and reports the end`() {
        tracker.setAnchor(q, elapsedMs = 100_000, isPlaying = true, durationMs = 120_000)
        now += 60_000
        assertEquals(120_000L, tracker.effectiveMs(q))
        assertTrue(tracker.isAtEnd(q))
    }

    @Test
    fun `a seek freeze ignores the server's echo of the old position`() {
        tracker.setAnchor(q, elapsedMs = 10_000, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticSeek(q, elapsedMs = 200_000, durationMs = 300_000)
        assertTrue(tracker.isFrozen(q))

        // MA reports the pre-seek position for a beat. It must not win.
        tracker.setAnchor(q, elapsedMs = 10_500, isPlaying = true, durationMs = 300_000)
        now += 1_000
        assertEquals(200_000L, tracker.effectiveMs(q))

        tracker.confirmPlaying(q)
        assertFalse(tracker.isFrozen(q))
        now += 2_000
        assertEquals(202_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a track-change freeze holds zero until confirmed`() {
        tracker.setAnchor(q, elapsedMs = 150_000, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticTrackChange(q)

        // The outgoing track's position keeps arriving; the bar stays at zero.
        tracker.setAnchor(q, elapsedMs = 151_000, isPlaying = true, durationMs = 300_000)
        now += 800
        assertEquals(0L, tracker.effectiveMs(q))

        tracker.confirmPlaying(q)
        tracker.setAnchor(q, elapsedMs = 1_000, isPlaying = true, durationMs = 240_000)
        now += 1_000
        assertEquals(2_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `anchors are per queue`() {
        tracker.setAnchor("a", elapsedMs = 1_000, isPlaying = true, durationMs = 300_000)
        tracker.setAnchor("b", elapsedMs = 90_000, isPlaying = false, durationMs = 300_000)
        now += 1_000
        assertEquals(2_000L, tracker.effectiveMs("a"))
        assertEquals(90_000L, tracker.effectiveMs("b"))
    }

    @Test
    fun `an unknown queue has no position`() {
        assertEquals(null, tracker.effectiveMs("nope"))
    }
}
