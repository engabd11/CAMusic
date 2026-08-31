package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR section, from the Audio EQ Cookbook.
 *
 * The coefficients are the standard RBJ forms, already divided through by a0 so
 * the difference equation is a plain multiply-accumulate. [process] runs the
 * Direct Form I recurrence, which costs two more state variables than Form II but
 * is far better behaved in single precision — and single precision is what the
 * audio path hands us.
 *
 * A section is **stateful and not thread-safe**: it belongs to one channel of one
 * stream, and [reset] must be called whenever that stream discontinues (a seek, a
 * track change, a flush) or the tail of the old audio rings into the new.
 */
class Biquad {

    // Coefficients, normalised by a0.
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Direct Form I state.
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    /** Coefficients for a straight wire, for a band that is doing nothing. */
    fun setPassthrough() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    /**
     * A peaking EQ at [freq] with [q] and [gainDb].
     *
     * The one used for every ordinary band. A gain of 0 dB is *not* silently a
     * passthrough here — the maths gives one anyway — so callers do not have to
     * special-case a band a listener has dialled back to flat.
     */
    fun setPeaking(sampleRate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * clampQ(q))).toFloat()

        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha / a) / a0
    }

    /** A low shelf: everything below [freq] lifted or cut by [gainDb]. */
    fun setLowShelf(sampleRate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * clampQ(q))).toFloat()
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val a0 = (a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha
        b0 = a * ((a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha) / a0
        b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW) / a0
        b2 = a * ((a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha) / a0
        a1 = -2f * ((a - 1f) + (a + 1f) * cosW) / a0
        a2 = ((a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha) / a0
    }

    /** A high shelf: everything above [freq] lifted or cut by [gainDb]. */
    fun setHighShelf(sampleRate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * clampQ(q))).toFloat()
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val a0 = (a + 1f) - (a - 1f) * cosW + twoSqrtAAlpha
        b0 = a * ((a + 1f) + (a - 1f) * cosW + twoSqrtAAlpha) / a0
        b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW) / a0
        b2 = a * ((a + 1f) + (a - 1f) * cosW - twoSqrtAAlpha) / a0
        a1 = 2f * ((a - 1f) - (a + 1f) * cosW) / a0
        a2 = ((a + 1f) - (a - 1f) * cosW - twoSqrtAAlpha) / a0
    }

    /** A 12 dB/octave high-pass at [freq] — for cutting rumble, not for EQ. */
    fun setHighPass(sampleRate: Int, freq: Float, q: Float) {
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * clampQ(q))).toFloat()

        val a0 = 1f + alpha
        b0 = ((1f + cosW) / 2f) / a0
        b1 = (-(1f + cosW)) / a0
        b2 = ((1f + cosW) / 2f) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha) / a0
    }

    /** A 12 dB/octave low-pass at [freq]. */
    fun setLowPass(sampleRate: Int, freq: Float, q: Float) {
        val w0 = 2.0 * PI * clampFreq(freq, sampleRate) / sampleRate
        val cosW = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * clampQ(q))).toFloat()

        val a0 = 1f + alpha
        b0 = ((1f - cosW) / 2f) / a0
        b1 = (1f - cosW) / a0
        b2 = ((1f - cosW) / 2f) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha) / a0
    }

    /** One sample through the section. */
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /**
     * The magnitude response at [freq], in dB — what this section does to a sine
     * of that frequency.
     *
     * Only used by tests and by anything that wants to draw the curve; the audio
     * path never calls it. Evaluates |H(e^jw)| directly from the coefficients, so
     * it verifies what the filter *is* rather than what it was asked to be.
     */
    fun magnitudeDb(sampleRate: Int, freq: Float): Float {
        val w = 2.0 * PI * freq / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)

        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        if (den <= 0.0 || num <= 0.0) return -120f
        return (20.0 * ln(num / den) / ln(10.0)).toFloat()
    }

    companion object {
        /**
         * Keep the centre frequency inside the band the maths is valid over.
         *
         * At or above Nyquist the cookbook formulae produce coefficients for an
         * unstable filter, and a listener with a 20 kHz band playing a 32 kHz file
         * should hear that band do nothing rather than hear the section explode.
         */
        private fun clampFreq(freq: Float, sampleRate: Int): Float =
            freq.coerceIn(10f, sampleRate * 0.49f)

        /** Q of zero is a divide by zero; absurdly high Q rings for seconds. */
        private fun clampQ(q: Float): Double = q.coerceIn(0.05f, 20f).toDouble()

        /**
         * The Q that gives a shelf or peak [bandwidthOctaves] wide.
         *
         * Offered because a listener thinks in octaves and the cookbook wants Q.
         */
        fun qForBandwidth(bandwidthOctaves: Float): Float {
            val bw = bandwidthOctaves.coerceIn(0.05f, 6f)
            val twoPowBw = 2.0.pow(bw.toDouble())
            return (sqrt(twoPowBw) / (twoPowBw - 1.0)).toFloat()
                .let { if (it.isFinite() && it > 0f) it else 1f }
        }

        /**
         * How much headroom a set of gains needs, in dB, to avoid clipping.
         *
         * The honest answer for arbitrary material is the sum of every boost: two
         * bands that overlap can add, and a signal already at full scale has
         * nowhere to go. That is also far too pessimistic to be usable — nobody
         * wants 18 dB of attenuation because they nudged six bands up by three.
         *
         * So this takes the largest single boost plus a share of the rest, which
         * is what every practical equaliser does. It is a guard against the common
         * case, not a proof, which is why [LocalDsp] also has a limiter behind it.
         */
        fun suggestedPreampDb(gainsDb: List<Float>): Float {
            val boosts = gainsDb.filter { it > 0f }.sortedDescending()
            if (boosts.isEmpty()) return 0f
            val head = boosts.first()
            val rest = boosts.drop(1).sum() * 0.5f
            return -(head + rest).coerceIn(0f, 24f)
        }

        /** dB to a linear amplitude factor. */
        fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

        /** True when a gain is close enough to flat that a band can be skipped. */
        fun isFlat(gainDb: Float): Boolean = abs(gainDb) < 0.05f
    }
}
