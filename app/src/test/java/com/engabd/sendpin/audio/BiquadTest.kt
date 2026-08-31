package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter maths, checked two independent ways.
 *
 * [magnitudeDb] is evaluated from the coefficients, so it says what the filter
 * *is*. The `measured` helper below pushes a real sine through [Biquad.process]
 * and reads the output level, so it says what the filter *does*. A mistake in the
 * cookbook coefficients shows up in both; a mistake in the difference equation
 * shows up only in the second, which is why both are here.
 */
class BiquadTest {

    private val rate = 48_000

    /** Peak amplitude of a sine at [freq] after passing through [b], in dB. */
    private fun measured(b: Biquad, freq: Float, cycles: Int = 200): Float {
        b.reset()
        val samplesPerCycle = rate / freq
        val n = (samplesPerCycle * cycles).toInt()
        // Ignore the first third: an IIR section needs time to settle, and
        // measuring through the transient would read the ringing, not the gain.
        val settle = n / 3
        var peak = 0f
        for (i in 0 until n) {
            val x = sin(2.0 * PI * freq * i / rate).toFloat()
            val y = b.process(x)
            if (i >= settle) peak = maxOf(peak, abs(y))
        }
        return (20.0 * kotlin.math.log10(peak.toDouble())).toFloat()
    }

    // ── peaking ───────────────────────────────────────────────────────────

