package com.engabd.sendpin.audio

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Real-time audio feature extraction: frequency bands + beat detection + melbank.
 *
 * Each `push` of one hop (~20 ms) slides a Hann-windowed FFT, buckets power into
 * frequency bands with per-band AGC, and runs SuperFlux onset detection with an
 * adaptive median+MAD threshold.
 *
 * Ported from syncoV2's `audio/analyzer.py:Analyzer`. The algorithm is preserved
 * exactly: SuperFlux spectral flux on log-compressed magnitudes, three onset
 * streams (broadband/bass/mid), LedFx-style melbank with per-bin AGC, absolute
 * loudness salience, and onset width for vocal/tone discrimination.
 *
 * The main adaptation is that numpy's FFT and array operations become explicit
 * loops and the [Fft] class. No external library is needed.
 */

// ── Constants (mirroring syncoV2 const.py + analyzer.py) ───────────────────

private const val ANALYSIS_SAMPLE_RATE = 22050
private const val ANALYSIS_HOP = 441        // ~20ms hop → ~50 frames/sec
private const val ANALYSIS_WINDOW = 1024   // FFT window size
private const val ANALYSIS_NOISE_FLOOR = 2.0e-3f
private const val NFFT = 2 * ANALYSIS_WINDOW  // zero-padded → ~10.8 Hz bins

// Frequency band edges (Hz): (low, high)
private val BANDS = mapOf(
    "sub_bass" to (20f to 60f),
    "bass" to (60f to 250f),
    "low_mid" to (250f to 800f),
    "mid" to (800f to 2500f),
    "high" to (2500f to 11000f),
)

// Melbank
private const val MELBANK_BINS = 16
private const val MELBANK_FMIN = 40f
private const val MELBANK_FMAX = 11000f

// Onset detection
private const val LOG_COMPRESSION = 10.0f
private const val MAX_FILTER_RADIUS = 3
private const val ONSET_BANDS = 36
private const val ONSET_FMIN = 40f
private const val ONSET_FMAX = 11000f
private const val BASS_ONSET_HZ = 200f
private const val MID_ONSET_HZ = 2500f
private const val MIN_ONSET_FLUX = 2.5f
private const val BASS_FLUX_SHARE = 0.25f
private const val MID_FLUX_SHARE = 0.30f
private const val ONSET_REFRACTORY = 3
private const val BEAT_SENSITIVITY = 1.2f

// Salience
private const val SALIENCE_DECAY = 0.9997f
private const val SALIENCE_SMOOTH = 0.30f
private const val SALIENCE_MIN_REF = 0.04f

// AGC decay (~70s half-life at 50fps)
private const val AGC_DECAY = 0.9998f
private const val MEL_AGC_DECAY = 0.9998f

// Long-horizon flux floor
private const val LONG_FLUX_DECAY = 0.9995f
private const val LONG_FLUX_FLOOR_FRAC = 0.20f

// Mid-attack state machine
private const val MID_MAX_ATTACK_FRAMES = 3
private const val MID_RISE_RATIO = 1.05f

// ── Analysis frame ─────────────────────────────────────────────────────────

/**
 * One frame of audio features, all band values normalised to 0..1.
 * Mirrors syncoV2's `AnalysisFrame` dataclass.
 */
data class AnalysisFrame(
    val bands: Map<String, Float> = emptyMap(),
    val energy: Float = 0f,
    val beat: Boolean = false,
    val beatStrength: Float = 0f,
    val flux: Float = 0f,
    val tAudio: Float = 0f,
    val bassFlux: Float = 0f,
    val bassBeat: Boolean = false,
    val bassStrength: Float = 0f,
    val midFlux: Float = 0f,
    val midBeat: Boolean = false,
    val midStrength: Float = 0f,
    val salience: Float = 1f,
    val onsetWidth: Float = 1f,
    val melbank: FloatArray = FloatArray(0),
    val centroid: Float = 0f,
)

