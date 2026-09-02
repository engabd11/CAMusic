package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OldRadioProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        assertEquals(false, OldRadioProcessor.Config(enabled = false, intensity = 0.5f).isActive())
        assertEquals(false, OldRadioProcessor.Config(enabled = true, intensity = 0f).isActive())
    }

    // ── the band ──────────────────────────────────────────────────────────

    @Test
    fun `high-pass and low-pass corners sweep with intensity`() {
        assertEquals(250f, OldRadioProcessor.highPassFreq(0f))
        assertEquals(500f, OldRadioProcessor.highPassFreq(1f))
        assertEquals(5_000f, OldRadioProcessor.lowPassFreq(0f))
        assertEquals(2_500f, OldRadioProcessor.lowPassFreq(1f))
    }

    @Test
    fun `the band at full intensity cuts well below 200 Hz and above 6 kHz`() {
        val rate = 44_100
        val hp = Biquad().apply { setHighPass(rate, OldRadioProcessor.highPassFreq(1f), q = 0.707f) }
        val lp = Biquad().apply { setLowPass(rate, OldRadioProcessor.lowPassFreq(1f), q = 0.707f) }

        fun bandDb(freq: Float) = hp.magnitudeDb(rate, freq) + lp.magnitudeDb(rate, freq)

        assertTrue(bandDb(100f) < -12f, "100 Hz should be well outside the telephone band")
        assertTrue(bandDb(8_000f) < -12f, "8 kHz should be well outside the telephone band")
        // Inside the band, near unity - this is meant to colour, not silence.
        assertEquals(0f, bandDb(1_000f), 1.5f)
    }

    @Test
    fun `saturation drive is unity at zero intensity`() {
        assertEquals(1f, OldRadioProcessor.saturationDrive(0f))
        assertTrue(OldRadioProcessor.saturationDrive(1f) > 1f)
    }

    @Test
    fun `saturate at unity drive is identity`() {
        assertEquals(0.4f, OldRadioProcessor.saturate(0.4f, 1f))
    }

    @Test
    fun `saturate at high drive stays within full scale`() {
        val drive = OldRadioProcessor.saturationDrive(1f)
        assertEquals(1f, OldRadioProcessor.saturate(1f, drive), 0.001f)
        assertEquals(-1f, OldRadioProcessor.saturate(-1f, drive), 0.001f)
    }

    // ── static and warble ────────────────────────────────────────────────

    @Test
    fun `static burst amplitude is capped and silent at zero intensity`() {
        assertEquals(0f, OldRadioProcessor.staticBurstAmplitude(0f))
        assertEquals(0.10f, OldRadioProcessor.staticBurstAmplitude(1f))
    }

    @Test
    fun `static interval gets more frequent with intensity`() {
        val sparse = OldRadioProcessor.staticInterval(0f, 44_100)
        val frequent = OldRadioProcessor.staticInterval(1f, 44_100)
        assertTrue(frequent < sparse)
    }

    @Test
    fun `hiss floor is quiet and warble depth is shallow`() {
        assertTrue(OldRadioProcessor.hissFloorAmplitude(1f) <= 0.02f)
        assertTrue(OldRadioProcessor.warbleDepth(1f) <= 0.03f)
    }

    // ── the whole processor ──────────────────────────────────────────────

    @Test
    fun `encode and decode round-trips`() {
        val cfg = OldRadioProcessor.Config(enabled = true, intensity = 0.6f)
        assertEquals(cfg, OldRadioProcessor.decode(OldRadioProcessor.encode(cfg)))
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, OldRadioProcessor.decode("not json"))
    }

    @Test
    fun `a disabled processor passes float audio through unchanged`() {
        val p = OldRadioProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
            flush()
            setConfig(OldRadioProcessor.Config(enabled = false, intensity = 1f))
        }
        val frames = 200
        val input = ByteBuffer.allocateDirect(frames * 2 * 4)
        repeat(frames) { i -> input.putFloat(i * 0.001f); input.putFloat(-i * 0.001f) }
        input.flip()
        val expected = input.duplicate().order(input.order())

        p.queueInput(input)

        val out = p.output
        assertEquals(expected.remaining(), out.remaining())
        while (expected.hasRemaining()) assertEquals(expected.float, out.float, 0.0001f)
    }

    @Test
    fun `output frame count matches input frame count when active`() {
        val p = OldRadioProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(OldRadioProcessor.Config(enabled = true, intensity = 0.7f))
        }
        val frames = 4_000
        val input = ByteBuffer.allocateDirect(frames * 2 * 2)
        repeat(frames * 2) { input.putShort(1000) }
        input.flip()

        p.queueInput(input)

        assertEquals(frames * 2 * 2, p.output.remaining())
    }

    @Test
    fun `reset preserves the active config instead of switching the mode off`() {
        val cfg = OldRadioProcessor.Config(enabled = true, intensity = 0.8f)
        val p = OldRadioProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(cfg)
        }
        p.queueInput(ByteBuffer.allocateDirect(0))
        assertEquals(cfg, p.currentConfigSafe())

        p.reset()

        assertEquals(cfg, p.currentConfigSafe())
    }
}
