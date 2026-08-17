package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.SongPhase
import kotlin.math.abs
import kotlin.math.exp

/**
 * Layer 2 — Emotional Arc: the room's colour temperature follows the song's
 * live structure. Calm sections read cool, a build warms toward the drop,
 * the drop itself is hot, and a breakdown pulls back cold. See
 * `docs/creative-light-shows.md`.
 *
 * Maps onto [StructureTracker]'s real four-value [SongPhase] — `STEADY`,
 * `BUILDING`, `DROP`, `BREAKDOWN`. There is no separate intro/outro phase to
 * key off; the gentle warm fade near the very end of a track is instead
 * computed here, directly, from how close [LayerContext.trackPositionS] is
 * to [LayerContext.scan]'s duration — arithmetic on two floats already in
 * the context, not a change to the structure tracker.
 *
 * A directional *nudge* on whatever hue the engine (and Music DNA, if also
 * enabled) already chose, never a replacement — see [apply]'s blend toward
 * a warm or cool anchor rather than an outright hue swap.
 */
class EmotionalArcLayer : LightShowLayer {
    override val id = "emotional_arc"

    /** Smoothed temperature, -1 (cold) .. +1 (hot). The layer's only state. */
    private var temperature = 0f

    override fun apply(base: Map<Int, Rgb>, context: LayerContext): Map<Int, Rgb> {
        val target = targetTemperature(context)
        val tau = if (target > temperature) TAU_RISE_S else TAU_FALL_S
        temperature += alpha(tau, context.dt) * (target - temperature)

        val t = temperature.coerceIn(-1f, 1f)
        if (abs(t) < MIN_EFFECT) return base

        val anchorHue = if (t >= 0f) WARM_HUE else COOL_HUE
        val blendWeight = abs(t) * MAX_HUE_BLEND
        val saturationMul = (1f + t * SATURATION_TEMP_COEFF).coerceIn(0f, 2f)

        return base.mapValues { (_, rgb) ->
            val (h, s, v) = rgbToHsv(rgb)
            val hue = blendHue(h, anchorHue, blendWeight)
            hsvToRgb(hue, (s * saturationMul).coerceIn(0f, 1f), v)
        }
    }

    private fun targetTemperature(context: LayerContext): Float {
        val structure = context.structure
        val phaseTemp = if (structure == null) {
            0f
        } else {
            when (structure.phase) {
                SongPhase.STEADY -> STEADY_TEMP
                SongPhase.BUILDING -> lerp(STEADY_TEMP, DROP_TEMP, structure.buildProgress)
                SongPhase.DROP -> DROP_TEMP
                SongPhase.BREAKDOWN -> BREAKDOWN_TEMP
            }
        }
        return (phaseTemp + endOfTrackFade(context) * OUTRO_BONUS).coerceIn(-1f, 1f)
    }

    /**
     * 0..1: how deep into the closing stretch of the track the playhead is.
     * Zero for most of the track, ramping to 1 only in the last few percent —
     * see [FADE_WINDOW_FRAC] — and zero whenever the duration or position
     * isn't known (an unscanned track, or before the tap has reported one).
     */
    private fun endOfTrackFade(context: LayerContext): Float {
        val duration = context.scan?.durationS ?: return 0f
        val pos = context.trackPositionS
        if (duration <= 0f || pos < 0f) return 0f
        val window = (duration * FADE_WINDOW_FRAC).coerceIn(FADE_WINDOW_MIN_S, FADE_WINDOW_MAX_S)
        val remaining = duration - pos
        if (remaining >= window) return 0f
        return (1f - remaining / window).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** EMA smoothing factor for a given time constant, matching [StructureTracker]'s own idiom. */
    private fun alpha(tauS: Float, dt: Float): Float = 1f - exp(-dt / tauS)

    private companion object {
        // Per-phase target temperature. Not measured — a starting point to
        // retune by ear against real tracks, same as SyncoModes' own presets.
        private const val STEADY_TEMP = -0.3f
        private const val DROP_TEMP = 1.0f
        private const val BREAKDOWN_TEMP = -1.0f

        /** Additive warm nudge as the end-of-track fade reaches full weight. */
        private const val OUTRO_BONUS = 0.6f
        private const val FADE_WINDOW_FRAC = 0.08f
        private const val FADE_WINDOW_MIN_S = 8f
        private const val FADE_WINDOW_MAX_S = 30f

        /** How quickly the smoothed temperature chases a warmer vs. cooler target. */
        private const val TAU_RISE_S = 1.2f
        private const val TAU_FALL_S = 2.5f

        private const val MIN_EFFECT = 0.02f
        private const val WARM_HUE = 0.04f
        private const val COOL_HUE = 0.58f
        private const val MAX_HUE_BLEND = 0.5f
        private const val SATURATION_TEMP_COEFF = 0.25f
    }
}
