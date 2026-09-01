package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemSoloProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        val cfg = StemSoloProcessor.Config(stem = StemSoloProcessor.Stem.NONE, enabled = false)
        assertEquals(false, cfg.isActive())
    }

    @Test
    fun `NONE stem is not active even when enabled`() {
        val cfg = StemSoloProcessor.Config(stem = StemSoloProcessor.Stem.NONE, enabled = true)
        assertEquals(false, cfg.isActive())
    }

    @Test
    fun `any non-NONE stem is active when enabled`() {
        for (stem in StemSoloProcessor.Stem.entries) {
            if (stem == StemSoloProcessor.Stem.NONE) continue
            val cfg = StemSoloProcessor.Config(stem = stem, enabled = true)
            assertTrue(cfg.isActive(), "$stem should be active when enabled")
        }
    }

    @Test
    fun `bass cascade is one low-pass`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.BASS, 44_100)
        assertEquals(1, cascade.size)
    }

    @Test
    fun `vocals cascade is high-pass plus low-pass (bandpass)`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.VOCALS, 44_100)
        assertEquals(2, cascade.size)
    }

    @Test
    fun `drums cascade is one high-pass`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.DRUMS, 44_100)
        assertEquals(1, cascade.size)
    }

    @Test
    fun `guitar cascade is bandpass (two sections)`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.GUITAR, 44_100)
        assertEquals(2, cascade.size)
    }

    @Test
    fun `synths cascade is one high-pass`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.SYNTHS, 44_100)
        assertEquals(1, cascade.size)
    }

    @Test
    fun `NONE cascade is empty`() {
        val cascade = StemSoloProcessor.buildCascade(StemSoloProcessor.Stem.NONE, 44_100)
        assertTrue(cascade.isEmpty())
    }

    @Test
    fun `encode and decode round-trips`() {
        val cfg = StemSoloProcessor.Config(stem = StemSoloProcessor.Stem.VOCALS, enabled = true)
        val encoded = StemSoloProcessor.encode(cfg)
        val decoded = StemSoloProcessor.decode(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, StemSoloProcessor.decode("not json"))
    }
}