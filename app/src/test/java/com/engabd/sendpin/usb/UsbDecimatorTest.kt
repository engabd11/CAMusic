package com.engabd.sendpin.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class UsbDecimatorTest {

    private fun db(x: Double) = 20 * log10(x)

    @Test
    fun `unity at DC and flat across the audible band at 192 kHz`() {
        assertEquals(0.0, db(UsbDecimator.response(0.0)), 1e-6)
        // 20 kHz at a 192 kHz input is 0.104 of the rate.
        for (f in listOf(1_000.0, 10_000.0, 20_000.0, 30_000.0)) {
            val gain = db(UsbDecimator.response(f / 192_000))
            assertEquals("gain at $f Hz", 0.0, gain, 0.001)
        }
    }

    @Test
    fun `what would fold back into the audible band is gone`() {
        // After 2:1, anything above 76 kHz at the 192 kHz input folds below 20 kHz.
        var worst = 0.0
        var f = 76_000.0
        while (f <= 96_000.0) {
            worst = maxOf(worst, UsbDecimator.response(f / 192_000))
            f += 250.0
        }
        assertTrue("worst alias ${db(worst)} dB", db(worst) < -120)
    }

    @Test
    fun `half-band - every other coefficient is zero`() {
        val c = UsbDecimator.COEFFS
        val centre = c.size / 2
        for (k in 2..centre step 2) assertEquals(0.0, c[centre + k], 1e-15)
    }

    private fun sine(frames: Int, hz: Double, rate: Int, channels: Int) =
        FloatArray(frames * channels) { i -> (0.5 * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }

    @Test
    fun `a 1 kHz tone keeps its level and half the frames`() {
        val d = UsbDecimator(2, 2)
        val input = sine(19_200, 1_000.0, 192_000, 2)
        val out = FloatArray(9_601 * 2)
        val n = d.process(input, 19_200, out)
        assertEquals(9_600, n)
        // Past the filter's start-up, the peak is the input's 0.5.
        var peak = 0f
        for (i in 2_000 * 2 until n * 2) peak = maxOf(peak, abs(out[i]))
        assertEquals(0.5f, peak, 0.001f)
    }

    @Test
    fun `chunks give exactly what one pass gives`() {
        val input = sine(4_001, 3_000.0, 192_000, 2)
        val whole = UsbDecimator(2, 2).let { d -> FloatArray(2_002 * 2).also { d.process(input, 4_001, it) } }
        val d = UsbDecimator(2, 2)
        val out = FloatArray(2_002 * 2)
        var written = 0
        var at = 0
        for (chunk in listOf(1, 999, 1_500, 1_501)) {
            val part = input.copyOfRange(at * 2, (at + chunk) * 2)
            val tmp = FloatArray((chunk / 2 + 1) * 2)
            val n = d.process(part, chunk, tmp)
            System.arraycopy(tmp, 0, out, written * 2, n * 2)
            written += n
            at += chunk
        }
        assertEquals(2_001, written)
        assertArrayEquals(whole.copyOf(written * 2), out.copyOf(written * 2), 1e-7f)
    }

    @Test
    fun `4 to 1 is two stages`() {
        val d = UsbDecimator(4, 1)
        val out = FloatArray(1_000)
        assertEquals(960, d.process(sine(3_840, 1_000.0, 192_000, 1), 3_840, out))
    }
}
