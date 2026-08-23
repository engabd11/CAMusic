package com.engabd.sendpin.hue

/**
 * The colour arithmetic every [LightShowLayer] works in.
 *
 * All four layers nudge the HSV of whatever [SyncoEngine] already rendered
 * rather than choosing a colour outright, so they all need the same round trip
 * out of and back into [Rgb], and the same shortest-way-round hue blend.
 * `internal` and package-local: this is the `hue` package's shared vocabulary,
 * not a general-purpose colour library, and nothing outside it gains
 * visibility. `SyncoPalette.kt`'s own byte-identical [hsvToRgb] is gone in
 * favour of this one — it was the fifth copy in this package, and the palette
 * tables read exactly the same against a shared definition.
 *
 * `AlbumColours.kt` keeps its own conversions, and should: it works in CIELAB
 * over three bare floats, which is a different job with a different signature.
 */

/** Wrap into [0, 1). */
internal fun wrap1(x: Float): Float {
    val m = x % 1f
    return if (m < 0f) m + 1f else m
}

/** Move [current] toward [target] by [weight], the short way round the hue wheel. */
internal fun blendHue(current: Float, target: Float, weight: Float): Float {
    var diff = (target - current) % 1f
    if (diff > 0.5f) diff -= 1f
    if (diff < -0.5f) diff += 1f
    return wrap1(current + diff * weight)
}

/**
 * [Rgb] to hue/saturation/value, each 0..1, into a caller-owned buffer.
 *
 * The allocating [rgbToHsv] below reads better and every test uses it, but a
 * layer calls this once per lamp per frame — so at 60 Hz with four layers on,
 * the version that returns a fresh `FloatArray(3)` is a couple of thousand
 * short-lived arrays a second on the render thread. Each layer keeps one buffer
 * for its own lifetime instead.
 */
internal fun rgbToHsvInto(rgb: Rgb, out: FloatArray) {
    val (r, g, b) = rgb
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val d = mx - mn
    out[1] = if (mx > 1e-6f) d / mx else 0f
    out[2] = mx
    var h = 0f
    if (d > 1e-9f) {
        h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h < 0f) h += 1f
    }
    out[0] = h
}

/** [Rgb] to hue/saturation/value, each 0..1. Value is the max channel. */
internal fun rgbToHsv(rgb: Rgb): FloatArray {
    val (r, g, b) = rgb
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val d = mx - mn
    val s = if (mx > 1e-6f) d / mx else 0f
    var h = 0f
    if (d > 1e-9f) {
        h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h < 0f) h += 1f
    }
    return floatArrayOf(h, s, mx)
}

/** Destructuring for [rgbToHsv]'s return, so callers can say `val (h, s, v) = …`. */
internal operator fun FloatArray.component1() = this[0]
internal operator fun FloatArray.component2() = this[1]
internal operator fun FloatArray.component3() = this[2]

/** The inverse of [rgbToHsv]. */
internal fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
    val i = (h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1f - s)
    val q = v * (1f - s * f)
    val t = v * (1f - s * (1f - f))
    return when (i % 6) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
}
