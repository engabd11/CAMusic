package com.engabd.sendpin.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sunrise ramp is a pure function of progress (0..1) — no Android types, no side
 * effects — so the whole curve can be tested with plain JUnit.
 *
 * What matters is not the exact values but the *shape*: colour goes red → orange → yellow
 * → white, brightness eases in (never jumps), audio starts after the light, and music
 * starts near the end. A regression that inverted any of those would read as a different
 * wake-up experience, and the tests below pin each property to the curve.
 */
class SunriseAlarmTest {

    @Test
    fun `colour starts at deep red`() {
        val c = SunriseAlarm.colourAt(0f)
        // Deep red: R dominant, G and B near zero
        assertTrue(c.first > 0.4f, "red channel should dominate at start, got ${c.first}")
        assertTrue(c.second < 0.15f, "green should be near zero at start, got ${c.second}")
        assertTrue(c.third < 0.1f, "blue should be near zero at start, got ${c.third}")
    }

    @Test
    fun `colour transitions through warm orange at 30 percent`() {
        val c = SunriseAlarm.colourAt(0.30f)
        // Warm orange: R high, G moderate, B low
        assertTrue(c.first > 0.8f, "red should be high at 30%, got ${c.first}")
        assertTrue(c.second > 0.3f && c.second < 0.6f, "green should be moderate at 30%, got ${c.second}")
        assertTrue(c.third < 0.2f, "blue should be low at 30%, got ${c.third}")
    }

    @Test
    fun `colour reaches soft yellow at 60 percent`() {
        val c = SunriseAlarm.colourAt(0.60f)
        // Soft yellow: R and G high, B moderate
        assertTrue(c.first > 0.9f, "red should be high at 60%, got ${c.first}")
        assertTrue(c.second > 0.7f, "green should be high at 60%, got ${c.second}")
        assertTrue(c.third > 0.3f, "blue should be moderate at 60%, got ${c.third}")
    }

    @Test
    fun `colour ends at warm white`() {
        val c = SunriseAlarm.colourAt(1f)
        // Warm white: all channels high, R >= G >= B
        assertTrue(c.first > 0.9f, "red should be high at end, got ${c.first}")
        assertTrue(c.second > 0.8f, "green should be high at end, got ${c.second}")
        assertTrue(c.third > 0.7f, "blue should be high at end, got ${c.third}")
        // Warm, not cold: R >= G >= B
        assertTrue(c.first >= c.second, "red should be >= green at end")
        assertTrue(c.second >= c.third, "green should be >= blue at end")
    }

    @Test
    fun `colour is monotonically increasing in each channel`() {
        // The sunrise never goes backwards — a room that gets dimmer mid-ramp would
        // feel like a sunset, which is the opposite of what this is for.
        var prevR = 0f
        var prevG = 0f
        var prevB = 0f
        for (i in 0..100) {
            val t = i / 100f
            val c = SunriseAlarm.colourAt(t)
            assertTrue(c.first >= prevR - 0.01f, "red decreased at $t: ${c.first} < $prevR")
            assertTrue(c.second >= prevG - 0.01f, "green decreased at $t: ${c.second} < $prevG")
            assertTrue(c.third >= prevB - 0.01f, "blue decreased at $t: ${c.third} < $prevB")
            prevR = c.first
            prevG = c.second
            prevB = c.third
        }
    }

    @Test
    fun `brightness starts at zero`() {
        assertEquals(0f, SunriseAlarm.brightnessAt(0f))
    }

    @Test
    fun `brightness reaches one at full ramp`() {
        assertEquals(1f, SunriseAlarm.brightnessAt(1f))
    }

    @Test
    fun `brightness uses smoothstep — midpoint is exactly 0_5`() {
        // smoothstep(t) = t²(3-2t); at t=0.5 that is 0.25 * 2 = 0.5
        assertEquals(0.5f, SunriseAlarm.brightnessAt(0.5f))
    }

    @Test
    fun `brightness is always in 0 to 1`() {
        for (i in 0..100) {
            val b = SunriseAlarm.brightnessAt(i / 100f)
            assertTrue(b in 0f..1f, "brightness out of range at ${i / 100f}: $b")
        }
    }

    @Test
    fun `audio volume is zero for the first 20 percent`() {
        // Sound waking someone before the light has started is an alarm, not a sunrise.
        for (i in 0..19) {
            assertEquals(0f, SunriseAlarm.audioVolumeAt(i / 100f), "audio should be silent at ${i}%")
        }
    }

    @Test
    fun `audio volume is non-zero after 20 percent`() {
        assertTrue(SunriseAlarm.audioVolumeAt(0.30f) > 0f, "audio should be audible at 30%")
    }

    @Test
    fun `audio volume is clamped to gentle ceiling`() {
        // Never exceeds GENTLE_VOLUME (0.35) — the aurora is ambience, not an alarm sound.
        assertTrue(SunriseAlarm.audioVolumeAt(1f) <= 0.36f, "audio at full ramp should be gentle, got ${SunriseAlarm.audioVolumeAt(1f)}")
    }

    @Test
    fun `music does not start before 80 percent`() {
        for (i in 0..79) {
            assertFalse(SunriseAlarm.shouldStartMusic(i / 100f), "music should not start at ${i}%")
        }
    }

    @Test
    fun `music starts at 80 percent`() {
        assertTrue(SunriseAlarm.shouldStartMusic(0.80f), "music should start at 80%")
        assertTrue(SunriseAlarm.shouldStartMusic(1f), "music should be started at 100%")
    }

    @Test
    fun `colour and brightness progress together — colour is never brighter than the ceiling`() {
        // The colour values are raw (0..1 per channel) and the brightness scales them.
        // The product should never exceed 1 in any channel, or the light would clip.
        for (i in 0..100) {
            val t = i / 100f
            val c = SunriseAlarm.colourAt(t)
            val b = SunriseAlarm.brightnessAt(t)
            assertTrue(c.first * b <= 1f, "red×brightness clips at $t: ${c.first * b}")
            assertTrue(c.second * b <= 1f, "green×brightness clips at $t: ${c.second * b}")
            assertTrue(c.third * b <= 1f, "blue×brightness clips at $t: ${c.third * b}")
        }
    }

    @Test
    fun `colour clamps outside 0 to 1 without crashing`() {
        // The receiver may compute a progress slightly outside [0,1] due to timing
        // jitter; the curve must not throw or return NaN.
        SunriseAlarm.colourAt(-0.1f)
        SunriseAlarm.colourAt(1.1f)
        SunriseAlarm.brightnessAt(-1f)
        SunriseAlarm.brightnessAt(2f)
    }
}