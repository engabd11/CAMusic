package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Music Assistant's tiers, by its documented rule and by its wire values. */
class QualityTierTest {

    @Test
    fun `formats grade by MA's rule`() {
        assertEquals(QualityTier.HI_RES, QualityTier.of(StreamQuality("flac", 96_000, 16)))
        assertEquals(QualityTier.HI_RES, QualityTier.of(StreamQuality("s24le", 48_000, 24)))
        assertEquals(QualityTier.LOSSLESS, QualityTier.of(StreamQuality("flac", 48_000, 16)))
        assertEquals(QualityTier.STANDARD, QualityTier.of(StreamQuality("mp3", 44_100, 0, bitrateKbps = 320)))
        assertEquals(QualityTier.LOW, QualityTier.of(StreamQuality("aac", 44_100, 0, bitrateKbps = 128)))
        assertNull(QualityTier.of(StreamQuality("opus", 48_000, 16)))
        assertNull(QualityTier.of(null))
    }

    @Test
    fun `wire values map to tiers`() {
        assertEquals(QualityTier.HI_RES, QualityTier.fromWire("hi_res"))
        assertEquals(QualityTier.LOSSLESS, QualityTier.fromWire("lossless"))
        assertEquals(QualityTier.STANDARD, QualityTier.fromWire("standard"))
        assertEquals(QualityTier.LOW, QualityTier.fromWire("low"))
        assertNull(QualityTier.fromWire("unknown"))
        assertNull(QualityTier.fromWire(null))
    }
}
