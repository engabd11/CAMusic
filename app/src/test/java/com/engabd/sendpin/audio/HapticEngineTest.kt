package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [HapticEngine].
 *
 * The engine's logic — the beat-to-amplitude mapping, the onset-to-amplitude
 * mapping, the 10 Hz throttle, and the enabled/disabled gate — is all pure
 * arithmetic with no Android dependencies. The [VibrationSink] interface
 * lets the test capture what the engine *would* fire without touching a real
 * [android.os.VibratorManager].
 */
class HapticEngineTest {

    /**
     * Recording sink: captures every [VibrationSink.vibrateOneShot] call so
     * the test can assert on the amplitude and timing.
     */
    private class RecordingSink : VibrationSink {
        data class Call(val durationMs: Long, val amplitude: Int)

        val calls = mutableListOf<Call>()

        override fun vibrateOneShot(durationMs: Long, amplitude: Int) {
            calls.add(Call(durationMs, amplitude))
        }
    }

    private fun engine(enabled: Boolean = true, intensity: Float = 0.5f): Pair<HapticEngine, RecordingSink> {
        val sink = RecordingSink()
        val engine = HapticEngine(sink)
        engine.setConfig(HapticEngine.Config(enabled = enabled, intensity = intensity))
        return engine to sink
    }

    // ── Beat-to-amplitude mapping ─────────────────────────────────────────

