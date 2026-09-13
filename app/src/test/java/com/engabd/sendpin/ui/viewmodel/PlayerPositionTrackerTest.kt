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
    fun `a new elapsed on the same capture stamp is still news`() {
        // The "no news" test compares the whole reading, not the stamp alone. If MA
        // ever reflects an outside seek in `elapsed_time` without moving the stamp, a
        // stamp-only test would swallow it - and on a remote speaker nothing else would
        // notice, because there is no local stream to contradict it.
        val stamp = now
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        now += 2_000
        assertEquals(12_000L, tracker.effectiveMs(q))

        tracker.setAnchor(q, elapsedMs = 200_000, capturedAtMs = stamp, isPlaying = true, durationMs = 300_000)
        // Applied, and projected from the stamp it came with, exactly like any other
        // reading: the server said 200 s was true 2 s ago.
        assertEquals(202_000L, tracker.effectiveMs(q))
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

    // ── Staleness: the official app's `isBefore` gate ──────────────────────────

    @Test
    fun `a reading stamped older than one already held is dropped`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        // A poll answer that was on the wire before the reading above: older stamp,
        // older position. Applying it would snap the bar back.
        tracker.setAnchor(q, elapsedMs = 55_000, capturedAtMs = now - 2_000, isPlaying = true, durationMs = 300_000)
        now += 1_000
        assertEquals(61_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `stamps seen while frozen still count, so a pre-seek answer cannot land after release`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticSeek(q, elapsedMs = 200_000, durationMs = 300_000)
        // The seek landed on the server: fresh stamp, target position. Ignored by the
        // freeze, but remembered.
        now += 500
        tracker.setAnchor(q, elapsedMs = 200_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.confirmPlaying(q)
        // A poll answer from *before* the seek arrives late. It must not win.
        tracker.setAnchor(q, elapsedMs = 60_400, capturedAtMs = now - 1_000, isPlaying = true, durationMs = 300_000)
        now += 1_000
        assertEquals(201_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a stamp older by more than a minute is a server clock that stepped, and is accepted`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now - 120_000, isPlaying = true, durationMs = 300_000)
        // Anchored at arrival (the capture is far too old to project from).
        assertEquals(10_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `an unstamped reading applies and leaves the high-water alone`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setAnchor(q, elapsedMs = 120_000, capturedAtMs = null, isPlaying = true, durationMs = 300_000)
        assertEquals(120_000L, tracker.effectiveMs(q))
        // The next stamped reading newer than the first still goes through.
        now += 1_000
        tracker.setAnchor(q, elapsedMs = 121_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        assertEquals(121_000L, tracker.effectiveMs(q))
    }

    // ── Stalled server clock: MA 2.10's short-track bug ───────────────────────

    @Test
    fun `the same elapsed under a newer stamp is a stalled clock, and the bar keeps ticking`() {
        // MA reports 0 with a fresh stamp every second for a track it sent in one burst.
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 30_000, itemId = "a")
        now += 5_000
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 30_000, itemId = "a")
        assertEquals(5_000L, tracker.effectiveMs(q))
        now += 5_000
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 30_000, itemId = "a")
        assertEquals(10_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a seek target pinned on a short track holds a ticking bar too`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 25_000, itemId = "a")
        now += 4_000
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 25_000, itemId = "a")
        assertEquals(14_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a changed elapsed is always news`() {
        tracker.setAnchor(q, elapsedMs = 120_300, capturedAtMs = now, isPlaying = true, durationMs = 300_000, itemId = "a")
        now += 2_000
        // Someone restarted the track from another controller.
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 300_000, itemId = "a")
        assertEquals(0L, tracker.effectiveMs(q))
    }

    @Test
    fun `a new item reporting the same value is news`() {
        // Two short tracks in a row, both pinned at zero by the server.
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 20_000, itemId = "a")
        now += 19_000
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 22_000, itemId = "b")
        assertEquals(0L, tracker.effectiveMs(q))
    }

    @Test
    fun `a projection that reached the end accepts the same value again`() {
        // Repeat-one of a pinned short track: the restart reports the value it was
        // pinned on, under the same item id.
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 20_000, itemId = "a")
        now += 21_000
        assertTrue(tracker.isAtEnd(q))
        tracker.setAnchor(q, elapsedMs = 0, capturedAtMs = now, isPlaying = true, durationMs = 20_000, itemId = "a")
        assertEquals(0L, tracker.effectiveMs(q))
    }

    @Test
    fun `paused readings never read as a stall`() {
        tracker.setAnchor(q, elapsedMs = 40_000, capturedAtMs = now, isPlaying = false, durationMs = 300_000, itemId = "a")
        now += 5_000
        // MA freezes the stamp while paused, so this is the "not news" path — but
        // even a refreshed stamp must not tick a paused bar.
        tracker.setAnchor(q, elapsedMs = 40_000, capturedAtMs = now, isPlaying = false, durationMs = 300_000, itemId = "a")
        now += 5_000
        assertEquals(40_000L, tracker.effectiveMs(q))
    }

    // ── setPlaying ───────────────────────────────────────────────────────────

    @Test
    fun `setPlaying snapshots the projection on a transition`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        now += 3_000
        tracker.setPlaying(q, false)
        now += 10_000
        assertEquals(13_000L, tracker.effectiveMs(q))
        tracker.setPlaying(q, true)
        now += 1_000
        assertEquals(14_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `setPlaying is ignored while frozen`() {
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticSeek(q, elapsedMs = 200_000, durationMs = 300_000)
        // The server flickers the player to paused around the stream rebuild.
        tracker.setPlaying(q, false)
        assertTrue(tracker.isFrozen(q))
        tracker.confirmPlaying(q)
        now += 1_000
        assertEquals(201_000L, tracker.effectiveMs(q))
    }

    @Test
    fun `a reading seen while frozen is not news when the freeze lifts`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        tracker.setOptimisticSeek(q, elapsedMs = 127_000, durationMs = 300_000)
        // MA's transient after a seek: the old stream's elapsed plus the offset.
        now += 100
        val bogusStamp = now
        tracker.setAnchor(q, elapsedMs = 168_205, capturedAtMs = bogusStamp, isPlaying = true, durationMs = 300_000)
        tracker.confirmPlaying(q)
        // The poll restates the same reading a moment later. It must not flash the bar.
        now += 100
        tracker.setAnchor(q, elapsedMs = 168_205, capturedAtMs = bogusStamp, isPlaying = true, durationMs = 300_000)
        assertEquals(127_100L, tracker.effectiveMs(q))
        // The server then repeats the seek offset while it is not yet counting: not
        // news either. Its first real reading is.
        now += 500
        tracker.setAnchor(q, elapsedMs = 127_000, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        assertEquals(127_600L, tracker.effectiveMs(q))
        now += 1_000
        tracker.setAnchor(q, elapsedMs = 128_300, capturedAtMs = now, isPlaying = true, durationMs = 300_000)
        assertEquals(128_300L, tracker.effectiveMs(q))
    }

    @Test
    fun `the held value repeated after release is a clock not yet counting`() {
        // A seek into the last ten seconds of a track: MA sends the remainder in one
        // burst and never starts counting, so it reports the seek offset for ever.
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        tracker.setOptimisticSeek(q, elapsedMs = 235_000, durationMs = 245_000)
        now += 100
        tracker.setAnchor(q, elapsedMs = 236_140, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        tracker.confirmPlaying(q)
        now += 4_500
        tracker.setAnchor(q, elapsedMs = 235_000, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        assertEquals(239_500L, tracker.effectiveMs(q))
        now += 5_000
        tracker.setAnchor(q, elapsedMs = 235_000, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        assertEquals(244_500L, tracker.effectiveMs(q))
    }

    @Test
    fun `with nothing seen while frozen the held value is still recognised`() {
        tracker.setAnchor(q, elapsedMs = 60_000, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        tracker.setOptimisticSeek(q, elapsedMs = 235_000, durationMs = 245_000)
        tracker.confirmPlaying(q)
        now += 3_000
        tracker.setAnchor(q, elapsedMs = 235_000, capturedAtMs = now, isPlaying = true, durationMs = 245_000, itemId = "a")
        assertEquals(238_000L, tracker.effectiveMs(q))
    }
}
