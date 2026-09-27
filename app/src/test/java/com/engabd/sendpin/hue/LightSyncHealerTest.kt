package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightSyncHealerTest {

    private val id = "001788fffe123456"

    @Test
    fun `a bridge that is not announcing gives nothing to act on`() {
        assertNull(LightSyncHealer.bridgeMove(id, "192.168.1.10", emptyList()))
        assertNull(LightSyncHealer.bridgeMove(id, "192.168.1.10", listOf("001788fffe999999" to "192.168.1.11")))
    }

    @Test
    fun `a bridge back at its address is recognised, whatever the id's case`() {
        assertEquals("192.168.1.10", LightSyncHealer.bridgeMove(id, "192.168.1.10", listOf(id.uppercase() to "192.168.1.10")))
    }

    @Test
    fun `a bridge at a new address reports that address`() {
        assertEquals("192.168.1.42", LightSyncHealer.bridgeMove(id, "192.168.1.10", listOf(id to "192.168.1.42")))
    }

    @Test
    fun `a bridge answering on its old address and another has not moved`() {
        val seen = listOf(id to "fe80::1", id to "192.168.1.10")
        assertEquals("192.168.1.10", LightSyncHealer.bridgeMove(id, "192.168.1.10", seen))
    }

    @Test
    fun `a bridge paired without an id is never matched`() {
        assertNull(LightSyncHealer.bridgeMove("", "192.168.1.10", listOf("" to "192.168.1.42")))
    }

    @Test
    fun `attempts are spaced out however many events arrive`() {
        val s = HealSchedule()
        assertEquals(0, s.waitBeforeAttemptMs(1_000_000), "the first attempt goes at once")
        s.attempted(1_000_000)
        assertEquals(HealSchedule.MIN_GAP_MS, s.waitBeforeAttemptMs(1_000_000))
        assertEquals(4_000, s.waitBeforeAttemptMs(1_006_000))
        assertEquals(0, s.waitBeforeAttemptMs(1_000_000 + HealSchedule.MIN_GAP_MS))
    }

    @Test
    fun `the timer floor backs off to five minutes and stays there`() {
        val s = HealSchedule()
        val steps = List(6) { s.nextTimerMs() }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), steps)
    }
}
