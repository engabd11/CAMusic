package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the sonic index reads every library again, and when it does not. */
class LocalSonicIndexRebuildTest {

    private val min = 60_000L

    @Test
    fun `nothing changed, no re-read, however often it is asked`() {
        assertFalse(LocalSonicIndex.needsRebuild(ageMs = 30 * min, builtFromScans = 500, scansNow = 500))
        assertFalse(LocalSonicIndex.needsRebuild(ageMs = 5 * 60 * min, builtFromScans = 500, scansNow = 500))
    }

    @Test
    fun `new scans are picked up, but not more often than every ten minutes`() {
        assertFalse(LocalSonicIndex.needsRebuild(ageMs = 2 * min, builtFromScans = 500, scansNow = 520))
        assertTrue(LocalSonicIndex.needsRebuild(ageMs = 10 * min, builtFromScans = 500, scansNow = 520))
        assertTrue(LocalSonicIndex.needsRebuild(ageMs = 10 * min, builtFromScans = 500, scansNow = 0), "analyses deleted")
    }

    @Test
    fun `a long-lived index is re-read for tracks a library dropped`() {
        assertTrue(LocalSonicIndex.needsRebuild(ageMs = 6 * 60 * min, builtFromScans = 500, scansNow = 500))
    }
}
