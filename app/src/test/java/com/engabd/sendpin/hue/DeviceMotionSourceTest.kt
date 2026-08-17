package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure math behind [DeviceMotionSource], fed synthetic sensor values
 * rather than a real [android.hardware.SensorManager] — there is no
 * Robolectric in this project's test suite, so anything that needs real
 * sensor plumbing is exercised by hand instead; this covers everything that
 * doesn't.
 */
class DeviceMotionSourceTest {

    @Test
    fun `tilt is zero when the phone is flat`() {
        val (x, y) = tiltFrom(0f, 0f, 9.81f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `tilt scales with gravity and clamps at the extremes`() {
        val (x, _) = tiltFrom(9.81f, 0f, 9.81f)
        assertEquals(1f, x, 1e-4f)
        val (over, _) = tiltFrom(30f, 0f, 9.81f)
        assertEquals(1f, over, 1e-4f, "tilt should clamp rather than exceed 1")
        val (neg, _) = tiltFrom(-9.81f, 0f, 9.81f)
        assertEquals(-1f, neg, 1e-4f)
    }

    @Test
    fun `a sharp jerk is a flick, ordinary variation is not`() {
        assertTrue(isFlick(previousMagnitude = 9.8f, magnitude = 30f, thresholdMs2 = 15f), "a wrist flick should register")
        assertFalse(isFlick(previousMagnitude = 9.8f, magnitude = 11f, thresholdMs2 = 15f), "ordinary holding should not")
    }

    @Test
    fun `stillness times out after the window, not before`() {
        val start = 0L
        val window = 5_000_000_000L // 5s in nanos, the spec-fixed auto-disable
        assertFalse(isStillnessTimedOut(start, start + window - 1, window), "one nanosecond short of the window")
        assertTrue(isStillnessTimedOut(start, start + window, window), "exactly at the window")
        assertTrue(isStillnessTimedOut(start, start + window + 1_000_000_000L, window), "well past the window")
    }

    @Test
    fun `motion resets the clock, so it never times out while moving`() {
        // The pattern DeviceMotionSource actually uses: lastMotionAtNanos is
        // bumped forward on every real movement, so a stream of frequent
        // small movements never accumulates toward the timeout even though
        // a lot of wall-clock time has passed overall.
        var lastMotionAt = 0L
        val window = 5_000_000_000L
        var now = 0L
        repeat(20) {
            now += 1_000_000_000L // 1s steps
            lastMotionAt = now // motion happened again this second
            assertFalse(isStillnessTimedOut(lastMotionAt, now, window))
        }
    }
}
