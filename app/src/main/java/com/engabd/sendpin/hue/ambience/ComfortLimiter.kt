package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import kotlin.math.abs
import kotlin.math.min

/**
 * How fast a scene is allowed to change, in perceived brightness per second.
 *
 * Declared by the script, because "comfortable" is a property of the scene: a fire may
 * breathe at most a few tenths of a unit a second and an aurora should hardly move at
 * all, while a thunderstorm is allowed to be violent for the fraction of a second a
 * stroke lasts and for nothing else.
 *
 * Every number is in [Perceptual] units (CIE L* over 100), measured on the brightest
 * channel. A change of 0.5 is the difference between a dim lamp and a bright one.
 *
 * @param risePerS fastest a single lamp may brighten, per second.
 * @param fallPerS fastest a single lamp may dim, per second.
 * @param roomSwingPerS cap on how fast the room's *mean* may move either way. The
 *   per-lamp limits stop one lamp lurching; this stops six lamps each moving politely
 *   in the same direction, which is a flash all the same.
 * @param burstRisePerS what a lamp may do while the script says a named event is in
 *   progress (see [AmbienceScript.burstActive]).
 * @param burstFallPerS likewise for falling.
 * @param burstRoomSwingPerS and for the room mean. Still finite: `FieldSafety` sits
 *   after this and enforces the photosensitivity budget regardless.
 */
data class ComfortProfile(
    val risePerS: Float = 0.5f,
    val fallPerS: Float = 0.5f,
    val roomSwingPerS: Float = 0.3f,
    val burstRisePerS: Float = 40f,
    val burstFallPerS: Float = 12f,
    val burstRoomSwingPerS: Float = 60f,
) {
    companion object {
        val DEFAULT = ComfortProfile()
    }
}

/**
 * Keeps an ambience scene gentle: a hard ceiling on how quickly any lamp, and the room
 * as a whole, may change.
 *
 * Replaces `EffectRateLimiter` on the ambience path only. That limiter exists to stop a
 * lamp reversing faster than the bridge can follow, and it does so by *holding a channel
 * at its extreme* on an early reversal, which on a slow scene prolongs a peak rather than
 * softening it. This one never holds anything. It moves each lamp towards what the
 * script asked for at no more than the scene's rate, so an over-eager pop becomes a
 * slightly slower pop with the same shape.
 *
 * Works on the brightest channel in perceived units and scales the colour with it, so
 * hue is never touched; chromaticity is slewed by the encoder, and Philips' guidance is
 * that colour may move faster than brightness.
 *
 * Named events may exceed the limits: a scene passes `burst = true` for as long as a
 * lightning stroke or firework burst is under way and the burst limits apply instead.
 * They are generous, not infinite, and `FieldSafety` still bounds what reaches a lamp.
 */
class ComfortLimiter(private val profile: ComfortProfile) {

    private class Lamp(var level: Float, var colour: Rgb) {
        /** Level before this step, so the room-wide pass can take part of a step back. */
        var prev = level
        var next = level
    }

    private val lamps = HashMap<Int, Lamp>()
    private val out = HashMap<Int, Rgb>()

    fun reset() {
        lamps.clear()
        out.clear()
    }

    /**
     * Limit [frame] (perceived colours) for a step of [dt] seconds.
     *
     * A lamp seen for the first time starts from darkness rather than passing straight
     * through, so a scene fades up at its own pace instead of switching on. The map
     * returned is reused between calls.
     */
    fun process(frame: Map<Int, Rgb>, dt: Float, burst: Boolean): Map<Int, Rgb> {
        val n = frame.size
        if (n == 0) return frame
        val step = dt.coerceIn(0f, 0.25f)
        val rise = (if (burst) profile.burstRisePerS else profile.risePerS) * step
        val fall = (if (burst) profile.burstFallPerS else profile.fallPerS) * step
        val swing = (if (burst) profile.burstRoomSwingPerS else profile.roomSwingPerS) * step

        // Pass 1: each lamp's own limit.
        var before = 0f
        var after = 0f
        for ((id, want) in frame) {
            val lamp = lamps.getOrPut(id) { Lamp(0f, want) }
            val target = Perceptual.levelOf(want).coerceIn(0f, 1f)
            lamp.prev = lamp.level
            lamp.next = lamp.level + (target - lamp.level).coerceIn(-fall, rise)
            before += lamp.prev
            after += lamp.next
        }

        // Pass 2: the room as a whole. If the mean would move further than allowed, every
        // lamp gives back the same proportion of its step. Proportional rather than
        // clamped, so the scene keeps its shape and is merely stretched in time.
        val delta = (after - before) / n
        val scale = if (abs(delta) > swing && abs(delta) > 1e-7f) swing / abs(delta) else 1f

        out.clear()
        for ((id, want) in frame) {
            val lamp = lamps.getValue(id)
            lamp.level = lamp.prev + (lamp.next - lamp.prev) * scale
            // The hue comes from the script. If it asked for black, keep the last hue so
            // a lamp easing down does not swing to white on the way.
            if (Perceptual.levelOf(want) > 1e-4f) lamp.colour = want
            out[id] = scaleTo(lamp.colour, lamp.level)
        }
        return out
    }

    private fun scaleTo(c: Rgb, level: Float): Rgb {
        val m = Perceptual.levelOf(c)
        if (m <= 1e-6f) return Rgb(level, level, level)
        val k = level / m
        return Rgb(min(1f, c.first * k), min(1f, c.second * k), min(1f, c.third * k))
    }
}
