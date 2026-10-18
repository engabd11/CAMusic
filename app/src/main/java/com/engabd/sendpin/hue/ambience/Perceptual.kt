package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Perceived brightness for the ambience scenes.
 *
 * ## Why scripts do not write linear light
 *
 * `HueStreamEncoder` sends `bri = max(r, g, b)` as a *linear* fraction of the lamp's
 * output. That is right for the music show, whose levels were tuned against it and which
 * the owner treats as the reference, and wrong for a room you live in: the eye reads
 * lightness roughly as the cube root of luminance, so the same linear step is enormous
 * at the bottom of the range and invisible at the top. A fire sitting at 6 percent
 * linear that wobbles by 2 points is a 30 percent change in what you see, and that is
 * the "pops so hard it becomes uncomfortable" the scenes were built to get away from.
 *
 * So an ambience script thinks in **perceived** brightness (CIE L*, divided by 100) and
 * this file is the one place that turns it into the linear value the encoder expects.
 * The conversion is applied inside [AmbienceSession.renderLights] only. The music show
 * and the encoder are untouched.
 *
 * Applied to the brightest channel and the colour scaled with it, so hue and saturation
 * survive: the encoder works from the channel ratios, and a per-channel curve would turn
 * every dim warm colour towards red.
 */
object Perceptual {

    /** CIE L*, 0..1, to relative luminance Y, 0..1. */
    fun toLinear(p: Float): Float {
        val l = p.coerceIn(0f, 1f) * 100f
        return if (l <= 8f) l / KAPPA else ((l + 16f) / 116f).pow(3)
    }

    /** Relative luminance Y, 0..1, to CIE L*, 0..1. The inverse of [toLinear]. */
    fun fromLinear(y: Float): Float {
        val yy = y.coerceIn(0f, 1f)
        val l = if (yy <= EPSILON) yy * KAPPA else 116f * cbrt(yy) - 16f
        return (l / 100f).coerceIn(0f, 1f)
    }

    /** The brightest channel, which is what the encoder reads as the lamp's level. */
    fun levelOf(c: Rgb): Float = max(c.first, max(c.second, c.third))

    /** A perceived colour as the linear colour the encoder wants, hue preserved. */
    fun encode(c: Rgb): Rgb {
        val m = levelOf(c)
        if (m <= 1e-6f) return Rgb(0f, 0f, 0f)
        val k = toLinear(min(m, 1f)) / m
        return Rgb(c.first * k, c.second * k, c.third * k)
    }

    private const val KAPPA = 903.3f
    private const val EPSILON = 216f / 24389f
}
