package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline analysis, checked where it can be checked on a JVM.
 *
 * Two kinds of test here, and the second is the important one. The first kind
 * asks whether each algorithm does the thing it claims — the beat tracker finds
 * beats, the octave guard survives 140 BPM, novelty peaks on a section change.
 * The second asks whether the offline extractor and the live [AudioAnalyzer]
 * agree about what a frame *is*. That is the invariant the whole feature rests
 * on: a scanned grid is only worth adopting if the frames it was derived from
 * are the same frames the tap will produce, and a drift there would be invisible
 * everywhere except in a show that is subtly, unaccountably off.
 *
 * Modelled on syncoV2 `tests/test_trackmap.py`.
 */
class TrackAnalysisTest {

    private val sr = ANALYSIS_SAMPLE_RATE

    // ── Signals ────────────────────────────────────────────────────────────

    /** A click track: short decaying bursts at [bpm], starting at 0.25 s. */
    private fun clicks(bpm: Float, seconds: Float, freq: Float = 120f): FloatArray {
        val sig = FloatArray((sr * seconds).toInt())
        val period = 60f / bpm
        val envLen = (0.08f * sr).toInt()
        var t = 0.25f
        while (t < seconds) {
            val i = (t * sr).toInt()
            for (k in 0 until min(envLen, sig.size - i)) {
                val env = exp(-k.toFloat() / (0.02f * sr))
                sig[i + k] += (sin(2.0 * PI * freq * k / sr) * env).toFloat()
            }
            t += period
        }
        return sig
    }

    private fun analyse(signal: FloatArray): OfflineExtractor =
        OfflineExtractor().also { ex ->
            // Pushed in awkward slices on purpose: the decoder hands over
            // buffers of whatever size it likes, and the framing must not
            // depend on them landing on hop boundaries.
            var i = 0
            while (i < signal.size) {
                val n = min(1000, signal.size - i)
                ex.push(signal.copyOfRange(i, i + n))
                i += n
            }
        }

    // ── Frame parity with the live analyzer ────────────────────────────────

    /**
     * The scanned frames and the live frames describe the same samples.
     *
     * Both paths are fed the identical signal — one hop at a time through
     * [AudioAnalyzer], in ragged chunks through [OfflineExtractor] — and their
     * onset envelopes must line up frame for frame. The live analyzer normalises
     * its flux through an AGC and the offline one does not, so the comparison is
     * of the *shape*: where the peaks are, which is what a beat grid is made of.
     */
    @Test
    fun `offline framing matches the live analyzer frame for frame`() {
        val signal = clicks(bpm = 120f, seconds = 12f)
        val ex = analyse(signal)

        val live = AudioAnalyzer()
        val livePeaks = ArrayList<Int>()
        var frame = 0
        var i = 0
        while (i + ANALYSIS_HOP <= signal.size) {
            if (live.push(signal.copyOfRange(i, i + ANALYSIS_HOP)).beat) livePeaks.add(frame)
            frame++
            i += ANALYSIS_HOP
        }

        // Frame counts agree exactly: one frame per whole hop, no more, however
        // ragged the chunks arriving at the offline side were.
        assertEquals(frame, ex.frames, "frame count")
        assertTrue(livePeaks.size > 10, "the live detector found nothing to compare against")

        // And the peaks land on the same frames. The live analyzer normalises
        // its flux through an AGC and the offline one does not, so what is
        // compared is where the energy is — which is all a beat grid is made of.
        val envelope = FloatArray(ex.frames) { ex.env[it] }
        val typical = envelope.average().toFloat()
        for (peak in livePeaks) {
            val here = (peak - 1..peak + 1).mapNotNull { envelope.getOrNull(it) }.max()
            assertTrue(here > 2f * typical, "no offline onset at live beat frame $peak")
        }
    }

    // ── Tempo ──────────────────────────────────────────────────────────────

    @Test
    fun `global tempo finds a 120 BPM click track`() {
        val ex = analyse(clicks(bpm = 120f, seconds = 20f))
        val (bpm, confidence) = estimateTempo(ex.env.toDoubleArray())
        assertEquals(120.0, bpm, 3.0, "bpm")
        assertTrue(confidence > 0.2, "confidence was $confidence")
    }

