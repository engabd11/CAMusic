package com.engabd.sendpin.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * Gain above unity, with a limiter so it can never clip.
 *
 * ReplayGain can ask to *raise* a quiet master, and the player's own volume cannot
 * do that — `Player.volume` stops at 1.0 — so every positive gain used to be thrown
 * away and quiet records stayed quiet. This is where the rise happens instead: a
 * multiply in [LocalDsp]'s float path, followed by a peak limiter that pulls the
 * gain back just enough whenever a boosted sample would pass [CEILING].
 *
 * The limiter is *linked* — one gain for every channel of a frame — so a peak in
 * one channel cannot shift the stereo image. Attack is instant (no look-ahead, so
 * no added latency, which the light show's timing depends on); release is
 * [RELEASE_S], slow enough not to pump on a drum hit.
 *
 * Changes of [target] are ramped over [RAMP_S] rather than stepped, since a step
 * in gain is a click.
 *
 * Threading: [target] is written from any thread; everything else runs on the
 * audio thread that owns the processor.
 */
class BoostLimiter {

    /** The linear boost to reach, 1 for none. Values under 1 are treated as 1. */
    @Volatile var target: Float = 1f

    private var current = 1f
    private var envelope = 1f
    private var rampStep = 1f / (RAMP_S * 44_100f)
    private var release = 1f - exp(-1f / (RELEASE_S * 44_100f))

    fun configure(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(8_000).toFloat()
        rampStep = 1f / (RAMP_S * sr)
        release = 1f - exp(-1f / (RELEASE_S * sr))
    }

    /** Whether a frame could come out different from how it went in. */
    val active: Boolean
        get() = target > 1f + EPS || current > 1f + EPS || envelope < 1f - EPS

    /** Process one frame of [channels] samples, in place. */
    fun processFrame(frame: FloatArray, channels: Int) {
        val goal = target.coerceAtLeast(1f)
        current = when {
            current < goal -> (current + rampStep).coerceAtMost(goal)
            current > goal -> (current - rampStep).coerceAtLeast(goal)
            else -> current
        }

        var peak = 0f
        for (c in 0 until channels) peak = maxOf(peak, abs(frame[c]))
        peak *= current

        // Recover toward unity, then clamp down to exactly what this frame allows.
        envelope += (1f - envelope) * release
        if (peak * envelope > CEILING) envelope = CEILING / peak

        val g = current * envelope
        for (c in 0 until channels) frame[c] *= g
    }

    /** A seek or a new stream: the envelope described audio that is no longer next. */
    fun reset() {
        envelope = 1f
    }

    companion object {
        /** Just under full scale: −0.18 dBFS, room for the resampler's overshoot. */
        const val CEILING = 0.98f
        const val RAMP_S = 0.05f
        const val RELEASE_S = 0.15f
        private const val EPS = 1e-4f
    }
}
