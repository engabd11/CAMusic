package com.engabd.sendpin.service

import com.engabd.sendpin.service.PlaybackOwner.Companion.yieldStep
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Media keys follow whichever player played most recently. */
class PlaybackOwnerYieldTest {

    @Test
    fun `a paused local queue yields when music assistant starts playing`() {
        assertTrue(yieldStep(false, localActive = true, localPlaying = false, maStarted = true, remoteStarted = false))
        assertTrue(yieldStep(false, localActive = true, localPlaying = false, maStarted = false, remoteStarted = true))
    }

    @Test
    fun `it stays yielded after both are paused, so the last player keeps the keys`() {
        assertTrue(yieldStep(true, localActive = true, localPlaying = false, maStarted = false, remoteStarted = false))
    }

    @Test
    fun `playing the local queue takes the session back`() {
        assertFalse(yieldStep(true, localActive = true, localPlaying = true, maStarted = false, remoteStarted = false))
    }

    @Test
    fun `a playing local queue never yields, even if a speaker elsewhere starts`() {
        assertFalse(yieldStep(false, localActive = true, localPlaying = true, maStarted = false, remoteStarted = true))
    }

    @Test
    fun `no local queue means nothing to yield`() {
        assertFalse(yieldStep(true, localActive = false, localPlaying = false, maStarted = true, remoteStarted = false))
    }

    @Test
    fun `a paused queue nobody overtook keeps the session`() {
        assertFalse(yieldStep(false, localActive = true, localPlaying = false, maStarted = false, remoteStarted = false))
    }
}
