package com.engabd.sendpin.audio

import com.engabd.sendpin.hue.CHAR_ATTACK_HI
import com.engabd.sendpin.hue.CHAR_ATTACK_LO
import com.engabd.sendpin.hue.CHAR_BUSY_FULL
import com.engabd.sendpin.hue.PICK_BEAT_FULL
import com.engabd.sendpin.hue.PICK_BPM_HI
import com.engabd.sendpin.hue.PICK_BPM_LO
import com.engabd.sendpin.hue.intensitySignal
import com.engabd.sendpin.hue.songCharacter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The offline half of the analysis: everything a whole track can be asked that a
 * frame at a time cannot.
 *
 * Ported from syncoV2 `audio/trackmap.py`, and deliberately a *narrow* port —
 * see [TrackScan] for what is left out and why. What is here:
 *
 * - **[OfflineExtractor]** — the same SuperFlux filterbank flux as the live
 *   [AudioAnalyzer], run over the whole file. It shares that class's window,
 *   hop, FFT size, filterbank and melbank rather than declaring its own, which
 *   is not tidiness: a scanned frame and a live frame have to mean the same
 *   thing, or the two halves of the show disagree by a fraction of a band.
 * - **Tempo** — autocorrelation under a log-Gaussian prior with a harmonic-comb
 *   octave guard, then a tempogram and a Viterbi path so a drifting drummer
 *   reads as locally-steady tempo instead of one smeared global peak.
 * - **Beats** — Ellis dynamic programming against that per-frame period. Unlike
 *   any causal tracker it cannot be fooled by a fill or a quiet bar, because it
 *   can see the future.
 * - **Sections** — a checkerboard kernel over a cosine self-similarity matrix.
 * - **[buildIntensityProfile]** — the four Auto-picker parameters that have been
 *   taking their defaults since the picker was ported.
 *
 * No Android imports: this is where the maths lives and it is all unit-testable
 * on the JVM. Decoding is [TrackScanner]'s problem.
 */

internal const val FRAME_PERIOD = ANALYSIS_HOP.toFloat() / ANALYSIS_SAMPLE_RATE

// Tempo prior. Matches the live tracker's range and centre, on purpose: the two
// must not disagree about what tempo a song even is.
private const val MIN_BPM = 70.0
private const val MAX_BPM = 190.0
private const val PRIOR_CENTER_BPM = 120.0
private const val PRIOR_WIDTH = 0.55

/**
 * How hard the Ellis DP punishes deviation from the tempo period relative to
 * onset strength. librosa uses 100 on a comparable scale.
 */
private const val TIGHTNESS = 100.0

// Section segmentation.
private const val BLOCK_S = 0.75f
private const val KERNEL_BLOCKS = 8       // checkerboard half-size, ~6 s of context
private const val MIN_SECTION_S = 8.0f

/** Under this many frames (~4 s) there is nothing worth analysing. */
internal const val MIN_ANALYSIS_FRAMES = 200

/** A p95 RMS under this is digital silence, not a quiet track. */
internal const val MIN_AUDIO_RMS = 1e-4

// Grid-quality confidence. Beats-on-peaks contrast below the floor reads as
// noise: white noise measures ~2.9, real steady grooves 4.2-5.7.
private const val CONTRAST_FLOOR = 3.4
private const val CONTRAST_RANGE = 1.6
private const val GRID_CONF_CAP = 0.85
private const val TIGHT_MAD_FULL = 0.05
private const val TIGHT_MAD_ZERO = 0.25

// Local tempo path.
private const val TG_WIN_S = 10.0f
private const val TG_HOP_S = 1.0f
private const val TG_WHITEN_S = 0.5f
private const val TEMPO_TRANS_PENALTY = 40.0
private const val PATH_STABLE_TOL = 0.04

/**
 * Autocorrelation upsampling factor, and the fix for 140 BPM.
 *
 * The beat period is fractional in frames — 140 BPM is 21.43 of them — while a
 * spiky onset envelope makes the true peak sharper than the frame grid. Scored
 * at integer lags only, the true period can lose to its own double: 42.86 rounds
 * onto 43 and aligns, while 21.43 splits its energy between 21 and 22. That is a
 * guaranteed octave error, not an unlucky one. Band-limited upsampling recovers
 * the fractional-lag peaks before anything is scored.
 */
private const val TG_UPSAMPLE = 4

/** Floor on the normalisation window, so a near-constant track can't divide by ~0. */
private const val MIN_SIG_SPAN = 0.06f

/**
 * The section-intensity curve is a **centred** moving average over this window.
 *
 * Centred, and that is the point. The live picker's causal envelope necessarily
 * lags the music; with the whole track in hand the value at a frame can reflect
 * the section that frame belongs to, so a rung switch lands on the section change
 * instead of a time constant later. Wide enough to smooth beat-to-beat pumping,
 * narrow enough to keep verse/chorus/drop contrast crisp.
 */
private const val SECTION_WIN_S = 1.4f

/** The intensity curve is persisted at this rate. See [IntensityProfile.curve]. */
internal const val CURVE_RATE_HZ = 10f

// ── Streamed feature extraction ────────────────────────────────────────────

/**
 * Streamed STFT feature extraction over a whole track, at constant memory.
 *
 * Feed arbitrary-length mono float32 chunks with [push]; the per-frame envelopes
 * accumulate. The framing, filterbank and melbank all come from [AudioAnalyzer]'s
 * geometry — see this file's header for why that is load-bearing.
 */
internal class OfflineExtractor(sampleRate: Int = ANALYSIS_SAMPLE_RATE) {

    private val window = ANALYSIS_WINDOW
    private val hop = ANALYSIS_HOP
    private val hann = FloatArray(window) { i ->
        (0.5f - 0.5f * cos(2f * PI.toFloat() * i / (window - 1)))
    }
    private val fft = Fft(NFFT)
    private val freqs = analysisBinFreqs(sampleRate)

    private val fb = makeOnsetFilterbank(freqs)
    private val fbStarts = fb.first
    private val fbCounts = fb.second
    val nBass = fb.third.first
    val nMid = fb.third.second

    private val mel = makeMelbank(freqs)
    private val melStarts = mel.first
    private val melCounts = mel.second

    private val namedBins = namedBandBins(freqs)