    @Test
    fun `full strength beat at full intensity produces maximum amplitude`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1.0f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        assertEquals(HapticEngine.BEAT_DURATION_MS, sink.calls[0].durationMs)
        assertEquals(HapticEngine.MAX_AMPLITUDE, sink.calls[0].amplitude)
    }

    @Test
    fun `half strength beat at half intensity produces proportional amplitude`() {
        val (engine, sink) = engine(enabled = true, intensity = 0.5f)
        engine.onBeat(strength = 0.5f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        // 0.5 × 0.5 × 255 = 63.75 → 63 (truncated)
        assertEquals(63, sink.calls[0].amplitude)
    }

    @Test
    fun `zero strength beat still fires at minimum amplitude`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 0f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        assertEquals(HapticEngine.MIN_AMPLITUDE, sink.calls[0].amplitude)
    }

    @Test
    fun `beat amplitude never exceeds MAX_AMPLITUDE`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        // Strength > 1 should be clamped.
        engine.onBeat(strength = 2.0f, timestampNs = 1_000_000L)
        assertEquals(HapticEngine.MAX_AMPLITUDE, sink.calls[0].amplitude)
    }

    @Test
    fun `beat amplitude never goes below MIN_AMPLITUDE when enabled`() {
        val (engine, sink) = engine(enabled = true, intensity = 0.01f)
        engine.onBeat(strength = 0.01f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        assertTrue(sink.calls[0].amplitude >= HapticEngine.MIN_AMPLITUDE,
            "amplitude ${sink.calls[0].amplitude} should be >= ${HapticEngine.MIN_AMPLITUDE}")
    }

    @Test
    fun `onset amplitude is lighter than beat at same strength`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1.0f, timestampNs = 1_000_000L)
        val beatAmp = sink.calls[0].amplitude
        sink.calls.clear()
        engine.reset()
        engine.onOnset(strength = 1.0f, timestampNs = 2_000_000L)
        val onsetAmp = sink.calls[0].amplitude
        assertTrue(onsetAmp < beatAmp,
            "onset ($onsetAmp) should be lighter than beat ($beatAmp)")
    }

    @Test
    fun `onset uses shorter duration than beat`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        val beatDuration = sink.calls[0].durationMs
        sink.calls.clear()
        engine.reset()
        engine.onOnset(strength = 1f, timestampNs = 2_000_000L)
        val onsetDuration = sink.calls[0].durationMs
        assertTrue(onsetDuration < beatDuration,
            "onset ($onsetDuration ms) should be shorter than beat ($beatDuration ms)")
    }

    // ── Throttling ────────────────────────────────────────────────────────

    @Test
    fun `two beats within the throttle window fire only once`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        val t0 = 1_000_000L  // 1 ms
        engine.onBeat(strength = 1f, timestampNs = t0)
        assertEquals(1, sink.calls.size)

        // 50 ms later — within the 100 ms window
        engine.onBeat(strength = 1f, timestampNs = t0 + 50_000_000L)
        assertEquals(1, sink.calls.size, "second beat within throttle window should be suppressed")
    }

    @Test
    fun `two beats outside the throttle window both fire`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        val t0 = 1_000_000L
        engine.onBeat(strength = 1f, timestampNs = t0)
        assertEquals(1, sink.calls.size)

        // 150 ms later — past the 100 ms window
        engine.onBeat(strength = 1f, timestampNs = t0 + 150_000_000L)
        assertEquals(2, sink.calls.size, "beat past throttle window should fire")
    }

    @Test
    fun `onset after beat within window is suppressed`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        engine.onOnset(strength = 1f, timestampNs = 1_000_000L + 30_000_000L)
        assertEquals(1, sink.calls.size, "onset within throttle window after a beat should be suppressed")
    }

    @Test
    fun `throttle allows at most MAX_HZ per second`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        // Fire as fast as possible for 1 second.
        val startNs = 1_000_000L
        for (i in 0 until 100) {
            engine.onBeat(strength = 1f, timestampNs = startNs + i * 10_000_000L) // every 10 ms
        }
        // At 10 ms intervals over 1 s, only every 10th should pass the 100 ms throttle.
        // The first fires at t=0, then at t=100ms, 200ms, ... — so about 10.
        assertTrue(sink.calls.size <= HapticEngine.MAX_HZ + 1,
            "at most ${HapticEngine.MAX_HZ + 1} vibrations in 1s, got ${sink.calls.size}")
        assertTrue(sink.calls.size >= HapticEngine.MAX_HZ,
            "at least ${HapticEngine.MAX_HZ} vibrations should fire, got ${sink.calls.size}")
    }

    @Test
    fun `reset clears the throttle`() {
        val (engine, sink) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertEquals(1, sink.calls.size)
        engine.reset()
        // After reset, the next event should fire even if it is immediately after.
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L + 1_000_000L)
        assertEquals(2, sink.calls.size, "reset should clear the throttle")
    }

    // ── Disabled ─────────────────────────────────────────────────────────

    @Test
    fun `disabled engine produces no output on beat`() {
        val (engine, sink) = engine(enabled = false, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertTrue(sink.calls.isEmpty(), "disabled engine should not fire on beat")
    }

    @Test
    fun `disabled engine produces no output on onset`() {
        val (engine, sink) = engine(enabled = false, intensity = 1.0f)
        engine.onOnset(strength = 1f, timestampNs = 1_000_000L)
        assertTrue(sink.calls.isEmpty(), "disabled engine should not fire on onset")
    }

    @Test
    fun `disabled by default`() {
        val sink = RecordingSink()
        val engine = HapticEngine(sink)
        // Default config should be disabled.
        assertFalse(engine.currentConfig().enabled)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertTrue(sink.calls.isEmpty(), "default (disabled) engine should not fire")
    }

    @Test
    fun `toggling enabled at runtime takes effect`() {
        val (engine, sink) = engine(enabled = false, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertTrue(sink.calls.isEmpty(), "should start disabled")
        engine.setConfig(HapticEngine.Config(enabled = true, intensity = 1.0f))
        engine.reset()
        engine.onBeat(strength = 1f, timestampNs = 2_000_000L)
        assertEquals(1, sink.calls.size, "should fire after enabling")
    }

    // ── On/OnBeat return values ──────────────────────────────────────────

    @Test
    fun `onBeat returns true when vibration fires`() {
        val (engine, _) = engine(enabled = true, intensity = 1.0f)
        assertTrue(engine.onBeat(strength = 1f, timestampNs = 1_000_000L))
    }

    @Test
    fun `onBeat returns false when disabled`() {
        val (engine, _) = engine(enabled = false, intensity = 1.0f)
        assertFalse(engine.onBeat(strength = 1f, timestampNs = 1_000_000L))
    }

    @Test
    fun `onBeat returns false when throttled`() {
        val (engine, _) = engine(enabled = true, intensity = 1.0f)
        engine.onBeat(strength = 1f, timestampNs = 1_000_000L)
        assertFalse(engine.onBeat(strength = 1f, timestampNs = 1_000_000L + 50_000_000L))
    }
}