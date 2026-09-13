package com.engabd.sendpin.protocol

import com.engabd.sendpin.audio.SendspinPlaybackSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the player mutes itself while its clock is converging. Local policy only:
 * nothing here goes on the wire any more (see [SyncGate]).
 */
class SyncGateTest {

    @Test
    fun `a converged clock plays`() {
        val d = SyncGate.decide(clockReady = true, unreadyMs = 0)
        assertFalse(d.muted)
    }

    @Test
    fun `an unconverged clock mutes`() {
        val d = SyncGate.decide(clockReady = false, unreadyMs = 500)
        assertTrue(d.muted)
    }

    /**
     * The escape hatch. A solo player has nothing to be out of step with, so once
     * the offset is clearly not coming, silence is the worse answer of the two.
     */
    @Test
    fun `past the deadline it stops muting`() {
        val d = SyncGate.decide(clockReady = false, unreadyMs = SyncGate.MAX_MUTE_MS)
        assertFalse(d.muted, "permanent silence is worse than being ungrouped")
    }

    @Test
    fun `the deadline is exclusive at the boundary`() {
        assertTrue(SyncGate.decide(false, SyncGate.MAX_MUTE_MS - 1).muted)
        assertFalse(SyncGate.decide(false, SyncGate.MAX_MUTE_MS).muted)
    }

    /**
     * These two deadlines have to stay equal. The head gate releases the first frame
     * of a stream once it gives up waiting for the clock; if the mute outlived that,
     * the opening of the song would be released into a silent track and simply lost.
     */
    @Test
    fun `the mute deadline matches the head gate's`() {
        assertEquals(SendspinPlaybackSupport.HeadGate.MAX_STALL_MS, SyncGate.MAX_MUTE_MS)
    }
}
