package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Musical key detection: a tuning-corrected pitch-class profile correlated
 * against major and minor key templates.
 *
 * Correlating a track's accumulated pitch-class energy against all 24 rotations
 * (12 tonics × major/minor) is the standard way to turn "how much energy landed
 * on each pitch class" into "what key is this". The correlation was never the
 * weak part. **The chroma was**, and in four separate ways:
 *
 * 1. **Resolution.** The live [chromaProjection] assigns each FFT bin to its
 *    nearest semitone at weight 1.0 from [CHROMA_FMIN] = 80 Hz up. At the
 *    analyser's geometry an FFT bin is `22050/2048` ≈ 10.8 Hz wide while a
 *    semitone at 80 Hz is 4.6 Hz, so across the bottom of that range several
 *    semitones share one bin and the assignment is very nearly arbitrary. The
 *    bass is the most tonally decisive part of most mixes and it was voting at
 *    random.
 *
 * 2. **Leakage, which is worse than resolution and is the one that decided the
 *    design here.** The analysis window is 1024 samples — 46 ms — so a partial's
 *    main lobe is about 43 Hz wide however finely the transform is sampled. At
 *    200 Hz that is three and a half semitones. *No* scheme that assigns raw bin
 *    magnitudes to pitch classes can work across the low end, however carefully
 *    it weights the assignment, because the energy genuinely is spread over
 *    three semitones. So this reads **interpolated spectral peaks** instead: a
 *    parabola through a peak and its two neighbours locates the partial to a
 *    fraction of a bin no matter how wide its lobe is.
 *
 * 3. **Tuning.** A hard semitone grid assumes A440. Real records are not: the
 *    album this was measured against sits some 4–7 cents sharp and one track on
 *    it is 24 cents flat. A fixed grid smears a detuned track across neighbouring
 *    pitch classes, which is exactly the error that flattens the best-versus-
 *    second-best gap [detectKey] reports as confidence. The offset is measured
 *    off the same interpolated peaks ([KeyChroma.tuningCents]) and divided out
 *    before the fold to twelve.
 *
 * 4. **Weighting.** A raw magnitude sum lets the loudest twenty seconds of a
 *    track decide its key and lets broadband percussion pile a flat pedestal onto
 *    all twelve classes. Every frame is now gated on tonalness, normalised to
 *    unit sum, and added — one vote each.
 *
 * The measure of all this is `tools/analysis-harness`: transpose a track up by
 * three semitones and its detected key must move up by exactly three, whatever
 * the true key is. The old path scored 12.4 % on that, against a 1-in-12 chance
 * floor — which is to say it was reading almost nothing.
 *
 * Deliberately its own file rather than folded into [TrackAnalysis] — the same
 * split as [SyncoPalette] out of `SyncoEngine.kt`: this has nothing to do with
 * the offline extractor's STFT plumbing, and it is all pure and unit-testable.
 * [chromaProjection] in `AudioAnalyzer.kt` is untouched and still used by
 * [SongPalette] for the live per-frame dominant-pitch-class hue, which is a
 * different job under a different constraint — it needs an answer this hop, not
 * the best answer over a track.
 */

enum class MusicalMode { MAJOR, MINOR }

data class MusicalKey(val tonic: Int, val mode: MusicalMode, val confidence: Float)

/**
 * Sub-bins per semitone in the accumulator.
 *
 * Five puts a sub-bin every 20 cents, fine enough that rotating by the measured
 * tuning is a correction rather than a re-quantisation, and coarse enough that
 * the whole accumulator is sixty doubles.
 */
internal const val SUBBINS = 5

/** Accumulator width: twelve pitch classes at [SUBBINS] each. */
internal const val KEY_CHROMA_BINS = 12 * SUBBINS

