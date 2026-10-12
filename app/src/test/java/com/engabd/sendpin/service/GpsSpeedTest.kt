package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reported case first: a 45 km/h drive alerted at 1100 km/h and 400 km/h,
 * because a Wi-Fi/cell fix tens of metres off was differenced against a GPS fix a
 * second earlier. Then the rules that keep a derived speed honest.
 */
class GpsSpeedTest {

    private val lat0 = -37.894521
    private val lon0 = 144.628036
    private val metresPerDegLat = 111_195.0

    /** A fix [northM] metres north of the start, at [atMs]. */
    private fun fix(
        atMs: Long,
        northM: Double,
        accuracyM: Float? = 4f,
        speedMs: Float? = null,
        speedAccuracyMs: Float? = null,
    ) = GpsSpeed.Fix(atMs, lat0 + northM / metresPerDegLat, lon0, accuracyM, speedMs, speedAccuracyMs)

    @Test
    fun `a network fix between gps fixes is not a speed`() {
        val est = GpsSpeed.Estimator()
        // 45 km/h is 12.5 m/s. GPS for a few seconds, no speed on the fixes.
        est.onFix(fix(0, 0.0), 0)
        est.onFix(fix(2_000, 25.0), 2_000)
        // Then a network fix 300 m off a second later — the 1100 km/h reading.
        assertNull(est.onFix(fix(3_000, 337.5, accuracyM = 87f), 3_000))
        // And the next good GPS fix is measured from the last good one, not from it.
        val kmh = assertNotNull(est.onFix(fix(4_000, 50.0), 4_000))
        assertEquals(45f, kmh, 1f)
    }

    @Test
    fun `reported doppler speed is used as is`() {
        val est = GpsSpeed.Estimator()
        val kmh = est.onFix(fix(0, 0.0, speedMs = 12.5f, speedAccuracyMs = 0.3f), 0)
        assertEquals(45f, assertNotNull(kmh), 0.01f)
    }

    @Test
    fun `reported speed past anything on a road is refused`() {
        val est = GpsSpeed.Estimator()
        assertNull(est.onFix(fix(0, 0.0, speedMs = 1100f / 3.6f), 0))
    }

    @Test
    fun `a jump no car could accelerate to is refused, then real readings resume`() {
        val est = GpsSpeed.Estimator()
        assertNotNull(est.onFix(fix(0, 0.0, speedMs = 12.5f), 0))
        // 45 → 200 km/h in a second: under the ceiling, past any car.
        assertNull(est.onFix(fix(1_000, 12.5, speedMs = 200f / 3.6f), 1_000))
        assertEquals(46f, assertNotNull(est.onFix(fix(2_000, 25.0, speedMs = 46f / 3.6f), 2_000)), 0.01f)
    }

    @Test
    fun `a stale cached fix is not a reading`() {
        val est = GpsSpeed.Estimator()
        assertNull(est.onFix(fix(0, 0.0, speedMs = 12.5f), nowElapsedMs = 60_000))
    }

    @Test
    fun `fixes too close together for their accuracy wait for a longer baseline`() {
        val est = GpsSpeed.Estimator()
        // ±15 m each: 30 m of possible error needs six seconds to be under 5 m/s.
        est.onFix(fix(0, 0.0, accuracyM = 15f), 0)
        assertNull(est.onFix(fix(1_000, 12.5, accuracyM = 15f), 1_000))
        assertNull(est.onFix(fix(3_000, 37.5, accuracyM = 15f), 3_000))
        val kmh = assertNotNull(est.onFix(fix(6_000, 75.0, accuracyM = 15f), 6_000))
        assertEquals(45f, kmh, 0.5f)
    }

    @Test
    fun `good gps fixes a second apart give a derived speed`() {
        val est = GpsSpeed.Estimator()
        est.onFix(fix(0, 0.0, accuracyM = 2f), 0)
        val kmh = assertNotNull(est.onFix(fix(1_000, 12.5, accuracyM = 2f), 1_000))
        assertEquals(45f, kmh, 0.5f)
    }

    @Test
    fun `a gap past the window restarts measuring`() {
        val est = GpsSpeed.Estimator()
        est.onFix(fix(0, 0.0), 0)
        assertNull(est.onFix(fix(40_000, 500.0), 40_000))
        assertNotNull(est.onFix(fix(42_000, 525.0), 42_000))
    }

    @Test
    fun `an uncertain reported speed falls back to positions`() {
        val est = GpsSpeed.Estimator()
        est.onFix(fix(0, 0.0, accuracyM = 2f), 0)
        val kmh = assertNotNull(est.onFix(fix(1_000, 12.5, accuracyM = 2f, speedMs = 80f, speedAccuracyMs = 20f), 1_000))
        assertEquals(45f, kmh, 0.5f)
    }

    @Test
    fun `distance matches a known span`() {
        // One degree of latitude.
        val d = GpsSpeed.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue(d in 111_100.0..111_300.0, "$d")
    }
}
