package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The current rhythm model. Times are on the analyzer's `tAudio` clock.
 *
 * Ported from syncoV2 `audio/tempo.py`.
 */
data class BeatGrid(
    val bpm: Float = 0f,
    /** Autocorrelation lock strength, 0..1. */
    val confidence: Float = 0f,
    val locked: Boolean = false,
    /** Seconds per beat; 0 when unlocked. */
    val periodS: Float = 0f,
    /** Position within the current beat, 0..1. */
    val phase: Float = 0f,
    val timeToNextBeat: Float = 0f,
    /** Absolute `tAudio` of the predicted next beat. */
    val nextBeatT: Float = 0f,
    /** Position within a 4-beat bar, 0..1. */
    val barPhase: Float = 0f,
    /** True on the single frame the beat clock ticks over. */
    val predictedBeat: Boolean = false,
    /**
     * Accent (0..1) of the *upcoming* beat, so anticipatory effects can size
     * each beat by how hard the song is about to hit it.
     */
    val accent: Float = 1f,
    /** Accent (0..1) of the beat that just started. */
    val accentNow: Float = 1f,
    /** Which beat of the bar just started; 0 is the downbeat. */
    val beatInBar: Int = 0,
    /**
     * How many beats there are to a bar.
     *
     * Only an offline scan ever measures this — see [TrackScan.beatsPerBar].
     * The causal tracker has no metre estimate and leaves the default, which is
     * the right guess when guessing is all there is; the field exists so the
     * engine can tell "measured as 4" from "assumed 4" without asking a second
     * object, and so a waltz's bar weighting is not a 4/4 table with a beat
     * lopped off.
     */
    val beatsPerBar: Int = 4,
    /**
     * How hard scheduled pulses may drive the show, 0..1. Ramped with lock
     * confidence so a marginal — possibly wrong — lock pulses modestly rather
     * than confidently.
     */
    val scheduleStrength: Float = 1f,
)

private const val MIN_BPM = 70f
private const val MAX_BPM = 190f
private const val PRIOR_CENTER_BPM = 120f

/** Log-tempo Gaussian width. An octave is about 0.69, so this is well inside one. */
private const val PRIOR_WIDTH = 0.55f

/**
 * Lock hysteresis: engage high, release low. A single threshold made the
 * choreography flip between grid-paced and reactive behaviour mid-song whenever
 * confidence hovered near it, which reads as random. Real music measures around
 * 0.7–0.9; noise and irregular onsets stay well below.
 */
private const val LOCK_ON = 0.40f
private const val LOCK_OFF = 0.25f

/** Largest phase error, in beats, that an onset may nudge the clock by. */
private const val PLL_GATE = 0.20f

/**
 * This many consecutive far-off onsets snap the phase to the onsets instead: the
 * grid was simply anchored wrong, typically onto the off-beat.
 */
private const val RESYNC_STREAK = 4

/**
 * Scheduled pulses ramp in with lock quality — a lock at [LOCK_ON] drives them
 * at this fraction, reaching full at [FULL_CONFIDENCE]. Detection still rides
 * along via max(), and carries the difference meanwhile.
 */
private const val MARGINAL_SCHEDULE = 0.6f
private const val FULL_CONFIDENCE = 0.60f

/** How much of the gap to a freshly measured tempo to take per recompute. */
private const val PERIOD_SMOOTH = 0.30f

/** Beats of accent history the rolling p90 normalises against. */
private const val ACCENT_HISTORY = 16

/**
 * Predictive beat grid: tempo plus phase lock over the onset envelope.
 *
 * The analyzer's spectral-flux detector says when a beat *just* happened. A good
 * light show needs to know when the *next* one will, so a wave can start rising
 * early and peak exactly on the kick rather than chasing it. That prediction
 * comes from the rhythm model, so it needs no look-ahead into future audio: once
 * tempo and phase are locked, the next beat time is known.
 *
 * Two stages:
 * - **Tempo** — autocorrelation of the recent onset envelope, weighted by a
 *   log-Gaussian prior around 120 BPM to suppress half- and double-tempo octave
 *   errors (the Davies/Ellis approach).
 * - **Phase** — a phase-locked loop. A beat clock free-runs at the locked tempo
 *   and is nudged toward each detected onset, so it stays in the pocket and
 *   rides small tempo drift without jumping.
 *
 * When the lock is weak — sparse or irregular onsets, speech, ambient — [locked]
 * stays false and consumers fall back to plain reactive behaviour.
 *
 * Ported from syncoV2 `audio/tempo.py`, constants unchanged. Not thread-safe;
 * owned by whichever thread drives the analyzer.
 */
