package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sound that *moves*, and sound that *rises* — the two things the engine could
 * not previously see.
 *
 * [AnalysisFrame.pan] has always carried where each melbank bin sits in the
 * stereo field, and the engine has always used it as a static per-lamp bias: a
 * left lamp gets more of whatever is panned left, right now. That is the correct
 * instantaneous answer and it is not a gesture. A pad sweeping across the stereo
 * field currently renders as the left lamp fading while the right one brightens,
 * which is true and reads as nothing at all.
 *
 * Two phenomena, two detectors, because conflating them is the surest way to
 * fire at the wrong moments:
 *
 *  - a **traversal** — a source moving across the stereo field while it sounds;
 *  - a **swell** — a linear rise with no onsets under it, a crescendo or a riser.
 *
 * The hard requirement is restraint. Not all music has either of these, and a
 * gesture that fires on a track with no movement in it is worse than one that
 * never fires, because it makes every track look like the ones it is supposed to
 * distinguish. So: confidence rather than a boolean, a refractory period, a
 * budget per minute, and detectors that are suspended entirely while a gesture
 * is already running.
 *
 * Shaped like [StructureTracker] on purpose — constructed with the analyzer's
 * frame period, stepped once per hop, reset on a seek. **Not thread-safe**;
 * owned by whichever thread drives the analyzer, which is the tap's own analysis
 * thread. See `DirectLightSync`.
 */
class GestureTracker(private val framePeriod: Float) {

    /** Seconds between stored samples, and how many of them fit in the history. */
    private val sampleS: Float = framePeriod * DECIMATE
    private val hist: Int = max(8, (HISTORY_S / sampleS).roundToInt())

    // Ring buffers, newest-last. ~1.3k floats at the shipped geometry.
    private val panHist = FloatArray(MELBANK_BINS * hist)
    private val melHist = FloatArray(MELBANK_BINS * hist)
    private val mixHist = FloatArray(hist)
    private val energyHist = FloatArray(hist)
    private val centroidHist = FloatArray(hist)
    private val quietHist = BooleanArray(hist)

    private var writeIdx = 0
    private var filled = 0
    private var sinceSample = 0

    /**
     * How stereo the material actually is, 0..1.
     *
     * Necessary because a mono source does **not** produce an empty `pan` — the
     * tap folds a mono or multichannel stream onto both sides on purpose, so
     * `pan` comes back as bins of exact zeros and nothing else distinguishes
     * that from a perfectly centred mix. An empty `pan` means silence. Without
     * this measure a downmixed stream would simply never move, which is correct,
     * but would leave the detector burning windows on it forever.
     */
    var stereo: Float = 0f
        private set

    /** True once the per-minute budget has been hit at least once this track. */
    var budgetHit: Boolean = false
        private set

    private val launches = ArrayDeque<Float>()
    private var nextId = 0L
    private var liveUntil = -1f
    private var blockedUntil = -1f
    private var current = GestureState()

    // Scratch, so the 10 Hz scoring pass allocates nothing.
    private val binScore = FloatArray(MELBANK_BINS)
    private val binWindow = IntArray(MELBANK_BINS)
    private val binFrom = FloatArray(MELBANK_BINS)
    private val binTo = FloatArray(MELBANK_BINS)

