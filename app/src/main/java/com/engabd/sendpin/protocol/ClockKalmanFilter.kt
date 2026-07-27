package com.engabd.sendpin.protocol

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 2-D Kalman filter for NTP-style Sendspin time synchronisation: tracks both the
 * client↔server clock **offset** and **drift** rate from `client/time` ↔
 * `server/time` round-trips, so audio can be scheduled sample-accurately across
 * grouped players.
 *
 * Clean-room Kotlin port of the algorithm in the Sendspin reference `sendspin-js`
 * (`time-filter.ts`) — the same filter shipped by the massdroid client
 * (MIT, github.com/sfortis/massdroid_native) and MA's own mobile app (Apache-2.0),
 * both studied as references. Pure Kotlin (no Android/coroutine deps) so it is
 * unit-tested on the JVM.
 *
 * Feed each `server/time` reply into [processTimeResponse] with the four NTP
 * timestamps in microseconds (the client stamps **T4** at the WebSocket
 * `onMessage` callback — stamping later biases the offset and plays late), then
 * use [serverToLocalUs] to place server-scheduled audio on the local clock.
 */
class ClockKalmanFilter {

    private companion object {
        const val ADAPTIVE_FORGETTING_CUTOFF = 2.0
        const val OFFSET_PROCESS_STD_DEV = 0.01
        const val FORGET_FACTOR = 1.1
        const val DRIFT_SIGNIFICANCE_THRESHOLD = 2.0
        const val MAX_DRIFT = 0.999          // guards the (1 + drift) divisor
        const val READY_MIN_SAMPLES = 8
        const val READY_MAX_ERROR_US = 5_000L
    }

    private var offset = 0.0
    private var drift = 0.0
    private var offsetCovariance = Double.MAX_VALUE
    private var offsetDriftCovariance = 0.0
    private var driftCovariance = 0.0
    private var lastUpdateUs = 0L
    private var count = 0
    private var useDrift = false
    private var lastRttUs = 0L

    private val offsetProcessVariance = OFFSET_PROCESS_STD_DEV * OFFSET_PROCESS_STD_DEV
    private val forgetVarianceFactor = FORGET_FACTOR * FORGET_FACTOR
    private val driftSignificanceSquared = DRIFT_SIGNIFICANCE_THRESHOLD * DRIFT_SIGNIFICANCE_THRESHOLD

    val sampleCount: Int get() = count

    fun nowMonotonicUs(): Long = System.nanoTime() / 1000
    fun lastRttUs(): Long = lastRttUs
    fun errorUs(): Long = round(sqrt(offsetCovariance.coerceAtLeast(0.0))).toLong()
    fun isSynced(): Boolean = count >= 1 && offsetCovariance < Double.MAX_VALUE
    fun driftPpm(): Double = if (useDrift) drift * 1_000_000.0 else 0.0

    /** Safe to start grouped playback: enough low-error samples have converged. */
    fun isReadyForPlaybackStart(): Boolean =
        count >= READY_MIN_SAMPLES && errorUs() <= READY_MAX_ERROR_US

    /**
     * Feed one `server/time` round-trip. All args in microseconds:
     *  - [clientTransmittedUs] T1 — client send, local clock
     *  - [serverReceivedUs]    T2 — server receive, server clock
     *  - [serverTransmittedUs] T3 — server send, server clock
     *  - [clientReceivedUs]    T4 — client receive, local clock (stamp at WS onMessage)
     */
    fun processTimeResponse(
        clientTransmittedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long,
        clientReceivedUs: Long,
    ) {
        val rtt = (clientReceivedUs - clientTransmittedUs) - (serverTransmittedUs - serverReceivedUs)
        lastRttUs = rtt.coerceAtLeast(0L)
        val measurement = ((serverReceivedUs - clientTransmittedUs) +
            (serverTransmittedUs - clientReceivedUs)) / 2.0
        val maxError = (lastRttUs / 2.0).coerceAtLeast(1.0)
        val measurementVariance = maxError * maxError

        if (clientReceivedUs == lastUpdateUs) return
        val dt = (clientReceivedUs - lastUpdateUs).toDouble()
        lastUpdateUs = clientReceivedUs

        // First sample: seed the offset.
        if (count <= 0) {
            count = 1
            offset = measurement
            offsetCovariance = measurementVariance
            drift = 0.0
            useDrift = false
            return
        }
        // Second sample: seed the drift from the finite difference (÷ dt²).
        if (count == 1) {
            count = 2
            drift = (measurement - offset) / dt
            driftCovariance = (offsetCovariance + measurementVariance) / (dt * dt)
            offset = measurement
            offsetCovariance = measurementVariance
            useDrift = false
            return
        }

        // Predict.
        val predictedOffset = offset + drift * dt
        val dt2 = dt * dt
        var pOffCov = offsetCovariance + 2 * offsetDriftCovariance * dt +
            driftCovariance * dt2 + dt * offsetProcessVariance
        var pOffDriftCov = offsetDriftCovariance + driftCovariance * dt
        var pDriftCov = driftCovariance

        val residual = measurement - predictedOffset
        if (count < 100) {
            count++
        } else if (abs(residual) > maxError * ADAPTIVE_FORGETTING_CUTOFF) {
            // Large prediction error after warm-up: forget faster to re-converge.
            pDriftCov *= forgetVarianceFactor
            pOffDriftCov *= forgetVarianceFactor
            pOffCov *= forgetVarianceFactor
        }

        // Update.
        val uncertainty = 1.0 / (pOffCov + measurementVariance)
        val offsetGain = pOffCov * uncertainty
        val driftGain = pOffDriftCov * uncertainty
        offset = predictedOffset + offsetGain * residual
        drift += driftGain * residual
        driftCovariance = pDriftCov - driftGain * pOffDriftCov
        offsetDriftCovariance = pOffDriftCov - driftGain * pOffCov
        offsetCovariance = pOffCov - offsetGain * pOffCov

        // Only apply drift once it is statistically significant vs its uncertainty.
        useDrift = drift * drift > driftSignificanceSquared * driftCovariance
        if (count >= 100) count++
    }

    private fun effectiveDrift(): Double =
        if (useDrift) drift.coerceIn(-MAX_DRIFT, MAX_DRIFT) else 0.0

    /** Convert a server-domain timestamp to the local monotonic clock. */
    fun serverToLocalUs(serverTimestampUs: Long): Long {
        val d = effectiveDrift()
        return round((serverTimestampUs - offset + d * lastUpdateUs) / (1.0 + d)).toLong()
    }

    /** Convert a local monotonic timestamp to server time (inverse of [serverToLocalUs]). */
    fun localToServerUs(localTimestampUs: Long): Long {
        val d = effectiveDrift()
        return round(localTimestampUs * (1.0 + d) + offset - d * lastUpdateUs).toLong()
    }

    /** Current offset (us), extrapolated to now by the drift term. */
    fun currentOffsetUs(): Long {
        val d = effectiveDrift()
        val dt = (nowMonotonicUs() - lastUpdateUs).toDouble()
        return round(offset + d * dt).toLong()
    }

    fun reset() {
        offset = 0.0
        drift = 0.0
        offsetCovariance = Double.MAX_VALUE
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        lastUpdateUs = 0L
        count = 0
        useDrift = false
        lastRttUs = 0L
    }
}