class TempoTracker(
    private val framePeriod: Float,
    historyS: Float = 6.0f,
) {
    private val maxLen = max(64, (historyS / framePeriod).toInt())
    private val flux = FloatArray(maxLen)
    private var fluxCount = 0
    private var fluxHead = 0

    private val minLag = max(1, (60f / MAX_BPM / framePeriod).roundToInt())
    private val maxLag = (60f / MIN_BPM / framePeriod).roundToInt()

    /** Log-tempo prior over candidate lags, precomputed once. */
    private val prior = FloatArray(max(0, maxLag - minLag + 1)) { i ->
        val candBpm = 60f / ((minLag + i) * framePeriod)
        exp(-0.5f * square(ln(candBpm / PRIOR_CENTER_BPM) / PRIOR_WIDTH))
    }

    /** Scratch for the autocorrelation, so the hop path stays allocation-free. */
    private val env = FloatArray(maxLen)

    private var bpm = 0f
    private var periodS = 0f
    private var confidence = 0f
    private var locked = false
    private var offgridStreak = 0
    private var phase = 0f
    private var beatCount = 0
    private var frames = 0
    private var lastT: Float? = null
    private val recomputeEvery = max(1, (0.10f / framePeriod).roundToInt())

    /** Onset strength per beat-of-bar, used to find the downbeat. */
    private val barAccent = FloatArray(4)
    private var downbeat = 0

    /**
     * Per-beat accent: the peak onset flux inside each beat window, normalised
     * by a rolling p90 of recent beats, so "strong" always means strong *for
     * this passage* rather than for the whole track or the AGC scale.
     */
    private var accCur = 0f
    private val accHist = FloatArray(ACCENT_HISTORY)
    private var accCount = 0
    private var accHead = 0
    private var accPred = 0.7f

    /**
     * Advance the grid by one frame and return the current prediction.
     *
     * [bass] (0..1, the frame's low-band level) weights how hard an onset may
     * pull the clock: kicks steer, hi-hats barely do.
     */
    fun update(
        tAudio: Float,
        fluxValue: Float,
        beat: Boolean,
        beatStrength: Float,
        bass: Float = 1f,
    ): BeatGrid {
        pushFlux(max(0f, fluxValue))
        frames++
        if (frames % recomputeEvery == 0 && fluxCount >= maxLen / 2) recomputeTempo()

        // Lock hysteresis, so behaviour cannot flap around a single threshold.
        if (locked) {
            if (confidence < LOCK_OFF || periodS <= 0f) locked = false
        } else if (confidence >= LOCK_ON && periodS > 0f) {
            locked = true
            offgridStreak = 0
        }

        // Advance by real elapsed audio time: frames may be produced and
        // consumed at slightly different rates.
        val prev = lastT
        var dt = if (prev == null) framePeriod else tAudio - prev
        if (dt <= 0f || dt > 0.25f) dt = framePeriod  // gap, seek or reset
        lastT = tAudio

        // Track the loudest onset moment inside the current beat window,
        // bass-weighted so kicks dominate the accent measure over hi-hat splash.
        accCur = max(accCur, max(0f, fluxValue) * (0.5f + 0.5f * bass.coerceIn(0f, 1f)))

        var predicted = false
        if (periodS > 0f) {
            phase += dt / periodS
            if (phase >= 1f) {
                phase -= 1f
                beatCount++
                predicted = true
                finishBeatAccent()
            }
            // An onset near a predicted beat nudges the clock toward the
            // boundary. Off-grid onsets — vocal hits, hi-hat flams — are ignored
            // unless they form a streak, which means the grid is anchored on the
            // wrong phase and should snap to where the onsets actually are.
            if (beat && locked) {
                val err = if (phase <= 0.5f) phase else phase - 1f
                if (abs(err) <= PLL_GATE) {
                    offgridStreak = 0
                    val gain = 0.10f * (0.4f + 0.6f * bass.coerceIn(0f, 1f))
                    phase = floorMod1(phase - gain * err)
                    accumulateAccent(beatStrength)
                } else {
                    offgridStreak++
                    if (offgridStreak >= RESYNC_STREAK) {
                        phase = 0f  // re-anchor the clock on the onsets
                        offgridStreak = 0
                    }
                }
            }
        }

        val ttn = if (periodS > 0f) (1f - phase) * periodS else 0f
        val beatIdx = Math.floorMod(beatCount - downbeat, 4)
        return BeatGrid(
            bpm = bpm,
            confidence = confidence,
            locked = locked,
            periodS = periodS,
            phase = phase,
            timeToNextBeat = ttn,
            nextBeatT = tAudio + ttn,
            barPhase = (beatIdx + phase) / 4f,
            predictedBeat = predicted,
            accent = accPred,
            accentNow = accPred,
            beatInBar = beatIdx,
            scheduleStrength = if (locked) scheduleWeight(confidence) else 1f,
        )
    }

    fun reset() {
        java.util.Arrays.fill(flux, 0f)
        fluxCount = 0
        fluxHead = 0
        bpm = 0f
        periodS = 0f
        confidence = 0f
        locked = false
        offgridStreak = 0
        phase = 0f
        beatCount = 0
        java.util.Arrays.fill(barAccent, 0f)
        downbeat = 0
        lastT = null
        accCur = 0f
        java.util.Arrays.fill(accHist, 0f)
        accCount = 0
        accHead = 0
        accPred = 0.7f
        frames = 0
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun pushFlux(v: Float) {
        flux[fluxHead] = v
        fluxHead = (fluxHead + 1) % maxLen
        if (fluxCount < maxLen) fluxCount++
    }

    /**
     * Fold the completed beat window's peak flux into the accent model.
     *
     * A causal tracker cannot know the upcoming beat's accent — the kick has not
     * sounded yet — so it predicts: an EMA of recent beats' measured accents,
     * each normalised by the rolling p90 so the scale is always "relative to
     * this passage". The engine corrects upward live when a detected on-grid
     * onset comes in hotter than the prediction.
     */
    private fun finishBeatAccent() {
        accHist[accHead] = accCur
        accHead = (accHead + 1) % ACCENT_HISTORY
        if (accCount < ACCENT_HISTORY) accCount++
        if (accCount >= 4) {
            val ref = percentile90(accHist, accCount)
            if (ref > 1e-6f) {
                val norm = min(1f, accCur / ref)
                accPred += 0.5f * (norm - accPred)
            }
        }
        accCur = 0f
    }

    private fun recomputeTempo() {
        // Copy the ring out in chronological order and mean-remove it.
        val n = fluxCount
        val start = if (fluxCount < maxLen) 0 else fluxHead
        var sum = 0f
        for (i in 0 until n) {
            val v = flux[(start + i) % maxLen]
            env[i] = v
            sum += v
        }
        val mean = sum / n
        var any = false
        for (i in 0 until n) {
            env[i] -= mean
            if (env[i] != 0f) any = true
        }
        if (!any) {
            confidence = 0f
            return
        }

        val hi = min(maxLag, n - 1)
        if (hi <= minLag) return

        // Autocorrelation at lag 0, the normaliser.
        var zero = 0f
        for (i in 0 until n) zero += env[i] * env[i]
        if (zero <= 1e-9f) zero = 1e-9f

        var bestLag = minLag
        var bestScored = Float.NEGATIVE_INFINITY
        var bestPeak = 0f
        for (lag in minLag..hi) {
            var acc = 0f
            for (i in 0 until n - lag) acc += env[i] * env[i + lag]
            val scored = acc * prior[lag - minLag]
            if (scored > bestScored) {
                bestScored = scored
                bestLag = lag
                bestPeak = acc / zero
            }
        }

        // Smooth the tempo so it drifts rather than jumps between recomputes.
        val newPeriod = bestLag * framePeriod
        periodS = if (periodS <= 0f) newPeriod else periodS + PERIOD_SMOOTH * (newPeriod - periodS)
        bpm = 60f / periodS
        confidence = bestPeak.coerceIn(0f, 1f)
    }

    private fun accumulateAccent(strength: Float) {
        val idx = beatCount % 4
        for (i in barAccent.indices) barAccent[i] *= 0.97f  // slowly forget old structure
        barAccent[idx] += strength
        var best = 0
        for (i in barAccent.indices) if (barAccent[i] > barAccent[best]) best = i
        downbeat = best
    }

    private companion object {
        fun square(x: Float) = x * x

        /** Wrap into [0, 1) — the phase may be nudged either side of a boundary. */
        fun floorMod1(x: Float): Float {
            val m = x % 1f
            return if (m < 0f) m + 1f else m
        }

        fun scheduleWeight(confidence: Float): Float {
            val span = max(1e-6f, FULL_CONFIDENCE - LOCK_ON)
            val t = ((confidence - LOCK_ON) / span).coerceIn(0f, 1f)
            return MARGINAL_SCHEDULE + (1f - MARGINAL_SCHEDULE) * t
        }

        /**
         * numpy's linear-interpolation percentile, over the first [count]
         * entries. Matched deliberately: the accent scale is what decides which
         * beats get a full flash, and a different interpolation would shift the
         * threshold relative to syncoV2's tuning.
         */
        fun percentile90(values: FloatArray, count: Int): Float {
            if (count <= 0) return 0f
            if (count == 1) return values[0]
            val sorted = values.copyOf(count)
            sorted.sort()
            val pos = 0.90f * (count - 1)
            val lo = pos.toInt()
            val hi = min(lo + 1, count - 1)
            val frac = pos - lo
            return sorted[lo] + frac * (sorted[hi] - sorted[lo])
        }
    }
}