    /**
     * Whole-track chroma, for key detection. Same projection matrix and same
     * per-frame accumulation the live analyzer uses (`AudioAnalyzer.push`'s
     * "Live chroma" block) — summed across the whole track instead of reset
     * every frame, since a key is a property of the song, not the instant.
     */
    private val chromaProj: FloatArray = chromaProjection(freqs, CHROMA_FMIN, CHROMA_FMAX)
    private val chromaAccum = FloatArray(12)

    /** Accumulated 12-bin pitch-class energy (0=C .. 11=B) over every frame seen. */
    val chroma: FloatArray get() = chromaAccum.copyOf()

    /** Sliding window over the input, with the newest samples at the end. */
    private val buf = FloatArray(window)

    /** Input samples buffered but not yet consumed by a frame. */
    private var pending = 0

    /** True until the first full hop has been consumed. See [push]. */
    private var priming = true

    private var prevLog: FloatArray? = null
    private var prevLin: FloatArray? = null

    // Per-frame outputs. Flat growable arrays rather than lists of boxed floats:
    // a six-minute track is 18000 frames and the filterbank rows alone would be
    // 18000 objects otherwise.
    val env = FloatList()        // SuperFlux onset envelope
    val bassEnv = FloatList()
    val midEnv = FloatList()
    val width = FloatList()      // onset broadbandness, 0..1
    val rms = FloatList()
    val centroid = FloatList()
    private val bandRows = FloatList()   // nBands per frame, row-major
    private val namedRows = FloatList()  // 5 per frame, row-major
    private val melRows = FloatList()    // MELBANK_BINS per frame, row-major

    /** Per-frame linear-domain mid dominance, the live detector's own gate. */
    val midDominant = BooleanList()

    val frames: Int get() = env.size
    val nBands: Int get() = fbStarts.size

    fun bandsAt(frame: Int, band: Int): Float = bandRows[frame * nBands + band]
    fun namedAt(frame: Int, band: Int): Float = namedRows[frame * namedBins.size + band]
    fun melAt(frame: Int, bin: Int): Float = melRows[frame * MELBANK_BINS + bin]

    /**
     * Analyse a chunk of mono samples at the analysis rate.
     *
     * Framing mirrors the live analyzer's sliding window **exactly**, including
     * the primed first frame: hop 0 arrives against a zeroed buffer there too,
     * so frame `i` covers the same samples on both paths and a frame index means
     * one thing in this app.
     *
     * This is a deliberate divergence from syncoV2, whose offline extractor
     * frames as `buf[i*hop : i*hop+window]` — labelling each frame by its window
     * *start* while its live analyzer labels by the window *end* minus a hop.
     * The two conventions differ by `window - hop`, about 26 ms, which is why a
     * syncoV2 map's beats sit a touch ahead of where the same track's live
     * detector puts them, and why it carries a timing calibrator to absorb the
     * difference. Since the point of a scan here is that its frames are
     * interchangeable with the tap's, matching the tap is the parity that counts.
     *
     * A trailing partial hop is dropped rather than zero-padded, as syncoV2 also
     * drops it: up to 20 ms at the very end of a track, against a padded final
     * frame whose flux is a fabricated decay into silence.
     */
    fun push(samples: FloatArray, count: Int = samples.size) {
        var offset = 0
        while (offset < count) {
            val take = min(hop - pending, count - offset)
            // Slide `take` samples in at the end, oldest falling off the front.
            System.arraycopy(buf, take, buf, 0, window - take)
            System.arraycopy(samples, offset, buf, window - take, take)
            offset += take
            pending += take
            if (pending == hop) {
                pending = 0
                priming = false
                frame()
            }
        }
    }

    private fun frame() {
        val windowed = FloatArray(window) { buf[it] * hann[it] }
        val mag = fft.magnitudePower(windowed)
        // magnitudePower hands back power; the live analyzer square-roots in
        // place to get linear magnitude and everything downstream assumes it.
        for (i in mag.indices) mag[i] = sqrt(mag[i])

        // Chroma: fold this frame's magnitude spectrum into pitch classes and
        // add it to the running total. Same shape as the live analyzer's own
        // per-frame fold, just summed rather than replaced.
        if (chromaProj.isNotEmpty()) {
            for (i in mag.indices) {
                val m = mag[i]
                if (m <= 0f) continue
                val base = i * 12
                for (p in 0 until 12) {
                    chromaAccum[p] += m * chromaProj[base + p]
                }
            }
        }

        var sq = 0f
        for (v in buf) sq += v * v
        rms.add(sqrt(sq / window))

        val lin = bandMeans(mag, fbStarts, fbCounts)
        val cur = logSpectrum(lin)
        val prevL = prevLog
        val prevLn = prevLin

        // SuperFlux: flux against a max-filtered reference, which kills a low
        // tone's leakage-skirt wobble in the band-limited envelopes too, not
        // just the broadband one.
        val reference = maxFilterFreq(prevL ?: cur)
        var flux = 0f
        var bass = 0f
        var mid = 0f
        var s1 = 0f
        var s2 = 0f
        for (i in cur.indices) {
            val d = cur[i] - reference[i]
            if (d > 0f) {
                flux += d
                s1 += d
                s2 += d * d
                if (i < nBass) bass += d else if (i < nMid) mid += d
            }
        }
        env.add(flux)
        bassEnv.add(bass)
        midEnv.add(mid)
        width.add(if (s2 > 1e-12f) (s1 * s1) / (cur.size * s2) else 0f)

        // Linear-domain shares, leakage-proof, as in the live detector. Without
        // this gate the offline mid stream fires on every kick's attack splash
        // and the "guitar" lights pop on the kicks.
        val linRef = prevLn ?: lin
        var linPos = 0f
        var linBass = 0f
        var linMid = 0f
        for (i in lin.indices) {
            val d = lin[i] - linRef[i]
            if (d > 0f) {
                linPos += d
                if (i < nBass) linBass += d else if (i < nMid) linMid += d
            }
        }
        val denom = max(linPos, 1e-9f)
        val bassShare = linBass / denom
        val midShare = linMid / denom
        midDominant.add(midShare >= 0.30f && midShare > bassShare)

        for (v in lin) bandRows.add(v)
        for ((lo, hi) in namedBins) {
            var p = 0f
            for (i in lo until hi) p += mag[i] * mag[i]
            namedRows.add(sqrt(p / (hi - lo)))
        }
        val melMeans = bandMeans(mag, melStarts, melCounts)
        for (v in melMeans) melRows.add(v)

        var total = 0f
        for (m in mag) total += m
        centroid.add(
            if (total > 1e-9f) {
                var weighted = 0f
                for (i in mag.indices) weighted += freqs[i] * mag[i]
                min(1f, (weighted / total) / 5000f)
            } else 0f
        )

        prevLog = cur
        prevLin = lin
    }
}

