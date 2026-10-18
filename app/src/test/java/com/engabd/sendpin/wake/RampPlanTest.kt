package com.engabd.sendpin.wake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The exact bridge commands for the sunrise and the wind-down. */
class RampPlanTest {
    private val thirty = 30 * 60_000L

    @Test
    fun `start of a 30 minute ramp is red-orange at 1 percent then 21 minutes to 2700 K at 60 percent`() {
        val b = RampPlan.bodies(WakeStage.LIGHTS_START, thirty, 0)
        assertEquals(
            listOf(
                """{"on":{"on":true},"dimming":{"brightness":1.0},"color":{"xy":{"x":0.6,"y":0.36}},"dynamics":{"duration":0}}""",
                """{"on":{"on":true},"dimming":{"brightness":60.0},"color_temperature":{"mirek":370},"dynamics":{"duration":1260000}}""",
            ),
            b,
        )
    }

    @Test
    fun `leg two is the last nine minutes to 4000 K at full`() {
        val b = RampPlan.bodies(WakeStage.LIGHTS_LEG2, thirty, 21 * 60_000L)
        assertEquals(
            listOf("""{"on":{"on":true},"dimming":{"brightness":100.0},"color_temperature":{"mirek":250},"dynamics":{"duration":540000}}"""),
            b,
        )
    }

    @Test
    fun `leg durations always add up to the ramp`() {
        for (min in listOf(20, 30, 45)) {
            val ramp = min * 60_000L
            val one = RampPlan.legOneMs(ramp)
            val start = RampPlan.bodies(WakeStage.LIGHTS_START, ramp, 0).last()
            val two = RampPlan.bodies(WakeStage.LIGHTS_LEG2, ramp, one).last()
            val d1 = Regex("\"duration\":(\\d+)").find(start)!!.groupValues[1].toLong()
            val d2 = Regex("\"duration\":(\\d+)").find(two)!!.groupValues[1].toLong()
            assertEquals(ramp, d1 + d2)
        }
    }

    @Test
    fun `joining ten minutes late jumps to the curve point and finishes on time`() {
        val b = RampPlan.bodies(WakeStage.LIGHTS_START, thirty, 10 * 60_000L)
        assertEquals(2, b.size)
        // 10 of 21 minutes in: 1 + 59 * 10/21 = 29.1 percent, mirek 500 - 130 * 10/21 = 438.
        assertEquals("""{"on":{"on":true},"dimming":{"brightness":29.1},"color_temperature":{"mirek":438},"dynamics":{"duration":0}}""", b[0])
        assertEquals("""{"on":{"on":true},"dimming":{"brightness":60.0},"color_temperature":{"mirek":370},"dynamics":{"duration":660000}}""", b[1])
    }

    @Test
    fun `joining after leg one goes straight into leg two`() {
        val b = RampPlan.bodies(WakeStage.LIGHTS_START, thirty, 25 * 60_000L)
        assertEquals(2, b.size)
        assertTrue(b[1].endsWith("\"dynamics\":{\"duration\":300000}}"))
    }

    @Test
    fun `past the end it simply holds full daylight`() {
        assertEquals(
            listOf("""{"on":{"on":true},"dimming":{"brightness":100.0},"color_temperature":{"mirek":250},"dynamics":{"duration":0}}"""),
            RampPlan.bodies(WakeStage.LIGHTS_START, thirty, thirty + 5_000),
        )
    }

    @Test
    fun `no ramp sends nothing`() {
        assertEquals(emptyList(), RampPlan.bodies(WakeStage.LIGHTS_START, 0, 0))
    }

    @Test
    fun `wind down bodies`() {
        assertEquals(
            """{"on":{"on":true},"dimming":{"brightness":40.0},"color_temperature":{"mirek":454},"dynamics":{"duration":1000}}""",
            WindDownPlan.amberBody(null),
        )
        assertEquals(
            """{"on":{"on":true},"dimming":{"brightness":75.0},"color_temperature":{"mirek":454},"dynamics":{"duration":1000}}""",
            WindDownPlan.amberBody(75.0),
        )
        assertEquals(
            """{"on":{"on":true},"dimming":{"brightness":1.0},"dynamics":{"duration":1740000}}""",
            WindDownPlan.dimBody(1_740_000),
        )
        assertEquals("""{"on":{"on":false},"dynamics":{"duration":2000}}""", WindDownPlan.offBody())
        assertEquals("""{"on":{"on":true},"dimming":{"brightness":12.5},"dynamics":{"duration":0}}""", WindDownPlan.holdBody(12.5))
    }
}
