package com.engabd.sendpin.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothPlayheadTest {

    /** Ticks every 250 ms at real speed, as the local player publishes them. */
    private fun SmoothPlayhead.play(fromPos: Long, fromAt: Long, ticks: Int): Long {
        var pos = fromPos
        var at = fromAt
        repeat(ticks) { onTick(pos, at); pos += 250; at += 250 }
        return at - 250
    }

    @Test
    fun `moves smoothly between ticks`() {
        val p = SmoothPlayhead()
        val lastAt = p.play(10_000, 0, 8)
        // Last tick said 11_750 at lastAt; 100 ms later it should read about 11_850.
        val v = p.valueAt(lastAt + 100)
        assertTrue("was $v", v in 11_820L..11_880L)
    }

    @Test
    fun `never steps backwards on jitter`() {
        val p = SmoothPlayhead()
        val lastAt = p.play(0, 0, 8)
        val before = p.valueAt(lastAt + 240)
        // A late reading, 120 ms behind where the clock says it should be.
        p.onTick(before + 10 - 120, lastAt + 250)
        assertTrue(p.valueAt(lastAt + 250) >= before)
    }

    @Test
    fun `a seek is taken at once`() {
        val p = SmoothPlayhead()
        val lastAt = p.play(60_000, 0, 6)
        p.onTick(5_000, lastAt + 250)
        assertEquals(5_000L, p.valueAt(lastAt + 250))
    }

    @Test
    fun `stops when the ticks stop`() {
        val p = SmoothPlayhead()
        val lastAt = p.play(0, 0, 6)
        // Paused: no more ticks. It runs on at most 400 ms past the last one, then holds.
        assertEquals(p.valueAt(lastAt + 400), p.valueAt(lastAt + 5_000))
    }
}