/**
 * The fractional phase of the box-filter downsample to the analysis rate.
 *
 * Shared between the live tap and the offline scanner because getting it wrong
 * is silent and total. An integer counter compared against a float ratio does
 * not work: at 48 kHz the ratio is 48000/22050 = 2.177, so `counter >= ratio`
 * first passes at 3 and the analyzer is fed 16 kHz while believing it has
 * 22050 — every band edge off by 1.378x and every envelope constant running
 * slow. Only 44.1 kHz, where the ratio is exactly 2, came out right.
 *
 * Kept as the *phase* alone rather than a full resampler so the tap's inner loop
 * stays a straight line with no callback: the channel accumulators are trivial,
 * and this is the part that has to agree.
 */
internal class BoxResampleClock(private val ratio: Float) {
    private var phase = 0f

    /** Advance by one input frame; returns how many output samples are now due. */
    fun advance(): Int {
        phase += 1f
        var due = 0
        while (phase >= ratio) {
            phase -= ratio
            due++
        }
        return due
    }

    fun reset() {
        phase = 0f
    }
}

/** A growable float array. `ArrayList<Float>` boxes; at 50 Hz that is not free. */
internal class FloatList(initial: Int = 4096) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]

    fun toDoubleArray(): DoubleArray = DoubleArray(size) { data[it].toDouble() }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}

internal class BooleanList(initial: Int = 4096) {
    private var data = BooleanArray(initial)
    var size = 0
        private set

    fun add(v: Boolean) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Boolean = data[i]
}

// ── Tempo ──────────────────────────────────────────────────────────────────

/**
 * Global `(bpm, confidence)` from the onset envelope's autocorrelation.
 *
 * The octave guard is the interesting half. A true fundamental has
 * autocorrelation energy at all its multiples — a harmonic comb — while a
 * double-tempo false peak (eighth notes mistaken for the beat) lacks the
 * sub-multiple support. Only the chosen period's half and double relatives are
 * considered, and only when the comb genuinely prefers one, so clear cases are
 * left alone and only real "every other beat" errors are corrected.
 */
internal fun estimateTempo(env: DoubleArray): Pair<Double, Double> {
    val n = env.size
    if (n < 2) return 0.0 to 0.0
    var mean = 0.0
    for (v in env) mean += v
    mean /= n
    val x = DoubleArray(n) { env[it] - mean }
    var any = false
    for (v in x) if (v != 0.0) { any = true; break }
    if (!any) return 0.0 to 0.0

    val minLag = max(1, (60.0 / MAX_BPM / FRAME_PERIOD).roundToInt())
    val maxLag = min(n - 1, (60.0 / MIN_BPM / FRAME_PERIOD).roundToInt())
    if (maxLag <= minLag) return 0.0 to 0.0

    // The comb reaches out to 3x the chosen lag, so the autocorrelation has to
    // be evaluated that far even though only minLag..maxLag is a candidate.
    val acLimit = min(n - 1, 3 * maxLag)
    val ac = DoubleArray(acLimit + 1)
    for (lag in 0..acLimit) {
        var acc = 0.0
        for (i in 0 until n - lag) acc += x[i] * x[i + lag]
        ac[lag] = acc
    }
    val zero = if (ac[0] > 1e-9) ac[0] else 1e-9

    val count = maxLag - minLag + 1
    val prior = DoubleArray(count) { i ->
        val bpm = 60.0 / ((minLag + i) * FRAME_PERIOD)
        exp(-0.5 * (ln(bpm / PRIOR_CENTER_BPM) / PRIOR_WIDTH).pow(2))
    }
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (i in 0 until count) {
        val s = ac[minLag + i] * prior[i]
        if (s > bestScore) { bestScore = s; best = i }
    }

    fun comb(idx: Int): Double {
        val lag = minLag + idx
        var s = ac[lag]
        if (2 * lag <= acLimit) s += 0.5 * ac[2 * lag]
        if (3 * lag <= acLimit) s += 0.34 * ac[3 * lag]
        return s * prior[idx]
    }

    var chosen = best
    var chosenComb = comb(best)
    for (rel in doubleArrayOf(0.5, 2.0)) {
        val idx = ((minLag + best) * rel).roundToInt() - minLag
        if (idx in 0 until count) {
            val c = comb(idx)
            if (c > chosenComb) { chosenComb = c; chosen = idx }
        }
    }
    val bpm = 60.0 / ((minLag + chosen) * FRAME_PERIOD)
    val confidence = (ac[minLag + chosen] / zero).coerceIn(0.0, 1.0)
    return bpm to confidence
}

/** A tempogram: per-window tempo salience, its lag axis, and the window centres. */
internal class Tempogram(
    /** `[window][lag]` salience. */
    val salience: Array<DoubleArray>,
    val lags: IntArray,
    /** Window centre, in frames. */
    val centres: DoubleArray,
    /** `[window][lag]` fractional lag from the upsampled autocorrelation. */
    val refined: Array<DoubleArray>,
)

/**
 * Sliding-window autocorrelation tempogram of the onset envelope.
 *
 * Each window is whitened — a short rolling mean subtracted — first, so long
 * quiet passages and slow dynamics cannot dilute the local peaks the way they
 * dilute the global autocorrelation. Null when the track is too short to hold
 * one window.
 */
