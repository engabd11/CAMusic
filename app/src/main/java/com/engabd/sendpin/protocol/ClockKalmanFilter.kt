package com.engabd.sendpin.protocol

import android.util.Log
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
 *
 * @param nowUs the local monotonic clock, in microseconds. Production passes
 *   [MonotonicClock.nowUs] — see there for why the default is not what runs on a
 *   phone. The default keeps this class constructible from a JVM unit test, which
 *   is the whole reason it has no Android dependency of its own.
 */
class ClockKalmanFilter(
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
) {

    private companion object {
        const val ADAPTIVE_FORGETTING_CUTOFF = 2.0
        const val OFFSET_PROCESS_STD_DEV = 0.01
        const val FORGET_FACTOR = 1.1
        const val DRIFT_SIGNIFICANCE_THRESHOLD = 2.0
        const val MAX_DRIFT = 0.999          // guards the (1 + drift) divisor
        const val READY_MIN_SAMPLES = 8
        const val READY_MAX_ERROR_US = 5_000L

        /**
         * How many live samples a *seeded* filter needs before grouped playback may
         * start.
         *
         * [seedOffset] already knows the offset — it was measured in an earlier session
         * and persisted precisely so a reconnect would not have to rediscover it. What
         * live samples add after a seed is not the offset but *corroboration*: evidence
         * that it has not moved since. Three of those is plenty, and the error bar and
         * the step detector in [isReadyForPlaybackStart] still have to agree.
         *
         * Demanding the full eight was costing audio, not buying safety. At the 300 ms
         * cold-start cadence it is around 1.8 s of waiting, during which Music Assistant
         * is already streaming — and [com.engabd.sendpin.audio.SendspinNativeEngine]'s
         * startup trim then drops every frame whose slot passed during the wait. The
         * result was a grouped speaker starting each track a second or two in, with the
         * intro simply gone. Three samples is roughly 0.9 s, and what remains to trim is
         * a fraction of a second.
         */
        const val READY_SEEDED_MIN_SAMPLES = 3

        /**
         * A residual this far outside the measurement's own error bar is not noise.
         *
         * Both tests have to pass, and they guard different things. The multiple of
         * `maxError` (half the round-trip) is what makes a slow reply — where the
         * measurement really could be out by that much — fail to count: a 1 s RTT
         * spike carries an error bar of 500 ms, so its residual has to clear 4 s
         * before it is believed. The absolute floor is what keeps ordinary jitter on
         * a fast LAN, where the error bar is a couple of milliseconds, from clearing
         * the multiple on its own.
         */
        const val STEP_RESIDUAL_FACTOR = 8.0
        const val STEP_MIN_RESIDUAL_US = 50_000.0

        /** [startupBackoffMs] tiers, keyed by consecutive rejection count. */
        const val STARTUP_BACKOFF_TIER_1_MS = 600L
        const val STARTUP_BACKOFF_TIER_2_MS = 1_200L
        const val STARTUP_BACKOFF_TIER_3_MS = 3_000L
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

    /**
     * The last sample's residual was too big to be measurement noise — the clock
     * underneath us may have stepped. Held until the next sample either confirms it
     * (→ re-seed) or comes back normal (→ it was an outlier after all).
     *
     * Zero when nothing is in doubt; otherwise the signed residual, so the
     * confirming sample can be required to point the same way.
     */
    private var suspectResidual = 0.0

    /**
     * Consecutive `server/time` replies the caller rejected before feeding them in
     * (e.g. an RTT gate during cold start) — see [markStartupRejected]. Reset the
     * moment a sample actually reaches [processTimeResponse].
     */
    private var consecutiveStartupRejections = 0

    private val offsetProcessVariance = OFFSET_PROCESS_STD_DEV * OFFSET_PROCESS_STD_DEV
    private val forgetVarianceFactor = FORGET_FACTOR * FORGET_FACTOR
    private val driftSignificanceSquared = DRIFT_SIGNIFICANCE_THRESHOLD * DRIFT_SIGNIFICANCE_THRESHOLD

    val sampleCount: Int get() = count

    fun nowMonotonicUs(): Long = nowUs()
    fun lastRttUs(): Long = lastRttUs
    fun errorUs(): Long = round(sqrt(offsetCovariance.coerceAtLeast(0.0))).toLong()
    fun isSynced(): Boolean = count >= 1 && offsetCovariance < Double.MAX_VALUE
    fun driftPpm(): Double = if (useDrift) drift * 1_000_000.0 else 0.0

    /** The last residual was too big to be noise, and nothing has resolved it yet. */
    fun isStepSuspected(): Boolean = suspectResidual != 0.0

    /**
     * Safe to start grouped playback: enough low-error samples have converged, and
     * nothing has just happened to suggest the offset moved under us.
     *
     * The covariance alone cannot answer the second half. After a clock step the
     * filter is *confidently wrong* — its error estimate stays in the hundreds of
     * microseconds while the offset it hands out is seconds off — so a gate that
     * only read [errorUs] would schedule the head of a stream against it and hold
     * the song silent until it landed. Saying "not ready" for the one sample it
     * takes to confirm the step costs a few hundred milliseconds and is the truth.
     *
     * A filter seeded from a persisted offset needs fewer live samples to clear the
     * first test — see [READY_SEEDED_MIN_SAMPLES]. Both other tests are unchanged, so a
     * seed that has gone stale is still caught: it shows up as a step, and
     * [isStepSuspected] holds the gate shut until the next sample resolves it.
     */
    fun isReadyForPlaybackStart(): Boolean =
        count >= (if (seeded) READY_SEEDED_MIN_SAMPLES else READY_MIN_SAMPLES) &&
            errorUs() <= READY_MAX_ERROR_US && !isStepSuspected()

    /**
     * The offset was seeded from a persisted value rather than measured from scratch.
     *
     * Cleared by [reset], which is a genuine cold start, but survives [softReset] —
     * that keeps the offset it already had, which is the same claim a seed makes.
     */
    private var seeded = false

    /**
     * The caller dropped a `server/time` reply without feeding it into the filter —
     * an RTT gate during cold start rejecting a slow round-trip, say — rather than
     * this class rejecting anything itself. Without this, a server slow enough to
     * keep tripping that gate never sees the cadence back off: [sampleCount] never
     * advances, so a caller keyed on it (like ours) stays at the fast cadence and
     * re-sends every 300ms indefinitely. [startupBackoffMs] ramps in response;
     * [processTimeResponse] clears the streak the moment a sample is actually
     * accepted, so a transient spike doesn't leave the cadence backed off longer
     * than the spike itself.
     */
    fun markStartupRejected() {
        consecutiveStartupRejections++
    }

    /**
     * Extra delay [markStartupRejected] wants before the next `client/time` request,
     * on top of whatever cadence the caller would otherwise use. Zero once the
     * rejection streak is still short enough that the ordinary fast cadence is fine.
     */
    fun startupBackoffMs(): Long = when {
        consecutiveStartupRejections < 3 -> 0L
        consecutiveStartupRejections < 6 -> STARTUP_BACKOFF_TIER_1_MS
        consecutiveStartupRejections < 12 -> STARTUP_BACKOFF_TIER_2_MS
        else -> STARTUP_BACKOFF_TIER_3_MS
    }

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
        // Any sample that reaches here was actually fed in, so whatever streak of
        // caller-side rejections preceded it is over.
        consecutiveStartupRejections = 0
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

        // ── Step detection ────────────────────────────────────────────────────
        //
        // A residual far outside this measurement's own error bar means the offset
        // moved, not that this sample was noisy — a server whose clock was stepped
        // by NTP, say. The Kalman update cannot cope with that on its own: its gain
        // is proportional to how sure it is, and after a few minutes of steady
        // samples it is very sure, so a step of seconds is walked off at a few
        // percent per sample — a minute of handing out an offset that is seconds
        // wrong while claiming a few hundred microseconds of error. Long enough
        // that a track tapped in that window is scheduled seconds into the future
        // and simply sits there.
        //
        // So: re-seed from the measurement instead of crawling to it. One sample of
        // corroboration first, because a single wild residual should not be allowed
        // to throw away a converged estimate — and the sample after a real step
        // agrees with it, while an outlier's successor does not.
        val stepThreshold = maxOf(STEP_MIN_RESIDUAL_US, maxError * STEP_RESIDUAL_FACTOR)
        if (abs(residual) > stepThreshold) {
            if (suspectResidual != 0.0 && (residual > 0) == (suspectResidual > 0)) {
                reseedFrom(measurement, measurementVariance, dt)
                return
            }
            // First sighting: flag it — which also stands the player down from
            // "ready", so nothing schedules against the old offset in the meantime
            // — and let the ordinary update have this sample, in case it was noise.
            suspectResidual = residual
        } else {
            suspectResidual = 0.0
        }

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

    /**
     * Throw the offset away and start again from [measurement], keeping the sample
     * count.
     *
     * The count stays because it is not what the step invalidated: it stands for
     * "this link has been measured enough to be worth believing", and the link is
     * the same one. What is unknown again is the offset — so its covariance goes
     * back to this single measurement's own error bar, which on a LAN is a couple
     * of milliseconds and clears [READY_MAX_ERROR_US] immediately, and on a slower
     * link does not, leaving the player honestly unready until more samples land.
     *
     * The drift estimate goes with it: a step tells us nothing about the rate, and
     * the residual it produced would otherwise be read as an enormous one. Its
     * covariance is re-seeded the same way the second-ever sample seeds it, rather
     * than zeroed — a zero there is a claim to know the drift exactly, and the gain
     * it implies would stop the filter ever learning the rate again.
     */
    private fun reseedFrom(measurement: Double, measurementVariance: Double, dt: Double) {
        offset = measurement
        offsetCovariance = measurementVariance
        offsetDriftCovariance = 0.0
        drift = 0.0
        driftCovariance = if (dt != 0.0) 2 * measurementVariance / (dt * dt) else 0.0
        useDrift = false
        suspectResidual = 0.0
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
        suspectResidual = 0.0
        consecutiveStartupRejections = 0
        // A full reset throws the offset away, so any seed that was standing behind it
        // is gone too — the next start has to earn the full sample count again.
        seeded = false
    }

    /**
     * Soft reset: throw away the converged offset but seed it from a persisted
     * value (e.g. saved across a reconnect), keeping the drift estimate and
     * sample count so the filter skips the cold-start convergence phase.
     *
     * Ported from MassDroid's `ClockSynchronizer.softReset` — used by
     * [SendspinClient] to persist the clock offset across reconnects, so a
     * player that was in sync before the socket dropped can resume on the same
     * timeline without waiting for 8 fresh `client/time` ↔ `server/time`
     * round-trips.
     *
     * @param previousOffsetUs the persisted offset (server-minus-wall, us)
     * @param preserveDrift if true, keep the drift estimate from before the reset
     * @param initialCovariance the covariance to seed the offset with — small
     *   enough to pass [isReadyForPlaybackStart] immediately on a good LAN,
     *   large enough that a real discrepancy is noticed quickly. Defaults to
     *   50 000 (≈7 ms std dev), matching MassDroid.
     */
    fun softReset(
        previousOffsetUs: Long,
        preserveDrift: Boolean = true,
        initialCovariance: Double = 50_000.0,
    ) {
        val prevDrift = drift
        val prevUseDrift = useDrift
        reset()
        if (previousOffsetUs != 0L) {
            offset = previousOffsetUs.toDouble()
            if (preserveDrift) {
                drift = prevDrift
                useDrift = prevUseDrift
            }
            offsetCovariance = initialCovariance
            count = 2  // skip initialization phase
            Log.d("ClockKalman", "Soft reset: seeded offset=${previousOffsetUs}us " +
                "drift=${"%.6f".format(drift)} useDrift=$useDrift " +
                "covariance=${"%.0f".format(initialCovariance)}")
        }
    }

    /**
     * Seed the offset from a persisted value on cold start, before any
     * `client/time` ↔ `server/time` round-trips have arrived.
     *
     * Unlike [softReset], this does not preserve drift (there is none yet) and
     * sets the covariance high enough that the filter will still converge from
     * real samples rather than trusting the seed blindly.
     */
    fun seedOffset(offsetUs: Long) {
        if (offsetUs == 0L || count > 0) return
        offset = offsetUs.toDouble()
        offsetCovariance = 50_000.0
        count = 2
        seeded = true
        Log.d("ClockKalman", "Seeded offset=${offsetUs}us from persisted value")
    }
}
