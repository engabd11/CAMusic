package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a track's scan is allowed to drive the room: from any position when the scan
 * is in hand and the position is exact, never across an item boundary the player has
 * not caught up with.
 */
class MapAdoptionTest {

    @Test
    fun `a cached scan is adopted mid-track when the position is the player's own`() {
        // Light Sync switched on two minutes in, or a resume, or a seek.
        assertEquals(true, MapAdoption.decide(null, hasScan = true, posS = 120f, stretchStartS = 120f, positionTrusted = true))
    }

    @Test
    fun `a guessed position keeps the six-second window from the top of the track`() {
        assertEquals(false, MapAdoption.decide(null, hasScan = true, posS = 120f, stretchStartS = 120f, positionTrusted = false))
        assertEquals(true, MapAdoption.decide(null, hasScan = true, posS = 3f, stretchStartS = 0f, positionTrusted = false))
    }

    @Test
    fun `a scan arriving after the PLL has had its six seconds is left for the next play`() {
        assertEquals(false, MapAdoption.decide(null, hasScan = true, posS = 127f, stretchStartS = 120f, positionTrusted = true))
        assertNull(MapAdoption.decide(null, hasScan = false, posS = 125f, stretchStartS = 120f, positionTrusted = true))
        assertEquals(false, MapAdoption.decide(null, hasScan = false, posS = 126.1f, stretchStartS = 120f, positionTrusted = true))
    }

    @Test
    fun `a decision once made is kept`() {
        assertEquals(false, MapAdoption.decide(false, hasScan = true, posS = 1f, stretchStartS = 0f, positionTrusted = true))
        assertEquals(true, MapAdoption.decide(true, hasScan = true, posS = 300f, stretchStartS = 0f, positionTrusted = true))
    }

    @Test
    fun `a gapless transition is a crossing`() {
        // The tap's position runs from the end of one item straight to the start of
        // the next, with no flush in between.
        assertTrue(MapAdoption.crossedBoundary(prevS = 245.3f, posS = 0.02f))
        // The last hop of the old track can even read slightly negative.
        assertTrue(MapAdoption.crossedBoundary(prevS = 245.3f, posS = -0.04f))
    }

    @Test
    fun `ordinary playback is not a crossing`() {
        assertFalse(MapAdoption.crossedBoundary(prevS = null, posS = 0f))
        assertFalse(MapAdoption.crossedBoundary(prevS = 10f, posS = 10.02f))
        // A position read a few milliseconds back is jitter, not a new track.
        assertFalse(MapAdoption.crossedBoundary(prevS = 10f, posS = 9.99f))
    }
}
