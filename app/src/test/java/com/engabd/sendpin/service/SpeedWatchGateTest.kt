package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GPS battery gate is the whole point of the strict refactor: a location
 * subscription may exist only while a speed feature is on *and* the designated car
 * Bluetooth device is connected. These four rows are the entire policy.
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
}
