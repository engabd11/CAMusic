package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import kotlin.math.pow

/**
 * Everything that happens to a script's frame between the script and `FieldSafety`.
 *
 * Pulled out of [AmbienceSession] so the 60 Hz path can be driven off-device with a
 * simulated clock: the comfort tests run ten minutes of every scene through exactly this
 * and assert on what a lamp would have been asked to do.
 *
 * In order:
 *
 * 1. **Fade and intensity.** The sleep timer's last minute, and the intensity slider's
 *    level share ([levelFor]), scale the scene's perceived brightness. Before the comfort
 *    limit, so a slider move or the fade eases in at the scene's own pace.
 * 2. **Comfort limit.** [ComfortLimiter] holds every lamp, and the room mean, to the
 *    scene's own speed limit, in perceived units. A scene's named events (see
 *    [AmbienceScript.burstActive]) get the generous burst limits instead.
 * 3. **Lift and brightness ceiling.** The level is lifted by [lift] on its way to the
 *    bridge, then the user's master brightness applies, last, so the slider means what
 *    it means everywhere else in the app.
 *
 * There is no perceptual-to-linear step. The lamps already dim on a perceptual curve of
 * their own (see `HueStreamEncoder`), so converting L* to luminance first darkened every
 * scene twice: a fire asking for 0.62 reached the bridge as 0.30 and its far lamps as
 * 0.08, which was the "very dim even at 100%" this replaced. The scripts' levels were
 * tuned against that curve, so [lift] brings a resting room up to where the eye expects
 * it; black stays black and full stays full. It runs after the comfort limiter, so the
 * scenes' speed limits are still measured in the units the scripts and tests use.
 */
class AmbienceFrameShaper(private val script: AmbienceScript) {

    private val comfort = ComfortLimiter(script.comfort)
    private val encoded = HashMap<Int, Rgb>()

    /**
     * The frame as the comfort limiter left it, still in perceived brightness. For tests
     * and diagnostics; the map is reused by the next call.
     */
    var perceived: Map<Int, Rgb> = emptyMap()
        private set

    /** Whether the last frame was shaped with the burst limits. */
    var burst: Boolean = false
        private set

    /**
     * Shape [frame] for a step of [dtS] seconds. [frame] is modified in place by the fade.
     * The returned map is reused by the next call.
     */
    fun shape(
        frame: MutableMap<Int, Rgb>,
        dtS: Float,
        fade: Float,
        ceiling: Float,
        intensity: Float = 1f,
    ): Map<Int, Rgb> {
        val g = fade * levelFor(intensity)
        if (g < 1f) {
            for ((k, v) in frame) frame[k] = Rgb(v.first * g, v.second * g, v.third * g)
        }
        burst = script.burstActive
        val limited = comfort.process(frame, dtS, burst)
        perceived = limited
        val c = ceiling.coerceIn(0f, 1f)
        encoded.clear()
        for ((k, v) in limited) {
            val m = Perceptual.levelOf(v)
            val g = if (m <= 1e-6f) 0f else lift(m) / m * c
            encoded[k] = Rgb(v.first * g, v.second * g, v.third * g)
        }
        return encoded
    }

    companion object {
        /**
         * How much of the scene's level the intensity slider allows: full at the top,
         * a little over half at the bottom. The scripts already use intensity for event
         * rate and depth; this makes the slider visibly dim and brighten the room too.
         */
        fun levelFor(intensity: Float): Float = 0.55f + 0.45f * intensity.coerceIn(0f, 1f)

        /** A scene's level as the bridge gets it: 0.1 becomes 0.25, 0.5 becomes 0.66. */
        fun lift(level: Float): Float = level.coerceIn(0f, 1f).pow(0.6f)
    }
}
