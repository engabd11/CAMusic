package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDspTest {

    private val rate = 48_000

    private fun dsp(config: LocalDsp.Config): LocalDsp = LocalDsp().apply {
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush()
        setConfig(config)
    }

    /** Stereo 16-bit interleaved sine, as ExoPlayer would hand it over. */
    private fun sineBuffer(freq: Float, frames: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (sin(2.0 * PI * freq * i / rate) * 0.5 * 32_767).toInt().toShort()
            buf.putShort(v)
            buf.putShort(v)
        }
        return buf.apply { flip() }
    }

    /** Peak level of the left channel of an output buffer, 0..1. */
    private fun peakLeft(out: ByteBuffer, skipFrames: Int): Float {
        val copy = out.duplicate().order(out.order())
        var peak = 0f
        var frame = 0
        while (copy.remaining() >= 4) {
            val l = copy.short / 32_768f
            copy.short  // right
            if (frame >= skipFrames) peak = maxOf(peak, abs(l))
            frame++
        }
        return peak
    }

    /** Push [frames] of a sine through and return the settled peak. */
    private fun run(processor: LocalDsp, freq: Float, frames: Int = 24_000): Float {
        processor.queueInput(sineBuffer(freq, frames))
        return peakLeft(processor.output, skipFrames = frames / 3)
    }

    // ── transparency ──────────────────────────────────────────────────────

    @Test
    fun `a disabled equaliser passes audio through untouched`() {
        val p = dsp(LocalDsp.Config(enabled = false))

        assertTrue(p.currentConfig().isTransparent())
        assertEquals(0.5f, run(p, 1_000f), 0.005f)
    }

    @Test
    fun `an enabled equaliser with every band flat is still transparent`() {
        // Switching the feature on must not change the sound until something is
        // moved. A cascade of ten "flat" biquads would be near-transparent but not
        // exactly so, which is why they are dropped rather than multiplied by one.
        val p = dsp(LocalDsp.Config(enabled = true))

        assertTrue(p.currentConfig().isTransparent())
        assertEquals(0.5f, run(p, 1_000f), 0.005f)
    }

    // ── the bands ─────────────────────────────────────────────────────────

    @Test
    fun `a boosted band lifts its own frequency`() {
        val p = dsp(
            LocalDsp.Config(
                enabled = true,
                autoPreamp = false,
                bands = listOf(LocalDsp.Band(frequency = 1_000f, gainDb = 6f, q = 1f)),
            ),
        )

        // 0.5 amplitude plus 6 dB is 1.0 - right at the ceiling, which is exactly
        // the case the preamp exists to prevent, so this is run with it disabled.
        assertTrue(run(p, 1_000f) > 0.9f)
    }

    @Test
    fun `a cut band lowers its own frequency and leaves the others`() {
        val p = dsp(
            LocalDsp.Config(
                enabled = true,
                autoPreamp = false,
                bands = listOf(LocalDsp.Band(frequency = 1_000f, gainDb = -12f, q = 1f)),
            ),
        )

        assertTrue("1 kHz should be cut", run(p, 1_000f) < 0.2f)

        val far = dsp(
            LocalDsp.Config(
                enabled = true,
                autoPreamp = false,
                bands = listOf(LocalDsp.Band(frequency = 1_000f, gainDb = -12f, q = 2f)),
            ),
        )
        assertEquals("60 Hz should be untouched", 0.5f, run(far, 60f), 0.03f)
    }

    // ── headroom ──────────────────────────────────────────────────────────

    @Test
    fun `the automatic preamp keeps a boosted curve under the ceiling`() {
        val boosted = LocalDsp.Config(
            enabled = true,
            autoPreamp = true,
            bands = listOf(
                LocalDsp.Band(frequency = 500f, gainDb = 8f),
                LocalDsp.Band(frequency = 1_000f, gainDb = 8f),
                LocalDsp.Band(frequency = 2_000f, gainDb = 8f),
            ),
        )
        val p = dsp(boosted)

        assertTrue("preamp should be negative", boosted.effectivePreampDb() < -8f)
        // The whole point: a hot signal through a boosted curve does not slam into
        // the ceiling, which would be heard as distortion and blamed on the file.
        assertTrue(run(p, 1_000f) < 0.99f)
    }

    @Test
    fun `a curve with only cuts needs no preamp`() {
        val cuts = LocalDsp.Config(
            enabled = true,
            bands = listOf(LocalDsp.Band(frequency = 1_000f, gainDb = -6f)),
        )

        assertEquals(0f, cuts.effectivePreampDb(), 1e-6f)
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    @Test
    fun `a disabled band is left out of the cascade`() {
        val config = LocalDsp.Config(
            enabled = true,
            bands = listOf(
                LocalDsp.Band(frequency = 1_000f, gainDb = 12f, enabled = false),
            ),
        )

        assertTrue(config.activeBands().isEmpty())
        assertTrue(config.isTransparent())
    }

    @Test
    fun `a pass filter counts as active even at zero gain`() {
        // A high-pass at 0 dB is still a high-pass. Treating gain as the only test
        // of "is this band doing anything" would silently drop it.
        val config = LocalDsp.Config(
            enabled = true,
            bands = listOf(
                LocalDsp.Band(type = LocalDsp.Band.Type.HIGH_PASS, frequency = 80f, gainDb = 0f),
            ),
        )

        assertFalse(config.isTransparent())
    }

    @Test
    fun `the default curve is ten flat bands`() {
        val bands = LocalDsp.Config.defaultBands()

        assertEquals(10, bands.size)
        assertTrue(bands.all { Biquad.isFlat(it.gainDb) })
        // Ascending, and spanning the audible range.
        assertEquals(bands.sortedBy { it.frequency }, bands)
        assertTrue(bands.first().frequency <= 32f)
        assertTrue(bands.last().frequency >= 16_000f)
    }

    @Test
    fun `an odd trailing byte is passed on rather than swallowed`() {
        val p = dsp(
            LocalDsp.Config(
                enabled = true,
                bands = listOf(LocalDsp.Band(frequency = 1_000f, gainDb = 6f)),
            ),
        )
        // Five bytes: two whole samples and a stray one. Dropping it would slide
        // every later sample by one byte and swap the channels for good.
        val input = ByteBuffer.allocateDirect(5).order(ByteOrder.nativeOrder())
        repeat(5) { input.put(it.toByte()) }
        input.flip()

        p.queueInput(input)

        assertEquals(5, p.output.remaining())
    }
}
