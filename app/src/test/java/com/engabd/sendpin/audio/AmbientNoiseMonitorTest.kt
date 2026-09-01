package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientNoiseMonitorTest {

    // ── noiseLevelToEnergyTarget ──────────────────────────────────────────

    /**
     * A silent room (noise 0) should pull the energy target down to the floor.
     */
    @Test
    fun `silence maps to low energy target`() {
        val baseTarget = 0.5f
        val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(0f, baseTarget)
        assertEquals(0.2f, result, 0.01f, "Silence should map to low energy (0.2)")
    }

    /**
     * A loud room (noise 1) should push the energy target above the base.
     */
    @Test
    fun `loud room maps to high energy target`() {
        val baseTarget = 0.5f
        val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(1f, baseTarget)
        // At noise=1: boosted = 0.5 + 0.3 * 1 = 0.8
        assertTrue(result > baseTarget, "Loud room ($result) should exceed base ($baseTarget)")
        assertEquals(0.8f, result, 0.01f, "Loud room should map to 0.8")
    }

    /**
     * A mid-level room (noise 0.5) should give approximately the base target.
     */
    @Test
    fun `mid-level noise is near the base target`() {
        val baseTarget = 0.6f
        val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(0.5f, baseTarget)
        assertEquals(baseTarget, result, 0.01f, "Mid-level should equal base target")
    }

    @Test
    fun `silence with high base target still pulls down`() {
        val baseTarget = 0.9f
        val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(0f, baseTarget)
        assertEquals(0.2f, result, 0.01f, "Silence should pull to floor even with high base")
    }

    @Test
    fun `loud room with low base still pushes up`() {
        val baseTarget = 0.2f
        val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(1f, baseTarget)
        // boosted = 0.2 + 0.3 = 0.5
        assertEquals(0.5f, result, 0.01f, "Loud room should push up even with low base")
    }

    @Test
    fun `monotonic in noise level`() {
        val baseTarget = 0.5f
        var prev = 0f
        for (i in 0..100) {
            val n = i / 100f
            val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(n, baseTarget)
            assertTrue(result >= prev - 1e-6f, "Should be monotonically non-decreasing in noise level")
            prev = result
        }
    }

    @Test
    fun `clamps to valid range`() {
        for (noise in listOf(-0.5f, 0f, 0.3f, 1f, 1.5f)) {
            for (base in listOf(-0.5f, 0f, 0.5f, 1f, 1.5f)) {
                val result = AmbientNoiseMonitor.noiseLevelToEnergyTarget(noise, base)
                assertTrue(result in 0f..1f, "Result $result out of [0,1] for noise=$noise base=$base")
            }
        }
    }

    // ── computeNoiseLevel ─────────────────────────────────────────────────

    @Test
    fun `computeNoiseLevel returns 0 for silence`() {
        val samples = ShortArray(1024) // all zeros
        val result = AmbientNoiseMonitor.computeNoiseLevel(samples, 1024)
        assertEquals(0f, result, 1e-5f, "Silence should give 0 noise level")
    }

    @Test
    fun `computeNoiseLevel returns positive for signal`() {
        val samples = ShortArray(1024) { 1000 } // constant DC-ish
        val result = AmbientNoiseMonitor.computeNoiseLevel(samples, 1024)
        assertTrue(result > 0f, "Non-silent samples should give positive noise level")
        assertTrue(result <= 1f, "Noise level should be clamped to 1")
    }

    @Test
    fun `computeNoiseLevel handles empty buffer`() {
        val samples = ShortArray(0)
        val result = AmbientNoiseMonitor.computeNoiseLevel(samples, 0)
        assertEquals(0f, result, "Empty buffer should give 0")
    }
}