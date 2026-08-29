package com.engabd.sendpin.hue

import com.engabd.sendpin.hue.FrameDelayQueue.Companion.LIGHT_PIPELINE_MS
import com.engabd.sendpin.hue.FrameDelayQueue.Companion.SLEW_MS_PER_S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delay queue is what makes the lights land on the beat instead of ahead of
 * it, so its arithmetic is worth pinning down.
 *
 * The direction is the part that is easy to get backwards. The tap sits before
 * the AudioTrack, so audio is analysed *earlier* than it is heard — the lights
 * run early, not late — and the correction is to hold frames back by the lead
 * minus what Hue's own pipeline costs. Get the sign wrong and the error doubles
 * instead of cancelling.
 */
class FrameDelayQueueTest {

    private val ms = 1_000_000L  // nanos per millisecond

    @Test
    fun `delay is the lead minus hue's pipeline latency`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(300f)
        assertEquals(300f - LIGHT_PIPELINE_MS, q.delayMs)
    }

    @Test
    fun `a lead shorter than the pipeline clamps to zero rather than going negative`() {
        // Nothing can be given back here: the audio is already closer to the ear
        // than the light can be to the bulb. Send at once and be slightly late.
        val q = FrameDelayQueue<Int>()
        q.resetDelay(40f)
        assertEquals(0f, q.delayMs)
    }

    @Test
    fun `an unknown lead holds the current delay instead of collapsing it`() {
        // Mid-seek the sink has no position. Dropping to zero would yank every
        // light forward by a couple of hundred milliseconds and snap back.
        val q = FrameDelayQueue<Int>()
        q.resetDelay(300f)
        val before = q.delayMs
        repeat(10) { q.updateDelay(null, 1f / 60f) }
        assertEquals(before, q.delayMs)
    }

    @Test
    fun `delay slews toward a new lead rather than stepping`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(200f)          // delay 100
        val dt = 1f / 60f
        q.updateDelay(500f, dt)     // target 400
        // One frame may only move by SLEW_MS_PER_S * dt = 2 ms.
        assertEquals(100f + SLEW_MS_PER_S * dt, q.delayMs, 0.01f)

        // And it must actually arrive: 300 ms of change at 120 ms/s is 2.5 s.
        repeat((2.5f * 60).toInt()) { q.updateDelay(500f, dt) }
        assertEquals(400f, q.delayMs, 1f)
    }

    @Test
    fun `slew converges from above as well as below`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(500f)  // delay 400
        val dt = 1f / 60f
        repeat((3.5f * 60).toInt()) { q.updateDelay(200f, dt) }
        assertEquals(100f, q.delayMs, 1f)
    }

    @Test
    fun `a frame is held for the delay and then released`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(300f)  // hold for 200 ms
        val t0 = 1_000_000_000L
        q.offer(42, t0)

        assertNull(q.poll(t0), "released immediately, the light would lead the audio")
        assertNull(q.poll(t0 + 199 * ms), "released early")
        assertEquals(42, q.poll(t0 + 200 * ms), "not released once due")
    }

    @Test
    fun `only the newest due frame is sent and the backlog is dropped`() {
        // The room only needs its latest state. Draining a backlog in order would
        // replay stale motion at whatever rate the loop could manage.
        val q = FrameDelayQueue<Int>()
        q.resetDelay(200f)  // hold 100 ms
        val t0 = 1_000_000_000L
        q.offer(1, t0)
        q.offer(2, t0 + 16 * ms)
        q.offer(3, t0 + 32 * ms)

        assertEquals(3, q.poll(t0 + 132 * ms), "expected the newest due frame")
        assertNull(q.poll(t0 + 132 * ms), "older frames should have been discarded, not queued")
    }

    @Test
    fun `zero delay passes frames straight through`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(LIGHT_PIPELINE_MS)  // delay 0
        val t0 = 1_000_000_000L
        q.offer(7, t0)
        assertEquals(7, q.poll(t0))
    }

    @Test
    fun `the queue cannot grow without bound`() {
        // A large delay while frames keep arriving must not let the deque
        // accumulate for the length of a track.
        val q = FrameDelayQueue<Int>()
        q.resetDelay(5_100f)  // hold 5 s, so nothing offered below is ever due
        val t0 = 1_000_000_000L
        repeat(1_000) { q.offer(it, t0 + it.toLong() * 16 * ms) }

        // Poll with a cutoff of exactly t0: only frame 0 was stamped that early.
        // An unbounded queue still holds it and would hand it back; a bounded one
        // evicted it long ago and has nothing due.
        assertNull(
            q.poll(t0 + 5_000 * ms),
            "the oldest frame was still queued after 1000 offers, the deque is unbounded",
        )
        // The recent tail is still there, so eviction took the old end, not everything.
        assertNotNull(q.poll(t0 + 1_000_000 * ms), "eviction dropped frames it should have kept")
    }

    /**
     * The regression that killed Light Sync on the Music Assistant path.
     *
     * The MA engine keeps ~2.5 s of decoded PCM in its native ring, so the tap runs
     * that far ahead of the speaker and the correct hold is ~2.4 s. The queue used to
     * be capped at a flat 120 entries — two seconds at the render rate — so every
     * frame was evicted before it came due, [FrameDelayQueue.poll] returned null on
     * every tick, and the room stopped reacting at all. It looked like "the lights
     * ignore the beat when a second speaker joins", because the extra buffering of a
     * grouped stream is what pushed the lead over the cap.
     */
    @Test
    fun `a lead longer than two seconds still delivers frames`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(2_500f)              // hold 2.4 s, the MA engine's real lead
        assertEquals(2_400f, q.delayMs)

        val t0 = 1_000_000_000L
        val frameNanos = 1_000L / 60 * ms // one render tick at 60 fps
        // Five seconds of frames at 60 fps — comfortably past the old 120-entry cap.
        var n = 0
        var t = t0
        var delivered: Int? = null
        repeat(300) {
            q.offer(n++, t)
            delivered = q.poll(t) ?: delivered
            t += frameNanos
        }
        assertNotNull(delivered, "nothing was ever due: the queue evicted frames before their turn")

        // And what came out is the frame from ~2.4 s ago, not the newest one.
        val expected = ((300 - 1) - (2_400f / (frameNanos / ms.toFloat()))).toInt()
        assertTrue(
            kotlin.math.abs(delivered!! - expected) <= 4,
            "expected a frame from ~2.4 s back (~$expected), got $delivered",
        )
    }

    @Test
    fun `eviction follows the delay rather than a fixed frame count`() {
        // A short delay must not keep five seconds of history around, and a long one
        // must: the bound is the delay, not a constant that contradicts it.
        val short = FrameDelayQueue<Int>()
        short.resetDelay(200f)   // hold 100 ms
        val long = FrameDelayQueue<Int>()
        long.resetDelay(3_100f)  // hold 3 s

        val t0 = 1_000_000_000L
        repeat(300) {
            short.offer(it, t0 + it.toLong() * 16 * ms)
            long.offer(it, t0 + it.toLong() * 16 * ms)
        }
        val end = t0 + 299L * 16 * ms

        // The short queue threw away everything older than its own hold plus margin.
        assertNull(
            short.poll(end - 2_000 * ms),
            "a 100 ms hold kept two seconds of history",
        )
        // The long one still has a frame from three seconds back to hand over.
        assertNotNull(
            long.poll(end),
            "a 3 s hold evicted the frame it was holding for",
        )
    }

    @Test
    fun `clear drops pending frames`() {
        val q = FrameDelayQueue<Int>()
        q.resetDelay(300f)
        val t0 = 1_000_000_000L
        q.offer(1, t0)
        q.clear()
        assertNull(q.poll(t0 + 500 * ms), "cleared frames should not be delivered")
    }

    @Test
    fun `a realistic session ends up aligned`() {
        // 250 ms of sink buffer is the common case on Android. The steady-state
        // delay should be 150 ms, so light and sound coincide: the frame waits
        // 150 ms, Hue spends 100 ms, and the sample surfaces at 250 ms.
        val q = FrameDelayQueue<Int>()
        q.resetDelay(250f)
        val dt = 1f / 60f
        repeat(120) { q.updateDelay(250f, dt) }
        assertEquals(150f, q.delayMs, 0.5f)
        assertTrue(
            q.delayMs + LIGHT_PIPELINE_MS == 250f,
            "hold plus pipeline should equal the lead, got ${q.delayMs} + $LIGHT_PIPELINE_MS",
        )
    }
}