internal fun tempogram(env: DoubleArray): Tempogram? {
    val n = env.size
    val win = (TG_WIN_S / FRAME_PERIOD).roundToInt()
    val hop = (TG_HOP_S / FRAME_PERIOD).roundToInt()
    val minLag = max(1, (60.0 / MAX_BPM / FRAME_PERIOD).roundToInt())
    val maxLag = (60.0 / MIN_BPM / FRAME_PERIOD).roundToInt()
    if (n < win || maxLag * 3 >= win) return null

    val k = max(1, (TG_WHITEN_S / FRAME_PERIOD).roundToInt())
    val white = subtractRollingMean(env, k)
    val nWin = 1 + (n - win) / hop
    val hannWin = DoubleArray(win) { 0.5 - 0.5 * cos(2.0 * PI * it / (win - 1)) }

    val u = TG_UPSAMPLE
    var nfft = 1
    while (nfft < 2 * win) nfft = nfft shl 1
    val fine = Fft(u * nfft)
    val forward = Fft(nfft)

    val lags = IntArray(maxLag - minLag + 1) { minLag + it }
    val salience = Array(nWin) { DoubleArray(lags.size) }
    val refined = Array(nWin) { DoubleArray(lags.size) }
    val centres = DoubleArray(nWin) { (win / 2 + hop * it).toDouble() }

    val half = u / 2  // ±half a frame around each integer lag, on the fine grid
    val spec = FloatArray(nfft * 2)
    val fineBuf = FloatArray(u * nfft * 2)

    val prior = DoubleArray(lags.size) { i ->
        val bpm = 60.0 / (lags[i] * FRAME_PERIOD)
        exp(-0.5 * (ln(bpm / PRIOR_CENTER_BPM) / PRIOR_WIDTH).pow(2))
    }

    val fineLen = u * nfft
    val acn = DoubleArray(u * win)

    for (w in 0 until nWin) {
        val start = w * hop
        java.util.Arrays.fill(spec, 0f)
        for (i in 0 until win) spec[i * 2] = (white[start + i] * hannWin[i]).toFloat()
        forward.forward(spec)

        // Autocorrelation through the power spectrum, zero-padded out to
        // `fineLen` bins before the transform back. Zero-padding in frequency
        // is band-limited interpolation in time, which is what puts real values
        // at the fractional lags between frames — see TG_UPSAMPLE.
        //
        // The power spectrum is real and non-negative, so the sequence it
        // describes is real and even, and its inverse transform equals its
        // forward transform up to a scale the normalisation below divides out.
        // Writing the Hermitian mirror is what makes that hold: filling only
        // the lower half would transform a different signal entirely.
        java.util.Arrays.fill(fineBuf, 0f)
        for (k2 in 0..nfft / 2) {
            val re = spec[k2 * 2]
            val im = spec[k2 * 2 + 1]
            val p = re * re + im * im
            fineBuf[k2 * 2] = p
            if (k2 > 0) fineBuf[(fineLen - k2) * 2] = p
        }
        fine.forward(fineBuf)
        val zero = max(fineBuf[0].toDouble(), 1e-12)
        for (i in 0 until u * win) acn[i] = fineBuf[i * 2] / zero

        for (li in lags.indices) {
            var sal = 0.0
            for (mult in 1..3) {
                val centre = u * mult * lags[li]
                val span = half * mult
                var peak = Double.NEGATIVE_INFINITY
                var peakAt = centre
                for (o in -span..span) {
                    val idx = (centre + o).coerceIn(0, acn.size - 1)
                    if (acn[idx] > peak) { peak = acn[idx]; peakAt = centre + o }
                }
                when (mult) {
                    1 -> { sal += peak; refined[w][li] = peakAt.toDouble() / u }
                    2 -> sal += 0.5 * peak
                    3 -> sal += 0.34 * peak
                }
            }
            salience[w][li] = max(sal, 0.0) * prior[li]
        }
    }
    return Tempogram(salience, lags, centres, refined)
}

/**
 * Viterbi-decode the most likely tempo trajectory through a tempogram.
 *
 * Transitions pay `-penalty * log(lag ratio)²`, so the path rides genuine drift
 * and real tempo changes but cannot flap between octave candidates window to
 * window. Returns the per-window fractional lag and the fraction of adjacent
 * transitions that were stable.
 */
internal fun tempoPath(tg: Tempogram): Pair<DoubleArray, Double> {
    val nWin = tg.salience.size
    val nLag = tg.lags.size
    val logLag = DoubleArray(nLag) { ln(tg.lags[it].toDouble()) }
    var score = DoubleArray(nLag) { ln(tg.salience[0][it] + 1e-6) }
    val back = Array(nWin) { IntArray(nLag) }

    for (t in 1 until nWin) {
        val next = DoubleArray(nLag)
        for (to in 0 until nLag) {
            var best = Double.NEGATIVE_INFINITY
            var bestFrom = 0
            for (from in 0 until nLag) {
                val d = logLag[to] - logLag[from]
                val c = score[from] - TEMPO_TRANS_PENALTY * d * d
                if (c > best) { best = c; bestFrom = from }
            }
            back[t][to] = bestFrom
            next[to] = best + ln(tg.salience[t][to] + 1e-6)
        }
        score = next
    }

    val path = IntArray(nWin)
    var argmax = 0
    for (i in 1 until nLag) if (score[i] > score[argmax]) argmax = i
    path[nWin - 1] = argmax
    for (t in nWin - 1 downTo 1) path[t - 1] = back[t][path[t]]

    val lagF = DoubleArray(nWin) { tg.refined[it][path[it]].takeIf { v -> v > 0.0 } ?: tg.lags[path[it]].toDouble() }
    var stable = 0
    for (t in 1 until nWin) if (abs(ln(lagF[t]) - ln(lagF[t - 1])) < PATH_STABLE_TOL) stable++
    val stability = if (nWin > 1) stable.toDouble() / (nWin - 1) else 1.0
    return lagF to stability
}

// ── Beats ──────────────────────────────────────────────────────────────────

/**
 * Ellis dynamic-programming beat tracker against a per-frame tempo period.
 *
 * Maximises `Σ onset(beat) − tightness·Σ log²(interval/period)`: the globally
 * optimal beat sequence for the tempo estimate. A constant [periodFrames]
 * reproduces the classic tracker; a tempogram-derived path lets the same DP ride
 * a drifting drummer or a real mid-song tempo change without losing its
 * regularising bias.
 */
