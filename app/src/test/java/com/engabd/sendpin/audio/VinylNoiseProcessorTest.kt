package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VinylNoiseProcessorTest {

    @Test
    fun `disabled processor is transparent`() {
        val p = VinylNoiseProcessor()
        val cfg = VinylNoiseProcessor.Config(enabled = false, intensity = 0.5f)
        assertEquals(false, cfg.isActive())
        assertEquals(false, VinylNoiseProcessor.Config(enabled = true, intensity = 0f).isActive())
    }

    @Test
    fun `crackle amplitude scales with intensity and is capped`() {
        assertEquals(0f, VinylNoiseProcessor.crackleAmplitude(0f))
        assertEquals(0.12f, VinylNoiseProcessor.crackleAmplitude(1f))
        // Above 1.0 intensity is clamped
        assertEquals(0.12f, VinylNoiseProcessor.crackleAmplitude(2f))
    }

    @Test
    fun `pop amplitude is louder than crackle at same intensity`() {
        val i = 0.5f
        assertTrue(VinylNoiseProcessor.popAmplitude(i) > VinylNoiseProcessor.crackleAmplitude(i))
    }

    @Test
    fun `rumble amplitude stays below 3 percent at max`() {
        assertEquals(0.03f, VinylNoiseProcessor.rumbleAmplitude(1f))
        assertTrue(VinylNoiseProcessor.rumbleAmplitude(0.5f) < 0.03f)
    }

    @Test
    fun `crackle interval decreases with intensity`() {
        val sr = 44_100
        val low = VinylNoiseProcessor.crackleInterval(0.1f, sr)
        val high = VinylNoiseProcessor.crackleInterval(1.0f, sr)
        assertTrue(high < low, "Higher intensity should mean more frequent crackle")
    }

    @Test
    fun `pop interval is much rarer than crackle`() {
        val sr = 44_100
        val i = 0.5f
        val crackleEvery = VinylNoiseProcessor.crackleInterval(i, sr)
        val popEvery = VinylNoiseProcessor.popInterval(i, sr)
        assertTrue(popEvery > crackleEvery * 10, "Pops should be at least 10x rarer than crackle")
    }

    @Test
    fun `low-pass coefficient is in valid range`() {
        val coeff = VinylNoiseProcessor.lowPassCoeff(50f, 44_100)
        assertTrue(coeff > 0f && coeff < 1f, "LP coefficient should be in (0, 1)")
        // Higher cutoff means higher coefficient (closer to passthrough)
        val higher = VinylNoiseProcessor.lowPassCoeff(500f, 44_100)
        assertTrue(higher > coeff)
    }

    @Test
    fun `encode and decode round-trips`() {
        val cfg = VinylNoiseProcessor.Config(enabled = true, intensity = 0.7f)
        val encoded = VinylNoiseProcessor.encode(cfg)
        val decoded = VinylNoiseProcessor.decode(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertEquals(null, VinylNoiseProcessor.decode("not json"))
    }

    @Test
    fun `crackle interval is roughly the same real-time density at 44100 and 96000 Hz`() {
        // onReset()'s bug fix aside, this pins the sample-rate-scaling fix
        // itself: before it, crackleInterval counted in raw samples and
        // ignored sampleRate entirely, so the interval in *milliseconds* at
        // 96 kHz was half of the 44.1 kHz one for the same intensity - a 96 kHz
        // file crackled twice as often in real time. Scaled correctly, the two
        // rates should land within a millisecond of each other.
        for (intensity in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val at44100 = VinylNoiseProcessor.crackleInterval(intensity, 44_100)
            val at96000 = VinylNoiseProcessor.crackleInterval(intensity, 96_000)
            val msAt44100 = at44100 * 1000.0 / 44_100
            val msAt96000 = at96000 * 1000.0 / 96_000
            assertTrue(
                kotlin.math.abs(msAt44100 - msAt96000) < 1.0,
                "intensity=$intensity: ${msAt44100}ms at 44.1kHz vs ${msAt96000}ms at 96kHz",
            )
        }
    }

    @Test
    fun `crackle interval at 44100 Hz is unchanged by the sample-rate scaling`() {
        // The fix must not change the character of existing 44.1 kHz material:
        // at the reference rate the scaling factor is exactly 1, so this should
        // match the pre-fix samples-only formula bit for bit.
        for (intensity in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            val base = (4000 - (intensity * 3700f).toInt()).coerceIn(100, 4000)
            assertEquals(base, VinylNoiseProcessor.crackleInterval(intensity, 44_100))
        }
    }

    @Test
    fun `hiss amplitude scales with intensity and is capped`() {
        assertEquals(0f, VinylNoiseProcessor.hissAmplitude(0f))
        assertEquals(0.015f, VinylNoiseProcessor.hissAmplitude(1f))
        assertTrue(VinylNoiseProcessor.hissAmplitude(0.5f) < VinylNoiseProcessor.hissAmplitude(1f))
    }

    @Test
    fun `hiss is quieter than rumble at the same intensity`() {
        // A texture between the clicks, not a second rumble.
        for (i in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(VinylNoiseProcessor.hissAmplitude(i) < VinylNoiseProcessor.rumbleAmplitude(i))
        }
    }

    @Test
    fun `left and right channels decorrelate at full intensity`() {
        // The whole point of per-channel crackle/pop state: two channels driven
        // from independent impulse trains should not produce identical output
        // even though both draw from the same session RNG.
        val p = VinylNoiseProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
            flush()
            setConfig(VinylNoiseProcessor.Config(enabled = true, intensity = 1f))
        }
        val frames = 20_000
        val input = ByteBuffer.allocateDirect(frames * 2 * 4)
        repeat(frames) { input.putFloat(0f); input.putFloat(0f) }
        input.flip()
        p.queueInput(input)
        val raw = p.output
        val out = raw.duplicate().order(raw.order())
        var identical = 0
        var total = 0
        while (out.remaining() >= 8) {
            val l = out.float
            val r = out.float
            if (l == r) identical++
            total++
        }
        assertTrue(total > 0)
        assertTrue(
            identical < total,
            "left and right should not be identical on every frame once decorrelated",
        )
    }

    @Test
    fun `output frame count matches input frame count`() {
        // A noise-adding processor must stay 1:1 - it is not a resampler.
        val p = VinylNoiseProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(VinylNoiseProcessor.Config(enabled = true, intensity = 0.8f))
        }
        val frames = 1000
        val input = ByteBuffer.allocateDirect(frames * 2 * 2)
        repeat(frames * 2) { input.putShort(0) }
        input.flip()
        p.queueInput(input)
        assertEquals(frames * 2 * 2, p.output.remaining())
    }

    @Test
    fun `reset preserves the active config instead of switching the mode off`() {
        val cfg = VinylNoiseProcessor.Config(enabled = true, intensity = 0.8f)
        val p = VinylNoiseProcessor().apply {
            configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            flush()
            setConfig(cfg)
        }
        // Drive one (empty) buffer through so `pending` is consumed into
        // `active` - queueInput drains `pending` before it looks at whether
        // there is anything to process, so this mirrors what a track change
        // does without needing real audio.
        p.queueInput(ByteBuffer.allocateDirect(0))
        assertEquals(cfg, p.currentConfigSafe())

        p.reset()

        // Before the fix, onReset() did `active = Config()` - enabled = false -
        // silently switching vinyl noise off with no settings change left to
        // ever turn it back on, since AppSettings.pref()'s deduped Flow will not
        // re-emit an unchanged value.
        assertEquals(cfg, p.currentConfigSafe())
    }
}