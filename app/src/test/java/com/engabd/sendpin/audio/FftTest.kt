package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FFT has to put energy in the bin the frequency actually belongs to.
 *
 * This is the test that was missing when the real→complex packing was wrong. The
 * scratch buffer is interleaved `[re, im, re, im, …]`, and copying a real window
 * into it linearly puts every odd sample into an *imaginary* slot — which
 * transforms `x[2n] + i·x[2n+1]` rather than the signal. Every band edge, the mel
 * filterbank and the SuperFlux onset bands then read the wrong frequencies. The
 * lights still moved, because broadband energy leaks into every bin regardless,
 * which is exactly why nothing caught it by eye.
 *
 * A single-bin sine is the sharpest available probe: if packing is wrong, the peak
 * lands somewhere else entirely and the mirror term shows up as a second peak.
 */
class FftTest {

    /** The analyzer's real geometry: 1024-sample window zero-padded to 2048. */
    private val nfft = 2048
    private val window = 1024
    private val sampleRate = 22050f

    /** Frequency of FFT bin [k] at this transform size. */
    private fun binFreq(k: Int): Float = k * sampleRate / nfft

    private fun sineWindow(freqHz: Float, n: Int = window): FloatArray =
        FloatArray(n) { i -> sin(2.0 * PI * freqHz * i / sampleRate).toFloat() }

    private fun peakBin(mags: FloatArray): Int {
        var best = 0
        for (i in mags.indices) if (mags[i] > mags[best]) best = i
        return best
    }

    @Test
    fun `sine energy lands in the bin containing its frequency`() {
        val fft = Fft(nfft)
        // Sweep across the spectrum, including the bass and mid band edges the
        // analyzer splits on (200 Hz for bass onsets, 2500 Hz for mid).
        for (freq in listOf(100f, 200f, 440f, 1000f, 2500f, 5000f, 9000f)) {
            val mags = fft.magnitudePower(sineWindow(freq))
            val peak = peakBin(mags)
            val expected = Math.round(freq / (sampleRate / nfft))
            assertTrue(
                abs(peak - expected) <= 1,
                "sine at $freq Hz peaked at bin $peak (${binFreq(peak)} Hz), expected ~$expected",
            )
        }
    }

    @Test
    fun `no mirror peak from odd samples`() {
        // The packing bug produced a second, comparable peak from the odd-sample
        // subsequence. Assert the true peak dominates everything outside its skirt.
        val fft = Fft(nfft)
        val mags = fft.magnitudePower(sineWindow(1000f))
        val peak = peakBin(mags)
        var runnerUp = 0f
        for (i in mags.indices) {
            if (abs(i - peak) <= 8) continue  // spectral leakage skirt of the rect-ish window
            if (mags[i] > runnerUp) runnerUp = mags[i]
        }
        assertTrue(
            runnerUp < mags[peak] * 0.01f,
            "expected the 1 kHz peak to dominate; peak=${mags[peak]} runnerUp=$runnerUp",
        )
    }

    @Test
    fun `dc input puts all energy in bin zero`() {
        val fft = Fft(nfft)
        val mags = fft.magnitudePower(FloatArray(window) { 1f })
        assertEquals(0, peakBin(mags), "constant input should peak at DC")
    }

    @Test
    fun `silence produces no energy`() {
        val fft = Fft(nfft)
        val mags = fft.magnitudePower(FloatArray(window))
        assertTrue(mags.all { it == 0f }, "silent window should have zero power in every bin")
    }

    @Test
    fun `matches a direct dft`() {
        // Ground truth against the definition, on a size small enough to brute force.
        val n = 64
        val fft = Fft(n)
        val x = FloatArray(n) { i -> sin(2.0 * PI * 5 * i / n).toFloat() + 0.5f * cos(2.0 * PI * 11 * i / n).toFloat() }
        val mags = fft.magnitudePower(x)
        for (k in mags.indices) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                re += x[t] * cos(a)
                im += x[t] * sin(a)
            }
            val expected = (re * re + im * im).toFloat()
            assertTrue(
                abs(mags[k] - expected) <= 1e-2f * maxOf(1f, abs(expected)),
                "bin $k: fft=${mags[k]} dft=$expected",
            )
        }
    }

    @Test
    fun `output buffer is reused across calls`() {
        // The hop path runs ~50 times a second for the length of a track, so the
        // scratch buffers are deliberately shared. Callers get the same array back
        // and must consume it before the next push; the analyzer square-roots it
        // in place, which only works because of this contract.
        val fft = Fft(nfft)
        val first = fft.magnitudePower(sineWindow(1000f))
        val second = fft.magnitudePower(sineWindow(1000f))
        assertTrue(first === second, "expected the same array instance back")
    }

    @Test
    fun `stale state does not leak between windows`() {
        // The scratch buffer is reused, so it must be cleared: a loud window
        // followed by silence has to read as silence, not as the previous window.
        val fft = Fft(nfft)
        fft.magnitudePower(sineWindow(1000f))
        val quiet = fft.magnitudePower(FloatArray(window))
        assertTrue(quiet.all { it == 0f }, "previous window's energy leaked into a silent one")
    }
}
