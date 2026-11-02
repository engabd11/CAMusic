package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import kotlin.math.max

/**
 * Perceived brightness for the ambience scenes.
 *
 * A script paints in perceived brightness, 0..1, where a step of 0.1 looks the same size
 * anywhere in the range, and [ComfortLimiter] holds its speed limits in those units.
 * The level is the brightest channel, with the colour scaled with it, so hue and
 * saturation survive.
 *
 * Nothing converts it to linear light on the way out: `HueStreamEncoder` sends the level
 * as Hue's brightness, and the bulb maps that through its own perceptual dimming curve.
 * See [AmbienceFrameShaper].
 */
object Perceptual {

    /** The brightest channel, which is what the encoder reads as the lamp's level. */
    fun levelOf(c: Rgb): Float = max(c.first, max(c.second, c.third))
}
