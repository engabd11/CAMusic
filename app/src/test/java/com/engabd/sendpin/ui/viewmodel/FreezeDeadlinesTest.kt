package com.engabd.sendpin.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek freeze's deadline, which has to be chosen by *what is going to confirm the
 * seek* rather than by the fact that it is a seek.
 *
 * The bug these pin: a 2.5 s watchdog on the self-player path expired before the audio
 * it was waiting for could possibly exist, so the bar was handed back to Music
 * Assistant's clock — which runs ahead of this phone's own output — and spent the rest
 * of the track about two seconds in front of the sound.
 */
class FreezeDeadlinesTest {

    /**
     * Measured on-device and recorded in `docs/ma-playhead-rewrite-plan.md` §3: about
     * 1.25 s from the tap to `stream/start`, and about 1.64 s more before the first
     * PCM reaches the output ring.
     */
    private val measuredTimeToFirstSoundMs = 1_250L + 1_640L

    @Test
    fun `a seek on this phone waits longer than it can take to hear the audio`() {
        val deadline = FreezeDeadlines.forSeek(selfPlayer = true)
        assertTrue(
            "the freeze must outlast the ${measuredTimeToFirstSoundMs}ms it takes to " +
                "become audible, or the watchdog releases the bar into silence",
            deadline > measuredTimeToFirstSoundMs,
        )
    }

    @Test
    fun `a seek on a remote player keeps the short deadline`() {
        // Nothing local confirms a remote seek, so the poll does — and MA publishes the
        // target before it rebuilds the stream. Waiting longer only prolongs the lie
        // told by a seek that was refused.
        assertEquals(FreezeDeadlines.REMOTE_SEEK_MS, FreezeDeadlines.forSeek(selfPlayer = false))
        assertTrue(FreezeDeadlines.REMOTE_SEEK_MS < FreezeDeadlines.DEFAULT_MS)
    }

    @Test
    fun `the self-player seek deadline is the ordinary one`() {
        // A seek on this phone has to wait out a stream restart exactly as a skip does,
        // so it gets the same allowance rather than a third number to keep in step.
        assertEquals(FreezeDeadlines.DEFAULT_MS, FreezeDeadlines.forSeek(selfPlayer = true))
    }

    @Test
    fun `every deadline is a liveness backstop, never long enough to wedge the bar`() {
        for (selfPlayer in listOf(true, false)) {
            val deadline = FreezeDeadlines.forSeek(selfPlayer)
            assertTrue("deadline must be positive", deadline > 0)
            assertTrue("a stuck bar past this is worse than a wrong one", deadline <= 10_000L)
        }
    }
}