    /**
     * Advance one analysis hop.
     *
     * [structure] is passed in rather than recomputed: the caller already has
     * this frame's arc, and `buildProgress` is the natural gate for the swell
     * half — a crescendo *is* a build, and the tracker already says so.
     *
     * Returns the current gesture. The [GestureState.id] changes on the frame a
     * new gesture is launched and at no other time, so a renderer can launch on
     * an id change and never re-fire per frame.
     */
    fun update(frame: AnalysisFrame, structure: StructureState? = null): GestureState {
        val t = frame.tAudio
        val pan = frame.pan
        val mel = frame.melbank

        // Silence. An empty pan is the analyzer saying it had nothing to measure,
        // which is not evidence that a sound stopped moving — so the history is
        // held rather than filled with zeros that would break any window
        // straddling the gap.
        if (pan.size < MELBANK_BINS || mel.size < MELBANK_BINS) return current

        // Broadband pan centroid, energy-weighted. Subtracting it is what makes
        // this a detector of a source moving *relative to the mix* rather than a
        // detector of mixes that lean. Without it every track with an off-centre
        // master trips, and that is a lot of them.
        var wSum = 0f
        var mixSum = 0f
        var spreadSum = 0f
        for (i in 0 until MELBANK_BINS) {
            val w = mel[i]
            wSum += w
            mixSum += w * pan[i]
        }
        val mix = if (wSum > 1e-6f) mixSum / wSum else 0f
        for (i in 0 until MELBANK_BINS) spreadSum += mel[i] * abs(pan[i] - mix)
        val spread = if (wSum > 1e-6f) spreadSum / wSum else 0f
        val alpha = 1f - exp(-framePeriod / STEREO_TAU_S)
        stereo += (spread - stereo) * alpha

        if (++sinceSample < DECIMATE) return current
        sinceSample = 0
        push(pan, mel, mix, frame)

        // While a gesture is running the detectors are suspended outright. That
        // is the hysteresis: two overlapping sweeps read as noise, so the second
        // one never gets to exist.
        if (t < liveUntil) return current
        if (t < blockedUntil) return current
        if (filled <= 2) return current

        val traversal = scoreTraversal()
        val swell = scoreSwell(structure)

        // A riser that also pans is one gesture and not two, so the traversal —
        // the more specific of the pair — wins and carries the rise through.
        val kind = when {
            traversal != null && traversal.strength >= START -> GestureKind.TRAVERSAL
            swell != null && swell.strength >= START -> GestureKind.SWELL
            else -> return current
        }
        val hit = if (kind == GestureKind.TRAVERSAL) traversal!! else swell!!
        val rise = if (kind == GestureKind.TRAVERSAL) (swell?.rise ?: hit.rise) else hit.rise

        // The budget. A track that trips the detector constantly — and there will
        // be one — degrades to the ordinary show rather than becoming a strobe.
        while (launches.isNotEmpty() && t - launches.first() > BUDGET_WINDOW_S) launches.removeFirst()
        if (launches.size >= MAX_PER_MIN) {
            budgetHit = true
            blockedUntil = t + REFRACTORY_MIN_S
            return current
        }
        launches.addLast(t)

        val duration = hit.durationS.coerceIn(MIN_DURATION_S, MAX_DURATION_S)
        liveUntil = t + duration
        blockedUntil = liveUntil + max(REFRACTORY_MIN_S, duration)
        current = GestureState(
            id = ++nextId,
            kind = kind,
            fromPan = hit.fromPan,
            toPan = hit.toPan,
            durationS = duration,
            strength = hit.strength.coerceIn(0f, 1f),
            rise = rise,
            stereo = stereo,
        )
        return current
    }

    /**
     * Drop everything a seek or a track change invalidates.
     *
     * [nextId] deliberately survives: it is monotonic for the life of the
     * tracker, so a renderer holding the id of a gesture from before the seek can
     * never mistake a fresh one for the same gesture.
     */
    fun reset() {
        panHist.fill(0f); melHist.fill(0f); mixHist.fill(0f)
        energyHist.fill(0f); centroidHist.fill(0f); quietHist.fill(false)
        writeIdx = 0; filled = 0; sinceSample = 0
        stereo = 0f
        budgetHit = false
        launches.clear()
        liveUntil = -1f
        blockedUntil = -1f
        current = GestureState()
    }

    // ── History ───────────────────────────────────────────────────────────

    private fun push(pan: FloatArray, mel: FloatArray, mix: Float, frame: AnalysisFrame) {
        val base = writeIdx * MELBANK_BINS
        for (i in 0 until MELBANK_BINS) {
            panHist[base + i] = pan[i]
            // Copied, not referenced: `frame.melbank` is the analyzer's own live
            // filter state and is mutated in place on the next hop.
            melHist[base + i] = mel[i]
        }
        mixHist[writeIdx] = mix
        energyHist[writeIdx] = frame.energy
        centroidHist[writeIdx] = frame.centroid
        quietHist[writeIdx] = !frame.beat && !frame.bassBeat && !frame.midBeat &&
            frame.flux < FLUX_QUIET
        writeIdx = (writeIdx + 1) % hist
        if (filled < hist) filled++
    }

    /** Slot holding the sample [back] steps before the newest one. */
    private fun slot(back: Int): Int = ((writeIdx - 1 - back) % hist + hist) % hist

    // ── Traversal ─────────────────────────────────────────────────────────

    private class Hit(
        val strength: Float,
        val durationS: Float,
        val fromPan: Float,
        val toPan: Float,
        val rise: Float,
    )