    /**
     * 140 BPM is the case the upsampling exists for.
     *
     * Its period is 21.43 frames. Scored only at integer lags the true peak
     * splits between 21 and 22 while its double, 42.86, rounds cleanly onto 43 —
     * so the double wins and the whole show runs at half speed. This has to come
     * out at 140, not 70.
     */
    @Test
    fun `140 BPM does not land on its own half`() {
        val ex = analyse(clicks(bpm = 140f, seconds = 30f))
        val env = ex.env.toDoubleArray()
        val tg = tempogram(env)
        assertNotNull(tg, "a 30 s track should produce a tempogram")
        val (lagPath, stability) = tempoPath(tg)
        val medianLag = lagPath.sorted()[lagPath.size / 2]
        val bpm = 60.0 / (medianLag * FRAME_PERIOD)
        assertEquals(140.0, bpm, 6.0, "tempogram bpm")
        assertTrue(stability > 0.8, "a metronome should be a stable path, was $stability")
    }

    // ── Beats ──────────────────────────────────────────────────────────────

    @Test
    fun `the beat tracker lands on the clicks`() {
        val bpm = 120f
        val ex = analyse(clicks(bpm = bpm, seconds = 20f))
        val env = ex.env.toDoubleArray()
        val period = 60.0 / bpm / FRAME_PERIOD
        val beats = trackBeats(env, DoubleArray(env.size) { period })

        assertTrue(beats.size > 30, "expected ~39 beats, got ${beats.size}")
        // Intervals should be the beat period, near enough.
        val intervals = DoubleArray(beats.size - 1) { (beats[it + 1] - beats[it]).toDouble() }
        assertEquals(period, percentile(intervals, 50.0), 1.0, "beat interval in frames")
        // And they should sit on the clicks, which start at 0.25 s.
        for (b in beats.drop(2)) {
            val t = b * FRAME_PERIOD
            val offBeat = ((t - 0.25f) % (60f / bpm)).let { min(it, 60f / bpm - it) }
            assertTrue(offBeat < 0.09f, "beat at ${t}s is $offBeat s off the grid")
        }
    }

    @Test
    fun `an empty or flat envelope produces no beats`() {
        assertEquals(0, trackBeats(DoubleArray(0), DoubleArray(0)).size)
        // A period array of zeros is nonsense and must not divide by it.
        assertEquals(0, trackBeats(DoubleArray(500) { 1.0 }, DoubleArray(500)).size)
    }

    @Test
    fun `the downbeat is the fold with the strongest bass`() {
        // Beats every 10 frames, with every fourth one carrying the bass.
        val beatFrames = IntArray(32) { it * 10 }
        val bass = DoubleArray(400)
        for (k in beatFrames.indices) if (k % 4 == 2) bass[beatFrames[k]] = 1.0
        val (metre, downbeat) = detectMetre(bass, flatChroma, beatFrames, emptyList())
        assertEquals(4, metre)
        assertEquals(2, downbeat)
    }

    /**
     * A bass line that says nothing, and a chord change every third beat.
     *
     * This is the case the old bass-only fold could not do at all: with the low
     * end level, its four candidate phases scored identically and it returned
     * the first one, in 4, whatever the music was. The harmonic term is the only
     * evidence here, and it is enough.
     */
    @Test
    fun `metre comes from harmony when the bass is flat`() {
        val beatFrames = IntArray(36) { it * 10 }
        val bass = DoubleArray(400) { 0.5 }
        // Chroma holds still and then jumps, every third beat starting at 1.
        val chroma = { frame: Int, pc: Int ->
            val beat = frame / 10
            val chord = (beat - 1).floorDiv(3)
            if (pc == Math.floorMod(chord * 5, 12)) 1f else 0f
        }
        val (metre, downbeat) = detectMetre(bass, chroma, beatFrames, emptyList())
        assertEquals(3, metre, "three beats to the bar")
        assertEquals(1, downbeat)
    }

    /** Four wins ties, because four is what almost everything is. */
    @Test
    fun `metre defaults to four on evidence that fits both`() {
        val beatFrames = IntArray(48) { it * 10 }
        val bass = DoubleArray(500)
        // Bass on every twelfth beat — consistent with a downbeat in either 3 or 4.
        for (k in beatFrames.indices) if (k % 12 == 0) bass[beatFrames[k]] = 1.0
        val (metre, _) = detectMetre(bass, flatChroma, beatFrames, emptyList())
        assertEquals(4, metre)
    }

