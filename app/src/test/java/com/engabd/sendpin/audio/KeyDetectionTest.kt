package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Key detection, in two halves.
 *
 * The first asks whether the correlation does its job: does a synthetic chroma
 * shaped like a real key's tone profile come back as that key, and does a thin
 * or ambiguous one come back honest about it rather than confidently guessing?
 *
 * The second asks the harder question, of [KeyChroma] rather than of the
 * correlation — whether a chroma built from an actual spectrum points at the
 * note that was played. That is where every real failure lived: the correlation
 * was never wrong about a clean vector, it was being handed vectors that did not
 * describe the music. These tests use synthesised tones with known frequencies,
 * so unlike the corpus harness in `tools/analysis-harness` they have a right
 * answer and can run in CI.
 */
class KeyDetectionTest {

    // Krumhansl-Kessler profiles, tonic at index 0. Kept as the test's own
    // copy rather than reaching into the private ones in KeyDetection.kt —
    // this way the test is checking the algorithm's behaviour against a
    // known-correct shape, not merely echoing whatever the implementation
    // currently holds.
    private val majorProfile = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val minorProfile = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )

    private fun rotate(profile: FloatArray, tonic: Int): FloatArray =
        FloatArray(12) { i -> profile[Math.floorMod(i - tonic, 12)] }

    @Test
    fun `a clean major profile is recovered exactly`() {
        val key = detectKey(rotate(majorProfile, 7))  // G major
        assertEquals(7, key?.tonic)
        assertEquals(MusicalMode.MAJOR, key?.mode)
    }

    @Test
    fun `a clean minor profile is recovered exactly`() {
        val key = detectKey(rotate(minorProfile, 9))  // A minor
        assertEquals(9, key?.tonic)
        assertEquals(MusicalMode.MINOR, key?.mode)
    }

    @Test
    fun `every tonic rotation is recovered, not just one`() {
        for (tonic in 0 until 12) {
            val key = detectKey(rotate(majorProfile, tonic))
            assertEquals(tonic, key?.tonic, "tonic $tonic")
            assertEquals(MusicalMode.MAJOR, key?.mode, "tonic $tonic")
        }
    }

    @Test
    fun `a silent or near-silent chroma vector yields no key`() {
        assertNull(detectKey(FloatArray(12)))
        assertNull(detectKey(FloatArray(12) { 1e-9f }))
    }

    @Test
    fun `wrong-length input yields no key`() {
        assertNull(detectKey(FloatArray(11)))
        assertNull(detectKey(FloatArray(13)))
    }

    @Test
    fun `an ambiguous blend of two keys a fifth apart is less confident than a clean read`() {
        val clean = detectKey(rotate(majorProfile, 0))!!

        // C major and G major share six of seven scale tones — the textbook
        // case this correlation method cannot call cleanly.
        val blended = FloatArray(12) { i -> rotate(majorProfile, 0)[i] + rotate(majorProfile, 7)[i] }
        val ambiguous = detectKey(blended)!!

        assertTrue(
            ambiguous.confidence < clean.confidence,
            "a blend of two keys should read less confident than a clean one: " +
                "${ambiguous.confidence} vs ${clean.confidence}",
        )
    }

    // ── The chroma itself ──────────────────────────────────────────────────

    private val freqs = analysisBinFreqs()

    /**
     * The magnitude spectrum of a sum of sinusoids, as the analyser would see
     * it — Hann-windowed and zero-padded to [NFFT], so the peaks have the real
     * leakage skirts [KeyChroma] has to cope with rather than being deltas.
     */
    private fun spectrumOf(vararg partials: Pair<Float, Float>): FloatArray {
        val window = ANALYSIS_WINDOW
        val buf = FloatArray(window)
        for (i in 0 until window) {
            var v = 0.0
            for ((freq, amp) in partials) {
                v += amp * kotlin.math.sin(2.0 * Math.PI * freq * i / ANALYSIS_SAMPLE_RATE)
            }
            val hann = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (window - 1))
            buf[i] = (v * hann).toFloat()
        }
        val mag = Fft(NFFT).magnitudePower(buf)
        for (i in mag.indices) mag[i] = kotlin.math.sqrt(mag[i])
        return mag
    }

    /** Concert A, and its first four harmonics, should read as pitch class 9. */
    @Test
    fun `a harmonic tone lands on its own pitch class`() {
        val chroma = KeyChroma(freqs)
        val mag = spectrumOf(440f to 1f, 880f to 0.5f, 1320f to 0.3f, 1760f to 0.2f)
        repeat(50) { chroma.push(mag, width = 0f) }
        val folded = chroma.fold()
        val loudest = folded.indices.maxByOrNull { folded[it] }
        assertEquals(9, loudest, "A440 is pitch class 9; got ${folded.toList()}")
    }

    /**
     * The point of the harmonic credit, as its own test.
     *
     * A tone's third harmonic sits a fifth above it, so a chroma that credits
     * every peak to its own pitch class reports a fifth that nobody played. Here
     * that would be pitch class 4 (E) alongside the A: the test is that A still
     * wins comfortably, which it did not before the credit table existed.
     */
    @Test
    fun `the fifth manufactured by the third harmonic does not outvote the fundamental`() {
        val chroma = KeyChroma(freqs)
        // A deliberately harmonic-heavy tone: the third partial is as loud as
        // the fundamental, which is the case that used to break it.
        val mag = spectrumOf(220f to 1f, 440f to 1f, 660f to 1f, 880f to 0.8f, 1100f to 0.6f)
        repeat(50) { chroma.push(mag, width = 0f) }
        val folded = chroma.fold()
        assertTrue(
            folded[9] > folded[4],
            "A should outweigh the E its third harmonic manufactures: ${folded.toList()}",
        )
    }

    /**
     * A detuned track reads as detuned, and by how much.
     *
     * 440 Hz sharpened by 30 cents is 447.7 Hz. The estimate is a circular mean
     * over interpolated peak positions, so it should land within a few cents —
     * an accuracy an FFT-bin assignment cannot reach at any frequency in range.
     */
    @Test
    fun `tuning is measured from the peaks, not the bin grid`() {
        val cents = 30.0
        val ratio = Math.pow(2.0, cents / 1200.0).toFloat()
        val chroma = KeyChroma(freqs)
        val mag = spectrumOf(
            440f * ratio to 1f, 660f * ratio to 0.6f, 880f * ratio to 0.5f, 1320f * ratio to 0.3f,
        )
        repeat(50) { chroma.push(mag, width = 0f) }
        assertEquals(cents.toFloat(), chroma.tuningCents, 8f)
    }

    /** And a track in tune reads as in tune, rather than as the shape of the bin grid. */
    @Test
    fun `an in-tune track measures near zero cents`() {
        val chroma = KeyChroma(freqs)
        val mag = spectrumOf(440f to 1f, 660f to 0.6f, 880f to 0.5f, 1320f to 0.3f, 1760f to 0.2f)
        repeat(50) { chroma.push(mag, width = 0f) }
        assertTrue(
            kotlin.math.abs(chroma.tuningCents) < 8f,
            "expected roughly zero, got ${chroma.tuningCents}",
        )
    }

    /**
     * The tuning correction actually reaches the fold.
     *
     * A tone 40 cents sharp of A is still an A, and must not be rounded up into
     * the A#/Bb next door — which is exactly what an uncorrected fold does once
     * the offset passes halfway.
     */
    @Test
    fun `a sharp track still lands on the right pitch class`() {
        val ratio = Math.pow(2.0, 40.0 / 1200.0).toFloat()
        val chroma = KeyChroma(freqs)
        val mag = spectrumOf(440f * ratio to 1f, 880f * ratio to 0.5f, 1320f * ratio to 0.3f)
        repeat(50) { chroma.push(mag, width = 0f) }
        val folded = chroma.fold()
        assertEquals(9, folded.indices.maxByOrNull { folded[it] }, "still an A, 40 cents sharp")
    }

    /** A percussive frame has no pitch class to offer and is not asked for one. */
    @Test
    fun `a broadband frame contributes nothing`() {
        val chroma = KeyChroma(freqs)
        val mag = spectrumOf(440f to 1f, 880f to 0.5f)
        repeat(20) { chroma.push(mag, width = 0.9f) }
        assertEquals(0, chroma.framesUsed)
        val folded = chroma.fold()
        assertTrue(folded.all { it == 0f }, "nothing should have accumulated")
    }

    /**
     * Temperley over Krumhansl-Kessler is a choice, so it is worth one test that
     * the choice is wired up: the shipped [detectKey] must agree with the
     * Temperley overload and not with the K-K one on a vector where they differ.
     */
    @Test
    fun `the shipped detector uses the Temperley profiles`() {
        val vec = FloatArray(12) { i -> TEMPERLEY_MINOR[Math.floorMod(i - 3, 12)].toFloat() }
        val shipped = detectKey(vec)
        val temperley = detectKey(vec, TEMPERLEY_MAJOR, TEMPERLEY_MINOR)
        assertEquals(temperley, shipped)
        assertEquals(3, shipped?.tonic)
        assertEquals(MusicalMode.MINOR, shipped?.mode)
    }
}
