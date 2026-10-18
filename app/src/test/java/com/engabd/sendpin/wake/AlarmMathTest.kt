package com.engabd.sendpin.wake

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When an alarm next rings: days, repeat masks, daylight saving in both hemispheres, snooze. */
class AlarmMathTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun zdt(ms: Long, zone: ZoneId) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)

    private fun alarm(h: Int, m: Int, days: Int = WakeAlarm.EVERY_DAY, enabled: Boolean = true) =
        WakeAlarm(id = "a", hour = h, minute = m, days = days, enabled = enabled)

    @Test
    fun `later today when the time has not passed`() {
        val next = AlarmMath.nextOccurrence(alarm(7, 0), at(melbourne, 2026, 10, 19, 6, 0), melbourne)!!
        assertEquals(zdt(at(melbourne, 2026, 10, 19, 7), melbourne), zdt(next, melbourne))
    }

    @Test
    fun `tomorrow when the time has passed, and exactly now counts as passed`() {
        val now = at(melbourne, 2026, 10, 19, 7, 0)
        val next = AlarmMath.nextOccurrence(alarm(7, 0), now, melbourne)!!
        assertEquals(at(melbourne, 2026, 10, 20, 7), next)
    }

    @Test
    fun `a disabled alarm never rings`() {
        assertNull(AlarmMath.nextOccurrence(alarm(7, 0, enabled = false), 0, melbourne))
    }

    @Test
    fun `weekdays skip the weekend`() {
        // Friday 2026-10-23, 08:00, after the alarm: next is Monday the 26th.
        val now = at(melbourne, 2026, 10, 23, 8)
        val next = AlarmMath.nextOccurrence(alarm(7, 0, WakeAlarm.WEEKDAYS), now, melbourne)!!
        assertEquals(DayOfWeek.MONDAY, zdt(next, melbourne).dayOfWeek)
        assertEquals(26, zdt(next, melbourne).dayOfMonth)
    }

    @Test
    fun `weekends only reach the coming Saturday`() {
        val now = at(melbourne, 2026, 10, 19, 12) // Monday
        val next = AlarmMath.nextOccurrence(alarm(9, 30, WakeAlarm.WEEKEND), now, melbourne)!!
        assertEquals(DayOfWeek.SATURDAY, zdt(next, melbourne).dayOfWeek)
        assertEquals(24, zdt(next, melbourne).dayOfMonth)
    }

    @Test
    fun `a single weekday set today but past rings next week`() {
        val monday = WakeAlarm.bit(DayOfWeek.MONDAY)
        val now = at(melbourne, 2026, 10, 19, 8) // Monday, after 07:00
        val next = AlarmMath.nextOccurrence(alarm(7, 0, monday), now, melbourne)!!
        assertEquals(26, zdt(next, melbourne).dayOfMonth)
    }

    @Test
    fun `a one-off alarm rings at the next 06 30 whichever day that is`() {
        val now = at(melbourne, 2026, 10, 19, 22)
        val next = AlarmMath.nextOccurrence(alarm(6, 30, 0), now, melbourne)!!
        assertEquals(20, zdt(next, melbourne).dayOfMonth)
    }

    @Test
    fun `melbourne spring forward keeps 06 30 on the wall clock`() {
        // 2026-10-04 is the first Sunday of October: 02:00 AEST becomes 03:00 AEDT.
        val now = at(melbourne, 2026, 10, 3, 7)
        val next = AlarmMath.nextOccurrence(alarm(6, 30), now, melbourne)!!
        val z = zdt(next, melbourne)
        assertEquals(4, z.dayOfMonth)
        assertEquals(6, z.hour)
        assertEquals(30, z.minute)
        // Only 23 hours after the previous morning's 06:30, because the clocks moved.
        assertEquals(23L * 3_600_000L, next - at(melbourne, 2026, 10, 3, 6, 30))
    }

    @Test
    fun `an alarm inside the spring forward gap rings when the clock jumps`() {
        val now = at(melbourne, 2026, 10, 3, 23)
        val next = AlarmMath.nextOccurrence(alarm(2, 30), now, melbourne)!!
        val z = zdt(next, melbourne)
        assertEquals(4, z.dayOfMonth)
        assertEquals(3, z.hour)
        assertEquals(30, z.minute)
    }

    @Test
    fun `melbourne fall back keeps 06 30 and lasts 25 hours`() {
        // 2026-04-05: 03:00 AEDT becomes 02:00 AEST.
        val now = at(melbourne, 2026, 4, 4, 7)
        val next = AlarmMath.nextOccurrence(alarm(6, 30), now, melbourne)!!
        assertEquals(6, zdt(next, melbourne).hour)
        assertEquals(25L * 3_600_000L, next - at(melbourne, 2026, 4, 4, 6, 30))
    }

    @Test
    fun `an alarm in the repeated fall back hour uses the first one`() {
        val now = at(melbourne, 2026, 4, 4, 23)
        val next = AlarmMath.nextOccurrence(alarm(2, 30), now, melbourne)!!
        assertEquals(11, zdt(next, melbourne).offset.totalSeconds / 3600) // AEDT, the first pass
    }

    @Test
    fun `new york spring forward and fall back`() {
        // 2026-03-08: 02:00 EST becomes 03:00 EDT. 2026-11-01: 02:00 EDT becomes 01:00 EST.
        val spring = AlarmMath.nextOccurrence(alarm(7, 0), at(newYork, 2026, 3, 7, 8), newYork)!!
        assertEquals(23L * 3_600_000L, spring - at(newYork, 2026, 3, 7, 7))
        assertEquals(7, zdt(spring, newYork).hour)

        val fall = AlarmMath.nextOccurrence(alarm(7, 0), at(newYork, 2026, 10, 31, 8), newYork)!!
        assertEquals(25L * 3_600_000L, fall - at(newYork, 2026, 10, 31, 7))
        assertEquals(7, zdt(fall, newYork).hour)
    }

    @Test
    fun `moving the zone moves the alarm`() {
        val now = at(melbourne, 2026, 10, 19, 6)
        val a = AlarmMath.nextOccurrence(alarm(7, 0), now, melbourne)!!
        val b = AlarmMath.nextOccurrence(alarm(7, 0), now, newYork)!!
        assertTrue(a != b)
    }

    @Test
    fun `the soonest alarm wins`() {
        val now = at(melbourne, 2026, 10, 19, 6)
        val early = alarm(6, 30).copy(id = "early")
        val late = alarm(9, 0).copy(id = "late")
        val off = alarm(6, 15, enabled = false).copy(id = "off")
        assertEquals("early", AlarmMath.next(listOf(late, off, early), now, melbourne)!!.first.id)
        assertNull(AlarmMath.next(listOf(off), now, melbourne))
    }

    @Test
    fun `snooze is nine minutes on`() {
        assertEquals(1_000L + 9 * 60_000L, AlarmMath.snoozeAt(1_000L))
    }

    @Test
    fun `stages follow the ramp and the fade in`() {
        val a = alarm(7, 0).copy(roomId = "gl", rampMinutes = 30, fadeInMinutes = 3,
            source = WakeSource(WakeSource.Kind.RESUME))
        val at = at(melbourne, 2026, 10, 20, 7)
        val s = AlarmMath.stages(a, at)
        assertEquals(listOf(WakeStage.LIGHTS_START, WakeStage.LIGHTS_LEG2, WakeStage.MUSIC), s.map { it.stage })
        assertEquals(at - 30 * 60_000L, s[0].atMs)
        // Leg one is 70 percent of 30 minutes: 21 minutes in.
        assertEquals(at - 30 * 60_000L + 21 * 60_000L, s[1].atMs)
        assertEquals(at - 3 * 60_000L, s[2].atMs)
    }

    @Test
    fun `no room means no lights stages, no source means no music stage`() {
        assertTrue(AlarmMath.stages(alarm(7, 0), 1_000_000L).isEmpty())
        val lightsOnly = alarm(7, 0).copy(roomId = "gl")
        assertEquals(2, AlarmMath.stages(lightsOnly, 100_000_000L).size)
    }

    @Test
    fun `storage round trips and unreadable text is null`() {
        val a = alarm(6, 45, WakeAlarm.WEEKDAYS).copy(
            label = "Gym",
            roomId = "r", roomName = "Bedroom",
            source = WakeSource(WakeSource.Kind.ITEM, "camusic://x", "Morning mix"),
        )
        assertEquals(listOf(a), WakeAlarm.decode(WakeAlarm.encode(listOf(a))))
        assertNull(WakeAlarm.decode("not json"))
        assertNotNull(WakeAlarm.decode("[]"))
    }

    @Test
    fun `text summaries`() {
        assertEquals("Weekdays", AlarmMath.daysText(WakeAlarm.WEEKDAYS))
        assertEquals("Once", AlarmMath.daysText(0))
        assertEquals("Mon Wed", AlarmMath.daysText(WakeAlarm.bit(DayOfWeek.MONDAY) or WakeAlarm.bit(DayOfWeek.WEDNESDAY)))
        assertEquals("7 h 30 min", AlarmMath.untilText(0, (7 * 60 + 30) * 60_000L))
    }
}
