package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WowFlutterProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        assertEquals(false, WowFlutterProcessor.Config(enabled = false, intensity = 0.5f).isActive())
        assertEquals(false, WowFlutterProcessor.Config(enabled = true, intensity = 0f).isActive())
    }

    @Test
    fun `depth samples are zero at zero intensity`() {
        assertEquals(0f, WowFlutterProcessor.wowDepthSamples(0f, 44_100))
        assertEquals(0f, WowFlutterProcessor.flutterDepthSamples(0f, 44_100))
    }

    @Test
    fun `depth samples grow with intensity`() {
        val low = WowFlutterProcessor.wowDepthSamples(0.2f, 44_100)
        val high = WowFlutterProcessor.wowDepthSamples(1f, 44_100)
        assertTrue(high > low)
    }

    @Test
    fun `peak modulation stays a small fraction of the base delay`() {
        // The base delay is 128 samples (see WowFlutterProcessor's private
        // baseDelaySamples) - the ring buffer only avoids over/underrun if
        // wow + flutter's combined peak deviation stays well under that,
        // independent of the processor's own internal constant.
        val total = WowFlutterProcessor.wowDepthSamples(1f, 44_100) +
            WowFlutterProcessor.flutterDepthSamples(1f, 44_100)
        assertTrue(total < 64f, "combined peak modulation ($total samples) should leave generous headroom")
    }

    private fun wowFlutter(config: WowFlutterProcessor.Config) = WowFlutterProcessor().apply {
        configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
        flush()
        setConfig(config)
    }

    @Test
    fun `disabled processor passes float audio through unchanged`() {
        val p = wowFlutter(WowFlutterProcessor.Config(enabled = false, intensity = 1f))
        val frames = 100
        val input = ByteBuffer.allocateDirect(frames * 2 * 4)
        repeat(frames) { i -> input.putFloat(i * 0.001f); input.putFloat(-i * 0.001f) }
        input.flip()
        val expected = input.duplicate().order(input.order())

        p.queueInput(input)

        val out = p.output
        assertEquals(expected.remaining(), out.remaining())
        while (expected.hasRemaining()) {
            assertEquals(expected.float, out.float, 0.0001f)
        }
    }

    @Test
    fun `output frame count matches input frame count when active`() {
        // A wobble, not a resample: media3's chain expects 1:1 unless a
        // processor explicitly changes the sample rate, which this one does not.
        val p = wowFlutter(WowFlutterProcessor.Config(enabled = true, intensity = 1f))
        val frames = 5_000
        val input = ByteBuffer.allocateDirect(frames * 2 * 4)
        repeat(frames * 2) { input.putFloat(0.1f) }
        input.flip()

        p.queueInput(input)

        assertEquals(frames * 2 * 4, p.output.remaining())
    }

    @Test
    fun `active output stays bounded and finite`() {
        val p = wowFlutter(WowFlutterProcessor.Config(enabled = true, intensity = 1f))
        val frames = 8_000
        val input = ByteBuffer.allocateDirect(frames * 2 * 4)
        repeat(frames) { i ->
            val x = kotlin.math.sin(i * 0.05).toFloat() * 0.8f
            input.putFloat(x); input.putFloat(x)
        }
        input.flip()

        p.queueInput(input)

        val out = p.output
        while (out.hasRemaining()) {
            val v = out.float
            assertTrue(v.isFinite() && abs(v) <= 1f, "sample out of range: $v")
        }
    }
}
