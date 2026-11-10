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

    @Test
    fun `a late scan takes over a locked beat only where one of its sections starts`() {
        val sections = listOf(0f, 31.5f, 64.2f)
        // Mid-phrase: the live beat is kept.
        assertFalse(MapAdoption.lateHandover(40f, 40.02f, sections, trackerLocked = true, positionTrusted = true))
        // The frame that reaches the next section start hands over.
        assertTrue(MapAdoption.lateHandover(64.19f, 64.21f, sections, trackerLocked = true, positionTrusted = true))
        // And only that frame: the one after is mid-section again.
        assertFalse(MapAdoption.lateHandover(64.21f, 64.23f, sections, trackerLocked = true, positionTrusted = true))
    }

    @Test
    fun `a late scan replaces a tracker that never locked at once`() {
        assertTrue(MapAdoption.lateHandover(40f, 40.02f, listOf(0f, 64.2f), trackerLocked = false, positionTrusted = true))
    }

    @Test
    fun `no late handover on a session clock, at a jump back, or before a first frame`() {
        val sections = listOf(0f, 31.5f)
        assertFalse(MapAdoption.lateHandover(31.49f, 31.51f, sections, trackerLocked = true, positionTrusted = false))
        assertFalse(MapAdoption.lateHandover(31.49f, 31.51f, sections, trackerLocked = false, positionTrusted = false))
        assertFalse(MapAdoption.lateHandover(200f, 0.02f, sections, trackerLocked = true, positionTrusted = true))
        assertFalse(MapAdoption.lateHandover(null, 31.5f, sections, trackerLocked = true, positionTrusted = true))
    }

    @Test
    fun `after a seek a late scan is decided afresh`() {
        // The seek resets a "no" to undecided; the scan now in hand is adopted at once
        // on the player's own clock.
        assertEquals(true, MapAdoption.decide(null, hasScan = true, posS = 90f, stretchStartS = 90f, positionTrusted = true))
    }
}
