package com.engabd.sendpin.audio

/**
 * Music-driven phone haptics: turns live beat and onset detection into
 * vibration pulses on the device's [VibratorManager].
 *
 * On each detected beat, fires a short one-shot vibration whose amplitude
 * scales with the beat's strength. On onsets (spectral flux peaks that are not
 * necessarily on the beat grid), fires a lighter pulse. Both are throttled to
 * at most [MAX_HZ] events per second so a dense onset stream never turns into
 * a continuous buzz.
 *
 * **Why haptics belong in the analysis pipeline.** The tap already runs beat
 * and onset detection for Light Sync; the vibration is a third consumer of
 * the same data, not a separate analysis. Firing from the analysis thread's
 * [AnalysisFrame] callback means the haptics are in lockstep with the lights
 * and the visualizer, with no extra latency and no second copy of the audio.
 *
 * **The config snapshot pattern.** Like [LocalDsp], the config is written from
 * the UI thread (a settings toggle) and read from the analysis thread (on
 * every beat). A `@Volatile` reference to an immutable data class is the
 * cheapest correct way to cross that boundary: the write publishes a whole
 * new object atomically, the read sees either the old one or the new one but
 * never a torn mix of fields. No locks, no allocation on the hot path.
 *
 * **The vibrator behind an interface.** Android's [VibratorManager] is not
 * available in JVM unit tests, so the actual vibration call is behind the
 * [VibrationSink] interface. The production implementation wraps a real
 * [android.os.VibratorManager]; the test uses a recording sink that captures
 * every call. This keeps the beat-to-amplitude mapping, the throttling and
 * the enable/disable logic all testable on the JVM.
 */
