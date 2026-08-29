package com.engabd.sendpin.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock-skew correction, and the deadband that keeps it out of the way.
 *
 * `elapsed_time_last_updated` is the *server's* wall clock; the tracker's is the
 * phone's. A server clock running behind this one makes every capture look uniformly
 * stale — comfortably inside `MAX_PROJECTION_MS`, so it was projected rather than
 * caught — and that staleness was added to the position on every single anchor. The bar
 * sat a second or two ahead of the music from 0:00 onwards, which is half of the
 * reported "songs start a second or two in".
 *
 * The estimate is a leaky rolling minimum of the apparent lag: real staleness is never
 * negative, so the smallest lag seen across many readings is very nearly the clock
 * offset by itself.
 */
class PlayerPositionSkewTest {

    private var now = 1_000_000L
    private val tracker = PlayerPositionTracker { now }
    private val q = "queue-1"

    /** A poll every second, each carrying a capture stamp [lagMs] behind our clock. */
    private fun poll(elapsedMs: Long, lagMs: Long) {
        tracker.setAnchor(
            q, elapsedMs = elapsedMs, capturedAtMs = now - lagMs,
            isPlaying = true, durationMs = 300_000,
        )
    }

    @Test
    fun `clocks that agree produce no correction at all`() {
        repeat(10) { poll(elapsedMs = it * 1_000L, lagMs = 0) ; now += 1_000 }
        assertEquals(0L, tracker.skew)
    }

    @Test
    fun `a server clock behind ours is measured`() {
        // Every reading looks 1.5s stale, but the readings are arriving promptly: that
        // uniform floor is the clock offset, not staleness.
        repeat(10) { poll(elapsedMs = it * 1_000L, lagMs = 1_500) ; now += 1_000 }
        assertTrue("expected ~1500, got ${tracker.rawSkew}", tracker.rawSkew in 1_400L..1_600L)
    }

    @Test
    fun `the measured skew is not added to the position`() {
        repeat(10) { poll(elapsedMs = it * 1_000L, lagMs = 1_500) ; now += 1_000 }
        // Last poll said 9_000 with a stamp 1.5s old, then 1s passed. Without the
        // correction the bar would read ~11_500: the true 10_000 plus the 1.5s of skew.
        poll(elapsedMs = 9_000, lagMs = 1_500)
        now += 1_000
        val shown = tracker.effectiveMs(q)
        assertTrue("bar ran ahead: $shown", shown in 9_500L..10_500L)
    }

    @Test
    fun `a small skew is left alone so the anchor stays exactly the server's own`() {
        // Below the deadband the correction is worth less than the idempotence it
        // would cost — see PlayerPositionTracker.skew.
        repeat(10) { poll(elapsedMs = it * 1_000L, lagMs = 80) ; now += 1_000 }
        assertEquals(0L, tracker.skew)
        assertTrue(tracker.rawSkew in 0L..120L)
    }

    @Test
    fun `network jitter above the floor does not raise the estimate`() {
        val jitter = listOf(1_500L, 1_900L, 2_400L, 1_500L, 1_700L, 1_500L, 3_000L, 1_500L)
        jitter.forEachIndexed { i, lag -> poll(elapsedMs = i * 1_000L, lagMs = lag); now += 1_000 }
        // The minimum is the signal; the spikes are the noise on top of it.
        assertTrue("got ${tracker.rawSkew}", tracker.rawSkew in 1_400L..1_700L)
    }

    @Test
    fun `readings taken while paused are not evidence`() {
        // A capture frozen across a pause looks arbitrarily stale and would only ever
        // push a minimum up.
        repeat(6) { poll(elapsedMs = it * 1_000L, lagMs = 0); now += 1_000 }
        tracker.setAnchor(q, elapsedMs = 6_000, capturedAtMs = now - 4_000, isPlaying = false)
        assertEquals(0L, tracker.skew)
    }

    @Test
    fun `the estimate can climb back when the clocks are corrected`() {
        repeat(20) { poll(elapsedMs = it * 1_000L, lagMs = 0); now += 1_000 }
        assertEquals(0L, tracker.rawSkew)
        // NTP moves the server clock: every reading now looks 1.5s stale. A pure
        // minimum could never notice; the leak means it tracks out.
        repeat(200) { poll(elapsedMs = 20_000 + it * 1_000L, lagMs = 1_500); now += 1_000 }
        assertTrue("stuck at ${tracker.rawSkew}", tracker.rawSkew >= 1_000)
    }

    @Test
    fun `the correction never anchors in the future`() {
        // A server clock *ahead* of ours already had a guard (rule 3 anchors at arrival
        // time); the correction must not undo it by pushing the anchor past now.
        repeat(10) { poll(elapsedMs = it * 1_000L, lagMs = 1_500); now += 1_000 }
        tracker.setAnchor(q, elapsedMs = 10_000, capturedAtMs = now + 5_000, isPlaying = true)
        assertEquals(10_000L, tracker.effectiveMs(q))
    }
}
