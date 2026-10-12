package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One lookup used to be the limit. These are the cases that made that read as
 * wrong zone data: a junction, a parallel road, a fix off the edge of the map.
 */
class DetectedLimitFilterTest {

    @Test
    fun `first limit found is taken at once`() {
        assertEquals(60, DetectedLimitFilter().onLookup(60))
    }

    @Test
    fun `one lookup on the cross street does not swap the limit`() {
        val f = DetectedLimitFilter()
        f.onLookup(60)
        assertEquals(60, f.onLookup(80))
        assertEquals(60, f.onLookup(60))
        assertEquals(60, f.onLookup(80))
    }

    @Test
    fun `a real zone change takes over on the second agreeing lookup`() {
        val f = DetectedLimitFilter()
        f.onLookup(60)
        assertEquals(60, f.onLookup(40))
        assertEquals(40, f.onLookup(40))
    }

    @Test
    fun `two different stray answers do not combine into a change`() {
        val f = DetectedLimitFilter()
        f.onLookup(60)
        f.onLookup(80)
        assertEquals(60, f.onLookup(100))
    }

    @Test
    fun `a miss or two keeps the known limit, a run of them drops it`() {
        val f = DetectedLimitFilter()
        f.onLookup(60)
        assertEquals(60, f.onLookup(null))
        assertEquals(60, f.onLookup(null))
        assertEquals(60, f.onLookup(60))
        repeat(DetectedLimitFilter.MISSES_TO_DROP - 1) { f.onLookup(null) }
        assertNull(f.onLookup(null))
    }

    @Test
    fun `a miss breaks a pending change`() {
        val f = DetectedLimitFilter()
        f.onLookup(60)
        f.onLookup(40)
        f.onLookup(null)
        assertEquals(60, f.onLookup(40))
    }
}
