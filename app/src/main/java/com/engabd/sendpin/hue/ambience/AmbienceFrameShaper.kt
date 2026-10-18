package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb

/**
 * Everything that happens to a script's frame between the script and `FieldSafety`.
 *
 * Pulled out of [AmbienceSession] so the 60 Hz path can be driven off-device with a
 * simulated clock: the comfort tests run ten minutes of every scene through exactly this
 * and assert on what a lamp would have been asked to do.
 *
 * In order:
 *
 * 1. **Fade.** The sleep timer's last minute scales the scene's perceived brightness.
 *    Perceived, so the room eases out evenly instead of seeming to hang and then drop.
 * 2. **Comfort limit.** [ComfortLimiter] holds every lamp, and the room mean, to the
 *    scene's own speed limit, in perceived units. A scene's named events (see
 *    [AmbienceScript.burstActive]) get the generous burst limits instead.
 * 3. **Perceptual curve.** [Perceptual.encode] turns perceived brightness into the linear
 *    value `HueStreamEncoder` expects. Only ambience goes through this; the music show is
 *    untouched.
 * 4. **Brightness ceiling.** The user's master brightness, in linear light, last, so the
 *    slider means what it means everywhere else in the app.
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
    fun shape(frame: MutableMap<Int, Rgb>, dtS: Float, fade: Float, ceiling: Float): Map<Int, Rgb> {
        if (fade < 1f) {
            for ((k, v) in frame) frame[k] = Rgb(v.first * fade, v.second * fade, v.third * fade)
        }
        burst = script.burstActive
        val limited = comfort.process(frame, dtS, burst)
        perceived = limited
        val c = ceiling.coerceIn(0f, 1f)
        encoded.clear()
        for ((k, v) in limited) {
            val lin = Perceptual.encode(v)
            encoded[k] = Rgb(lin.first * c, lin.second * c, lin.third * c)
        }
        return encoded
    }
}