internal fun trackBeats(env: DoubleArray, periodFrames: DoubleArray): IntArray {
    val n = env.size
    if (n < 16 || periodFrames.size != n) return IntArray(0)
    var pMin = Double.MAX_VALUE
    var pMax = 0.0
    for (p in periodFrames) {
        if (p <= 0.0) return IntArray(0)
        if (p < pMin) pMin = p
        if (p > pMax) pMax = p
    }

    // Unit-std normalisation so TIGHTNESS is scale-free.
    var mean = 0.0
    for (v in env) mean += v
    mean /= n
    var variance = 0.0
    for (v in env) variance += (v - mean) * (v - mean)
    val std = sqrt(variance / n)
    val local = if (std > 1e-9) DoubleArray(n) { env[it] / std } else env.copyOf()

    val lo = -(2.0 * pMax).roundToInt()
    val hi = -max(1, (pMin / 2.0).roundToInt())
    if (lo > hi) return IntArray(0)
    val span = hi - lo + 1
    val logNeg = DoubleArray(span) { ln(-(lo + it).toDouble()) }

    val cumscore = local.copyOf()
    val backlink = IntArray(n) { -1 }
    var firstBeat = true

    for (i in max(1, -lo) until n) {
        val logPeriod = ln(periodFrames[i])
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (j in 0 until span) {
            val d = logNeg[j] - logPeriod
            val c = cumscore[i + lo + j] - TIGHTNESS * d * d
            if (c > bestScore) { bestScore = c; best = j }
        }
        if (firstBeat) {
            // Don't force a chain before the music has started.
            cumscore[i] = local[i] + max(0.0, bestScore)
            if (bestScore > 0.0) {
                backlink[i] = i + lo + best
                firstBeat = false
            }
        } else {
            cumscore[i] = local[i] + bestScore
            backlink[i] = i + lo + best
        }
    }

    val tailStart = max(0, n - periodFrames[n - 1].roundToInt())
    var last = tailStart
    for (i in tailStart until n) if (cumscore[i] > cumscore[last]) last = i

    val out = ArrayList<Int>(n / max(1, pMin.toInt()) + 4)
    var cur = last
    while (cur >= 0) {
        out.add(cur)
        cur = backlink[cur]
    }
    out.reverse()
    return out.toIntArray()
}

/**
 * Grid-quality-first confidence, returning `(confidence, madLocal, contrast, coverage)`.
 *
 * The global autocorrelation dilutes on dynamics and smears on drift, so the
 * tracked grid itself is the primary evidence: beats-on-peaks **contrast** (the
 * one thing the DP's regularising bias cannot fake — it is a hard multiplier, so
 * noise scores zero), tightness of the intervals against the *local* tempo path
 * (honest under drift, unlike a global-period MAD), coverage, and the path's own
 * stability. The raw autocorrelation only wins when it is higher, which is the
 * clean-global-lock case.
 */
internal fun gridConfidence(
    env: DoubleArray,
    beatFrames: IntArray,
    beats: DoubleArray,
    periodFrames: DoubleArray,
    stability: Double,
    durationS: Double,
    autocorrConf: Double,
): DoubleArray {
    val unchanged = doubleArrayOf(autocorrConf, 0.0, 0.0, 0.0)
    if (beats.size < 16 || durationS < 10.0) return unchanged
    val intervals = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
    val med = percentile(intervals, 50.0)
    if (med <= 0.0 || med < 60.0 / MAX_BPM || med > 60.0 / MIN_BPM) return unchanged
    var mean = 0.0
    for (v in env) mean += v
    mean /= env.size
    if (mean <= 1e-9) return unchanged

    val residuals = DoubleArray(intervals.size) { i ->
        val midIdx = ((beatFrames[i] + beatFrames[i + 1]) / 2).coerceIn(0, periodFrames.size - 1)
        val localP = max(periodFrames[midIdx] * FRAME_PERIOD, 1e-6)
        abs(intervals[i] / localP - 1.0)
    }
    val madLocal = percentile(residuals, 50.0)
    val coverage = (beats.last() - beats.first()) / max(durationS, 1e-6)
    var onPeaks = 0.0
    for (f in beatFrames) onPeaks += env[min(f, env.size - 1)]
    val contrast = onPeaks / beatFrames.size / mean

    val contrastS = unitD((contrast - CONTRAST_FLOOR) / CONTRAST_RANGE)
    val tightS = unitD((TIGHT_MAD_ZERO - madLocal) / (TIGHT_MAD_ZERO - TIGHT_MAD_FULL))
    val covS = unitD((coverage - 0.5) / 0.4)
    val gridConf = min(
        GRID_CONF_CAP,
        contrastS * (0.55 + 0.20 * tightS + 0.15 * covS + 0.10 * stability),
    )
    return doubleArrayOf(max(autocorrConf, gridConf), madLocal, contrast, coverage)
}

/**
 * Per-beat accents normalised by a rolling p90 of the surrounding beats.
 *
 * Normalising against the single loudest onset of the whole track lets one drop
 * impact compress every ordinary kick to ~0.3, and the show's pulses all come
 * out half-strength. A rolling reference keeps "strong" meaning strong *for this
 * passage*, so the verse keeps its hierarchy and the chorus keeps its headroom.
 */
internal fun rollingAccents(raw: DoubleArray, halfWindow: Int = 8): FloatArray {
    val n = raw.size
    val out = FloatArray(n)
    for (i in 0 until n) {
        val from = max(0, i - halfWindow)
        val to = min(n, i + halfWindow + 1)
        val seg = DoubleArray(to - from) { raw[from + it] }
        val ref = percentile(seg, 90.0)
        out[i] = if (ref > 1e-9) min(1.0, raw[i] / ref).toFloat() else 0f
    }
    return out
}

/** Which of the four beat positions bars start on: the strongest bass fold. */
internal fun findDownbeat(bassEnv: DoubleArray, beatFrames: IntArray): Int {
    if (beatFrames.size < 8) return 0
    var best = 0
    var bestSum = Double.NEGATIVE_INFINITY
    for (k in 0 until 4) {
        var sum = 0.0
        var i = k
        while (i < beatFrames.size) {
            sum += bassEnv[min(beatFrames[i], bassEnv.size - 1)]
            i += 4
        }
        if (sum > bestSum) { bestSum = sum; best = k }
    }
    return best
}

// ── Sections ───────────────────────────────────────────────────────────────