/**
 * The band peaks are read from, in Hz.
 *
 * The floor is not about resolution — peak interpolation handles that — but
 * about *accuracy of the interpolation*: the fitted peak position is good to
 * roughly a tenth of a bin, which is about 1 Hz here, and 1 Hz is 22 cents at
 * 80 Hz against 9 cents at 200 Hz and 4 cents at 400 Hz. Below the floor the
 * frequency estimate is no longer precise enough to place a note inside its own
 * semitone.
 *
 * The ceiling is about harmony rather than precision: high partials belong to
 * progressively less related pitch classes — the seventh harmonic is nearly a
 * tritone out — so extending it upward adds more confusion than evidence.
 */
internal const val KEY_CHROMA_FMIN = 180f
internal const val KEY_CHROMA_FMAX = 2400f

/** Below this total energy the chroma vector is too thin to trust a key from. */
private const val MIN_CHROMA_ENERGY = 1e-6

/**
 * Onset broadbandness above which a frame is treated as percussion and
 * contributes nothing to the chroma.
 *
 * The same measure [AutoIntensityPicker] calibrates `CHAR_ATTACK_LO/HI` against:
 * a drum kit splashes across the whole filterbank, a sung tone or a pad does
 * not. A cymbal has no pitch class to offer and, being loud and flat, was
 * contributing to all twelve of them equally.
 */
private const val TONAL_WIDTH_LO = 0.10f
private const val TONAL_WIDTH_HI = 0.30f

/**
 * A confident correlation gap, in the -1..1 range [correlation] returns, on the
 * far side of which [MusicalKey.confidence] saturates to 1.0.
 *
 * Measured rather than assumed, now that there is something to measure against:
 * across the harness corpus a key that survives all eleven transpositions
 * separates from its runner-up by roughly this much, and one that does not
 * usually sits well below it. Two keys a fifth or a relative-minor apart scoring
 * alike remains this method's standard failure and still reads as uncertain,
 * which is what it is.
 */
private const val CONFIDENT_GAP = 0.22

// ── Key profiles ───────────────────────────────────────────────────────────

/**
 * Krumhansl & Kessler's 1982 probe-tone weights — how strongly each pitch class
 * is felt to belong to a key once the tonic is fixed at index 0.
 *
 * Kept because the harness compares against them, but not what ships: see
 * [TEMPERLEY_MAJOR].
 */
internal val KK_MAJOR = doubleArrayOf(
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88,
)
internal val KK_MINOR = doubleArrayOf(
    6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17,
)

/**
 * Temperley's 2001 revision, and what this detector uses.
 *
 * K-K's weights come from a probe-tone listening study — how well a note *fits*
 * a key once you are already in it. That is a different question from how often
 * a note *occurs* in music in that key, and the gap between the two shows up as
 * the relative major/minor confusion K-K is known for: its minor profile puts
 * enough weight on the relative major's tonic that the two rotations score
 * alike. The album this was measured against is mostly minor and the old
 * detector called most of it major, which is that failure exactly.
 */
internal val TEMPERLEY_MAJOR = doubleArrayOf(
    5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0,
)
internal val TEMPERLEY_MINOR = doubleArrayOf(
    5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0,
)

/**
 * Where a peak's energy is credited, and how much of it goes to each place.
 *
 * A peak is not necessarily a fundamental. It is at least as likely to be a
 * harmonic of something lower, and harmonics do not land on musically neutral
 * ground: the third lands a fifth above its fundamental, and the fifth lands a
 * major third above it. So a chroma built by crediting every peak to its own
 * pitch class systematically manufactures the dominant of whatever is playing,
 * and manufactures a major third over whatever is playing.
 *
 * Both errors were visible in the harness before this existed, and they were the
 * dominant failure mode rather than a rounding effect: on the tracks it got
 * wrong, the detector picked a key **exactly a fifth above** the right one, over
 * and over, on eight separate tracks. It was hearing the third harmonic and
 * calling it the tonic.
 *
 * The fix is the standard one — credit each peak to every fundamental it could
 * be the n-th harmonic of, with geometrically decreasing confidence. Entries are
 * `(semitones below the peak, weight)` for harmonics 1 to 5 at `0.6^(n-1)`,
 * reduced modulo the octave since the output is octave-invariant anyway: the
 * octave harmonics 2 and 4 collapse onto the peak's own class, the third lands
 * 7.02 semitones below it, and the fifth 3.86 below.
 *
 * Not tempered intervals — the real ones. A just fifth is 7.02 semitones and not
 * 7, and since the accumulator resolves 20 cents there is no reason to round a
 * quantity that is known exactly.
 */
