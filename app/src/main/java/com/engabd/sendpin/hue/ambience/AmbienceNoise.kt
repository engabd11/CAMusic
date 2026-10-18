package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Smooth noise as a pure function of `(seed, t)`.
 *
 * The scenes' slow wander (flames, caustics, cloud) used to be sines at a few fixed
 * rates, which repeat and are visibly periodic after a minute. Value noise on a hashed
 * lattice never repeats, costs a few multiplies, and — the property that matters here —
 * holds **no state**. Two threads, or a test replaying a scene out of order, get the
 * same answer for the same `(seed, t)`, so the light tick can read it without a lock
 * and without sharing a random generator with the scheduler.
 */
internal object SmoothNoise {

    /** Hash an integer lattice point to -1..1. */
    private fun lattice(seed: Int, i: Int): Float {
        var h = i * -0x61c88647 + seed * 0x2c1b3c6d
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 8388608f - 1f
    }

    /** Quintic ease: zero first and second derivative at each lattice point. */
    private fun ease(f: Float) = f * f * f * (f * (f * 6f - 15f) + 10f)

    /** One octave at position [x] (already scaled to lattice units), -1..1. */
    fun value(seed: Int, x: Double): Float {
        val i = floor(x)
        val f = (x - i).toFloat()
        val a = lattice(seed, i.toInt())
        val b = lattice(seed, i.toInt() + 1)
        return a + (b - a) * ease(f)
    }

    /**
     * Several octaves summed, roughly 1/f: each octave is faster and, by [rolloff],
     * smaller. Returns about -1..1 (normalised by the sum of the weights, so it rarely
     * reaches the ends).
     *
     * @param baseHz the slowest octave's lattice rate.
     * @param ratio how much faster each octave is than the last.
     */
    fun fractal(seed: Int, tS: Double, baseHz: Float, octaves: Int, ratio: Float = 3f, rolloff: Float = 0.6f): Float {
        var sum = 0f
        var norm = 0f
        var w = 1f
        var hz = baseHz
        for (o in 0 until octaves) {
            sum += w * value(seed + o * 7919, tS * hz)
            norm += w
            w *= rolloff
            hz *= ratio
        }
        return sum / norm
    }
}

/**
 * A recording-driven level that cannot jump.
 *
 * The analysis behind a bed reports a sample at 50 Hz, can briefly report none (a loop
 * point, a seek, the first moment of a show), and reads wildly in the middle of a
 * clap. A scene that draws straight from it flickers with the recording and snaps to a
 * default when it goes missing, which is exactly the "roar" that made the fire unsettling.
 *
 * This holds the last value when there is nothing new and otherwise eases towards the
 * target with a time constant of a second or more, so the recording shapes the *mood* of
 * a scene over seconds and never its per-frame brightness.
 */
internal class SlowFollower(private val tauS: Float, initial: Float = 0.5f) {
    var value: Float = initial
        private set

    /** Step by [dt]; [valid] false means no reading, so hold. */
    fun step(valid: Boolean, target: Float, dt: Float): Float {
        if (valid && dt > 0f) {
            val a = 1f - exp(-dt / tauS)
            value += (target - value) * a
        }
        return value
    }

    fun reset(to: Float) { value = to }
}

/** Colour of a black body, for fire and for the warm end of everything else. */
internal object Blackbody {

    /**
     * Chromaticity of a black body at [kelvin] as an Rgb whose brightest channel is 1.
     *
     * Tanner Helland's fit to the Planckian locus, good from 1000 K to 40000 K, which
     * covers an ember (1800 K) to a clear sky. Brightness is deliberately not part of it;
     * the caller scales.
     */
    fun rgb(kelvin: Float): Rgb {
        val t = kelvin.coerceIn(1000f, 12000f) / 100f
        val r = if (t <= 66f) 255f else 329.698727446f * (t - 60f).pow(-0.1332047592f)
        val g = if (t <= 66f) 99.4708025861f * ln(t) - 161.1195681661f
        else 288.1221695283f * (t - 60f).pow(-0.0755148492f)
        val b = when {
            t >= 66f -> 255f
            t <= 19f -> 0f
            else -> 138.5177312231f * ln(t - 10f) - 305.0447927307f
        }
        val rr = r.coerceIn(0f, 255f) / 255f
        val gg = g.coerceIn(0f, 255f) / 255f
        val bb = b.coerceIn(0f, 255f) / 255f
        val m = maxOf(rr, gg, bb).coerceAtLeast(1e-3f)
        return Rgb(rr / m, gg / m, bb / m)
    }
}

/** Linear blend of two colours. */
internal fun mix(a: Rgb, b: Rgb, t: Float) = Rgb(
    a.first + (b.first - a.first) * t,
    a.second + (b.second - a.second) * t,
    a.third + (b.third - a.third) * t,
)

/** Smooth 0..1 ramp between [e0] and [e1]. */
internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** [c] scaled so its brightest channel is 1: a pure direction in colour, brightness set elsewhere. */
internal fun normalised(c: Rgb): Rgb {
    val m = maxOf(c.first, c.second, c.third)
    return if (m <= 1e-6f) Rgb(1f, 1f, 1f) else Rgb(c.first / m, c.second / m, c.third / m)
}

/**
 * A path through colour and perceived brightness over a scene's own clock (0..1).
 *
 * Used by the scenes that move through a day: dawn, sunset. Each pair of neighbouring
 * keys is blended with a smoothstep, so the path has no corner at a key and a change
 * of colour never reads as a step. Colours are directions (brightest channel 1) and the
 * brightness is carried separately, which is what [com.engabd.sendpin.hue.ambience.ComfortLimiter]
 * wants to see.
 */
internal class Keyframes(
    private val at: FloatArray,
    colours: Array<Rgb>,
    private val levels: FloatArray,
) {
    private val dirs = Array(colours.size) { normalised(colours[it]) }

    private fun segment(p: Float): Int {
        var i = 0
        while (i < at.size - 2 && p >= at[i + 1]) i++
        return i
    }

    private fun weight(p: Float, i: Int) = smoothstep(at[i], at[i + 1], p)

    fun colourAt(p: Float): Rgb {
        val i = segment(p)
        return normalised(mix(dirs[i], dirs[i + 1], weight(p, i)))
    }

    fun levelAt(p: Float): Float {
        val i = segment(p)
        return levels[i] + (levels[i + 1] - levels[i]) * weight(p, i)
    }
}