    /**
     * Sub-frame refinement moves a beat toward the envelope's real peak without
     * ever letting it pass its neighbour.
     */
    @Test
    fun `beat refinement interpolates the peak and stays ordered`() {
        val env = DoubleArray(100)
        // A peak whose true position is a third of a frame past index 20:
        // asymmetric neighbours are exactly what the parabola reads.
        env[19] = 0.5; env[20] = 1.0; env[21] = 0.8
        env[39] = 0.8; env[40] = 1.0; env[41] = 0.5
        val refined = refineBeats(env, intArrayOf(20, 40))
        assertTrue(refined[0] > 20 * FRAME_PERIOD, "a peak leaning right moves right")
        assertTrue(refined[0] < 20.5f * FRAME_PERIOD, "and never by more than half a frame")
        assertTrue(refined[1] < 40 * FRAME_PERIOD, "a peak leaning left moves left")
        assertTrue(refined[1] > refined[0], "still ascending")
    }

    /** A flat envelope has no peak to interpolate; the frame index stands. */
    @Test
    fun `beat refinement leaves a flat envelope alone`() {
        val env = DoubleArray(100) { 1.0 }
        val refined = refineBeats(env, intArrayOf(20, 40))
        assertEquals(20 * FRAME_PERIOD, refined[0], 1e-6f)
        assertEquals(40 * FRAME_PERIOD, refined[1], 1e-6f)
    }

    /** Sections that sound alike get the same label; a new sound gets a new one. */
    @Test
    fun `repeated sections share a label`() {
        fun profile(vararg v: Double) = DoubleArray(8) { i -> v.getOrElse(i) { 0.0 } }
            .also { a ->
                val n = kotlin.math.sqrt(a.sumOf { it * it })
                if (n > 0) for (i in a.indices) a[i] /= n
            }
        val verse = profile(1.0, 0.2, 0.1)
        val chorus = profile(0.1, 0.3, 1.0)
        val labels = labelSections(
            listOf(verse, chorus, profile(0.98, 0.22, 0.12), profile(0.12, 0.31, 0.97)),
        )
        assertEquals(labels[0], labels[2], "the second verse is the verse")
        assertEquals(labels[1], labels[3], "the second chorus is the chorus")
        assertTrue(labels[0] != labels[1], "and a verse is not a chorus")
    }

    /** A chroma that is the same everywhere, for tests about the other terms. */
    private val flatChroma = { _: Int, pc: Int -> if (pc == 0) 1f else 0f }

    // ── Sections ───────────────────────────────────────────────────────────

    /**
     * Novelty has to peak where the music changes, and only there.
     *
     * The signal is two halves with entirely different spectra and the same
     * loudness, so nothing but the self-similarity can find the boundary.
     */
    @Test
    fun `sectioning finds a spectral boundary`() {
        val seconds = 60f
        val sig = FloatArray((sr * seconds).toInt())
        val half = sig.size / 2
        for (i in sig.indices) {
            val freq = if (i < half) 200.0 else 3000.0
            sig[i] = (0.5 * sin(2.0 * PI * freq * i / sr)).toFloat()
            // A click track underneath both halves, so the RMS never changes.
            if (i % (sr / 2) < 400) sig[i] += 0.4f
        }
        val ex = analyse(sig)
        val sections = segmentSections(ex, ex.frames * FRAME_PERIOD)

        assertTrue(sections.size >= 2, "expected a boundary, got ${sections.size} section(s)")
        val boundary = sections[0].endS
        assertEquals(seconds / 2, boundary, 3f, "boundary position")
        // Sections must tile the track with no gaps and no overlaps.
        for (i in 0 until sections.size - 1) {
            assertEquals(sections[i].endS, sections[i + 1].startS, 1e-3f, "gap at section $i")
        }
    }

    @Test
    fun `a track too short to segment is one section`() {
        val ex = analyse(clicks(bpm = 120f, seconds = 5f))
        val sections = segmentSections(ex, 5f)
        assertEquals(1, sections.size)
        assertEquals(1f, sections[0].energy)
    }