private val HARMONIC_CREDIT = arrayOf(
    // harmonics 1, 2 and 4 all fold onto the peak's own pitch class
    0.0f to (1.0f + 0.6f + 0.216f),
    19.0196f % 12f to 0.36f,     // the third harmonic: a fifth above its fundamental
    27.8631f % 12f to 0.1296f,   // the fifth harmonic: a major third above it
)

// ── The chroma accumulator ─────────────────────────────────────────────────

/**
 * A peak-based, tuning-aware pitch-class accumulator over a whole track.
 *
 * Reads interpolated spectral peaks rather than binned magnitudes, for the
 * reason set out at the top of this file: at this analyser's window length the
 * low end's main lobes are wider than the semitones they have to be sorted
 * into, so the only quantity down there that survives is the *position* of a
 * partial, not the distribution of its energy.
 *
 * It is also far cheaper than what it replaces. The dense projection matrix was
 * `binCount × 12` floats of which each row held exactly one non-zero entry, and
 * only rows inside the band held even that — so a fold cost 12,300 multiply-adds
 * a frame to do the work of about 170, and over a twelve-minute track spent some
 * 440 million operations multiplying by zero. A peak pass is one comparison per
 * bin plus a few multiplies per peak, and there are a few dozen peaks in a frame.
 *
 * Not thread-safe, and does not need to be: one of these belongs to one
 * [OfflineExtractor], which belongs to one scan on one thread.
 */
