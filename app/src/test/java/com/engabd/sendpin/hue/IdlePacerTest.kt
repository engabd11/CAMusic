package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdlePacerTest {

    private val active = 1_000_000_000L / 60
    private val second = 1_000_000_000L

    @Test
    fun `music always runs at full rate`() {
        val p = IdlePacer(active)
        for (i in 0 until 600) assertFalse(p.onFrame(idle = false, now = i * active))
        assertFalse(p.deep)
        assertEquals(active, p.frameNanos)
    }

    @Test
    fun `a short pause changes nothing`() {
        val p = IdlePacer(active)
        p.onFrame(idle = true, now = 0)
        assertFalse(p.onFrame(idle = true, now = 59 * second))
        assertEquals(active, p.frameNanos)
    }

    @Test
    fun `a long pause eases off once, and music restores it at once`() {
        val p = IdlePacer(active)
        p.onFrame(idle = true, now = 0)
        assertTrue(p.onFrame(idle = true, now = 60 * second), "the transition is reported")
        assertFalse(p.onFrame(idle = true, now = 61 * second), "and only once")
        assertEquals(IdlePacer.DEEP_FRAME_NANOS, p.frameNanos)
        assertTrue(p.onFrame(idle = false, now = 62 * second))
        assertEquals(active, p.frameNanos)
    }

    @Test
    fun `the idle clock restarts after music`() {
        val p = IdlePacer(active)
        p.onFrame(idle = true, now = 0)
        p.onFrame(idle = false, now = 50 * second)
        p.onFrame(idle = true, now = 51 * second)
        assertFalse(p.onFrame(idle = true, now = 100 * second), "50 s of the earlier pause counted")
        assertTrue(p.onFrame(idle = true, now = 111 * second))
    }

    @Test
    fun `the step clamp lets a slow frame through only when slow is intended`() {
        val p = IdlePacer(active)
        assertEquals(0.1f, p.maxStepS(0.1f))
        p.onFrame(idle = true, now = 0)
        p.onFrame(idle = true, now = 60 * second)
        assertTrue(p.maxStepS(0.1f) >= 0.15f - 1e-6f)
    }
}
