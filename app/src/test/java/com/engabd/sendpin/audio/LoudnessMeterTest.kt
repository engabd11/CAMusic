package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BS.1770-4 meter against signals whose loudness is known from the standard, in
 * the manner of EBU Tech 3341 / 3342: a stereo 1 kHz sine at -23 dBFS reads -23.0 LUFS,
 * and the gates leave a track's loud part alone however much quiet surrounds it.
 *
 * Generated in the test, at both rates the library is mostly in.
 */
class LoudnessMeterTest {

    private val rates = listOf(44_100, 48_000)

    /** Stereo sine, [peakDb] dBFS peak on both channels, for [seconds]. */
    private fun feedSine(
        meter: LoudnessMeter,
        rate: Int,
        peakDb: Double,
        seconds: Double,
        freq: Double = 1_000.0,
        startFrame: Long = 0,
    ): Long {
        val amp = 10.0.pow(peakDb / 20.0)
        val frames = (seconds * rate).toLong()
        for (i in 0 until frames) {
            val v = (amp * sin(2.0 * PI * freq * (startFrame + i) / rate)).toFloat()
            meter.push(v)
            meter.push(v)
        }
        return startFrame + frames
    }

    private fun feedSilence(meter: LoudnessMeter, rate: Int, seconds: Double) {
        repeat((seconds * rate).toInt() * 2) { meter.push(0f) }
    }

    // -- Tech 3341 case 1 and 2: level --------------------------------------

    @Test
    fun `a stereo 1 kHz sine at -23 dBFS reads -23 LUFS`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            feedSine(m, rate, -23.0, 20.0)
            assertEquals(-23.0, m.integratedLufs()!!, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `a stereo 1 kHz sine at -33 dBFS reads -33 LUFS`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            feedSine(m, rate, -33.0, 20.0)
            assertEquals(-33.0, m.integratedLufs()!!, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `a single channel reads 3 LU under the same level on two`() {
        val m = LoudnessMeter(48_000, 1)
        val amp = 10.0.pow(-23.0 / 20.0)
        for (i in 0 until 48_000 * 20) m.push((amp * sin(2.0 * PI * 1_000.0 * i / 48_000)).toFloat())
        assertEquals(-26.0, m.integratedLufs()!!, 0.1)
    }

    // -- Tech 3341 gating cases ---------------------------------------------

    @Test
    fun `the absolute gate drops everything under -70 LUFS`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            // About -75 LUFS: below the gate, so there is nothing to integrate.
            feedSine(m, rate, -75.0, 20.0)
            assertNull(m.integratedLufs(), "at $rate Hz")
            assertNull(m.result())
        }
    }

    @Test
    fun `silence around a tone does not pull the reading down`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            feedSilence(m, rate, 10.0)
            val next = feedSine(m, rate, -23.0, 20.0, startFrame = 0)
            assertTrue(next > 0)
            feedSilence(m, rate, 10.0)
            assertEquals(-23.0, m.integratedLufs()!!, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `the relative gate drops a section more than 10 LU under the programme`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            // 20 s at -23, 20 s at -45 (22 LU under: above the absolute gate, below
            // the relative one), 20 s at -23. Ungated, the quiet part would cost
            // about 1.8 LU; gated it must cost nothing.
            var at = feedSine(m, rate, -23.0, 20.0)
            at = feedSine(m, rate, -45.0, 20.0, startFrame = at)
            feedSine(m, rate, -23.0, 20.0, startFrame = at)
            assertEquals(-23.0, m.integratedLufs()!!, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `a quiet section within 10 LU of the programme is kept`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            // -23 then -29: only 6 LU apart, both stay. Mean power of equal times
            // is 10*log10((10^-2.3 + 10^-2.9)/2) = -25.0 LUFS.
            val at = feedSine(m, rate, -23.0, 20.0)
            feedSine(m, rate, -29.0, 20.0, startFrame = at)
            val expected = 10.0 * log10((10.0.pow(-2.3) + 10.0.pow(-2.9)) / 2.0)
            assertEquals(expected, m.integratedLufs()!!, 0.15, "at $rate Hz")
        }
    }

    @Test
    fun `a clip shorter than one 400 ms block has no reading`() {
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, -23.0, 0.3)
        assertNull(m.integratedLufs())
    }

    @Test
    fun `the lfe channel of a 5 point 1 stream is left out and the surrounds weigh more`() {
        val rate = 48_000
        val amp = 10.0.pow(-23.0 / 20.0)
        fun measure(hot: Int): Double {
            val m = LoudnessMeter(rate, 6)
            for (i in 0 until rate * 10) {
                val v = (amp * sin(2.0 * PI * 1_000.0 * i / rate)).toFloat()
                for (c in 0 until 6) m.push(if (c == hot) v else 0f)
            }
            return m.integratedLufs()!!
        }
        val front = measure(0)
        assertEquals(front + 1.5, measure(4), 0.1, "a surround counts +1.5 dB")
        assertNull(
            LoudnessMeter(rate, 6).also { m ->
                for (i in 0 until rate * 10) {
                    val v = (amp * sin(2.0 * PI * 1_000.0 * i / rate)).toFloat()
                    for (c in 0 until 6) m.push(if (c == 3) v else 0f)
                }
            }.integratedLufs(),
            "the LFE is not measured",
        )
    }

    // -- True peak ------------------------------------------------------------

    @Test
    fun `a sine a quarter of the sample rate at 45 degrees has a true peak above its samples`() {
        for (rate in rates) {
            // Every sample is +-0.9*sin(45deg) = 0.636 (-3.9 dBFS) but the wave itself
            // reaches 0.9 (-0.92 dBTP): the intersample peak a sample peak misses.
            val m = LoudnessMeter(rate, 2)
            val amp = 0.9
            for (i in 0 until rate * 3) {
                val v = (amp * sin(2.0 * PI * 0.25 * i + PI / 4.0)).toFloat()
                m.push(v)
                m.push(v)
            }
            val samplePeakDb = 20.0 * log10(amp * sin(PI / 4.0))
            val truePeak = m.truePeakDb()!!
            assertEquals(20.0 * log10(amp), truePeak, 0.1, "at $rate Hz")
            assertTrue(truePeak > samplePeakDb + 2.5, "true peak $truePeak vs sample peak $samplePeakDb")
        }
    }

    @Test
    fun `a full scale 1 kHz sine has a true peak of about 0 dBTP`() {
        for (rate in rates) {
            val m = LoudnessMeter(rate, 2)
            feedSine(m, rate, 0.0, 2.0)
            assertEquals(0.0, m.truePeakDb()!!, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `a signal that is 6 dB down peaks 6 dB down`() {
        val m = LoudnessMeter(44_100, 2)
        feedSine(m, 44_100, -6.0, 2.0, freq = 997.0)
        assertEquals(-6.0, m.truePeakDb()!!, 0.1)
    }

    @Test
    fun `the result carries loudness and true peak together`() {
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, -23.0, 10.0)
        val r = m.result()
        assertNotNull(r)
        assertEquals(-23.0f, r.integratedLufs, 0.1f)
        assertEquals(-23.0f, r.truePeakDb, 0.1f)
    }
}
