package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The driving floating window: when it opens by itself, and which buttons it carries. */
class DrivingPipTest {

    @Test
    fun `the play button says pause while playing and play while paused`() {
        assertEquals(
            listOf(DrivingPip.CONTROL_PREV, DrivingPip.CONTROL_PAUSE, DrivingPip.CONTROL_NEXT),
            DrivingPip.controlsFor(playing = true),
        )
        assertEquals(
            listOf(DrivingPip.CONTROL_PREV, DrivingPip.CONTROL_PLAY, DrivingPip.CONTROL_NEXT),
            DrivingPip.controlsFor(playing = false),
        )
    }

    @Test
    fun `a drive starting with the app in front opens the window`() {
        val gate = DrivingPip.EntryGate()
        assertFalse(gate.onDriving(active = false, resumed = true))
        assertTrue(gate.onDriving(active = true, resumed = true))
    }

    @Test
    fun `coming back into the app during a drive does not bounce it back into the window`() {
        val gate = DrivingPip.EntryGate()
        gate.onDriving(active = false, resumed = true)
        assertTrue(gate.onDriving(active = true, resumed = true))
        // The window's expand button, or the launcher icon: the activity resumes and
        // the still-true driving state is delivered again.
        assertFalse(gate.onDriving(active = true, resumed = true))
        assertFalse(gate.onDriving(active = true, resumed = true))
    }

    @Test
    fun `a drive that began in the background does not open the window when the app is opened`() {
        val gate = DrivingPip.EntryGate()
        // The car connected while the app was in the background.
        assertFalse(gate.onDriving(active = true, resumed = false))
        // The user opens the app mid-drive.
        assertFalse(gate.onDriving(active = true, resumed = true))
    }

    @Test
    fun `the next drive opens the window again`() {
        val gate = DrivingPip.EntryGate()
        assertTrue(gate.onDriving(active = true, resumed = true))
        assertFalse(gate.onDriving(active = false, resumed = true))
        assertTrue(gate.onDriving(active = true, resumed = true))
    }
}