/**
 * Novelty-based sectioning, plus each section's energy relative to the track's peak.
 *
 * Block band profiles → cosine self-similarity → a Hann-tapered checkerboard
 * kernel down the diagonal → novelty. Peaks are picked against `median + 5·MAD`
 * rather than `mean + std`, because with flat novelty *everything* sits near a
 * mean-based threshold and a uniform track fragments into nonsense.
 */
internal fun segmentSections(ex: OfflineExtractor, durationS: Float): List<ScanSection> {
    val block = max(1, (BLOCK_S / FRAME_PERIOD).roundToInt())
    val nBlocks = ex.frames / block
    val k = KERNEL_BLOCKS
    val rmsBlocks = DoubleArray(max(0, nBlocks)) { b ->
        var s = 0.0
        for (i in 0 until block) s += ex.rms[b * block + i]
        s / block
    }
    if (nBlocks < 2 * k) return listOf(ScanSection(0f, durationS, 1f))

    val nBands = ex.nBands
    // Block features: mean band profile, log-compressed and L2-normalised, so
    // the similarity is about spectral *shape* rather than loudness.
    val feat = Array(nBlocks) { b ->
        val row = DoubleArray(nBands)
        for (i in 0 until block) {
            val f = b * block + i
            for (c in 0 until nBands) row[c] += ex.bandsAt(f, c).toDouble()
        }
        var norm = 0.0
        for (c in 0 until nBands) {
            row[c] = ln(1.0 + 10.0 * (row[c] / block))
            norm += row[c] * row[c]
        }
        norm = sqrt(norm)
        if (norm > 1e-9) for (c in 0 until nBands) row[c] /= norm
        row
    }

    // Checkerboard kernel: +1 on same-side quadrants, −1 across the boundary,
    // Hann-tapered so novelty peaks exactly on a section change.
    val size = 2 * k
    val hann = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    val kernel = Array(size) { r ->
        DoubleArray(size) { c ->
            val quad = if ((r < k) != (c < k)) -1.0 else 1.0
            hann[r] * hann[c] * quad
        }
    }

    val novelty = DoubleArray(nBlocks)
    for (i in k until nBlocks - k) {
        var acc = 0.0
        for (r in 0 until size) {
            val fr = feat[i - k + r]
            for (c in 0 until size) {
                val fc = feat[i - k + c]
                var dot = 0.0
                for (b in 0 until nBands) dot += fr[b] * fc[b]
                acc += dot * kernel[r][c]
            }
        }
        novelty[i] = acc
    }

    val core = DoubleArray(nBlocks - 2 * k) { novelty[k + it] }
    val med = percentile(core, 50.0)
    val deviations = DoubleArray(core.size) { abs(core[it] - med) }
    val mad = percentile(deviations, 50.0)
    val threshold = med + 5.0 * max(mad, 1e-6)
    val minGap = max(1, (MIN_SECTION_S / BLOCK_S).roundToInt())

    val bounds = ArrayList<Int>()
    for (i in 1 until nBlocks - 1) {
        if (novelty[i] >= threshold && novelty[i] >= novelty[i - 1] && novelty[i] >= novelty[i + 1]) {
            if (bounds.isEmpty() || i - bounds.last() >= minGap) bounds.add(i)
        }
    }

    val edges = ArrayList<Float>(bounds.size + 2)
    edges.add(0f)
    for (b in bounds) edges.add(b * BLOCK_S)
    edges.add(durationS)

    val peak = percentile(rmsBlocks, 95.0).takeIf { it > 1e-9 } ?: 1.0
    val out = ArrayList<ScanSection>(edges.size - 1)
    for (i in 0 until edges.size - 1) {
        val a = edges[i]
        val b = edges[i + 1]
        if (b - a < 1f) continue
        val i0 = min(nBlocks - 1, (a / BLOCK_S).toInt())
        val i1 = max(i0 + 1, min(nBlocks, (b / BLOCK_S).toInt()))
        var s = 0.0
        for (j in i0 until i1) s += rmsBlocks[j]
        out.add(ScanSection(a, b, unitD(s / (i1 - i0) / peak).toFloat()))
    }
    return out.ifEmpty { listOf(ScanSection(0f, durationS, 1f)) }
}

// ── Intensity profile ──────────────────────────────────────────────────────

/**
 * Derive the song's [IntensityProfile] — the four picker parameters that have
 * been taking their live-estimated defaults, plus the lag-free signal.
 *
 * [character] is built only from terms that survive per-track normalisation,
 * which is the whole point: a chill track has to score low however loud its own
 * chorus is. Both curve-derived terms are energy-weighted so a quiet intro does
 * not get an equal vote with the body of the track.
 */