internal class KeyChroma(
    freqs: FloatArray,
    private val fmin: Float = KEY_CHROMA_FMIN,
    private val fmax: Float = KEY_CHROMA_FMAX,
) {
    /** Hz per FFT bin — the spectrum is uniformly spaced, so one number covers it. */
    private val binHz: Float = if (freqs.size > 1) freqs[1] - freqs[0] else 1f

    /** Search bounds, with a bin either side spare for the interpolation. */
    private val binLo = max(1, (fmin / binHz).toInt())
    private val binHi = min(freqs.size - 2, (fmax / binHz).toInt() + 1)

    /** Running sub-binned total, [KEY_CHROMA_BINS] wide. */
    private val accum = DoubleArray(KEY_CHROMA_BINS)

    /** This frame's contribution, before it is normalised and added. */
    private val frame = FloatArray(KEY_CHROMA_BINS)

    /** Reused output for [frameChroma], which is called once per analysis hop. */
    private val twelve = FloatArray(12)

    /** Circular accumulator for the tuning deviation. See [tuningCents]. */
    private var tuneRe = 0.0
    private var tuneIm = 0.0

    /** How many frames cleared the gates and contributed. Reported by the harness. */
    var framesUsed = 0
        private set

    /**
     * Fold one frame's magnitude spectrum in.
     *
     * [width] is the frame's onset broadbandness, as computed by
     * [OfflineExtractor]. A percussive frame is discarded rather than
     * down-weighted, because a cymbal's contribution is not a quieter opinion
     * about the key — it is not an opinion about the key.
     *
     * The frame is normalised to unit sum before it is added, so a chorus and
     * the verse before it each get one vote. That is also what makes the silence
     * test sufficient: an all-but-silent frame is rejected on its raw sum, so
     * the normalisation can never amplify a noise floor to full scale.
     */
    fun push(mag: FloatArray, width: Float) {
        if (width >= TONAL_WIDTH_HI) return
        if (binHi <= binLo) return

        java.util.Arrays.fill(frame, 0f)
        var total = 0f
        var re = 0.0
        var im = 0.0

        var loudest = 0f
        for (i in binLo..binHi) if (mag[i] > loudest) loudest = mag[i]
        if (loudest <= 0f) return
        val floorLevel = loudest * PEAK_FLOOR_FRACTION

        for (i in binLo..binHi) {
            val m = mag[i]
            if (m < floorLevel) continue
            // A local maximum, with the tie broken one way round so a flat pair
            // is counted once rather than twice.
            if (m <= mag[i - 1] || m < mag[i + 1]) continue

            val offset = peakOffset(mag, i)
            val amp = peakHeight(mag, i, offset)
            val f = (i + offset) * binHz
            if (f < fmin || f > fmax) continue

            val midi = 69.0 + 12.0 * log2(f / 440.0)
            // Deviation from the nearest semitone, in semitones. Continuous,
            // which is the whole reason for interpolating: read off the bin grid
            // instead, this quantity describes where the FFT put its bins rather
            // than where the music put its notes. See [tuningCents].
            val dev = midi - Math.round(midi)
            val phase = 2.0 * PI * dev
            re += amp * cos(phase)
            im += amp * sin(phase)

            val pos = ((midi - 60.0) * SUBBINS)
            for ((semitonesDown, weight) in HARMONIC_CREDIT) {
                addAt(frame, pos - semitonesDown * SUBBINS, amp * weight)
                total += amp * weight
            }
        }
        if (total <= FRAME_MIN_ENERGY) return

        // Tonalness ramp: fully counted below TONAL_WIDTH_LO, fading out to the
        // hard rejection above.
        val tonal = if (width <= TONAL_WIDTH_LO) {
            1f
        } else {
            1f - (width - TONAL_WIDTH_LO) / (TONAL_WIDTH_HI - TONAL_WIDTH_LO)
        }
        val scale = tonal / total
        for (i in 0 until KEY_CHROMA_BINS) accum[i] += (frame[i] * scale).toDouble()
        tuneRe += re * scale
        tuneIm += im * scale
        framesUsed++
    }

    /**
     * This one frame's twelve-bin chroma, L2-normalised, into a reused buffer.
     *
     * For callers that compare frames rather than accumulate them — the metre
     * detector's harmonic-change term, which asks whether the chord changed
     * between two beats. Unlike [push] this is neither gated nor tuning-
     * corrected, and both omissions are deliberate: a percussive frame still has
     * to occupy its slot in the row store so frame indices line up with
     * everything else, and a constant tuning offset cancels out of a comparison
     * between two frames of the same track.
     *
     * Normalised because the question is *which* pitch classes are sounding, not
     * how loudly — otherwise every distance would be dominated by the track
     * getting louder.
     */
    fun frameChroma(mag: FloatArray): FloatArray {
        java.util.Arrays.fill(twelve, 0f)
        if (binHi > binLo) {
            var loudest = 0f
            for (i in binLo..binHi) if (mag[i] > loudest) loudest = mag[i]
            if (loudest > 0f) {
                val floorLevel = loudest * PEAK_FLOOR_FRACTION
                for (i in binLo..binHi) {
                    val m = mag[i]
                    if (m < floorLevel || m <= mag[i - 1] || m < mag[i + 1]) continue
                    val offset = peakOffset(mag, i)
                    val f = (i + offset) * binHz
                    if (f < fmin || f > fmax) continue
                    val midi = 69.0 + 12.0 * log2(f / 440.0)
                    val amp = peakHeight(mag, i, offset)
                    // Same harmonic credit as [push], so "did the chord change"
                    // is asked of the same quantity the key is read from.
                    for ((semitonesDown, weight) in HARMONIC_CREDIT) {
                        val pc = Math.floorMod(Math.round(midi - semitonesDown).toInt(), 12)
                        twelve[pc] += amp * weight
                    }
                }
            }
        }
        var sq = 0f
        for (v in twelve) sq += v * v
        if (sq > 1e-12f) {
            val inv = 1f / sqrt(sq)
            for (i in 0 until 12) twelve[i] *= inv
        }
        return twelve
    }

    /**
     * The track's tuning offset from A440 in cents, in (-50, +50].
     *
     * The amplitude-weighted circular mean of every peak's deviation from the
     * semitone grid. Circular because the quantity wraps: +49 cents and −49
     * cents are two cents apart, not ninety-eight, and an arithmetic mean over a
     * distribution straddling the wrap would land near zero however far out the
     * music actually was.
     *
     * Measured off interpolated peaks and not off bin assignments, which is the
     * distinction the first attempt at this got wrong and the harness caught.
     * FFT bins are spaced linearly and pitch classes logarithmically, so the
     * *positions of the bins themselves* modulo a semitone are strongly
     * non-uniform across the bottom of the band — and a statistic gathered over
     * them therefore reports the shape of the bin grid, with a bias of over ten
     * cents, whatever the music is doing. That version read every track on a
     * +4-to-+7-cent album as 13 to 18 cents flat, and responded to a real 30-cent
     * detune with 13 cents of movement.
     */
    val tuningCents: Float
        get() {
            if (tuneRe * tuneRe + tuneIm * tuneIm < 1e-18) return 0f
            val semitones = atan2(tuneIm, tuneRe) / (2.0 * PI)
            return (semitones * 100.0).toFloat()
        }

    /**
     * The twelve-bin pitch-class vector, with the measured tuning divided out.
     *
     * The rotation is fractional and interpolated, which is the point: rounding
     * it to the nearest sub-bin would put back a quantisation error of up to ten
     * cents, having just gone to the trouble of measuring it to better than that.
     */
    fun fold(): FloatArray {
        // Positive cents means the record sits sharp of A440, so its energy is
        // above the sub-bin it belongs in and the accumulator has to be read
        // from higher up to bring it back down.
        val shift = tuningCents / 100.0 * SUBBINS
        val out = FloatArray(12)
        // Rotate, then fold. The other order would collapse the sub-bin
        // structure the rotation is defined over before using it.
        for (i in 0 until KEY_CHROMA_BINS) {
            val src = i + shift
            val base = floor(src).toInt()
            val f = src - base
            val a = Math.floorMod(base, KEY_CHROMA_BINS)
            val b = if (a == KEY_CHROMA_BINS - 1) 0 else a + 1
            out[i / SUBBINS] += (accum[a] * (1.0 - f) + accum[b] * f).toFloat()
        }
        return out
    }

    /**
     * Add [amount] at a fractional sub-bin position, split across the two
     * sub-bins either side of it and wrapped round the octave.
     */
    private fun addAt(into: FloatArray, position: Double, amount: Float) {
        var pos = position % KEY_CHROMA_BINS
        if (pos < 0) pos += KEY_CHROMA_BINS
        val base = floor(pos).toInt()
        val frac = (pos - base).toFloat()
        val a = base % KEY_CHROMA_BINS
        val b = if (a == KEY_CHROMA_BINS - 1) 0 else a + 1
        into[a] += amount * (1f - frac)
        into[b] += amount * frac
    }

    /**
     * Sub-bin position of the peak at [i], from a parabola through its log
     * magnitude and that of its two neighbours.
     *
     * In the log domain rather than the linear one because a windowed sinusoid's
     * main lobe is close to Gaussian, and a Gaussian is exactly a parabola once
     * logged — so the fit describes the shape that is actually there. That is
     * what makes the frequency estimate independent of how wide the lobe is,
     * which matters enormously at the bottom of the band.
     */
    private fun peakOffset(mag: FloatArray, i: Int): Float {
        val a = ln((mag[i - 1] + PEAK_EPS).toDouble())
        val b = ln((mag[i] + PEAK_EPS).toDouble())
        val c = ln((mag[i + 1] + PEAK_EPS).toDouble())
        val denom = a - 2.0 * b + c
        if (denom >= -1e-12) return 0f
        return (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5).toFloat()
    }

    /** The fitted peak height, a little above the sampled one when the true peak falls between bins. */
    private fun peakHeight(mag: FloatArray, i: Int, offset: Float): Float {
        val a = ln((mag[i - 1] + PEAK_EPS).toDouble())
        val b = ln((mag[i] + PEAK_EPS).toDouble())
        val c = ln((mag[i + 1] + PEAK_EPS).toDouble())
        return exp(b - 0.25 * (a - c) * offset).toFloat()
    }

    private companion object {
        /** A frame contributing less than this is silence, not a quiet chord. */
        const val FRAME_MIN_ENERGY = 1e-6f

        /**
         * Peaks below this fraction of the frame's loudest are not partials.
         *
         * Low enough to keep the quieter voices of a chord, high enough that the
         * ripple either side of a strong partial does not register as a note of
         * its own.
         */
        const val PEAK_FLOOR_FRACTION = 0.02f

        /** Keeps the log finite where a neighbouring bin is exactly zero. */
        const val PEAK_EPS = 1e-9f
    }
}

