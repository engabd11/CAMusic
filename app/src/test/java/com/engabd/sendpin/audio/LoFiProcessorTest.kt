package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class LoFiProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        val cfg = LoFiProcessor.Config(enabled = false, intensity = 0.5f)
        assertEquals(false, cfg.isActive())
    }

    @Test
    fun `bit depth maps from 16 to 6 across intensity range`() {
        assertEquals(16, LoFiProcessor.bitDepth(0f))
        assertEquals(16, LoFiProcessor.bitDepth(0.005f))
        // At 0.5 intensity, should be around 12 bits
        val mid = LoFiProcessor.bitDepth(0.5f)
        assertTrue(mid in 10..14, "Mid intensity should give ~12 bits, got $mid")
        // At full intensity, should be minimum (6 bits)
        assertEquals(6, LoFiProcessor.bitDepth(1.0f))
    }

    @Test
    fun `quantise at 16 bits is identity`() {
        val x = 0.5f
        assertEquals(x, LoFiProcessor.quantise(x, 16))
    }

    @Test
    fun `quantise at low bits produces discrete steps`() {
        val bits = 4  // 15 levels
        val levels = (1 shl bits) - 1
        // 0.0 should quantise to 0
        assertEquals(0f, LoFiProcessor.quantise(0f, bits))
        // 1.0 should quantise to 1.0 (max level)
        assertEquals(1f, LoFiProcessor.quantise(1f, bits))
        // A value between steps should snap to the nearest level
        val step = 1f / levels
        val input = step * 1.5f  // halfway between levels 1 and 2
        val result = LoFiProcessor.quantise(input, bits)
        // Should be either level 1 or level 2, not between
        val level1 = step
        val level2 = 2f * step
        assertTrue(abs(result - level1) < 0.001f || abs(result - level2) < 0.001f,
            "Quantised value should be on a discrete step, got $result")
    }

    @Test
    fun `decimation factor increases with intensity`() {
        assertEquals(1, LoFiProcessor.decimationFactor(0f))
        assertTrue(LoFiProcessor.decimationFactor(1.0f) > 1)
    }

    @Test
    fun `saturation drive is unity at zero intensity`() {
        assertEquals(1f, LoFiProcessor.saturationDrive(0f))
        assertEquals(3f, LoFiProcessor.saturationDrive(1f))
    }

    @Test
    fun `saturate at unity drive is identity`() {
        val x = 0.5f
        assertEquals(x, LoFiProcessor.saturate(x, 1f))
    }

    @Test
    fun `saturate at high drive compresses toward unity`() {
        val drive = 3f
        // A full-scale input should stay at unity
        assertEquals(1f, LoFiProcessor.saturate(1f, drive), 0.001f)
        // A mid-level input should be slightly louder (saturation adds energy)
        val result = LoFiProcessor.saturate(0.5f, drive)
        assertTrue(result > 0.5f, "Saturation should boost mid-level signals")
    }

    @Test
    fun `low-pass frequency is near-inaudible at zero intensity`() {
        assertEquals(20_000f, LoFiProcessor.lowPassFreqHz(0f))
    }

    @Test
    fun `low-pass frequency decreases with intensity`() {
        val low = LoFiProcessor.lowPassFreqHz(0.2f)
        val high = LoFiProcessor.lowPassFreqHz(0.8f)
        assertTrue(high < low, "Higher intensity should mean a lower low-pass corner")
    }

    @Test
    fun `high-pass is bypassed at zero intensity`() {
        assertEquals(0f, LoFiProcessor.highPassFreq(0f))
    }

    @Test
    fun `high-pass frequency rises with intensity, within lo-fi range`() {
        assertTrue(LoFiProcessor.highPassFreq(1f) in 100f..150f)
        assertTrue(LoFiProcessor.highPassFreq(0.5f) < LoFiProcessor.highPassFreq(1f))
    }

    @Test
    fun `lo-fi crackle is quieter than dedicated vinyl crackle`() {
        val i = 0.5f
        assertTrue(LoFiProcessor.loFiCrackleAmplitude(i) < VinylNoiseProcessor.crackleAmplitude(i))
    }

    @Test
    fun `lo-fi hiss floor is quieter than dedicated vinyl hiss`() {
        for (i in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(LoFiProcessor.loFiHissAmplitude(i) < VinylNoiseProcessor.hissAmplitude(i))
        }
    }

    @Test
    fun `encode and decode round-trips`() {
        val cfg = LoFiProcessor.Config(enabled = true, intensity = 0.7f, shareVinylCrackle = true)
        val encoded = LoFiProcessor.encode(cfg)
        val decoded = LoFiProcessor.decode(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, LoFiProcessor.decode("not json"))
    }

    @Test
    fun `the crackle interval borrowed from VinylNoiseProcessor scales with sample rate`() {
        // LoFiProcessor's internal crackle (used when VinylNoiseProcessor isn't
        // sharing) reuses VinylNoiseProcessor.crackleInterval directly, so the
        // sample-rate scaling fix covers this path too. Same invariant as
        // VinylNoiseProcessorTest: the interval in milliseconds should be
        // roughly the same at 44.1 kHz and 96 kHz, not twice as dense at the
        // higher rate.
        for (intensity in listOf(0f, 0.5f, 1f)) {
            val at44100 = VinylNoiseProcessor.crackleInterval(intensity, 44_100)
            val at96000 = VinylNoiseProcessor.crackleInterval(intensity, 96_000)
            val msAt44100 = at44100 * 1000.0 / 44_100
            val msAt96000 = at96000 * 1000.0 / 96_000
            assertTrue(abs(msAt44100 - msAt96000) < 1.0)
        }
    }

    @Test
    fun `output frame count matches input frame count at full intensity`() {
        // High-pass, low-pass and the noise texture are all new wiring in this
        // processor - this guards against any of it accidentally not being 1:1.
        val p = LoFiProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(LoFiProcessor.Config(enabled = true, intensity = 1f, shareVinylCrackle = false))
        }
        val frames = 1000
        val input = ByteBuffer.allocateDirect(frames * 2 * 2)
        repeat(frames * 2) { input.putShort(0) }
        input.flip()
        p.queueInput(input)
        assertEquals(frames * 2 * 2, p.output.remaining())
    }

    @Test
    fun `reset preserves the active config instead of switching lo-fi off`() {
        val cfg = LoFiProcessor.Config(enabled = true, intensity = 0.6f, shareVinylCrackle = true)
        val p = LoFiProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(cfg)
        }
        // Drain `pending` into `active`, the same way a track change would.
        p.queueInput(ByteBuffer.allocateDirect(0))
        assertEquals(cfg, p.currentConfigSafe())

        p.reset()

        // Before the fix, onReset() did `active = Config()` - enabled = false -
        // switching lo-fi off with no settings change left to ever turn it back
        // on, since AppSettings.pref()'s deduped Flow will not re-emit an
        // unchanged value.
        assertEquals(cfg, p.currentConfigSafe())
    }
}