    @Test
    fun `a peaking band gives its gain at the centre frequency`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 1f, gainDb = 6f)

        assertEquals(6f, b.magnitudeDb(rate, 1_000f), 0.05f)
        assertEquals(6f, measured(b, 1_000f), 0.2f)
    }

    @Test
    fun `a peaking cut gives its cut at the centre frequency`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 1f, gainDb = -9f)

        assertEquals(-9f, b.magnitudeDb(rate, 1_000f), 0.05f)
        assertEquals(-9f, measured(b, 1_000f), 0.2f)
    }

    @Test
    fun `a peaking band leaves distant frequencies alone`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 2f, gainDb = 9f)

        // Four octaves either side is untouched to within a fraction of a dB.
        assertEquals(0f, b.magnitudeDb(rate, 62.5f), 0.4f)
        assertEquals(0f, b.magnitudeDb(rate, 16_000f), 0.4f)
    }

    @Test
    fun `zero gain is a straight wire`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 1f, gainDb = 0f)

        for (f in listOf(50f, 400f, 1_000f, 5_000f, 15_000f)) {
            assertEquals("at $f Hz", 0f, b.magnitudeDb(rate, f), 0.01f)
        }
    }

    @Test
    fun `a higher Q makes a narrower band`() {
        val wide = Biquad().apply { setPeaking(rate, 1_000f, q = 0.7f, gainDb = 9f) }
        val narrow = Biquad().apply { setPeaking(rate, 1_000f, q = 4f, gainDb = 9f) }

        // Both hit 9 dB dead centre...
        assertEquals(9f, wide.magnitudeDb(rate, 1_000f), 0.05f)
        assertEquals(9f, narrow.magnitudeDb(rate, 1_000f), 0.05f)
        // ...and an octave up the narrow one has already let go.
        assertTrue(wide.magnitudeDb(rate, 2_000f) > narrow.magnitudeDb(rate, 2_000f))
    }

    // ── shelves ───────────────────────────────────────────────────────────

    @Test
    fun `a low shelf lifts the bottom and leaves the top`() {
        val b = Biquad()
        b.setLowShelf(rate, freq = 200f, q = 0.7f, gainDb = 6f)

        assertEquals(6f, b.magnitudeDb(rate, 20f), 0.3f)
        assertEquals(3f, b.magnitudeDb(rate, 200f), 0.6f)   // half gain at the corner
        assertEquals(0f, b.magnitudeDb(rate, 8_000f), 0.2f)
        assertEquals(6f, measured(b, 30f), 0.4f)
    }

    @Test
    fun `a high shelf lifts the top and leaves the bottom`() {
        val b = Biquad()
        b.setHighShelf(rate, freq = 4_000f, q = 0.7f, gainDb = -6f)

        assertEquals(-6f, b.magnitudeDb(rate, 18_000f), 0.4f)
        assertEquals(0f, b.magnitudeDb(rate, 100f), 0.2f)
    }

    // ── pass filters ──────────────────────────────────────────────────────

    @Test
    fun `a high-pass is minus three dB at its corner and rolls off below`() {
        val b = Biquad()
        b.setHighPass(rate, freq = 100f, q = 0.707f)

        assertEquals(-3f, b.magnitudeDb(rate, 100f), 0.3f)
        assertEquals(0f, b.magnitudeDb(rate, 2_000f), 0.2f)
        // Twelve dB per octave: two octaves down is about -24 dB.
        assertTrue(b.magnitudeDb(rate, 25f) < -20f)
    }

    @Test
    fun `a low-pass is minus three dB at its corner and rolls off above`() {
        val b = Biquad()
        b.setLowPass(rate, freq = 8_000f, q = 0.707f)

        assertEquals(-3f, b.magnitudeDb(rate, 8_000f), 0.3f)
        assertEquals(0f, b.magnitudeDb(rate, 200f), 0.2f)
        assertTrue(b.magnitudeDb(rate, 20_000f) < -12f)
    }

    // ── stability and safety ──────────────────────────────────────────────

    @Test
    fun `a band above Nyquist is clamped rather than allowed to explode`() {
        // A 20 kHz band on a 32 kHz file: Nyquist is 16 kHz, and the cookbook
        // formulae past it produce an unstable section. The listener should hear
        // that band do nothing, not hear the filter blow up.
        val b = Biquad()
        b.setPeaking(32_000, freq = 20_000f, q = 1f, gainDb = 12f)

        var x = 1f
        repeat(4_000) { x = b.process(if (it == 0) 1f else 0f) }
        assertTrue("impulse response diverged", abs(x) < 1f)
    }

    @Test
    fun `a zero Q does not divide by zero`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 0f, gainDb = 6f)

        assertTrue(b.magnitudeDb(rate, 1_000f).isFinite())
        assertTrue(b.process(0.5f).isFinite())
    }

    @Test
    fun `reset clears the ringing so a new track cannot inherit the last one`() {
        val b = Biquad()
        b.setPeaking(rate, freq = 1_000f, q = 8f, gainDb = 12f)
        // Excite it hard, then stop.
        repeat(500) { b.process(sin(2.0 * PI * 1_000 * it / rate).toFloat()) }

        val ringing = b.process(0f)
        b.reset()
        val afterReset = b.process(0f)

        assertTrue("expected the filter to be ringing", abs(ringing) > 1e-4f)
        assertEquals(0f, afterReset, 1e-9f)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    @Test
    fun `a one-octave bandwidth gives the textbook Q`() {
        // The standard result: one octave is Q ~= 1.41, two octaves ~= 0.67.
        assertEquals(1.41f, Biquad.qForBandwidth(1f), 0.02f)
        assertEquals(0.67f, Biquad.qForBandwidth(2f), 0.02f)
    }

    @Test
    fun `the preamp answers zero for a curve with no boosts in it`() {
        assertEquals(0f, Biquad.suggestedPreampDb(listOf(-3f, -6f, 0f)), 1e-6f)
        assertEquals(0f, Biquad.suggestedPreampDb(emptyList()), 1e-6f)
    }

    @Test
    fun `the preamp pulls back by the largest boost plus a share of the rest`() {
        // One 6 dB boost costs 6 dB of headroom.
        assertEquals(-6f, Biquad.suggestedPreampDb(listOf(6f)), 1e-4f)
        // Three of them cost the largest plus half the others - not all 18, which
        // is the pessimistic answer nobody would accept, and not 6, which clips.
        assertEquals(-12f, Biquad.suggestedPreampDb(listOf(6f, 6f, 6f)), 1e-4f)
    }

    @Test
    fun `dB and linear agree at the landmarks`() {
        assertEquals(1f, Biquad.dbToLinear(0f), 1e-6f)
        assertEquals(2f, Biquad.dbToLinear(6.0206f), 1e-3f)
        assertEquals(0.5f, Biquad.dbToLinear(-6.0206f), 1e-3f)
    }
}