internal fun buildIntensityProfile(
    ex: OfflineExtractor,
    bpm: Float,
    beatTimes: FloatArray,
): IntensityProfile? {
    val n = ex.frames
    if (n < 50) return null  // under ~1 s of frames: nothing meaningful to profile

    val energy = normaliseToPercentile(ex.rms, 95.0, floor = 0.01f)
    val tempo = if (bpm > 0f) unitF((bpm - PICK_BPM_LO) / (PICK_BPM_HI - PICK_BPM_LO)) else 0.5f
    val perc = beatRateCurve(beatTimes, n)

    // The live picker's own blend, vectorised over the track. Globally
    // p95-normalised energy is both the loudness and the salience term here, as
    // it is for map playback in syncoV2: it has seen the whole track, which is
    // strictly better than the live rolling reference.
    val raw = FloatArray(n) { intensitySignal(energy[it], energy[it], tempo, perc[it]) }
    val curveFull = centredMovingAverage(raw, (SECTION_WIN_S / FRAME_PERIOD).roundToInt())

    val curveD = DoubleArray(n) { curveFull[it].toDouble() }
    var lo = percentile(curveD, 10.0).toFloat()
    var hi = percentile(curveD, 95.0).toFloat()
    val dynamics = max(0f, hi - lo)  // the true spread drives the dynamics-honest blend
    if (hi - lo < MIN_SIG_SPAN) {
        val mid = 0.5f * (lo + hi)
        lo = max(0f, mid - 0.5f * MIN_SIG_SPAN)
        hi = min(1f, mid + 0.5f * MIN_SIG_SPAN)
    }

    // Spectral tilt: energy-weighted low-versus-high balance. The bands are
    // p95-normalised per band, so the weighted mean reflects how often each band
    // is loud rather than how loud it is in absolute terms.
    var wsum = 0.0
    for (i in 0 until n) wsum += energy[i]
    wsum += 1e-9
    val named = Array(5) { c -> normalisePerBand(ex, c, n) }
    var low = 0.0
    var high = 0.0
    for (i in 0 until n) {
        val w = energy[i]
        low += (named[0][i] + named[1][i]) * w
        high += (named[3][i] + named[4][i]) * w
    }
    low /= wsum
    high /= wsum
    val tilt = ((low - high) / (low + high + 1e-9)).coerceIn(-1.0, 1.0).toFloat()

    // Attack: onset broadbandness, stored un-normalised precisely so it can be
    // read absolutely. A drum kit splashes across the filterbank; a pad or a
    // sung tone does not.
    var attackAcc = 0.0
    var busyAcc = 0.0
    val fluxNorm = normaliseToPercentile(ex.env, 99.0, floor = 2.5f)
    for (i in 0 until n) {
        attackAcc += ex.width[i] * energy[i]
        busyAcc += fluxNorm[i] * energy[i]
    }
    val meanWidth = (attackAcc / wsum).toFloat()
    val attack = (meanWidth - CHAR_ATTACK_LO) / (CHAR_ATTACK_HI - CHAR_ATTACK_LO)
    // Mean onset flux, deliberately not beats per second: on a gridded scan that
    // would just be bpm/60, i.e. the tempo term again, and a half- or
    // double-time lock would then swing two of the four terms at once.
    val busy = (busyAcc / wsum).toFloat() / CHAR_BUSY_FULL

    val character = songCharacter(
        tempo = tempo,
        busy = busy,
        attack = attack,
        bass = 0.5f + 0.5f * tilt,
    )

    return IntensityProfile(
        sigLo = lo,
        sigHi = hi,
        dynamics = dynamics,
        tilt = tilt,
        tempo = tempo,
        character = character,
        curve = decimate(curveFull, FRAME_PERIOD, CURVE_RATE_HZ),
        curveRateHz = CURVE_RATE_HZ,
    )
}

/**
 * Per-frame percussiveness: the same leaky beats-per-second the live picker uses,
 * driven by the scan's own beat times so the two agree on the same track.
 */
internal fun beatRateCurve(beatTimes: FloatArray, n: Int): FloatArray {
    val out = FloatArray(n)
    if (beatTimes.isEmpty() || n <= 0) return out
    val tau = 1.5f
    val decay = max(0f, 1f - FRAME_PERIOD / tau)
    val flags = BooleanArray(n)
    for (t in beatTimes) flags[(t / FRAME_PERIOD).toInt().coerceIn(0, n - 1)] = true
    var rate = 0f
    for (i in 0 until n) {
        rate *= decay
        if (flags[i]) rate += 1f / tau
        out[i] = unitF(rate / PICK_BEAT_FULL)
    }
    return out
}

/**
 * Centred, edge-normalised moving average.
 *
 * Unlike a causal EMA this does not lag the music — the value at frame `i`
 * reflects the window *around* `i`. See [SECTION_WIN_S].
 */
internal fun centredMovingAverage(x: FloatArray, win: Int): FloatArray {
    val n = x.size
    if (win <= 1 || n == 0) return x.copyOf()
    val half = win / 2
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
    return FloatArray(n) { i ->
        val lo = max(0, i - half)
        val hi = min(n, i - half + win)
        ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
    }
}

/** Resample a per-frame curve down to [targetHz], averaging within each output step. */
internal fun decimate(curve: FloatArray, framePeriod: Float, targetHz: Float): FloatArray {
    if (curve.isEmpty()) return FloatArray(0)
    val step = max(1, (1f / targetHz / framePeriod).roundToInt())
    val out = FloatArray((curve.size + step - 1) / step)
    for (o in out.indices) {
        val from = o * step
        val to = min(curve.size, from + step)
        var s = 0.0
        for (i in from until to) s += curve[i]
        out[o] = (s / (to - from)).toFloat()
    }
    return out
}

/**
 * Normalise so the [pct] percentile maps to 1.0, clipped to 0..1.
 *
 * [floor] is an absolute minimum reference, so a band that is genuinely silent
 * throughout the track does not get its noise amplified to full scale — the
 * offline counterpart of the live analyzer's noise gate.
 */
internal fun normaliseToPercentile(values: FloatList, pct: Double, floor: Float): FloatArray {
    val n = values.size
    val ref = max(percentile(values.toDoubleArray(), pct).toFloat(), floor)
    return FloatArray(n) { unitF(values[it] / ref) }
}

private fun normalisePerBand(ex: OfflineExtractor, band: Int, n: Int): FloatArray {
    val raw = DoubleArray(n) { ex.namedAt(it, band).toDouble() }
    val ref = max(percentile(raw, 95.0), 0.5)
    return FloatArray(n) { unitD(raw[it] / ref).toFloat() }
}

/**
 * Per-bin absolute-loudness weight: each melbank bin's p95 reference divided by
 * the loudest bin's. See [AnalysisFrame.melbankRef].
 */
internal fun melbankReference(ex: OfflineExtractor): FloatArray {
    val n = ex.frames
    if (n == 0) return FloatArray(0)
    val refs = FloatArray(MELBANK_BINS)
    val scratch = DoubleArray(n)
    for (c in 0 until MELBANK_BINS) {
        for (i in 0 until n) scratch[i] = ex.melAt(i, c).toDouble()
        refs[c] = percentile(scratch, 95.0).toFloat()
    }
    // Floor against the p95 of the whole melbank, pooled over every frame and
    // bin, so a bin that is genuinely silent throughout can't have its noise
    // normalised up into a weight. Sampled every fourth frame: the percentile of
    // a quarter of 300 000 values is the same number to more decimal places than
    // this is used to.
    val pooled = DoubleArray((n + 3) / 4 * MELBANK_BINS)
    var p = 0
    var i = 0
    while (i < n) {
        for (c in 0 until MELBANK_BINS) pooled[p++] = ex.melAt(i, c).toDouble()
        i += 4
    }
    val floor = (0.02 * max(percentile(pooled.copyOf(p), 95.0), 1e-6)).toFloat()
    var mx = 0f
    for (c in 0 until MELBANK_BINS) {
        refs[c] = max(refs[c], floor)
        if (refs[c] > mx) mx = refs[c]
    }
    if (mx <= 1e-9f) return FloatArray(MELBANK_BINS) { 1f }
    for (c in 0 until MELBANK_BINS) refs[c] /= mx
    return refs
}

