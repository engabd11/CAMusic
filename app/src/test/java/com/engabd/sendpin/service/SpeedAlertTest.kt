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
    fun `first alerting speed is the whole km per h strictly past the trigger`() {
        // 100 + 5% = 105 exactly: 105 is *at* the tolerance, 106 is past it.
        assertEquals(106, SpeedAlert.firstAlertingSpeedKmh(limitKmh = 100, tolerancePct = 5))
        // 50 + 3% = 51.5: 51 is under, 52 is the first whole number over.
        assertEquals(52, SpeedAlert.firstAlertingSpeedKmh(limitKmh = 50, tolerancePct = 3))
        assertEquals(81, SpeedAlert.firstAlertingSpeedKmh(limitKmh = 80, tolerancePct = 0))
    }

    @Test
    fun `a reading that displays as the trigger is not over it`() {
        // 105.4 shows as "105" on every speedometer, the overlay and the
        // notification — beeping for it is beeping at the tolerance, not past it.
        assertFalse(SpeedAlert.isOver(speedKmh = 105.4f, triggerKmh = 105f))
        assertFalse(SpeedAlert.isOver(speedKmh = 105f, triggerKmh = 105f))
        assertTrue(SpeedAlert.isOver(speedKmh = 105.6f, triggerKmh = 105f))
        assertTrue(SpeedAlert.isOver(speedKmh = 106f, triggerKmh = 105f))
        // Fractional trigger: 51.6 displays as 52, which is past 51.5.
        assertTrue(SpeedAlert.isOver(speedKmh = 51.6f, triggerKmh = 51.5f))
        assertFalse(SpeedAlert.isOver(speedKmh = 51.4f, triggerKmh = 51.5f))
    }

    @Test
    fun `a whole-number trigger is exact in float`() {
        // 100 × 1.05f is 104.99999, and that is how 105 km/h came to beep in a 100
        // zone with 5% tolerance: the reading was over a trigger that should have
        // been equal to it.
        assertEquals(105f, SpeedAlert.triggerSpeedKmh(limitKmh = 100, tolerancePct = 5))
        assertFalse(SpeedAlert.isOver(speedKmh = 105f, triggerKmh = SpeedAlert.triggerSpeedKmh(100, 5)))
        assertTrue(SpeedAlert.isOver(speedKmh = 106f, triggerKmh = SpeedAlert.triggerSpeedKmh(100, 5)))
    }

    @Test
    fun `sitting a fraction over the trigger never beeps`() {
        val t = SpeedAlert.Tracker()
        repeat(10) { assertFalse(t.onReading(speedKmh = 105.4f, triggerKmh = 105f, nowMs = it * 1000L)) }
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
