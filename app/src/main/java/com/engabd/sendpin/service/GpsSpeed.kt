package com.engabd.sendpin.service

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns location fixes into a speed the driver can trust, or into nothing.
 *
 * Split out of [SpeedMonitor] for the same reason [SpeedAlert] was: the decision is
 * the part that went wrong, and it cannot be tested while it is welded to
 * `Location`, whose maths is a stub off-device.
 *
 * ## The bug this exists for
 *
 * A driver doing 45 km/h was alerted at 1100 km/h and then 400 km/h. The
 * subscription was taking the fused provider at its default *balanced* quality,
 * which hands over Wi-Fi and cell positions (±87 m on the reporting phone) with no
 * speed attached, mixed in with GPS fixes whenever another app had GPS running.
 * With no speed on the fix, the monitor differenced it against the one before: a
 * GPS fix, then a network fix 300 m off a second later, is 1100 km/h. Two of those
 * two seconds apart satisfied the alert's confirmation window on their own.
 *
 * So a derived speed is now only taken from two fixes whose positions are good
 * enough, over a baseline long enough, that their own error cannot fake more than
 * [MAX_DERIVED_ERROR_MS] of speed — and no reading of any kind gets through if a car
 * could not physically produce it.
 */
object GpsSpeed {

    /**
     * One location fix, as plain values.
     *
     * [elapsedMs] is on the monotonic elapsed-realtime clock, not wall-clock time:
     * a fix's UTC time comes from whichever source produced it, and GPS and network
     * time disagreeing by a second is a second's worth of fake speed.
     */
    data class Fix(
        val elapsedMs: Long,
        val lat: Double,
        val lon: Double,
        /** Horizontal accuracy in metres (68% radius), or null when not reported. */
        val accuracyM: Float?,
        /** The provider's own speed in m/s, or null when the fix has none. */
        val speedMs: Float? = null,
        /** The provider's speed accuracy in m/s, or null when not reported. */
        val speedAccuracyMs: Float? = null,
    )

    /**
     * Stateful, because both a derived speed and the plausibility check depend on
     * what came before, but every input is a plain value so a test can step it.
     */
    class Estimator {
        /** The fix a derived speed is measured from. Advanced only once it has been used. */
        private var anchor: Fix? = null
        private var lastSpeedMs: Float? = null
        private var lastSpeedAtMs = 0L

        fun reset() {
            anchor = null
            lastSpeedMs = null
        }

        /** @return the speed in km/h this fix supports, or null if it supports none. */
        fun onFix(fix: Fix, nowElapsedMs: Long): Float? {
            // A cached fix handed over when the subscription starts, or when the
            // platform lets a backgrounded app have locations again, is a position
            // from the past. Its speed is history, not a reading.
            if (nowElapsedMs - fix.elapsedMs > MAX_FIX_AGE_MS) return null
            val goodPosition = fix.accuracyM != null && fix.accuracyM <= MAX_DERIVE_ACCURACY_M

            val reported = fix.speedMs?.takeIf { speed ->
                speed >= 0f && (fix.speedAccuracyMs == null || fix.speedAccuracyMs <= MAX_SPEED_ACCURACY_MS)
            }
            val speedMs = if (reported != null) {
                // A Doppler speed is good whatever the position error, so the anchor
                // only moves to a fix a later derivation could actually use.
                if (goodPosition) anchor = fix
                reported
            } else {
                derive(fix, goodPosition) ?: return null
            }
            return accept(speedMs, fix.elapsedMs)?.let { it * 3.6f }
        }

        private fun derive(fix: Fix, goodPosition: Boolean): Float? {
            // A coarse fix is neither a reading nor a point to measure the next one
            // from — the old code made it both.
            if (!goodPosition) return null
            val from = anchor
            if (from == null) {
                anchor = fix
                return null
            }
            val gapMs = fix.elapsedMs - from.elapsedMs
            if (gapMs <= 0L) return null
            if (gapMs > MAX_DERIVE_GAP_MS) {
                // A resumed subscription, not a journey. Start measuring again.
                anchor = fix
                return null
            }
            val gapS = gapMs / 1000f
            // Each fix may be out by its accuracy, so the pair may be out by both.
            // Over too short a baseline that alone is a fast car; the anchor is kept
            // and the baseline simply grows until it is not.
            val noiseM = from.accuracyM!! + fix.accuracyM!!
            if (gapMs < MIN_DERIVE_GAP_MS || noiseM / gapS > MAX_DERIVED_ERROR_MS) return null
            anchor = fix
            return (distanceMeters(from.lat, from.lon, fix.lat, fix.lon) / gapS).toFloat()
        }

        /** The physical sanity check every reading passes through, whatever its source. */
        private fun accept(speedMs: Float, atMs: Long): Float? {
            if (speedMs * 3.6f > MAX_PLAUSIBLE_KMH) return null
            val previous = lastSpeedMs
            val sinceMs = atMs - lastSpeedAtMs
            if (previous != null && sinceMs in 1..ACCEL_CHECK_WINDOW_MS) {
                // Rejected readings do not move the reference, but it only holds for
                // the window: a wrong reading that slipped through cannot lock out the
                // right ones for longer than that.
                if (abs(speedMs - previous) / (sinceMs / 1000f) > MAX_ACCEL_MS2) return null
            }
            lastSpeedMs = speedMs
            lastSpeedAtMs = atMs
            return speedMs
        }
    }

    /** Great-circle distance in metres. `Location.distanceBetween` is a stub in unit tests. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** A fix older than this when it arrives is a cached one. */
    const val MAX_FIX_AGE_MS = 5_000L

    /** Coarser than this (a Wi-Fi or cell position) is not differenced at all. */
    const val MAX_DERIVE_ACCURACY_M = 20f

    /** How much speed the two fixes' position error may account for: 5 m/s, 18 km/h. */
    const val MAX_DERIVED_ERROR_MS = 5f

    /** Two fixes closer together than this cannot be differenced usefully. */
    const val MIN_DERIVE_GAP_MS = 300L

    /** Two fixes further apart than this are a resumed subscription, not a journey. */
    const val MAX_DERIVE_GAP_MS = 30_000L

    /** A reported speed less certain than this (m/s) is treated as no speed. */
    const val MAX_SPEED_ACCURACY_MS = 5f

    /** Nothing on a public road goes faster. */
    const val MAX_PLAUSIBLE_KMH = 300f

    /** Past a sports car's braking, never mind its acceleration. */
    const val MAX_ACCEL_MS2 = 12f

    /** How long one accepted reading is used to judge the next. */
    const val ACCEL_CHECK_WINDOW_MS = 5_000L
}
