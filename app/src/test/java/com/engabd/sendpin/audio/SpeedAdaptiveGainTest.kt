package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedAdaptiveGainTest {

    @Test
    fun `under 40 kmh adds no gain`() {
        assertEquals(0f, SpeedAdaptiveGain.gainDb(0f))
        assertEquals(0f, SpeedAdaptiveGain.gainDb(39.9f))
        assertEquals(1f, SpeedAdaptiveGain.gainFactor(0f))
    }

    @Test
    fun `each band steps up 2 dB`() {
        assertEquals(2f, SpeedAdaptiveGain.gainDb(40f))
        assertEquals(4f, SpeedAdaptiveGain.gainDb(80f))
        assertEquals(6f, SpeedAdaptiveGain.gainDb(120f))
        assertEquals(6f, SpeedAdaptiveGain.gainDb(200f), "caps at the top band rather than climbing forever")
    }

    @Test
    fun `gain factor is the correct linear conversion of the dB value`() {
        // +6dB is very close to 2x amplitude.
        assertTrue(abs(SpeedAdaptiveGain.gainFactor(120f) - 2f) < 0.01f)
    }

    @Test
    fun `gain never goes below unity`() {
        assertTrue(SpeedAdaptiveGain.gainFactor(0f) >= 1f)
    }
}