    // ── Intensity profile ──────────────────────────────────────────────────

    @Test
    fun `the profile spans a track that gets louder`() {
        val seconds = 40f
        val sig = clicks(bpm = 128f, seconds = seconds)
        // A sustained bed under the clicks, because a bare click track is
        // silent 80% of the time and its loudness curve describes the gaps more
        // than the music. Real material is continuous, and the profile's whole
        // job is to describe how *that* moves.
        for (i in sig.indices) sig[i] += (0.25 * sin(2.0 * PI * 220.0 * i / sr)).toFloat()
        // Second half at four times the level: a real quiet-to-loud arc.
        for (i in sig.size / 2 until sig.size) sig[i] *= 4f
        val ex = analyse(sig)
        val profile = buildIntensityProfile(ex, 128f, FloatArray(0))
        assertNotNull(profile)

        assertTrue(profile.sigHi > profile.sigLo, "window is inverted")
        assertTrue(profile.dynamics > 0.1f, "a 4x level change should read as dynamic, got ${profile.dynamics}")
        assertTrue(profile.character in 0f..1f)
        assertTrue(profile.tempo in 0f..1f)
        assertTrue(profile.tilt in -1f..1f)

        // The curve is decimated but still covers the track.
        val covered = profile.curve.size / profile.curveRateHz
        assertEquals(seconds, covered, 1f, "curve coverage")
        // And the loud half really is higher than the quiet one.
        val quiet = profile.sampleAt(seconds * 0.3f)!!
        val loud = profile.sampleAt(seconds * 0.8f)!!
        assertTrue(loud > quiet, "loud half read $loud, quiet half $quiet")
    }

    @Test
    fun `a near-constant track still gets a usable window`() {
        val ex = analyse(clicks(bpm = 120f, seconds = 20f))
        val profile = buildIntensityProfile(ex, 120f, FloatArray(0))
        assertNotNull(profile)
        // The span guard has to keep the window from collapsing to nothing, or
        // the picker divides by ~0 and every frame reads as either 0 or 1.
        assertTrue(profile.sigHi - profile.sigLo >= 0.059f, "window collapsed")
    }

    @Test
    fun `too few frames to profile gives up rather than guessing`() {
        val ex = analyse(clicks(bpm = 120f, seconds = 0.5f))
        assertNull(buildIntensityProfile(ex, 120f, FloatArray(0)))
    }

    // ── The guards ─────────────────────────────────────────────────────────

    @Test
    fun `silence is refused rather than analysed as a dark track`() {
        val ex = analyse(FloatArray(sr * 10))
        val result = finishScan(ex)
        assertTrue(result is ScanResult.Failed, "silence produced $result")
        assertEquals(ScanFailure.SILENT, (result as ScanResult.Failed).reason)
    }

    @Test
    fun `a clip too short to analyse is refused`() {
        val ex = analyse(clicks(bpm = 120f, seconds = 2f))
        val result = finishScan(ex)
        assertTrue(result is ScanResult.Failed)
        assertEquals(ScanFailure.TOO_SHORT, (result as ScanResult.Failed).reason)
    }

    @Test
    fun `a full scan of a click track is usable end to end`() {
        val ex = analyse(clicks(bpm = 128f, seconds = 45f))
        val result = finishScan(ex)
        assertTrue(result is ScanResult.Ok, "scan failed: $result")
        val scan = (result as ScanResult.Ok).scan

        assertEquals(128f, scan.bpm, 4f, "bpm")
        assertTrue(scan.gridUsable, "confidence ${scan.confidence} was not enough to trust")
        assertEquals(scan.beats.size, scan.accents.size, "accents must be parallel to beats")
        assertTrue(scan.beats.toList().zipWithNext().all { (a, b) -> b > a }, "beats must ascend")
        assertTrue(scan.downbeat in 0..3)
        assertEquals(MELBANK_BINS, scan.melbankRef.size, "melbank reference")
        assertTrue(scan.melbankRef.all { it in 0f..1f }, "reference weights must be 0..1")
        assertTrue(scan.melbankRef.any { it > 0.99f }, "the loudest bin should be 1.0")
        assertNotNull(scan.intensity)
    }

