package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Programme loudness to ITU-R BS.1770-4 and EBU R128: integrated loudness in LUFS and
 * true peak in dBTP.
 *
 *  - **K-weighting**: a high shelf (the head's acoustic effect) then a 38 Hz high-pass,
 *    both worked out from the standard's analogue prototypes for the actual sample
 *    rate, so 44.1 and 48 kHz are measured by the same maths rather than by tables
 *    that only hold at 48.
 *  - **Blocks**: 400 ms with 75% overlap, so a new block every 100 ms. Channels are
 *    summed by power (surrounds +1.5 dB, the LFE left out).
 *  - **Gating**: blocks quieter than -70 LUFS are dropped, then blocks more than 10 LU
 *    under the average of what remains, and the integrated loudness is the mean power
 *    of the survivors.
 *  - **True peak**: 4x oversampled with a windowed-sinc interpolator, so a peak that
 *    falls between two samples (and clips a DAC or a lossy encoder) is found.
 *
 * Pure Kotlin so it can be unit tested against generated signals. Feed it interleaved
 * or per-channel samples as they are decoded and ask [result] at the end; nothing is
 * allocated per sample.
 *
 * @param channels the stream's channel count. Mono is measured as is, 2 as L/R, and 6
 *   as the usual L R C LFE Ls Rs. Anything else weights every channel equally.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {

    private val weights = DoubleArray(channels) { ch ->
        if (channels == 6) when (ch) { 3 -> 0.0; 4, 5 -> 1.41; else -> 1.0 } else 1.0
    }

    // Each channel's two filter stages, as direct-form-II transposed state.
    private val shelf = Coefficients.shelf(sampleRate)
    private val highPass = Coefficients.highPass(sampleRate)
    private val s1a = DoubleArray(channels)
    private val s1b = DoubleArray(channels)
    private val s2a = DoubleArray(channels)
    private val s2b = DoubleArray(channels)

    // 100 ms sub-blocks: a gating block is four of them.
    private val subLength = (sampleRate * SUB_BLOCK_S).roundToInt().coerceAtLeast(1)
    private var subFill = 0
    private var subEnergy = 0.0
    private val recent = DoubleArray(SUBS_PER_BLOCK)
    private var subsSeen = 0

    private var blocks = DoubleArray(1024)
    private var blockCount = 0

    // True peak: a ring of the last RING samples per channel, interpolated half a
    // window behind the newest.
    private val ring = Array(channels) { DoubleArray(RING) }
    private var ringPos = 0
    private var ringFilled = 0
    private var peak = 0.0

    /** Frame being assembled by [push]. */
    private var framePos = 0
    private val frame = DoubleArray(channels)

    /** Feed one sample for the next channel; every [channels] calls complete a frame. */
    fun push(sample: Float) {
        frame[framePos++] = sample.toDouble()
        if (framePos == channels) {
            framePos = 0
            pushFrame()
        }
    }

    /** Feed [count] interleaved samples (a whole number of frames). */
    fun pushInterleaved(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) push(samples[i])
    }

    private fun pushFrame() {
        var energy = 0.0
        for (c in 0 until channels) {
            val x = frame[c]

            // K-weighting, for the loudness.
            val y1 = shelf.b0 * x + s1a[c]
            s1a[c] = shelf.b1 * x - shelf.a1 * y1 + s1b[c]
            s1b[c] = shelf.b2 * x - shelf.a2 * y1
            val y2 = highPass.b0 * y1 + s2a[c]
            s2a[c] = highPass.b1 * y1 - highPass.a1 * y2 + s2b[c]
            s2b[c] = highPass.b2 * y1 - highPass.a2 * y2
            energy += weights[c] * y2 * y2

            // The raw signal, for the peak.
            ring[c][ringPos] = x
        }
        subEnergy += energy
        if (++subFill == subLength) endSubBlock()

        ringPos = (ringPos + 1) and RING_MASK
        if (ringFilled < RING) ringFilled++
        if (ringFilled >= TAPS) scanPeak()
    }

    private fun endSubBlock() {
        recent[subsSeen % SUBS_PER_BLOCK] = subEnergy / subLength
        subsSeen++
        subEnergy = 0.0
        subFill = 0
        if (subsSeen >= SUBS_PER_BLOCK) {
            var sum = 0.0
            for (e in recent) sum += e
            if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
            blocks[blockCount++] = sum / SUBS_PER_BLOCK
        }
    }

    /**
     * Interpolate between the two samples in the middle of the ring and keep the
     * largest magnitude. The samples themselves count too; they are phase zero.
     */
    private fun scanPeak() {
        // The newest sample is at ringPos (just written), so the window of TAPS
        // samples ends there. The pair being interpolated is the middle two.
        for (c in 0 until channels) {
            val r = ring[c]
            // x[n + j] for j in -(HALF-1)..HALF, with n the older of the middle pair.
            val base = ringPos - TAPS + 1
            val centre = r[(base + HALF - 1) and RING_MASK]
            val a = abs(centre)
            if (a > peak) peak = a
            for (phase in 1 until OVERSAMPLE) {
                val h = phases[phase]
                var acc = 0.0
                for (k in 0 until TAPS) acc += h[k] * r[(base + k) and RING_MASK]
                val m = abs(acc)
                if (m > peak) peak = m
            }
        }
    }

    /**
     * What has been measured so far, or null when there is nothing to report: no
     * complete 400 ms block, or nothing above the absolute gate (silence).
     */
    fun result(): Result? {
        val integrated = integratedLufs() ?: return null
        val truePeakDb = if (peak > 0.0) 20.0 * log10(peak) else return null
        return Result(integrated.toFloat(), truePeakDb.toFloat())
    }

    /** Integrated loudness, LUFS, or null when every block was gated out. */
    fun integratedLufs(): Double? {
        val absThreshold = energyOf(ABSOLUTE_GATE_LUFS)
        var sum = 0.0
        var n = 0
        for (i in 0 until blockCount) {
            if (blocks[i] > absThreshold) { sum += blocks[i]; n++ }
        }
        if (n == 0) return null

        val relThreshold = energyOf(lufsOf(sum / n) + RELATIVE_GATE_LU)
        sum = 0.0
        n = 0
        for (i in 0 until blockCount) {
            if (blocks[i] > absThreshold && blocks[i] > relThreshold) { sum += blocks[i]; n++ }
        }
        if (n == 0) return null
        return lufsOf(sum / n)
    }

    /** The largest true peak seen, dBTP, or null for silence. */
    fun truePeakDb(): Double? = if (peak > 0.0) 20.0 * log10(peak) else null

    data class Result(val integratedLufs: Float, val truePeakDb: Float)

    private data class Coefficients(
        val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double,
    ) {
        companion object {
            /** Stage 1, the high shelf. Constants are the standard's own prototype. */
            fun shelf(fs: Int): Coefficients {
                val f0 = 1681.974450955533
                val gainDb = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / fs)
                val vh = 10.0.pow(gainDb / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1.0 + k / q + k * k
                return Coefficients(
                    b0 = (vh + vb * k / q + k * k) / a0,
                    b1 = 2.0 * (k * k - vh) / a0,
                    b2 = (vh - vb * k / q + k * k) / a0,
                    a1 = 2.0 * (k * k - 1.0) / a0,
                    a2 = (1.0 - k / q + k * k) / a0,
                )
            }

            /** Stage 2, the revised low-frequency B-curve high-pass. */
            fun highPass(fs: Int): Coefficients {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / fs)
                val a0 = 1.0 + k / q + k * k
                return Coefficients(
                    b0 = 1.0, b1 = -2.0, b2 = 1.0,
                    a1 = 2.0 * (k * k - 1.0) / a0,
                    a2 = (1.0 - k / q + k * k) / a0,
                )
            }
        }
    }

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0

        private const val SUB_BLOCK_S = 0.1
        private const val SUBS_PER_BLOCK = 4

        private const val OVERSAMPLE = 4

        /** Taps per interpolation phase: 16 reaches within a few hundredths of a dB. */
        private const val TAPS = 16
        private const val HALF = TAPS / 2
        private const val RING = 16
        private const val RING_MASK = RING - 1

        /** Kaiser window shape; wide enough to keep the passband flat to the top. */
        private const val KAISER_BETA = 8.0

        /**
         * The interpolator, one row per phase. Phase p is the point p/4 of the way
         * from the older to the newer of the middle pair; row k weighs the k-th
         * sample of the window.
         */
        private val phases: Array<DoubleArray> = Array(OVERSAMPLE) { p ->
            val d = p.toDouble() / OVERSAMPLE
            DoubleArray(TAPS) { k ->
                val t = d - (k - (HALF - 1))
                sinc(t) * kaiser(t / HALF)
            }
        }

        private fun sinc(x: Double): Double =
            if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double {
            if (abs(x) >= 1.0) return 0.0
            return bessel0(KAISER_BETA * sqrt(1.0 - x * x)) / bessel0(KAISER_BETA)
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }

        /** LUFS of a mean-square energy, per BS.1770: -0.691 + 10 log10(energy). */
        fun lufsOf(energy: Double): Double = -0.691 + 10.0 * log10(max(energy, 1e-30))

        fun energyOf(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)
    }
}