// ── Detection ──────────────────────────────────────────────────────────────

/**
 * The best-fit tonic and mode for a 12-bin pitch-class energy vector
 * (0=C .. 11=B), or null when there is not enough tonal signal to trust — a
 * near-silent or purely percussive track, for instance.
 *
 * [MusicalKey.confidence] is the gap between the best and second-best
 * correlation, not the correlation itself: two keys a fifth apart scoring almost
 * identically is a common ambiguity for this method and should read as uncertain
 * even when the top score is high. [MusicDnaLayer] takes that literally,
 * interpolating how hard the key anchors the room's hue by exactly this number.
 */
internal fun detectKey(chroma: FloatArray): MusicalKey? = detectKey(chroma, TEMPERLEY_MAJOR, TEMPERLEY_MINOR)

/**
 * [detectKey] against a nominated pair of profiles, so the harness can compare
 * them on the same chroma rather than on two separate runs of the analyser.
 */
internal fun detectKey(
    chroma: FloatArray,
    majorProfile: DoubleArray,
    minorProfile: DoubleArray,
): MusicalKey? {
    if (chroma.size != 12) return null
    var energy = 0.0
    for (v in chroma) energy += v.toDouble()
    if (energy < MIN_CHROMA_ENERGY) return null

    val vec = DoubleArray(12) { chroma[it].toDouble() }

    var bestScore = -2.0
    var secondBest = -2.0
    var bestTonic = 0
    var bestMode = MusicalMode.MAJOR

    val rotated = DoubleArray(12)
    for (tonic in 0 until 12) {
        for (mode in MusicalMode.entries) {
            val profile = if (mode == MusicalMode.MAJOR) majorProfile else minorProfile
            for (i in 0 until 12) rotated[i] = profile[(i - tonic + 12) % 12]
            val score = correlation(vec, rotated)
            if (score > bestScore) {
                secondBest = bestScore
                bestScore = score
                bestTonic = tonic
                bestMode = mode
            } else if (score > secondBest) {
                secondBest = score
            }
        }
    }

    val gap = (bestScore - secondBest).coerceIn(0.0, 2.0)
    val confidence = (gap / CONFIDENT_GAP).coerceIn(0.0, 1.0).toFloat()
    return MusicalKey(tonic = bestTonic, mode = bestMode, confidence = confidence)
}

/** Pearson correlation of two equal-length vectors; 0 when either is flat. */
private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    val meanA = a.average()
    val meanB = b.average()
    var cov = 0.0
    var varA = 0.0
    var varB = 0.0
    for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        cov += da * db
        varA += da * da
        varB += db * db
    }
    val denom = sqrt(varA * varB)
    return if (denom > 1e-12) cov / denom else 0.0
}
