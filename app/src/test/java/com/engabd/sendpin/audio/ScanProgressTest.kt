package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the analysis card is allowed to say about itself.
 *
 * The states matter because they used to be indistinguishable. A sweep parked on a
 * metered connection, a sweep that had genuinely finished, and a sweep that had failed
 * on every file all produced the same thing on screen: a count that had stopped moving.
 */
class ScanProgressTest {

    @Test
    fun `idle is neither busy nor waiting`() {
        val idle = ScanProgress()
        assertFalse(idle.busy)
        assertFalse(idle.waitingForNetwork)
        assertNull(idle.sweepFraction)
    }

    @Test
    fun `parked with nothing running is waiting for the network, not idle`() {
        val parked = ScanProgress(parked = 12, sweepTotal = 40, sweepDone = 28)
        assertTrue(parked.waitingForNetwork)
        assertFalse(parked.busy)
    }

    @Test
    fun `parked while another track is still being read is not yet waiting`() {
        // Something is still moving, so "waiting for Wi-Fi" would be the wrong headline.
        val working = ScanProgress(current = "Cinnamon Girl", parked = 3)
        assertFalse(working.waitingForNetwork)
        assertTrue(working.busy)
    }

    @Test
    fun `the sweep fraction is how far through it is`() {
        assertEquals(0.25f, ScanProgress(sweepTotal = 40, sweepDone = 10).sweepFraction)
        assertEquals(1f, ScanProgress(sweepTotal = 40, sweepDone = 40).sweepFraction)
    }

    @Test
    fun `a sweep with no total has no fraction to report`() {
        // Enumerating the library is itself work; a bar at zero-of-zero would read as
        // stuck rather than as starting.
        assertNull(ScanProgress(sweeping = true).sweepFraction)
    }

    @Test
    fun `the fraction never runs past the end`() {
        assertEquals(1f, ScanProgress(sweepTotal = 10, sweepDone = 14).sweepFraction)
    }

    @Test
    fun `failures separate what is worth retrying from what is settled`() {
        val progress = ScanProgress(
            failed = 3,
            failures = listOf(
                ScanFailureNote("A", "could not fetch the audio", retryable = true),
                ScanFailureNote("B", "it is silent", retryable = false),
                ScanFailureNote("C", "too short to analyse", retryable = false),
            ),
        )
        assertEquals(1, progress.failures.count { it.retryable })
    }
}