    /**
     * A bin whose stereo position walked steadily from one place to another while
     * it was still sounding.
     *
     * Six conditions, and each one rules out a different impostor:
     *
     *  - the **relative** excursion must be large — a real crossing, not jitter,
     *    and measured against the mix so a track with an off-centre master does
     *    not trip it;
     *  - the **absolute** excursion must also be large, which is a different
     *    claim and catches the opposite failure. When a loud one-sided source
     *    stops, every *other* bin's position relative to the mix swings the other
     *    way without any of them having moved at all. Requiring the source to
     *    have moved in absolute terms too says "the sound went somewhere",
     *    where the relative test only says "it went somewhere the mix did not";
     *  - the motion must be **monotone** over the window — a sweep, not a wobble;
     *  - and it must not have **come back**. Half a period of any oscillation is
     *    perfectly monotone, so monotonicity alone calls a 1.2 s vibrato a sweep
     *    four times a bar. A traversal is a one-way trip, so its midpoint is
     *    crossed once across the whole history and a wobble crosses it repeatedly;
     *  - the bin must have held energy throughout (one that went silent and came
     *    back elsewhere cut, it did not travel);
     *  - and the material must actually be stereo.
     */
    private fun scoreTraversal(): Hit? {
        val stereoGate = gate(STEREO_MIN, STEREO_FULL, stereo)
        if (stereoGate <= 0f) return null

        binScore.fill(0f)
        for (i in 0 until MELBANK_BINS) {
            for (windowS in WINDOWS_S) {
                val w = (windowS / sampleS).roundToInt()
                if (w < 2 || w >= filled) continue

                val panNew = panHist[slot(0) * MELBANK_BINS + i]
                val panOld = panHist[slot(w) * MELBANK_BINS + i]
                val relNew = panNew - mixHist[slot(0)]
                val relOld = panOld - mixHist[slot(w)]
                val exc = abs(relNew - relOld)
                if (exc < EXC_MIN) continue
                val absExc = abs(panNew - panOld)
                if (absExc < ABS_EXC_MIN) continue

                var tot = 0f
                var eMin = Float.MAX_VALUE
                var prev = relOld
                for (k in w - 1 downTo 0) {
                    val s = slot(k)
                    val rel = panHist[s * MELBANK_BINS + i] - mixHist[s]
                    tot += abs(rel - prev)
                    prev = rel
                    eMin = min(eMin, melHist[s * MELBANK_BINS + i])
                }
                eMin = min(eMin, melHist[slot(w) * MELBANK_BINS + i])

                if (crossings(i, 0.5f * (relOld + relNew)) > MAX_CROSSINGS) continue

                val mono = if (tot > 1e-6f) exc / tot else 0f
                val score = gate(EXC_MIN, EXC_FULL, exc) *
                    gate(ABS_EXC_MIN, ABS_EXC_FULL, absExc) *
                    gate(MONO_MIN, MONO_FULL, mono) *
                    gate(ENERGY_MIN, ENERGY_FULL, eMin) *
                    stereoGate
                if (score > binScore[i]) {
                    binScore[i] = score
                    binWindow[i] = w
                    binFrom[i] = panHist[slot(w) * MELBANK_BINS + i]
                    binTo[i] = panHist[slot(0) * MELBANK_BINS + i]
                }
            }
        }

        // A real source occupies more than one melbank bin, so a lone bin drifting
        // is far more likely to be the per-bin AGC rebalancing than a sound
        // moving. Requiring a neighbour to agree costs almost nothing and throws
        // that whole class of false positive away.
        var bestBin = -1
        var bestScore = 0f
        var bestNeighbour = -1
        for (i in 0 until MELBANK_BINS) {
            if (binScore[i] <= 0f) continue
            val lo = if (i > 0) binScore[i - 1] else 0f
            val hi = if (i < MELBANK_BINS - 1) binScore[i + 1] else 0f
            val nIdx = if (lo >= hi) i - 1 else i + 1
            val nScore = max(lo, hi)
            if (nScore < ADJACENCY_FRACTION * binScore[i]) continue
            val combined = 0.5f * (binScore[i] + nScore)
            if (combined > bestScore) { bestScore = combined; bestBin = i; bestNeighbour = nIdx }
        }
        if (bestBin < 0) return null

        // Endpoints are the *absolute* pan values, not the mix-relative ones used
        // to detect: relative pan says a source moved, absolute pan says where in
        // the room to draw it.
        val from = 0.5f * (binFrom[bestBin] + binFrom[bestNeighbour])
        val to = 0.5f * (binTo[bestBin] + binTo[bestNeighbour])
        return Hit(
            strength = bestScore,
            durationS = binWindow[bestBin] * sampleS,
            fromPan = from.coerceIn(-1f, 1f),
            toPan = to.coerceIn(-1f, 1f),
            rise = 0f,
        )
    }