// ── AGC ────────────────────────────────────────────────────────────────────

private class Agc(private val decay: Float = AGC_DECAY, private val floor: Float = 1e-6f) {
    private var peak = floor

    fun normalise(value: Float): Float {
        peak = maxOf(value, peak * decay, floor)
        return min(1f, value / peak)
    }

    fun reset() { peak = floor }
}

// ── Exponential filter (envelope follower) ─────────────────────────────────

private class ExpFilter(var state: FloatArray, val alphaRise: Float, val alphaDecay: Float) {
    fun update(input: FloatArray): FloatArray {
        for (i in input.indices) {
            val a = if (input[i] > state[i]) alphaRise else alphaDecay
            state[i] += (input[i] - state[i]) * a
        }
        return state
    }
    fun reset(zeros: FloatArray) { state = zeros }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun logSpectrum(mag: FloatArray): FloatArray =
    FloatArray(mag.size) { ln(1f + LOG_COMPRESSION * mag[it]) }

/**
 * Maximum filter across ±radius bands (SuperFlux vibrato guard).
 */
private fun maxFilterFreq(logmag: FloatArray, radius: Int = MAX_FILTER_RADIUS): FloatArray {
    val out = logmag.copyOf()
    for (s in 1..radius) {
        for (i in 0 until out.size - s) out[i] = max(out[i], logmag[i + s])
        for (i in s until out.size) out[i] = max(out[i], logmag[i - s])
    }
    return out
}

/** Band means: aggregate a magnitude spectrum into contiguous band means. */
private fun bandMeans(mag: FloatArray, starts: IntArray, counts: IntArray): FloatArray {
    val out = FloatArray(starts.size)
    for (i in starts.indices) {
        var sum = 0f
        val lo = starts[i]
        val hi = min(lo + counts[i], mag.size)
        val n = max(1, hi - lo)
        for (j in lo until hi) sum += mag[j]
        out[i] = sum / n
    }
    return out
}

/** Log-spaced band starts/counts for onset filterbank. */
private fun makeOnsetFilterbank(freqs: FloatArray, nBands: Int, fmin: Float, fmax: Float): Triple<IntArray, IntArray, Pair<Int, Int>> {
    val edges = FloatArray(nBands + 1) { i ->
        fmin * kotlin.math.pow((fmax / fmin).toDouble(), (i.toFloat() / nBands).toDouble()).toFloat()
    }
    val idx = IntArray(nBands + 1) { i ->
        var result = 0
        var lo = 0; var hi = freqs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (freqs[mid] < edges[i]) { result = mid + 1; lo = mid + 1 } else { hi = mid - 1 }
        }
        result
    }
    for (i in 1 until idx.size) idx[i] = max(idx[i], idx[i - 1] + 1)
    for (i in idx.indices) idx[i] = min(idx[i], freqs.size - 1)
    val starts = IntArray(nBands) { idx[it] }
    val counts = IntArray(nBands) { max(1, idx[it + 1] - idx[it]) }
    val nBass = max(1, edges.indices.count { it > 0 && edges[it] <= BASS_ONSET_HZ })
    val nMid = max(nBass + 1, edges.indices.count { it > 0 && edges[it] <= MID_ONSET_HZ })
    return Triple(starts, counts, nBass to nMid)
}

/** Log-spaced melbank starts/counts. */
private fun makeMelbank(freqs: FloatArray, nBins: Int, fmin: Float, fmax: Float): Pair<IntArray, IntArray> {
    val edges = FloatArray(nBins + 1) { i ->
        fmin * kotlin.math.pow((fmax / fmin).toDouble(), (i.toFloat() / nBins).toDouble()).toFloat()
    }
    val idx = IntArray(nBins + 1) { i ->
        var result = 0
        var lo = 0; var hi = freqs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (freqs[mid] < edges[i]) { result = mid + 1; lo = mid + 1 } else { hi = mid - 1 }
        }
        result
    }
    for (i in 1 until idx.size) idx[i] = max(idx[i], idx[i - 1] + 1)
    for (i in idx.indices) idx[i] = min(idx[i], freqs.size - 1)
    val starts = IntArray(nBins) { idx[it] }
    val counts = IntArray(nBins) { max(1, idx[it + 1] - idx[it]) }
    return starts to counts
}

