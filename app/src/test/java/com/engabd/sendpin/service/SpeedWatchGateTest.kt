package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GPS battery gate is the whole point of the strict refactor: a location
 * subscription may exist only while a speed feature is on *and* the designated car
 * Bluetooth device is connected — [SpeedWatchGate.shouldWatch] is that whole
 * policy, and the first four rows below are all of it.
 *
 * [SpeedWatchGate.state] answers the same question and says *which* condition is
 * missing, which is what the settings screen reports; the rest of the rows are
 * that, including the case the reported bug was — the alert on, no car ever
 * nominated, and therefore no possible location subscription at any speed.
 */
class SpeedWatchGateTest {

    @Test
    fun `gps runs with a feature on and the car linked`() {
        assertTrue(SpeedWatchGate.shouldWatch(featureOn = true, carLinked = true))
    }

    @Test
    fun `no speed feature means no gps even in the car`() {
        assertFalse(SpeedWatchGate.shouldWatch(featureOn = false, carLinked = true))
    }

    @Test
    fun `no designated car link means no gps whatever else is happening`() {
        // The cases the old gate accepted and the new one refuses: music playing,
        // a manual tile tap, the app holding a media session — none of them is a
        // connected car, so none of them starts GPS.
        assertFalse(SpeedWatchGate.shouldWatch(featureOn = true, carLinked = false))
    }

    @Test
    fun `nothing running at all`() {
        assertFalse(SpeedWatchGate.shouldWatch(featureOn = false, carLinked = false))
    }

    // ── Why it is not watching ──────────────────────────────────────────────
    //
    // The three ways to be idle used to be one silence, and the settings screen
    // guessed at which one it was with a sentence describing the *old* gate. These
    // are what it now says instead, so the wrong one cannot be shown.

    @Test
    fun `a feature on and the car linked is watching`() {
        assertEquals(
            SpeedWatchGate.State.WATCHING,
            SpeedWatchGate.state(featureOn = true, carNominated = true, carLinked = true),
        )
    }

    @Test
    fun `no feature outranks everything else`() {
        // Nothing to tell the driver about a car they have not asked to be watched in.
        assertEquals(
            SpeedWatchGate.State.NO_FEATURE,
            SpeedWatchGate.state(featureOn = false, carNominated = true, carLinked = true),
        )
        assertEquals(
            SpeedWatchGate.State.NO_FEATURE,
            SpeedWatchGate.state(featureOn = false, carNominated = false, carLinked = false),
        )
    }

    @Test
    fun `a feature on with no car nominated is the dead end`() {
        // The reported bug: the alert switched on, no car ever picked, and so no
        // location subscription is possible at any speed. This is the case that has
        // to be distinguishable from "waiting for the car" — one is a drive that has
        // not started, the other is a setting that has to be gone and changed.
        assertEquals(
            SpeedWatchGate.State.NO_CAR,
            SpeedWatchGate.state(featureOn = true, carNominated = false, carLinked = false),
        )
    }

    @Test
    fun `a nominated car that is not connected is the ordinary idle`() {
        assertEquals(
            SpeedWatchGate.State.CAR_AWAY,
            SpeedWatchGate.state(featureOn = true, carNominated = true, carLinked = false),
        )
    }

    @Test
    fun `a link without a nomination is reported as no car, not as watching`() {
        // Not reachable through DrivingMode — the link is *to* the nominated address
        // — but the ordering must not let a stale flag start a watch for a car that
        // is no longer picked.
        assertEquals(
            SpeedWatchGate.State.NO_CAR,
            SpeedWatchGate.state(featureOn = true, carNominated = false, carLinked = true),
        )
    }

    @Test
    fun `state agrees with shouldWatch on every combination`() {
        for (feature in listOf(false, true)) {
            for (nominated in listOf(false, true)) {
                for (linked in listOf(false, true)) {
                    val watching =
                        SpeedWatchGate.state(feature, nominated, linked) == SpeedWatchGate.State.WATCHING
                    // The one disagreement is the unreachable row above, where a link
                    // without a nomination is refused rather than honoured.
                    val expected = SpeedWatchGate.shouldWatch(feature, linked) && nominated
                    assertEquals(expected, watching, "feature=$feature nominated=$nominated linked=$linked")
                }
            }
        }
    }
}
