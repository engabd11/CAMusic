package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VinylNoiseProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        val p = VinylNoiseProcessor()
        val cfg = VinylNoiseProcessor.Config(enabled = false, intensity = 0.5f)
        assertEquals(false, cfg.isActive())
        assertEquals(false, VinylNoiseProcessor.Config(enabled = true, intensity = 0f).isActive())
    }

    @Test
    fun `crackle amplitude scales with intensity and is capped`() {
        assertEquals(0f, VinylNoiseProcessor.crackleAmplitude(0f))
        assertEquals(0.12f, VinylNoiseProcessor.crackleAmplitude(1f))
        // Above 1.0 intensity is clamped
        assertEquals(0.12f, VinylNoiseProcessor.crackleAmplitude(2f))
    }

    @Test
    fun `pop amplitude is louder than crackle at same intensity`() {
        val i = 0.5f
        assertTrue(VinylNoiseProcessor.popAmplitude(i) > VinylNoiseProcessor.crackleAmplitude(i))
    }

    @Test
    fun `rumble amplitude stays below 3 percent at max`() {
        assertEquals(0.03f, VinylNoiseProcessor.rumbleAmplitude(1f))
        assertTrue(VinylNoiseProcessor.rumbleAmplitude(0.5f) < 0.03f)
    }

    @Test
    fun `crackle interval decreases with intensity`() {
        val sr = 44_100
        val low = VinylNoiseProcessor.crackleInterval(0.1f, sr)
        val high = VinylNoiseProcessor.crackleInterval(1.0f, sr)
        assertTrue(high < low, "Higher intensity should mean more frequent crackle")
    }

    @Test
    fun `pop interval is much rarer than crackle`() {
        val sr = 44_100
        val i = 0.5f
        val crackleEvery = VinylNoiseProcessor.crackleInterval(i, sr)
        val popEvery = VinylNoiseProcessor.popInterval(i, sr)
        assertTrue(popEvery > crackleEvery * 10, "Pops should be at least 10x rarer than crackle")
    }

    @Test
    fun `low-pass coefficient is in valid range`() {
        val coeff = VinylNoiseProcessor.lowPassCoeff(50f, 44_100)
        assertTrue(coeff > 0f && coeff < 1f, "LP coefficient should be in (0, 1)")
        // Higher cutoff means higher coefficient (closer to passthrough)
        val higher = VinylNoiseProcessor.lowPassCoeff(500f, 44_100)
        assertTrue(higher > coeff)
    }

    @Test
    fun `encode and decode round-trips`() {
        val cfg = VinylNoiseProcessor.Config(enabled = true, intensity = 0.7f)
        val encoded = VinylNoiseProcessor.encode(cfg)
        val decoded = VinylNoiseProcessor.decode(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, VinylNoiseProcessor.decode("not json"))
    }
}