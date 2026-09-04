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
    fun `two readings a long way apart still need a second one`() {
        // The confirmation window is time, so one fix and then another five seconds
        // later spans it — but [MIN_READINGS] is what stops a lone spike counting.
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertTrue(t.onReading(90f, 84f, 5_000L))
    }

    @Test
    fun `part of the window is not enough`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertFalse(t.onReading(90f, 84f, 500L))
        assertFalse(t.onReading(90f, 84f, 1_000L))
    }

    @Test
    fun `beeps once the confirmation window has been held`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertFalse(t.onReading(90f, 84f, 1_000L))
        assertTrue(t.onReading(90f, 84f, SpeedAlert.CONFIRM_WINDOW_MS))
    }

    @Test
    fun `one dipping sample costs its own second, not the whole window`() {
        // The case the old consecutive-readings tracker got wrong: a driver sitting
        // a couple of km/h over produces readings that cross the trigger both ways,
        // and every crossing used to put the window back to zero. The streak now
        // survives the dip — but the second spent under it does not count toward
        // the two, so the beep lands a second later than an uninterrupted run.
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(85f, 84f, 0L))
        assertFalse(t.onReading(83f, 84f, 1_000L)) // noise, not a slow-down
        assertFalse(t.onReading(85f, 84f, 2_000L))
        assertTrue(t.onReading(85f, 84f, 3_000L))
    }

    @Test
    fun `two brief excursions do not add up to two seconds over`() {
        // Over, legal for a second and a half, over again. That is not "two seconds
        // over the limit", and crediting the gap would make it read as one.
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertFalse(t.onReading(70f, 84f, 500L))
        assertFalse(t.onReading(70f, 84f, 1_500L))
        assertFalse(t.onReading(90f, 84f, 2_000L))
        assertFalse(t.onReading(90f, 84f, 2_500L))
        assertTrue(t.onReading(90f, 84f, 3_500L))
    }

    @Test
    fun `genuinely slowing down ends the streak`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertFalse(t.onReading(90f, 84f, 1_000L))
        // Back under, and staying under past the grace period.
        repeat(6) { assertFalse(t.onReading(70f, 84f, 2_000L + it * 1000L)) }
        // Speeding up again starts a fresh window rather than beeping immediately.
        assertFalse(t.onReading(90f, 84f, 9_000L))
        assertFalse(t.onReading(90f, 84f, 10_000L))
        assertTrue(t.onReading(90f, 84f, 11_000L))
    }

    @Test
    fun `does not beep again inside the repeat interval`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertTrue(t.onReading(90f, 84f, 2_000L))
        // Still well over the limit a few seconds later - no second beep yet.
        assertFalse(t.onReading(90f, 84f, 8_000L))
    }

    @Test
    fun `beeps again once the repeat interval has passed`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertTrue(t.onReading(90f, 84f, 2_000L))
        assertTrue(t.onReading(90f, 84f, 2_000L + SpeedAlert.REPEAT_INTERVAL_MS))
    }

    @Test
    fun `slowing down does not buy a second beep inside the repeat interval`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertTrue(t.onReading(90f, 84f, 2_000L))
        // Drop under for long enough to end the streak, then straight back over.
        repeat(6) { assertFalse(t.onReading(60f, 84f, 3_000L + it * 1000L)) }
        assertFalse(t.onReading(90f, 84f, 9_000L))
        assertFalse(t.onReading(90f, 84f, 10_000L))
        assertFalse(t.onReading(90f, 84f, 13_000L))
    }

    @Test
    fun `reset clears the repeat interval so a new drive can beep straight away`() {
        val t = SpeedAlert.Tracker()
        assertFalse(t.onReading(90f, 84f, 0L))
        assertTrue(t.onReading(90f, 84f, 2_000L))
        t.reset()
        assertFalse(t.onReading(90f, 84f, 3_000L))
        assertTrue(t.onReading(90f, 84f, 5_000L))
    }
}