// (Float.pow extension removed — use kotlin.math.pow directly via .toDouble().pow().toFloat())

// ── Analyzer ──────────────────────────────────────────────────────────────

/**
 * Turns a stream of audio hops into [AnalysisFrame] features.
 *
 * Feed it mono float32 samples at the analysis sample rate (22050 Hz).
 * Each hop should be [ANALYSIS_HOP] samples (~20ms).
 */
class AudioAnalyzer(
    val sampleRate: Int = ANALYSIS_SAMPLE_RATE,
    val window: Int = ANALYSIS_WINDOW,
    val hop: Int = ANALYSIS_HOP,
) {
    private val buf = FloatArray(window)
    private val hann = FloatArray(window) { i ->
        (0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * i / (window - 1))).toFloat()
    }
    private val fft = Fft(NFFT)

    // Precomputed bin frequencies
    private val freqs: FloatArray = FloatArray(NFFT / 2 + 1) { i ->
        i.toFloat() * sampleRate.toFloat() / NFFT.toFloat()
    }

    // Band bin ranges
    private val bandBins: Map<String, Pair<Int, Int>> = BANDS.mapValues { (_, range) ->
        val lo = binarySearchGe(freqs, range.first)
        val hi = binarySearchGt(freqs, range.second)
        lo to max(lo + 1, hi)
    }

    // AGC for bands + energy + flux
    private val agc = BANDS.keys.associateWith { Agc() }
    private val energyAgc = Agc()
    private val fluxAgc = Agc()
    private val bassFluxAgc = Agc()
    private val midFluxAgc = Agc()

    // Onset filterbank
    private val fbFilterbank = makeOnsetFilterbank(freqs, ONSET_BANDS, ONSET_FMIN, ONSET_FMAX)
    private val fbStarts: IntArray = fbFilterbank.first
    private val fbCounts: IntArray = fbFilterbank.second
    private val bassMidSplit: Pair<Int, Int> = fbFilterbank.third
    private val nBass: Int = bassMidSplit.first
    private val nMid: Int = bassMidSplit.second

    // Melbank
    private val melFilterbank = makeMelbank(freqs, MELBANK_BINS, MELBANK_FMIN, MELBANK_FMAX)
    private val melStarts: IntArray = melFilterbank.first
    private val melCounts: IntArray = melFilterbank.second
    private val nMel: Int = melStarts.size
    private val melAgc = Array(nMel) { Agc(MEL_AGC_DECAY) }
    private val melFilter = ExpFilter(FloatArray(nMel), alphaRise = 0.85f, alphaDecay = 0.20f)

    // Onset state
    private var prevLog: FloatArray? = null
    private var prevLin: FloatArray? = null
    private var midEprev = 0f
    private var midRiseN = 0
    private var midAttackOk = false
    private var midAttackFlux = 0f
    private val fluxHist = ArrayDeque<Float>()
    private val bassHist = ArrayDeque<Float>()
    private val midHist = ArrayDeque<Float>()
    private var sinceBeat = ONSET_REFRACTORY
    private var sinceBass = ONSET_REFRACTORY
    private var sinceMid = ONSET_REFRACTORY
    private val beatTimes = ArrayDeque<Double>()
    private var frameIndex = 0

    // Salience
    private var rmsSmooth = 0f
    private var loudRef = SALIENCE_MIN_REF

    // Long-horizon flux peaks
    private var bassSlow = 0f
    private var midSlow = 0f

    val framePeriod: Float get() = hop.toFloat() / sampleRate.toFloat()

    /**
     * Process one hop of mono float32 samples and return features.
     */
    fun push(hopData: FloatArray): AnalysisFrame {
        val n = hopData.size
        if (n >= window) {
            System.arraycopy(hopData, n - window, buf, 0, window)
        } else {
            System.arraycopy(buf, n, buf, 0, window - n)
            System.arraycopy(hopData, 0, buf, window - n, n)
        }

        // RMS + noise gate
        var rms = 0f
        for (v in buf) rms += v * v
        rms = sqrt(rms / window)

        if (rms < ANALYSIS_NOISE_FLOOR) {
            frameIndex++
            // Maintain onset state coherence through silence.
            sinceBeat++; sinceBass++; sinceMid++
            midRiseN = 0
            // Let melbank decay.
            val zeros = FloatArray(nMel)
            val mel = melFilter.update(zeros)
            return AnalysisFrame(
                bands = BANDS.keys.associateWith { 0f },
                tAudio = (frameIndex - 1) * framePeriod,
                melbank = mel,
                salience = 0f,
            )
        }

        // FFT magnitude
        val windowed = FloatArray(window) { buf[it] * hann[it] }
        val mag = fft.magnitudePower(windowed)
        // sqrt to get linear magnitude (mag is power = |X|²)
        for (i in mag.indices) mag[i] = sqrt(mag[i])

        // Frequency bands
        val bands = HashMap<String, Float>()
        for ((name, range) in bandBins) {
            val (lo, hi) = range
            var raw = 0f
            for (i in lo until hi) raw += mag[i] * mag[i]  // power
            raw = sqrt(raw / (hi - lo))
            bands[name] = agc[name]!!.normalise(raw)
        }

        val energy = energyAgc.normalise(rms)

        // Salience
        rmsSmooth += (rms - rmsSmooth) * SALIENCE_SMOOTH
        loudRef = maxOf(rmsSmooth, loudRef * SALIENCE_DECAY, SALIENCE_MIN_REF)
        val salience = min(1f, rmsSmooth / loudRef)

        // Onset detection
        val onsets = detectOnsets(mag)

        // Melbank
        val melMeans = bandMeans(mag, melStarts, melCounts)
        val melNormed = FloatArray(nMel) { i -> melAgc[i].normalise(melMeans[i]) }
        val melbank = melFilter.update(melNormed)

        // Spectral centroid
        var total = 0f
        for (m in mag) total += m
        var weighted = 0f
        if (total > 1e-9f) {
            for (i in mag.indices) weighted += freqs[i] * mag[i]
            val centroidHz = weighted / total
            val centroid = min(1f, centroidHz / 5000f)

            val tempo = estimateTempo()
            val tAudio = frameIndex * framePeriod
            frameIndex++

            return AnalysisFrame(
                bands = bands,
                energy = energy,
                melbank = melbank,
                beat = onsets.beat,
                beatStrength = onsets.strength,
                flux = fluxAgc.normalise(onsets.flux),
                tAudio = tAudio,
                centroid = centroid,
                bassFlux = bassFluxAgc.normalise(onsets.bassFlux),
                bassBeat = onsets.bassBeat,
                bassStrength = onsets.bassStrength,
                midFlux = midFluxAgc.normalise(onsets.midFlux),
                midBeat = onsets.midBeat,
                midStrength = onsets.midStrength,
                salience = salience,
                onsetWidth = onsets.width,
            )
        }

        val tempo = estimateTempo()
        val tAudio = frameIndex * framePeriod
        frameIndex++

        return AnalysisFrame(
            bands = bands,
            energy = energy,
            melbank = melbank,
            beat = onsets.beat,
            beatStrength = onsets.strength,
            flux = fluxAgc.normalise(onsets.flux),
            tAudio = tAudio,
            bassFlux = bassFluxAgc.normalise(onsets.bassFlux),
            bassBeat = onsets.bassBeat,
            bassStrength = onsets.bassStrength,
            midFlux = midFluxAgc.normalise(onsets.midFlux),
            midBeat = onsets.midBeat,
            midStrength = onsets.midStrength,
            salience = salience,
            onsetWidth = onsets.width,
        )
    }

    // ── Onset detection ───────────────────────────────────────────────────

    private data class OnsetResult(
        val beat: Boolean, val strength: Float, val flux: Float,
        val bassBeat: Boolean, val bassStrength: Float, val bassFlux: Float,
        val midBeat: Boolean, val midStrength: Float, val midFlux: Float,
        val width: Float,
    )

    private fun detectOnsets(mag: FloatArray): OnsetResult {
        val lin = bandMeans(mag, fbStarts, fbCounts)
        val cur = logSpectrum(lin)

        if (prevLog == null || prevLin == null) {
            prevLog = cur; prevLin = lin
            return OnsetResult(false, 0f, 0f, false, 0f, 0f, false, 0f, 0f, 1f)
        }

        val mfPrev = maxFilterFreq(prevLog!!)
        val diff = FloatArray(cur.size) { i -> cur[i] - mfPrev[i] }

        // Broadband flux
        var flux = 0f
        for (d in diff) if (d > 0) flux += d

        // Onset width (inverse participation ratio)
        var s1 = 0f
        var s2 = 0f
        var nPos = 0
        for (d in diff) {
            if (d > 0) { s1 += d; s2 += d * d; nPos++ }
        }
        val width = if (s2 > 1e-12f && diff.size > 0) (s1 * s1) / (diff.size * s2) else 0f

        // Bass + mid flux
        var bassFlux = 0f
        for (i in 0 until nBass) if (diff[i] > 0) bassFlux += diff[i]
        var midFlux = 0f
        for (i in nBass until nMid) if (diff[i] > 0) midFlux += diff[i]

        // Linear-domain shares
        val ldiff = FloatArray(lin.size) { i -> lin[i] - prevLin!![i] }
        var linPos = 0f
        for (d in ldiff) if (d > 0) linPos += d
        var linBass = 0f
        for (i in 0 until nBass) if (ldiff[i] > 0) linBass += ldiff[i]
        var linMid = 0f
        for (i in nBass until nMid) if (ldiff[i] > 0) linMid += ldiff[i]
        val bassShare = if (linPos > 1e-9f) linBass / linPos else 0f
        val midShare = if (linPos > 1e-9f) linMid / linPos else 0f

        prevLog = cur
        prevLin = lin

        // Long-horizon flux peaks
        bassSlow = max(bassFlux, bassSlow * LONG_FLUX_DECAY)
        midSlow = max(midFlux, midSlow * LONG_FLUX_DECAY)

        // Broadband beat
        val (beat, strength) = thresholdOnset(flux, fluxHist, sinceBeat)
        sinceBeat = if (beat) 0 else sinceBeat + 1
        if (beat) beatTimes.add(frameIndex * framePeriod.toDouble())

        // Bass beat: requires bass dominance
        val bassResult = if (bassShare >= BASS_FLUX_SHARE && bassShare >= midShare) {
            val (b, s) = thresholdOnset(bassFlux, bassHist, sinceBass, floor = LONG_FLUX_FLOOR_FRAC * bassSlow)
            Triple(b, s, if (b) 0 else sinceBass + 1)
        } else {
            Triple(false, 0f, sinceBass + 1)
        }
        sinceBass = bassResult.third

        // Mid beat: attack state machine
        val eMid = (nBass until nMid).sumOf { lin[it] }
        val rising = eMid > midEprev * MID_RISE_RATIO
        var midBeat = false
        var midStrength = 0f
        if (rising) {
            if (midRiseN == 0) { midAttackFlux = 0f; midAttackOk = false }
            midRiseN++
            if (midShare >= MID_FLUX_SHARE && midShare > bassShare) {
                midAttackOk = true
                midAttackFlux = max(midAttackFlux, midFlux)
            }
            sinceMid++
        } else {
            if (midRiseN in 1..MID_MAX_ATTACK_FRAMES && midAttackOk) {
                val (b, s) = thresholdOnset(midAttackFlux, midHist, sinceMid, requireRising = false, floor = LONG_FLUX_FLOOR_FRAC * midSlow)
                midBeat = b; midStrength = s
                sinceMid = if (b) 0 else sinceMid + 1
            } else {
                sinceMid++
            }
            midRiseN = 0
        }
        midEprev = eMid

        // Histories
        fluxHist.addLast(flux); if (fluxHist.size > 43) fluxHist.removeFirst()
        bassHist.addLast(bassFlux); if (bassHist.size > 43) bassHist.removeFirst()
        midHist.addLast(midFlux); if (midHist.size > 43) midHist.removeFirst()

        return OnsetResult(beat, strength, flux, bassResult.first, bassResult.second, bassFlux, midBeat, midStrength, midFlux, width)
    }

    /**
     * Adaptive median+k·MAD threshold with a refractory period.
     */
    private fun thresholdOnset(flux: Float, hist: ArrayDeque<Float>, since: Int, requireRising: Boolean = true, floor: Float = 0f): Pair<Boolean, Float> {
        if (hist.size < 22) return false to 0f  // need half the window
        val arr = hist.toList().toFloatArray()
        val med = median(arr)
        val mad = median(arr.map { kotlin.math.abs(it - med) }.toFloatArray())
        val threshold = maxOf(med + BEAT_SENSITIVITY * 3f * mad, MIN_ONSET_FLUX, floor)
        val rising = !requireRising || flux > hist.last()
        if (flux > threshold && rising && since >= ONSET_REFRACTORY) {
            return true to min(3f, flux / threshold)
        }
        return false to 0f
    }

    private fun estimateTempo(): Float? {
        if (beatTimes.size < 4) return null
        val intervals = DoubleArray(beatTimes.size - 1) { i -> beatTimes[i + 1] - beatTimes[i] }
        val valid = intervals.filter { it > 0.25 && it < 2.0 }
        if (valid.size < 2) return null
        return (60.0 / median(valid.toDoubleArray())).toFloat()
    }

    fun reset() {
        for (a in agc.values) a.reset()
        energyAgc.reset()
        for (a in melAgc) a.reset()
        melFilter.reset(FloatArray(nMel))
        prevLog = null; prevLin = null
        midEprev = 0f; midRiseN = 0; midAttackOk = false; midAttackFlux = 0f
        fluxHist.clear(); bassHist.clear(); midHist.clear()
        beatTimes.clear()
        sinceBeat = ONSET_REFRACTORY; sinceBass = ONSET_REFRACTORY; sinceMid = ONSET_REFRACTORY
        rmsSmooth = 0f; loudRef = SALIENCE_MIN_REF; bassSlow = 0f; midSlow = 0f
        frameIndex = 0
    }

    companion object {
        private fun binarySearchGe(arr: FloatArray, target: Float): Int {
            var lo = 0; var hi = arr.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (arr[mid] < target) lo = mid + 1 else hi = mid }
            return lo
        }
        private fun binarySearchGt(arr: FloatArray, target: Float): Int {
            var lo = 0; var hi = arr.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (arr[mid] <= target) lo = mid + 1 else hi = mid }
            return lo
        }
        private fun median(arr: FloatArray): Float {
            if (arr.isEmpty()) return 0f
            val sorted = arr.sortedArray()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }
        private fun median(arr: DoubleArray): Double {
            if (arr.isEmpty()) return 0.0
            val sorted = arr.sortedArray()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
        }
    }
}