    /**
     * How many times bin [i] has crossed [mid] across the whole stored history.
     *
     * The "did it come back" test. A dead zone either side of the midpoint keeps
     * a signal that merely rests near it from being counted as crossing back and
     * forth on noise alone.
     */
    private fun crossings(i: Int, mid: Float): Int {
        var count = 0
        var prevSide = 0
        for (k in filled - 1 downTo 0) {
            val s = slot(k)
            val v = panHist[s * MELBANK_BINS + i] - mixHist[s] - mid
            val side = when {
                v > CROSS_DEADZONE -> 1
                v < -CROSS_DEADZONE -> -1
                else -> 0
            }
            if (side != 0) {
                if (prevSide != 0 && side != prevSide) count++
                prevSide = side
            }
        }
        return count
    }

    // ── Swell ─────────────────────────────────────────────────────────────

    /**
     * Energy climbing in a straight line with nothing hitting underneath it.
     *
     * The linearity term is what makes this a swell rather than "the chorus got
     * louder": a crescendo fits a line, and a pumping compressor or a loop
     * getting busier does not. The centroid's own climb separates a riser, which
     * brightens as it goes, from a plain volume ramp — and it is what decides the
     * direction of a vertical gesture, which is the "bottom to top" half of the
     * request.
     */
    private fun scoreSwell(structure: StructureState?): Hit? {
        val build = structure?.buildProgress ?: 0f
        var best: Hit? = null
        for (windowS in WINDOWS_S) {
            val w = (windowS / sampleS).roundToInt()
            if (w < 4 || w >= filled) continue

            var quiet = 0
            for (k in 0..w) if (quietHist[slot(k)]) quiet++
            val quietFrac = quiet.toFloat() / (w + 1)
            if (quietFrac < QUIET_MIN) continue

            val (slope, r2) = regress(energyHist, w)
            val riseE = slope * w
            if (riseE <= 0f) continue
            val (cSlope, _) = regress(centroidHist, w)

            val score = gate(RISE_MIN, RISE_FULL, riseE) *
                gate(R2_MIN, R2_FULL, r2) *
                gate(QUIET_MIN, QUIET_FULL, quietFrac) *
                (0.5f + 0.5f * build)
            if (score > (best?.strength ?: 0f)) {
                best = Hit(
                    strength = score,
                    durationS = windowS,
                    fromPan = 0f,
                    toPan = 0f,
                    rise = (cSlope * w).coerceIn(0f, 1f),
                )
            }
        }
        return best
    }

    /**
     * Least-squares line through the last [w] + 1 samples of [ring], oldest
     * first. Returns the slope per sample and the fit's r².
     */
    private fun regress(ring: FloatArray, w: Int): Pair<Float, Float> {
        val n = w + 1
        var sx = 0f; var sy = 0f; var sxy = 0f; var sxx = 0f
        for (k in 0..w) {
            val x = (w - k).toFloat()   // 0 is the oldest sample in the window
            val y = ring[slot(k)]
            sx += x; sy += y; sxy += x * y; sxx += x * x
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9f) return 0f to 0f
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        val meanY = sy / n
        var ssTot = 0f; var ssRes = 0f
        for (k in 0..w) {
            val x = (w - k).toFloat()
            val y = ring[slot(k)]
            val d = y - meanY
            val e = y - (slope * x + intercept)
            ssTot += d * d; ssRes += e * e
        }
        val r2 = if (ssTot > 1e-9f) (1f - ssRes / ssTot).coerceIn(0f, 1f) else 0f
        return slope to r2
    }

