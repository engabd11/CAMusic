package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedAlertTest {

    @Test
    fun `trigger speed is the limit plus tolerance`() {
        assertEquals(84f, SpeedAlert.triggerSpeedKmh(limitKmh = 80, tolerancePct = 5))
    }

    @Test
    fun `zero tolerance triggers at exactly the limit`() {
        assertEquals(80f, SpeedAlert.triggerSpeedKmh(limitKmh = 80, tolerancePct = 0))
    }

    @Test
    fun `at or below the trigger never beeps`() {
        val t = SpeedAlert.Tracker()
        repeat(10) { assertFalse(t.onReading(speedKmh = 84f, triggerKmh = 84f, nowMs = it * 1000L)) }
    }

    @Test
    fun `a single reading over the trigger does not beep`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(speedKmh = 90f, triggerKmh = 84f, nowMs = 0L))
    }

    @Test
    fun `the Nth consecutive reading over the trigger beeps`() {
        val t = SpeedAlert.Tracker()
        repeat(SpeedAlert.CONSECUTIVE_READINGS_REQUIRED - 1) {
            assertFalse(t.onReading(90f, 84f, nowMs = it * 1000L))
        }
        assertTrue(t.onReading(90f, 84f, nowMs = SpeedAlert.CONSECUTIVE_READINGS_REQUIRED * 1000L))
    }

    @Test
    fun `dropping below the trigger resets the streak`() {
        val t = SpeedAlert.Tracker()
        repeat(SpeedAlert.CONSECUTIVE_READINGS_REQUIRED - 1) { assertFalse(t.onReading(90f, 84f, it * 1000L)) }
        // One reading back under the limit — a genuine drop, not noise on the last sample.
        assertFalse(t.onReading(80f, 84f, 10_000L))
        repeat(SpeedAlert.CONSECUTIVE_READINGS_REQUIRED - 1) {
            assertFalse(t.onReading(90f, 84f, 11_000L + it * 1000L))
        }
        assertTrue(t.onReading(90f, 84f, 20_000L))
    }

    @Test
    fun `does not beep again inside the repeat interval`() {
        val t = SpeedAlert.Tracker()
        var now = 0L
        repeat(SpeedAlert.CONSECUTIVE_READINGS_REQUIRED) { now += 1000; t.onReading(90f, 84f, now) }
        // Still well over the limit a few seconds later - no second beep yet.
        assertFalse(t.onReading(90f, 84f, now + 5_000L))
    }

    @Test
    fun `beeps again once the repeat interval has passed`() {
        val t = SpeedAlert.Tracker()
        var now = 0L
        repeat(SpeedAlert.CONSECUTIVE_READINGS_REQUIRED) { now += 1000; t.onReading(90f, 84f, now) }
        assertTrue(t.onReading(90f, 84f, now + SpeedAlert.REPEAT_INTERVAL_MS))
    }
}