private fun subtractRollingMean(x: DoubleArray, win: Int): DoubleArray {
    val n = x.size
    val half = win / 2
    val prefix = DoubleArray(n + 1)
    for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
    return DoubleArray(n) { i ->
        val lo = max(0, i - half)
        val hi = min(n, i - half + win)
        x[i] - (prefix[hi] - prefix[lo]) / (hi - lo)
    }
}

// ── Putting it together ────────────────────────────────────────────────────

/**
 * Turn a completed [OfflineExtractor] into a [TrackScan].
 *
 * The guards come first and are not negotiable: too few frames means the fetch
 * was truncated, and a silent "track" must stay unanalysable rather than become
 * an all-dark scan that the show then faithfully reproduces.
 */
internal fun finishScan(ex: OfflineExtractor, fullDurationS: Float = 0f): ScanResult {
    val n = ex.frames
    if (n < MIN_ANALYSIS_FRAMES) return ScanResult.Failed(ScanFailure.TOO_SHORT)
    val rmsD = ex.rms.toDoubleArray()
    if (percentile(rmsD, 95.0) < MIN_AUDIO_RMS) return ScanResult.Failed(ScanFailure.SILENT)

    val env = ex.env.toDoubleArray()
    val bassEnvD = ex.bassEnv.toDoubleArray()
    // What was analysed, which is what every window, threshold and segmentation below
    // is measured against. It is the whole track unless the decode stopped at
    // `TrackScanner.MAX_TRACK_S`, and [fullDurationS] — the container's own answer,
    // which the decode has no way to reach — is what tells the two apart.
    val durationS = n * FRAME_PERIOD

    val (rawBpm, autocorrConf) = estimateTempo(env)
    var bpm = rawBpm

    // On anything longer than two tempogram windows, decode the per-window
    // trajectory so drift and real tempo changes read as locally-steady tempo.
    // Short tracks keep the scalar path.
    var stability = 1.0
    var periodFrames: DoubleArray? = null
    if (durationS >= 2f * TG_WIN_S) {
        tempogram(env)?.let { tg ->
            val (lagPath, stab) = tempoPath(tg)
            stability = stab
            periodFrames = interpolateToFrames(tg.centres, lagPath, n)
        }
    }
    if (periodFrames == null && bpm > 0.0) {
        periodFrames = DoubleArray(n) { 60.0 / bpm / FRAME_PERIOD }
    }
    val periods = periodFrames ?: return ScanResult.Failed(ScanFailure.TOO_SHORT, "no tempo")

    val beatFrames = trackBeats(env, periods)
    var beats = DoubleArray(beatFrames.size) { beatFrames[it] * FRAME_PERIOD.toDouble() }

    var confidence = autocorrConf
    if (beatFrames.size >= 2) {
        confidence = gridConfidence(
            env, beatFrames, beats, periods, stability, durationS.toDouble(), autocorrConf,
        )[0]
    }

    var accents = FloatArray(0)
    var downbeat = 0
    var beatTimes = FloatArray(0)
    if (beatFrames.size >= 4) {
        val rawAccents = DoubleArray(beatFrames.size) { env[min(beatFrames[it], env.size - 1)] }
        // Bass-weight each accent exactly as the live tracker does
        // (flux * (0.5 + 0.5*bass)): rhythmic impact is perceived almost
        // entirely in the low end, so a treble stab must not read as a big beat.
        val bassAt = DoubleArray(beatFrames.size) { bassEnvD[min(beatFrames[it], bassEnvD.size - 1)] }
        val bassRef = percentile(bassAt, 95.0)
        if (bassRef > 1e-9) {
            for (i in rawAccents.indices) rawAccents[i] *= 0.5 + 0.5 * unitD(bassAt[i] / bassRef)
        }
        accents = rollingAccents(rawAccents)
        downbeat = findDownbeat(bassEnvD, beatFrames)
        // Refine the BPM from the tracked beats, which is more honest than the lag.
        val intervals = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        if (intervals.isNotEmpty()) bpm = 60.0 / percentile(intervals, 50.0)
        beatTimes = FloatArray(beats.size) { beats[it].toFloat() }
    } else {
        beats = DoubleArray(0)
    }

    val gridOk = confidence >= TrackScan.MIN_GRID_CONFIDENCE && beats.size >= 8
    val sections = segmentSections(ex, durationS)
    // Without a trustworthy grid the profile's percussiveness term has no honest
    // event set, so it falls back to the flux-only blend rather than a grid we
    // have just rejected.
    val profile = buildIntensityProfile(ex, bpm.toFloat(), if (gridOk) beatTimes else FloatArray(0))
    val key = detectKey(ex.chroma)

    return ScanResult.Ok(
        TrackScan(
            // The track's real length where the container gave one, so a scan that
            // covers the first twelve minutes of a forty-minute set says so rather than
            // presenting itself as a complete reading of a twelve-minute track.
            durationS = if (fullDurationS > durationS) fullDurationS else durationS,
            analysedS = durationS,
            bpm = bpm.toFloat(),
            confidence = confidence.toFloat(),
            beats = beatTimes,
            accents = accents,
            downbeat = downbeat,
            sections = sections,
            intensity = profile,
            melbankRef = melbankReference(ex),
            key = key,
        )
    )
}

/** numpy's `interp`: piecewise-linear resample of [y] at `0 until n`. */
internal fun interpolateToFrames(x: DoubleArray, y: DoubleArray, n: Int): DoubleArray {
    val out = DoubleArray(n)
    if (x.isEmpty()) return out
    var j = 0
    for (i in 0 until n) {
        val t = i.toDouble()
        while (j < x.size - 2 && x[j + 1] < t) j++
        out[i] = when {
            t <= x[0] -> y[0]
            t >= x[x.size - 1] -> y[y.size - 1]
            else -> {
                val span = x[j + 1] - x[j]
                if (span <= 0.0) y[j] else y[j] + (y[j + 1] - y[j]) * (t - x[j]) / span
            }
        }
    }
    return out
}
