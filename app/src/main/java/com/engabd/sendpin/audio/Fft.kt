package com.engabd.sendpin.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT (Cooley-Tukey). No external library.
 *
 * Operates on real input (the audio samples) and produces complex output as
 * interleaved [re, im, re, im, ...] pairs, matching the usage pattern in
 * `AudioAnalyzer`. The FFT size must be a power of 2.
 *
 * Ported from the standard radix-2 algorithm used in syncoV2's `numpy.fft.rfft`
 * — but without numpy, so the bit-reversal and butterfly loops are explicit.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of 2, got $size" }
    }

    /**
     * Pre-computed twiddle factors, `w[k] = e^(-2πik/N)`, split into parallel
     * float arrays rather than an `Array<Pair<Float, Float>>`: a `Pair<Float,
     * Float>` boxes both components, so the butterfly's innermost loop would
     * chase two pointers and unbox on every read.
     */
    private val twiddleRe = FloatArray(size / 2) { k ->
        cos(-2.0 * Math.PI * k / size).toFloat()
    }
    private val twiddleIm = FloatArray(size / 2) { k ->
        sin(-2.0 * Math.PI * k / size).toFloat()
    }

    /**
     * Bit-reversal table for the input permutation.
     */
    private val bitReverse: IntArray = IntArray(size) { i ->
        var v = 0
        var x = i
        var bits = size
        bits = Integer.numberOfTrailingZeros(bits) // log2(size)
        repeat(bits) {
            v = (v shl 1) or (x and 1)
            x = x shr 1
        }
        v
    }

    /**
     * In-place forward FFT on complex data stored as interleaved [re, im, ...].
     * Input length must be `size * 2` (size complex pairs).
     */
    fun forward(data: FloatArray) {
        require(data.size == size * 2) { "Data must be ${size * 2} floats ($size complex pairs), got ${data.size}" }

        // Bit-reversal permutation.
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = data[i * 2]; data[i * 2] = data[j * 2]; data[j * 2] = tr
                val ti = data[i * 2 + 1]; data[i * 2 + 1] = data[j * 2 + 1]; data[j * 2 + 1] = ti
            }
        }

        // Butterfly: combine pairs at each stage.
        var len = 2
        while (len <= size) {
            val halfLen = len / 2
            val step = size / len
            for (i in 0 until size step len) {
                for (j in 0 until halfLen) {
                    val k = j * step
                    val wr = twiddleRe[k]
                    val wi = twiddleIm[k]
                    val ar = i + j
                    val br = i + j + halfLen
                    val tr = data[br * 2] * wr - data[br * 2 + 1] * wi
                    val ti = data[br * 2] * wi + data[br * 2 + 1] * wr
                    data[br * 2] = data[ar * 2] - tr
                    data[br * 2 + 1] = data[ar * 2 + 1] - ti
                    data[ar * 2] = data[ar * 2] + tr
                    data[ar * 2 + 1] = data[ar * 2 + 1] + ti
                }
            }
            len *= 2
        }
    }

    /** Scratch complex buffer, reused across calls. See [magnitudePower]. */
    private val complex = FloatArray(size * 2)

    /** Scratch output buffer, reused across calls. See [magnitudePower]. */
    private val mags = FloatArray(size / 2 + 1)

    /**
     * One-sided power spectrum of a real-valued window: `size/2 + 1` bins of
     * `re² + im²`. Not square-rooted — callers that only compare bins can skip
     * the sqrt, and [AudioAnalyzer] takes it in place when it does want linear
     * magnitude.
     *
     * The returned array is **owned by this Fft instance** and is overwritten by
     * the next call. Callers may mutate it (the analyzer square-roots in place)
     * but must finish with it before pushing another window. This keeps the hop
     * path allocation-free, which matters because it runs at ~50 Hz for the whole
     * length of a track.
     *
     * Real input goes in the real slots only — `complex[2i]` — leaving the
     * imaginary slots zero. Packing it any other way (e.g. `arraycopy` straight
     * into the interleaved buffer) transforms `x[2n] + i·x[2n+1]` instead of the
     * signal, which is the packed real-FFT trick and is only valid with a
     * conjugate-symmetry unpacking step this does not do.
     */
    fun magnitudePower(window: FloatArray): FloatArray {
        java.util.Arrays.fill(complex, 0f)
        val copyLen = minOf(window.size, size)
        for (i in 0 until copyLen) complex[i * 2] = window[i]
        forward(complex)
        for (i in mags.indices) {
            val re = complex[i * 2]
            val im = complex[i * 2 + 1]
            mags[i] = re * re + im * im
        }
        return mags
    }
}