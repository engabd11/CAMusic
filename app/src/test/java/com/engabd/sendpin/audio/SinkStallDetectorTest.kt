package com.engabd.sendpin.audio

import com.engabd.sendpin.audio.SinkStallDetector.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Back pressure versus a fault.
 *
 * The bug this guards against is not a crash — it is the opposite. A sink that refuses
 * every buffer and says nothing looks, from outside, exactly like a sink that is briefly
 * full: the player reports playing, the position advances, and there is no sound. These
 * pin where one becomes the other, and that saying so happens exactly once.
 */
class SinkStallDetectorTest {

    private fun detector() = SinkStallDetector(refusalsBeforeWarning = 5, refusalsBeforeFailure = 10)

    @Test
    fun `a buffer that goes in is not a refusal`() {
        val d = detector()
        repeat(100) { assertEquals(Verdict.OK, d.onBuffer(accepted = 512, offered = 512)) }
        assertEquals(0, d.refusals)
    }

    @Test
    fun `a partly accepted buffer is progress, not a stall`() {
        // The ring took some of it, which it can only do if the other end is draining.
        val d = detector()
        repeat(100) { assertEquals(Verdict.OK, d.onBuffer(accepted = 100, offered = 512)) }
        assertEquals(0, d.refusals)
    }

    @Test
    fun `a short run of refusals is back pressure and stays quiet`() {
        val d = detector()
        repeat(4) { assertEquals(Verdict.OK, d.onBuffer(accepted = 0, offered = 512)) }
    }

    @Test
    fun `a longer run warns, once`() {
        val d = detector()
        repeat(4) { d.onBuffer(0, 512) }
        assertEquals(Verdict.WARN, d.onBuffer(0, 512))
        // Not again on the sixth, seventh or eighth — one line, not one per buffer.
        repeat(4) { assertEquals(Verdict.OK, d.onBuffer(0, 512)) }
    }

    @Test
    fun `a run that never ends is a stall, reported once`() {
        val d = detector()
        repeat(9) { d.onBuffer(0, 512) }
        assertEquals(Verdict.STALLED, d.onBuffer(0, 512))
        repeat(50) { assertEquals(Verdict.OK, d.onBuffer(0, 512)) }
    }

    @Test
    fun `one accepted buffer clears the count`() {
        val d = detector()
        repeat(9) { d.onBuffer(0, 512) }
        assertEquals(Verdict.OK, d.onBuffer(accepted = 512, offered = 512))
        assertEquals(0, d.refusals)
        // And the run has to start over — nine more is still not a stall.
        repeat(9) { assertEquals(Verdict.OK, d.onBuffer(0, 512)) }
    }

    @Test
    fun `an empty buffer is neither accepted nor refused`() {
        val d = detector()
        repeat(50) { assertEquals(Verdict.OK, d.onBuffer(accepted = 0, offered = 0)) }
        assertEquals(0, d.refusals)
    }

    @Test
    fun `a reset lets the next stream fail on its own account`() {
        val d = detector()
        repeat(10) { d.onBuffer(0, 512) }
        d.reset()
        assertEquals(0, d.refusals)
        repeat(4) { d.onBuffer(0, 512) }
        assertEquals(Verdict.WARN, d.onBuffer(0, 512))
        repeat(4) { d.onBuffer(0, 512) }
        assertEquals(Verdict.STALLED, d.onBuffer(0, 512))
    }
}
