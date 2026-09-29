package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostLimiterTest {

    private fun run(limiter: BoostLimiter, amp: Float, seconds: Float, sr: Int = 48_000): List<Float> {
        val out = ArrayList<Float>()
        val frame = FloatArray(2)
        for (i in 0 until (seconds * sr).toInt()) {
            val x = amp * sin(2 * PI * 440 * i / sr).toFloat()
            frame[0] = x; frame[1] = x * 0.5f
            limiter.processFrame(frame, 2)
            out += frame[0]
        }
        return out
    }

    @Test
    fun `unity is a straight wire`() {
        val l = BoostLimiter().apply { configure(48_000) }
        assertFalse(l.active)
        val frame = floatArrayOf(0.3f, -0.7f)
        l.processFrame(frame, 2)
        assertEquals(0.3f, frame[0]); assertEquals(-0.7f, frame[1])
    }

    @Test
    fun `a quiet signal is raised by the full boost`() {
        val l = BoostLimiter().apply { configure(48_000); target = 2f }  // +6 dB
        val out = run(l, amp = 0.2f, seconds = 0.5f)
        val peak = out.takeLast(4_800).maxOf { abs(it) }
        assertEquals(0.4f, peak, 0.01f)
    }

    @Test
    fun `a boosted loud signal never passes the ceiling`() {
        val l = BoostLimiter().apply { configure(48_000); target = 2f }
        val out = run(l, amp = 0.9f, seconds = 0.5f)
        assertTrue(out.all { abs(it) <= BoostLimiter.CEILING + 1e-6f }, "peak ${out.maxOf { abs(it) }}")
    }

    @Test
    fun `the boost ramps in rather than stepping`() {
        val l = BoostLimiter().apply { configure(48_000); target = 2f }
        val frame = floatArrayOf(0.1f, 0.1f)
        l.processFrame(frame, 2)
        assertTrue(frame[0] < 0.101f, "first frame ${frame[0]} should be ~unity")
        assertTrue(l.active)
    }
}
