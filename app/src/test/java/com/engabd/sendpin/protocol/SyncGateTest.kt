package com.engabd.sendpin.protocol

import com.engabd.sendpin.audio.SendspinAudioEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the player tells the server about itself while its clock is converging.
 *
 * Reporting "synchronized" from the moment the socket opens — which is what this
 * replaced — tells the server the player is ready to join a group while its offset
 * is still seconds wide. In a group that is audible.
 */
class SyncGateTest {

    @Test
    fun `a converged clock reports synchronized and plays`() {
        val d = SyncGate.decide(clockReady = true, unreadyMs = 0)
        assertEquals(SyncGate.Report.SYNCHRONIZED, d.report)
        assertFalse(d.muted)
    }

    @Test
    fun `an unconverged clock reports error and mutes`() {
        val d = SyncGate.decide(clockReady = false, unreadyMs = 500)
        assertEquals(SyncGate.Report.ERROR, d.report)
        assertTrue(d.muted)
    }

    /**
     * The escape hatch. A solo player has nothing to be out of step with, so once
     * the offset is clearly not coming, silence is the worse answer of the two.
     */
    @Test
    fun `past the deadline it stops muting but keeps telling the truth`() {
        val d = SyncGate.decide(clockReady = false, unreadyMs = SyncGate.MAX_MUTE_MS)
        assertEquals(SyncGate.Report.ERROR, d.report)
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
        assertEquals(SendspinAudioEngine.HeadGate.MAX_STALL_MS, SyncGate.MAX_MUTE_MS)
    }
}
