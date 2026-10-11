package com.engabd.sendpin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** The reconnect schedule: quick for a blip, patient for an absent server. */
class ReconnectBackoffTest {

    @Test
    fun `the first attempts keep the old quick ladder`() {
        assertEquals(
            listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L),
            (0 until ReconnectBackoff.FAST_ATTEMPTS).map(ReconnectBackoff::delayMs),
        )
    }

    @Test
    fun `a server gone for good is tried once a minute, then every five`() {
        assertEquals(60_000L, ReconnectBackoff.delayMs(ReconnectBackoff.FAST_ATTEMPTS))
        assertEquals(60_000L, ReconnectBackoff.delayMs(ReconnectBackoff.SLOW_ATTEMPTS - 1))
        assertEquals(300_000L, ReconnectBackoff.delayMs(ReconnectBackoff.SLOW_ATTEMPTS))
        assertEquals(300_000L, ReconnectBackoff.delayMs(10_000))
    }

    @Test
    fun `the fast part covers about a minute and a half`() {
        val fast = (0 until ReconnectBackoff.FAST_ATTEMPTS).sumOf(ReconnectBackoff::delayMs)
        assertEquals(90_500L, fast)
    }
}
