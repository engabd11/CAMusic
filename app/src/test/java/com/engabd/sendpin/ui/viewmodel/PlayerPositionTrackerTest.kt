package com.engabd.sendpin.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection and the freeze, exercised off-device with a hand-cranked clock.
 *
 * These pin the behaviours the massdroid-style anchor model guarantees: re-deriving
 * from the server's own capture time lands on the same value, a reading the server has
 * merely restated does not move the bar, and a capture too stale to project from is
 * taken at face value rather than folded into the position.
 *
 * The clock is a plain counter, so "now" only moves when a test moves it.
 */
class PlayerPositionTrackerTest {

    private var now = 1_000_000L
    private val tracker = PlayerPositionTracker { now }
    private val q = "queue-1"

    @Test
    fun `projects forward from the anchor while playing`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        now += 2_500
        assertEquals(12_500L, tracker.effectiveMs(q))
    }

    @Test
    fun `does not advance while paused`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = false, durationMs = 300_000)
        now += 5_000
        assertEquals(10_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `scales the projection by playback speed`() {
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 300_000, speed = 1.5f)
        now += 10_000
        assertEquals(15_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `caps at the duration and reports the end`() {
        tracker.setAnchor(q, elapsedMs = 100_000, capturedAtMs = now, isPlaying = true, durationMs = 120_000)
        now += 60_000
        assertEquals(120_000L, tracker.effectiveMs(q))
        assertTrue(tracker.isAtEnd(q))
    }

    @Test
    fun `re-anchoring on the server's capture time is idempotent`() {
        // The core massdroid property: the displayed value is a function of
        // (elapsed, capturedAt, now), so any reading describing the same moment
        // re-derives the same answer. No jump, and so nothing to filter.
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        now += 2_000
        assertEquals(12_000L, tracker.effectiveMs(q))

        // A fresh reading taken just now: elapsed has moved on by exactly as much as
        // the capture time has.
        tracker.setAnchor(q, elapsedMs = 12_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        assertEquals(12_000L, tracker.effectiveMs(q))

        // And one the server captured a second ago, arriving late, lands there too.
        tracker.setAnchor(q, elapsedMs = 11_000, capturedAtMs = now - 1_000, isPlaying = true, durationMs = 300_000)
        assertEquals(12_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a repeated capture stamp is not news`() {
        val stamp = now
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        now += 3_000
        assertEquals(13_000L, tracker.effectiveMs(q))

        // The server has not recomputed - same elapsed, same stamp. The projection
        // already running is the better answer, so it must carry on rather than being
        // re-based back to the anchored value.
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        assertEquals(13_000L, tracker.effectiveMs(q))
        now += 2_000
        assertEquals(15_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `the bar keeps moving while the server goes quiet for longer than the cap`() {
        // For a remote speaker a repeated `elapsed_time_last_updated` is most polls and
        // the gaps run to seconds (see PositionSlew). Interpolation has to carry across
        // them. Capping the *projection* rather than the *anchor* would stall the bar
        // at elapsed + MAX_PROJECTION_MS and then jump it on the next recompute, which
        // is the stepping this design exists to remove.
        val stamp = now
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)

        repeat(20) {
            now += 1_000
            tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        }

        // 20 s of wall clock is 20 s of playhead, not MAX_PROJECTION_MS of it.
        assertEquals(30_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a capture older than the cap is anchored at arrival time`() {
        // MA freezes `elapsed_time_last_updated` while paused, so a reading landing
        // after a long pause carries a capture stale by the whole pause. Projecting it
        // would jump the bar by the paused seconds (and can shoot past the track end),
        // so it is taken at face value instead.
        val stale = now - (PlayerPositionTracker.MAX_PROJECTION_MS + 20_000)
        tracker.setAnchor(q, elapsedMs = 50_000, capturedAtMs = stale, isPlaying = true, durationMs = 300_000)
        assertEquals(50_000L, tracker.effectiveMs(q))

        // ...and interpolation carries on from there, unbounded.
        now += 8_000
        assertEquals(58_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a capture from a clock ahead of ours is anchored at arrival time`() {
        // There is no clock-offset estimation between phone and server. A server clock
        // running ahead would give a negative projection delta, freezing the bar until
        // ours caught up; it is treated as unusable and anchored on arrival instead.
        tracker.setAnchor(q, elapsedMs = 40_000, capturedAtMs = now + 3_000, isPlaying = true, durationMs = 300_000)
        assertEquals(40_000L, tracker.effectiveMs(q))
        now += 2_000
        assertEquals(42_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `pausing snapshots the position instead of folding in the paused time`() {
        val stamp = now
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        now += 3_000

        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = false, durationMs = 300_000)
        now += 60_000                      // a minute spent paused
        assertEquals(13_000L, tracker.effectiveMs(q))

        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        now += 1_000
        assertEquals(14_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `resuming does not fold in a capture frozen across the pause`() {
        // The pause is long enough that projecting the frozen capture would be obvious:
        // without the play-state re-anchor the bar would leap by the paused seconds the
        // moment the server said "playing" again.
        val stamp = now
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        now += 2_000
        assertEquals(62_000L, tracker.effectiveMs(q))

        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = stamp, isPlaying = false, durationMs = 300_000)
        now += 30_000
        assertEquals(62_000L, tracker.effectiveMs(q))

        // Resume, still carrying the stamp MA froze before the pause.
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        assertEquals(62_000L, tracker.effectiveMs(q))
        now += 1_000
        assertEquals(63_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a server that sends no capture stamp is anchored on arrival`() {
        // Null means "can't tell", which counts as news - a server omitting the field
        // must not read as one that has stopped updating.
        tracker.setAnchor(q, elapsedMs = 20_000, capturedAtMs = null, isPlaying = true, durationMs = 300_000)
        now += 1_500
        assertEquals(21_500L, tracker.effectiveMs(q))

        tracker.setAnchor(q, elapsedMs = 25_000, capturedAtMs = null, isPlaying = true, durationMs = 300_000)
        assertEquals(25_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a seek freeze ignores the server's echo of the old position`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticSeek(q, elapsedMs = 200_000, durationMs = 300_000)
        assertTrue(tracker.isFrozen(q))

        // MA reports the pre-seek position for a beat. It must not win.
        tracker.setAnchor(q, elapsedMs = 10_500, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        now += 1_000
        assertEquals(200_000L, tracker.effectiveMs(q))

        tracker.confirmPlaying(q)
        assertFalse(tracker.isFrozen(q))
        now += 2_000
        assertEquals(202_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a track-change freeze holds zero until confirmed`() {
        tracker.setAnchor(q, elapsedMs = 150_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticTrackChange(q)

        // The outgoing track's position keeps arriving; the bar stays at zero.
        tracker.setAnchor(q, elapsedMs = 151_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        now += 800
        assertEquals(0L, tracker.effectiveMs(q))

        tracker.confirmPlaying(q)
        tracker.setAnchor(q, elapsedMs = 1_000, capturedAtMs = now, isPlaying = true, durationMs = 240_000)
        now += 1_000
        assertEquals(2_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `anchors are per queue`() {
        tracker.setAnchor("a", elapsedMs = 1_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setAnchor("b", elapsedMs = 90_000, capturedAtMs = now, isPlaying = false, durationMs = 300_000)
        now += 1_000
        assertEquals(2_000L, tracker.effectiveMs("a"))
        assertEquals(90_000L, tracker.effectiveMs("b"))
    }

    @Test
    fun `an unknown queue has no position`() {
        assertEquals(null, tracker.effectiveMs("nope"))
    }
}
