package com.engabd.sendpin.wake

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Re-arming: what gets set after a boot, a clock change, an edit or a finished stage. */
class AlarmPlannerTest {
    private val zone = ZoneId.of("Australia/Melbourne")

    private class FakePort : AlarmPort {
        val calls = mutableListOf<String>()
        var clock: Pair<Long, String>? = null
        var stage: Triple<Long, WakeStage, Long>? = null
        override fun setAlarmClock(atMs: Long, alarmId: String) { clock = atMs to alarmId; calls += "clock" }
        override fun setStage(atMs: Long, alarmId: String, alarmAtMs: Long, stage: WakeStage) {
            this.stage = Triple(atMs, stage, alarmAtMs); calls += "stage"
        }
        override fun setSnooze(atMs: Long, alarmId: String, alarmAtMs: Long) { calls += "snooze" }
        override fun cancelSchedule() { calls += "cancel"; clock = null; stage = null }
        override fun cancelSnooze() { calls += "cancelSnooze" }
    }

    private fun ms(d: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val sunrise = WakeAlarm(
        id = "a", hour = 7, minute = 0, days = WakeAlarm.EVERY_DAY,
        roomId = "gl", roomName = "Bedroom", rampMinutes = 30, fadeInMinutes = 3,
        source = WakeSource(WakeSource.Kind.RESUME),
    )

    @Test
    fun `boot re-arms the next alarm and its first stage`() {
        val port = FakePort()
        val armed = AlarmPlanner.rearm(port, listOf(sunrise), ms(19, 22), zone)!!
        assertEquals(ms(20, 7), armed.alarmAtMs)
        assertEquals(ms(20, 7) to "a", port.clock)
        val (at, stage, alarmAt) = port.stage!!
        assertEquals(WakeStage.LIGHTS_START, stage)
        assertEquals(ms(20, 6, 30), at)
        assertEquals(ms(20, 7), alarmAt)
        assertEquals("cancel", port.calls.first())
    }

    @Test
    fun `with nothing enabled everything is cancelled and nothing set`() {
        val port = FakePort()
        assertNull(AlarmPlanner.rearm(port, listOf(sunrise.copy(enabled = false)), ms(19, 22), zone))
        assertEquals(listOf("cancel"), port.calls)
        assertNull(port.clock)
    }

    @Test
    fun `a stage that already ran moves on to the next`() {
        val port = FakePort()
        val at = ms(20, 7)
        val done = setOf(StageAt(WakeStage.LIGHTS_START, 0).key(at))
        AlarmPlanner.rearm(port, listOf(sunrise), ms(20, 6, 40), zone, done)
        assertEquals(WakeStage.LIGHTS_LEG2, port.stage!!.second)
        assertEquals(ms(20, 6, 51), port.stage!!.first)
    }

    @Test
    fun `an overdue stage runs straight away rather than being skipped`() {
        val port = FakePort()
        // Alarm set at 06:50 for 07:00 with a 30 minute ramp: the sunrise is already late.
        val now = ms(20, 6, 50)
        AlarmPlanner.rearm(port, listOf(sunrise), now, zone)
        assertEquals(WakeStage.LIGHTS_START, port.stage!!.second)
        assertEquals(now + AlarmPlanner.MIN_LEAD_MS, port.stage!!.first)
    }

    @Test
    fun `all stages done leaves only the alarm itself`() {
        val port = FakePort()
        val at = ms(20, 7)
        val done = AlarmMath.stages(sunrise, at).map { it.key(at) }.toSet()
        val armed = AlarmPlanner.rearm(port, listOf(sunrise), ms(20, 6, 59), zone, done)!!
        assertNull(armed.nextStage)
        assertNull(port.stage)
        assertEquals(at, port.clock!!.first)
    }

    @Test
    fun `after the alarm rings the planner looks to the next day`() {
        val port = FakePort()
        val armed = AlarmPlanner.rearm(port, listOf(sunrise), ms(20, 7) + 1, zone)!!
        assertEquals(ms(21, 7), armed.alarmAtMs)
    }

    @Test
    fun `a clock change re-arms to the new time`() {
        val port = FakePort()
        val before = AlarmPlanner.rearm(port, listOf(sunrise), ms(19, 22), zone)!!
        // The phone's clock is corrected an hour forward; the alarm is recomputed, not shifted.
        val after = AlarmPlanner.rearm(port, listOf(sunrise), ms(19, 23), zone)!!
        assertEquals(before.alarmAtMs, after.alarmAtMs)
        assertTrue(port.calls.count { it == "cancel" } == 2)
    }

    @Test
    fun `a music only alarm has just the fade in stage`() {
        val port = FakePort()
        AlarmPlanner.rearm(port, listOf(sunrise.copy(roomId = "")), ms(19, 22), zone)
        assertEquals(WakeStage.MUSIC, port.stage!!.second)
        assertEquals(ms(20, 6, 57), port.stage!!.first)
    }
}
