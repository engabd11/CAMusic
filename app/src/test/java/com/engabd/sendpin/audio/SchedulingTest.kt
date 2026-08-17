package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long to wait before writing the head of a stream.
 *
 * The buffered term is the whole reason this is a separate function. While a track
 * was rebuilt for every stream it was always empty at that moment, so waiting until
 * the frame's own timestamp was right. Now that a track survives a track change, a
 * sample handed to it does not reach the speaker until everything queued ahead of it
 * has played — so scheduling against the raw timestamp would put every continuation
 * exactly one buffer late, on every track, forever.
 */
class SchedulingTest {

    private val sched = SendspinPlaybackSupport.Scheduling

    /** A fresh track holds nothing, so this must behave exactly as it always did. */
    @Test
    fun `an empty track schedules against the raw timestamp`() {
        val wait = sched.waitUs(headLocalUs = 5_000_000, nowUs = 4_000_000, bufferedUs = 0, staticDelayUs = 0)
        assertEquals(1_000_000, wait)
    }

    @Test
    fun `queued audio comes off the wait one for one`() {
        val empty = sched.waitUs(5_000_000, 4_000_000, bufferedUs = 0, staticDelayUs = 0)
        val full = sched.waitUs(5_000_000, 4_000_000, bufferedUs = 1_000_000, staticDelayUs = 0)
        assertEquals(empty - 1_000_000, full)
        assertEquals(0, full)
    }

    /**
     * Positive trim means "this output adds latency", so the frame is written
     * earlier — the wait shrinks. This is the spec's sense, not the "delay this
     * speaker" sense Music Assistant's own control reads in.
     */
    @Test
    fun `a positive static delay plays earlier`() {
        val none = sched.waitUs(5_000_000, 4_000_000, bufferedUs = 0, staticDelayUs = 0)
        val trimmed = sched.waitUs(5_000_000, 4_000_000, bufferedUs = 0, staticDelayUs = 250_000)
        assertTrue(trimmed < none)
        assertEquals(750_000, trimmed)
    }

    /**
     * A head whose moment has already passed once the queued audio is counted comes
     * back negative, which is what lets the caller apply its late-drop tolerance.
     */
    @Test
    fun `a head behind the drain point goes negative`() {
        val wait = sched.waitUs(5_000_000, 4_900_000, bufferedUs = 500_000, staticDelayUs = 0)
        assertTrue(wait < 0, "expected a negative wait, got $wait")
        assertEquals(-400_000, wait)
    }

    @Test
    fun `buffered and static delay both apply`() {
        assertEquals(
            250_000,
            sched.waitUs(5_000_000, 4_000_000, bufferedUs = 500_000, staticDelayUs = 250_000),
        )
    }
}
