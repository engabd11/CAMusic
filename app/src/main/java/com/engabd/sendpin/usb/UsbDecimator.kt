package com.engabd.sendpin.usb

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact-ratio downsampling for a track above the DAC's highest rate: 192 kHz to 96 kHz
 * on a DAC that stops at 96, for instance.
 *
 * The alternative was Android's own path, which on many phones converts everything to a
 * fixed 48 kHz — for USB bit-perfect, the one thing it exists to avoid. This keeps the
 * track on CAMusic's driver at the highest rate the DAC takes, and the signal path says
 * plainly that this track was converted: nothing that changes the rate is bit-perfect.
 *
 * Integer ratios only (2 or 4), done as stages of 2:1: a linear-phase half-band
 * windowed-sinc low-pass, then every other sample. [TAPS] taps with a Kaiser window
 * give a passband flat to the audible range and better than 120 dB of rejection where
 * the images would fold back. Half-band means every other coefficient is zero, which
 * the filter skips. Float in, float out; interleaved.
 */
class UsbDecimator(private val factor: Int, private val channels: Int) {

    init {
        require(factor == 2 || factor == 4) { "factor must be 2 or 4" }
        require(channels >= 1)
    }

    private val stages = List(if (factor == 4) 2 else 1) { Stage(channels) }

    /**
     * Downsample [frames] interleaved frames from [input]; returns the output frames,
     * written to [output] (which needs `frames / factor + 1` frames of room).
     */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        var buf = input
        var n = frames
        for ((i, stage) in stages.withIndex()) {
            val out = if (i == stages.lastIndex) output else scratchFor(n / 2 + 1)
            n = stage.process(buf, n, out)
            buf = out
        }
        return n
    }

    fun reset() = stages.forEach(Stage::reset)

    private var scratch = FloatArray(0)
    private fun scratchFor(frames: Int): FloatArray {
        val need = frames * channels
        if (scratch.size < need) scratch = FloatArray(need)
        return scratch
    }

    /** One 2:1 half-band stage, with its own history per channel. */
    private class Stage(private val channels: Int) {
        // History of the last TAPS-1 input frames, per channel, then the new input.
        private val history = Array(channels) { FloatArray(TAPS - 1) }
        private var phase = 0 // which input sample the next output lands on

        fun reset() {
            history.forEach { it.fill(0f) }
            phase = 0
        }

        fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
            var outFrames = 0
            // Work per channel over [history | input], then keep the tail as history.
            val work = FloatArray(TAPS - 1 + frames)
            val firstPhase = phase
            for (c in 0 until channels) {
                System.arraycopy(history[c], 0, work, 0, TAPS - 1)
                for (f in 0 until frames) work[TAPS - 1 + f] = input[f * channels + c]
                var o = 0
                var f = firstPhase
                while (f < frames) {
                    // Output aligned with input frame f: the filter's centre sits on it.
                    val end = TAPS - 1 + f
                    var acc = COEFFS[CENTER].toDouble() * work[end - CENTER]
                    // Half-band: only odd offsets from the centre are non-zero.
                    var k = 1
                    while (k <= CENTER) {
                        acc += COEFFS[CENTER + k] * (work[end - CENTER - k] + work[end - CENTER + k].toDouble())
                        k += 2
                    }
                    output[o * channels + c] = acc.toFloat()
                    o++
                    f += 2
                }
                outFrames = o
                System.arraycopy(work, frames, history[c], 0, TAPS - 1)
            }
            phase = (firstPhase + outFrames * 2) - frames
            return outFrames
        }
    }

    companion object {
        /** Filter length: odd, and 4k+3 so the half-band's odd taps land symmetrically. */
        const val TAPS = 127
        private const val CENTER = (TAPS - 1) / 2
        private const val KAISER_BETA = 12.0

        /** Windowed-sinc low-pass at a quarter of the input rate (the output's Nyquist), unity DC gain. */
        internal val COEFFS: DoubleArray = DoubleArray(TAPS) { i ->
            val m = i - CENTER
            val sinc = if (m == 0) 0.5 else sin(PI * m / 2) / (PI * m)
            val x = 2.0 * i / (TAPS - 1) - 1.0
            sinc * besselI0(KAISER_BETA * sqrt(1 - x * x)) / besselI0(KAISER_BETA)
        }.let { h -> val sum = h.sum(); DoubleArray(h.size) { h[it] / sum } }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (true) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                if (term < sum * 1e-17 || k > 200) return sum
                k++
            }
        }

        /** The frequency response magnitude at [freq] (as a fraction of the input rate), for tests. */
        internal fun response(freq: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in COEFFS.indices) {
                val w = 2 * PI * freq * (i - CENTER)
                re += COEFFS[i] * kotlin.math.cos(w)
                im -= COEFFS[i] * sin(w)
            }
            return sqrt(re * re + im * im)
        }
    }
}