    // ── Small pieces ───────────────────────────────────────────────────────

    @Test
    fun `percentile interpolates the way numpy does`() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0, percentile(values, 0.0), 1e-9)
        assertEquals(4.0, percentile(values, 100.0), 1e-9)
        assertEquals(2.5, percentile(values, 50.0), 1e-9)
        assertEquals(1.75, percentile(values, 25.0), 1e-9)
        assertEquals(0.0, percentile(DoubleArray(0), 50.0), 1e-9)
    }

    @Test
    fun `the moving average is centred, not lagged`() {
        // A step in the middle: a centred average crosses halfway *at* the step,
        // where a causal one would still be climbing well past it.
        val x = FloatArray(200) { if (it < 100) 0f else 1f }
        val smoothed = centredMovingAverage(x, 21)
        assertEquals(0.5f, smoothed[100], 0.06f, "the crossing should sit on the step")
        assertEquals(0f, smoothed[50], 1e-6f)
        assertEquals(1f, smoothed[150], 1e-6f)
    }

    @Test
    fun `the moving average normalises its edges`() {
        val x = FloatArray(50) { 1f }
        val smoothed = centredMovingAverage(x, 21)
        // Without edge normalisation the ends would droop toward zero.
        assertTrue(smoothed.all { abs(it - 1f) < 1e-5f }, "edges drooped: ${smoothed.first()}")
    }

    @Test
    fun `rolling accents keep a quiet passage's hierarchy`() {
        // One enormous hit among ordinary ones. Normalised against the whole
        // track's peak the ordinary hits would all read ~0.1; against a rolling
        // reference they stay near full.
        val raw = DoubleArray(60) { 1.0 }
        raw[30] = 10.0
        val accents = rollingAccents(raw)
        assertEquals(1f, accents[0], 1e-4f, "an ordinary hit far from the spike")
        assertTrue(accents[30] > 0.9f, "the spike itself should be strong")
        assertTrue(accents[55] > 0.9f, "hits after the spike must recover")
    }

    @Test
    fun `decimation preserves the shape and the duration`() {
        val curve = FloatArray(1000) { it / 1000f }
        val out = decimate(curve, FRAME_PERIOD, CURVE_RATE_HZ)
        assertEquals(200, out.size, "1000 frames at 50 Hz is 20 s, which is 200 samples at 10 Hz")
        assertTrue(out.first() < 0.05f && out.last() > 0.95f, "the ramp did not survive")
        assertTrue(out.toList().zipWithNext().all { (a, b) -> b >= a }, "monotonicity did not survive")
    }

    /**
     * The resample phase, which is where a whole-analysis-wide error once lived.
     *
     * At 48 kHz the ratio is 2.177, so an integer counter would emit its first
     * sample after three input frames and feed the analyzer 16 kHz while every
     * band edge assumed 22050.
     */
    @Test
    fun `the resample clock holds the true rate`() {
        val clock = BoxResampleClock(48_000f / ANALYSIS_SAMPLE_RATE)
        var emitted = 0
        repeat(48_000) { emitted += clock.advance() }
        assertEquals(ANALYSIS_SAMPLE_RATE, emitted, 2, "one second of 48 kHz")
    }

    @Test
    fun `the resample clock upsamples when the source is slower`() {
        val clock = BoxResampleClock(16_000f / ANALYSIS_SAMPLE_RATE)
        var emitted = 0
        repeat(16_000) { emitted += clock.advance() }
        assertEquals(ANALYSIS_SAMPLE_RATE, emitted, 2, "one second of 16 kHz")
    }

    @Test
    fun `interpolation matches its anchors and clamps outside them`() {
        val x = doubleArrayOf(0.0, 10.0, 20.0)
        val y = doubleArrayOf(1.0, 3.0, 2.0)
        val out = interpolateToFrames(x, y, 25)
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(3.0, out[10], 1e-9)
        assertEquals(2.0, out[20], 1e-9)
        assertEquals(2.0, out[5], 1e-9, "halfway between 1 and 3")
        assertEquals(2.0, out[24], 1e-9, "past the last anchor, held")
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int, message: String) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$message: expected $expected ± $tolerance, was $actual",
        )
    }
}
