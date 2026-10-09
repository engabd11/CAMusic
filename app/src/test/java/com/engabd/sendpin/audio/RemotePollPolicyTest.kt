package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How often a remote player (MPD) is read, with and without its push channel. */
class RemotePollPolicyTest {

    @Test
    fun `without pushes the old schedule stands`() {
        assertEquals(1_000L, RemotePollPolicy.intervalMs(playing = true, foreground = true, pushed = false))
        assertEquals(2_000L, RemotePollPolicy.intervalMs(playing = false, foreground = true, pushed = false))
        assertEquals(15_000L, RemotePollPolicy.intervalMs(playing = false, foreground = false, pushed = false))
    }

    @Test
    fun `with pushes reads are rare, the player says when it changes`() {
        assertEquals(5_000L, RemotePollPolicy.intervalMs(playing = true, foreground = true, pushed = true))
        assertEquals(30_000L, RemotePollPolicy.intervalMs(playing = false, foreground = true, pushed = true))
        assertEquals(300_000L, RemotePollPolicy.intervalMs(playing = false, foreground = false, pushed = true))
    }

    @Test
    fun `failed reads back off instead of retrying every tick`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map(RemotePollPolicy::retryAfterMs))
        assertTrue(RemotePollPolicy.STALE_AFTER_FAILURES in 2..5)
    }
}