    private fun gate(lo: Float, hi: Float, v: Float): Float {
        if (hi <= lo) return if (v >= hi) 1f else 0f
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

enum class GestureKind { NONE, TRAVERSAL, SWELL }

/**
 * A room gesture the engine should render, or [GestureKind.NONE] for none.
 *
 * [id] changes only on the frame a gesture is launched, so a renderer launches
 * on a change and never on a threshold — the same problem `waveArmed` exists to
 * solve for beat wavefronts, solved once here instead of again there.
 */
data class GestureState(
    val id: Long = 0L,
    val kind: GestureKind = GestureKind.NONE,
    /** Where the sound started, −1 hard left to +1 hard right. */
    val fromPan: Float = 0f,
    /** Where it ended. */
    val toPan: Float = 0f,
    /** Measured, never assumed: a slow pad and a fast riser must not look alike. */
    val durationS: Float = 0f,
    /** Confidence × size, 0..1. The renderer fades in over the low end of it. */
    val strength: Float = 0f,
    /** How far the spectral centroid climbed — the "bottom to top" cue. */
    val rise: Float = 0f,
    /** How stereo the material was. 0 means mono; see [GestureTracker.stereo]. */
    val stereo: Float = 0f,
)

// ── Tuning ────────────────────────────────────────────────────────────────
//
// Starting values, reasoned from the signal rather than measured against a
// corpus. Step five of the plan is to move them against real tracks.

/** Store one sample in five, so pan history runs at 10 Hz. */
private const val DECIMATE = 5

/**
 * How much history to keep, in seconds.
 *
 * The decimation is itself a second, slower filter on top of the analyzer's
 * ~100 ms pan smoothing — which was tuned for the static per-lamp bias, where
 * responsiveness matters, and is faster than a traversal detector wants. Doing
 * it here rather than by slowing `panSmooth` leaves the shipped behaviour of a
 * feature that already works exactly as it was.
 */
private const val HISTORY_S = 4.0f

/** Window lengths scored, in seconds. A traversal is 0.5–4 s by ear. */
private val WINDOWS_S = floatArrayOf(0.6f, 1.2f, 2.4f, 4.0f)

/** Pan excursion: below [EXC_MIN] it is jitter, at [EXC_FULL] it crossed the room. */
private const val EXC_MIN = 0.30f
private const val EXC_FULL = 0.85f

/**
 * The same excursion measured in absolute pan rather than against the mix.
 *
 * Lower than [EXC_MIN] because a mix partly follows a loud source across, so a
 * genuine traversal's absolute movement can be a little smaller than its
 * movement relative to the mix.
 */
private const val ABS_EXC_MIN = 0.20f
private const val ABS_EXC_FULL = 0.60f

/** Midpoint crossings permitted across the history. One is the traversal itself. */
private const val MAX_CROSSINGS = 1

/** Dead zone around the midpoint, so resting near it is not a crossing. */
private const val CROSS_DEADZONE = 0.05f

/**
 * Monotonicity — net movement over total movement.
 *
 * A steady sweep scores near 1; a vibrato-ish wobble that ends where it started
 * scores near 0. This single ratio is what separates the two, and it needs no
 * history beyond what is already stored.
 */
private const val MONO_MIN = 0.70f
private const val MONO_FULL = 0.92f

/** The bin must have stayed audible for the whole window. */
private const val ENERGY_MIN = 0.08f
private const val ENERGY_FULL = 0.20f

/** Per-bin stereo spread below which the material is mono in all but name. */
private const val STEREO_MIN = 0.02f
private const val STEREO_FULL = 0.10f
private const val STEREO_TAU_S = 5.0f

/** A neighbouring bin must score at least this fraction of the winner's score. */
private const val ADJACENCY_FRACTION = 0.5f

/** Flux below which a frame counts as having nothing hitting in it. */
private const val FLUX_QUIET = 0.25f

/** Total energy climb across the window. */
private const val RISE_MIN = 0.02f
private const val RISE_FULL = 0.15f

/** How straight the climb has to be. */
private const val R2_MIN = 0.55f
private const val R2_FULL = 0.85f

/** Fraction of the window with no onset in it. */
private const val QUIET_MIN = 0.75f
private const val QUIET_FULL = 0.98f

/** Score a detection must reach to launch anything at all. */
private const val START = 0.55f

/** Shortest and longest gesture worth rendering. */
private const val MIN_DURATION_S = 0.6f
private const val MAX_DURATION_S = 6.0f

/** Quiet period after a gesture ends, on top of the gesture's own length. */
private const val REFRACTORY_MIN_S = 2.0f

/** The budget, and the window it is measured over. */
private const val MAX_PER_MIN = 4
private const val BUDGET_WINDOW_S = 60f
