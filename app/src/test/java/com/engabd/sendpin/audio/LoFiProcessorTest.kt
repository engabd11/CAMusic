package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoFiProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        val cfg = LoFiProcessor.Config(enabled = false, intensity = 0.5f)
        assertEquals(false, cfg.isActive())
    }

    @Test
    fun `bit depth maps from 16 to 6 across intensity range`() {
        assertEquals(16, LoFiProcessor.bitDepth(0f))
        assertEquals(16, LoFiProcessor.bitDepth(0.005f))
        // At 0.5 intensity, should be around 12 bits
        val mid = LoFiProcessor.bitDepth(0.5f)
        assertTrue(mid in 10..14, "Mid intensity should give ~12 bits, got $mid")
        // At full intensity, should be minimum (6 bits)
        assertEquals(6, LoFiProcessor.bitDepth(1.0f))
    }

    @Test
    fun `quantise at 16 bits is identity`() {
        val x = 0.5f
        assertEquals(x, LoFiProcessor.quantise(x, 16))
    }

    @Test
    fun `quantise at low bits produces discrete steps`() {
        val bits = 4  // 15 levels
        val levels = (1 shl bits) - 1
        // 0.0 should quantise to 0
        assertEquals(0f, LoFiProcessor.quantise(0f, bits))
        // 1.0 should quantise to 1.0 (max level)
        assertEquals(1f, LoFiProcessor.quantise(1f, bits))
        // A value between steps should snap to the nearest level
        val step = 1f / levels
        val input = step * 1.5f  // halfway between levels 1 and 2
        val result = LoFiProcessor.quantise(input, bits)
        // Should be either level 1 or level 2, not between
        val level1 = step
        val level2 = 2f * step
        assertTrue(abs(result - level1) < 0.001f || abs(result - level2) < 0.001f,
            "Quantised value should be on a discrete step, got $result")
    }

    @Test
    fun `decimation factor increases with intensity`() {
        assertEquals(1, LoFiProcessor.decimationFactor(0f))
        assertTrue(LoFiProcessor.decimationFactor(1.0f) > 1)
    }

    @Test
    fun `saturation drive is unity at zero intensity`() {
        assertEquals(1f, LoFiProcessor.saturationDrive(0f))
        assertEquals(3f, LoFiProcessor.saturationDrive(1f))
    }

    @Test
    fun `saturate at unity drive is identity`() {
        val x = 0.5f
        assertEquals(x, LoFiProcessor.saturate(x, 1f))
    }

    @Test
    fun `saturate at high drive compresses toward unity`() {
        val drive = 3f
        // A full-scale input should stay at unity
        assertEquals(1f, LoFiProcessor.saturate(1f, drive), 0.001f)
        // A mid-level input should be slightly louder (saturation adds energy)
        val result = LoFiProcessor.saturate(0.5f, drive)
        assertTrue(result > 0.5f, "Saturation should boost mid-level signals")
    }

    @Test
    fun `low-pass coefficient is passthrough at zero intensity`() {
        assertEquals(1f, LoFiProcessor.lowPassCoeff(0f, 44_100))
    }

    @Test
    fun `low-pass coefficient decreases with intensity`() {
        val low = LoFiProcessor.lowPassCoeff(0.2f, 44_100)
        val high = LoFiProcessor.lowPassCoeff(0.8f, 44_100)
        assertTrue(high < low, "Higher intensity should mean more filtering")
    }

    @Test
    fun `lo-fi crackle is quieter than dedicated vinyl crackle`() {
        val i = 0.5f
        assertTrue(LoFiProcessor.loFiCrackleAmplitude(i) < VinylNoiseProcessor.crackleAmplitude(i))
    }

    @Test
    fun `encode and decode round-trips`() {
        val cfg = LoFiProcessor.Config(enabled = true, intensity = 0.7f, shareVinylCrackle = true)
        val encoded = LoFiProcessor.encode(cfg)
        val decoded = LoFiProcessor.decode(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, LoFiProcessor.decode("not json"))
    }
}