class HapticEngine(
    private val sink: VibrationSink,
) {
    /**
     * Configuration for the haptic engine.
     *
     * @param enabled whether haptics are on at all. Off by default — a feature
     *   that buzzes the phone needs to be explicitly turned on, not
     *   implicitly enabled.
     * @param intensity master gain, 0..1. Scales every vibration amplitude.
     *   At 0 the engine is effectively disabled (but still processes frames,
     *   so toggling intensity back up resumes immediately).
     */
    data class Config(
        val enabled: Boolean = false,
        val intensity: Float = 0.5f,
    )

    @Volatile
    private var pending: Config = Config()

    /**
     * Update the config. Safe to call from any thread — the [pending]
     * reference is volatile, so the analysis thread picks up the new value
     * on its next frame without locks.
     */
    fun setConfig(config: Config) {
        pending = config
    }

    /** The currently active config (for display / diagnostics). */
    fun currentConfig(): Config = pending

    // ── Throttling state ──────────────────────────────────────────────────

    /**
     * Wall-clock nanoseconds of the last vibration, for throttle timing.
     * Owned by the analysis thread; no need for volatile because only one
     * thread calls [onFrame] / [onBeat].
     */
    private var lastVibrationNs: Long = 0L

    /**
     * Minimum gap between two consecutive vibrations, in nanoseconds.
     * At [MAX_HZ] = 10 this is 100 ms, which is long enough to feel each
     * pulse individually rather than as a continuous tingle.
     */
    private val minGapNs: Long = 1_000_000_000L / MAX_HZ

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Process a beat event from the analysis pipeline.
     *
     * Called from the analysis thread when [AnalysisFrame.beat] (or
     * [AnalysisFrame.bassBeat] / [AnalysisFrame.midBeat]) is true. The
     * [strength] is the beat's accent, 0..1; it is mapped to a vibration
     * amplitude proportional to both the beat strength and the master
     * [Config.intensity].
     *
     * Returns true if a vibration was actually fired, false if it was
     * suppressed (disabled, throttled, or zero amplitude). The return value
     * is primarily for tests.
     */
    fun onBeat(strength: Float, timestampNs: Long): Boolean {
        val config = pending
        if (!config.enabled) return false

        if (!shouldFire(timestampNs)) return false

        // Map beat strength (0..1) × intensity (0..1) to Android's
        // VibrationEffect amplitude range (1..255). A floor of 1 (not 0)
        // because amplitude 0 is silently dropped by the framework — we want
        // the throttle to decide when *not* to vibrate, not the amplitude.
        val amplitude = beatAmplitude(strength, config.intensity)
        if (amplitude <= 0) return false

        sink.vibrateOneShot(BEAT_DURATION_MS, amplitude)
        lastVibrationNs = timestampNs
        return true
    }

    /**
     * Process an onset event from the analysis pipeline.
     *
     * Onsets are spectral flux peaks that are not necessarily aligned to the
     * beat grid — a hi-hat, a vocal entrance, a snare. They get a lighter,
     * shorter pulse than a beat so the two are haptically distinct.
     *
     * Shares the same throttle as [onBeat]: a beat followed immediately by
     * an onset does not fire twice. The beat wins because it is the stronger
     * event and the throttle keeps the onset from buzzing on top of it.
     */
    fun onOnset(strength: Float, timestampNs: Long): Boolean {
        val config = pending
        if (!config.enabled) return false

        if (!shouldFire(timestampNs)) return false

        val amplitude = onsetAmplitude(strength, config.intensity)
        if (amplitude <= 0) return false

        sink.vibrateOneShot(ONSET_DURATION_MS, amplitude)
        lastVibrationNs = timestampNs
        return true
    }

    /**
     * Reset the throttle state. Call on a track change or seek so the first
     * beat of the new track is not suppressed by the throttle window left
     * over from the last.
     */
    fun reset() {
        lastVibrationNs = 0L
    }

    // ── Internal logic ────────────────────────────────────────────────────

    /**
     * Whether enough time has passed since the last vibration to fire again.
     *
     * The first vibration (when [lastVibrationNs] is 0) always passes.
     * Subsequent ones must wait at least [minGapNs] nanoseconds.
     */
    private fun shouldFire(timestampNs: Long): Boolean {
        if (lastVibrationNs == 0L) return true
        return timestampNs - lastVibrationNs >= minGapNs
    }

    /**
     * Map a beat strength (0..1) and master intensity (0..1) to Android's
     * [VibrationEffect] amplitude range (1..255).
     *
     * The mapping is `strength × intensity × 255`, clamped to [1, 255].
     * A square-root curve would compress the dynamic range and make quiet
     * beats more feelable; a linear mapping keeps the dynamic range honest,
     * which is what a listener who set the intensity to 0.3 expects: 30% of
     * the haptic range, not a compressed approximation of it.
     */
    private fun beatAmplitude(strength: Float, intensity: Float): Int {
        val raw = (strength.coerceIn(0f, 1f) * intensity.coerceIn(0f, 1f) * MAX_AMPLITUDE)
        return raw.toInt().coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
    }

    /**
     * Onset amplitude: half the beat amplitude at the same strength, so the
     * two are haptically distinct. Clamped to [1, 255] the same way.
     */
    private fun onsetAmplitude(strength: Float, intensity: Float): Int {
        val raw = (strength.coerceIn(0f, 1f) * intensity.coerceIn(0f, 1f) * MAX_AMPLITUDE * ONSET_AMPLITUDE_RATIO)
        return raw.toInt().coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
    }

    companion object {
        /**
         * Maximum vibration event rate. At 10 Hz each pulse has 100 ms to
         * land and decay before the next, which is what a phone's vibration
         * motor needs to produce distinct taps rather than a continuous hum.
         * Above this the pulses merge perceptually and the haptics stop
         * feeling like beats.
         */
        const val MAX_HZ = 10

        /** One-shot beat vibration duration, in milliseconds. */
        const val BEAT_DURATION_MS = 40L

        /** One-shot onset vibration duration, in milliseconds. */
        const val ONSET_DURATION_MS = 20L

        /** Android's maximum VibrationEffect amplitude. */
        const val MAX_AMPLITUDE = 255

        /** Minimum amplitude we ever send — 0 is silently dropped by the framework. */
        const val MIN_AMPLITUDE = 1

        /**
         * Onset amplitude as a fraction of beat amplitude. Half so onsets
         * feel like lighter taps alongside the stronger beat pulse.
         */
        const val ONSET_AMPLITUDE_RATIO = 0.5f
    }
}

/**
 * Abstraction over the platform vibrator, so the engine's logic is testable
 * on the JVM.
 *
 * The production implementation is a thin wrapper around
 * [android.os.VibratorManager] that calls
 * [android.os.VibrationEffect.createOneShot] and has no other behaviour. The
 * test implementation records every call so assertions can inspect what was
 * fired, when and at what amplitude.
 */
interface VibrationSink {
    /**
     * Fire a one-shot vibration.
     *
     * @param durationMs vibration duration in milliseconds
     * @param amplitude vibration intensity, 1..255 (0 is silently dropped by
     *   Android, so callers should ensure ≥ 1)
     */
    fun vibrateOneShot(durationMs: Long, amplitude: Int)
}