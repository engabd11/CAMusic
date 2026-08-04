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
     * Pre-computed twiddle factors (cos, sin) for each stage.
     * `twiddles[k] = (cos(-2πk/N), sin(-2πk/N))`.
     */
    private val twiddles: Array<Pair<Float, Float>> = Array(size / 2) { k ->
        val angle = -2.0 * Math.PI * k / size
        cos(angle).toFloat() to sin(angle).toFloat()
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
                    val (wr, wi) = twiddles[k]
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

    /**
     * One-sided magnitude spectrum of a real-valued window.
     * Returns `size/2 + 1` magnitudes (re²+im², not sqrt'd — callers that
     * only compare values can skip the sqrt for speed).
     */
    fun magnitudePower(window: FloatArray): FloatArray {
        // Prepare complex input (real, imag=0), zero-padded if needed.
        val complex = FloatArray(size * 2)
        val copyLen = minOf(window.size, size)
        System.arraycopy(window, 0, complex, 0, copyLen)
        forward(complex)
        val nOut = size / 2 + 1
        val mags = FloatArray(nOut)
        for (i in 0 until nOut) {
            val re = complex[i * 2]
            val im = complex[i * 2 + 1]
            mags[i] = re * re + im * im
        }
        return mags
    }
}