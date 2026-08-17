package com.engabd.sendpin.hue

/**
 * The colours a show is drawn in: the weighted [Palette] the renderer samples, the
 * static schemes, and the HSV conversion behind them.
 *
 * Split out of `SyncoEngine.kt` — see the note at the top of that file. Pure, and
 * unit-testable without a bridge, an audio frame or a room.
 */

// ── Colour palette ─────────────────────────────────────────────────────────

/**
 * An ordered list of RGB anchor colours (0..1), sampled as a cyclic gradient.
 * Ported from syncoV2's `color/palette.py:Palette`.
 */
/**
 * How much of a full colour cycle a weighted segment spends crossfading.
 * Small, so the palette reads as its colours holding rather than as a gradient.
 */
private const val PALETTE_XFADE = 0.05f

/**
 * A cyclic colour gradient the engine samples by position.
 *
 * Two sampling modes. Unweighted is a plain even interpolation across the
 * anchors, which is what the static schemes want. **Weighted** sizes each
 * segment by its share and *holds* the colour inside it, crossfading only
 * within [PALETTE_XFADE] of each boundary — so a cover that is 90% green and
 * 10% red spends 90% of the cycle green instead of half of it. That dwell-time
 * fidelity is the whole point of album-art v2; an even gradient over the same
 * colours looks nothing like the sleeve.
 */
class Palette(val colors: List<Rgb>, weights: List<Float>? = null) {
    init { if (colors.isEmpty()) throw IllegalArgumentException("Palette must have at least one color") }

    /** Normalised segment sizes, or null for even spacing. */
    private val weights: List<Float>? = weights
        ?.takeIf { it.size == colors.size && it.sum() > 1e-6f }
        ?.let { w -> val total = w.sum(); w.map { (it / total).coerceAtLeast(0f) } }

    fun sample(pos: Float): Rgb {
        if (colors.size == 1) return colors[0]
        val p = ((pos % 1f) + 1f) % 1f  // wrap to 0..1
        return if (weights != null) weightedSample(p) else evenSample(p)
    }

    private fun evenSample(p: Float): Rgb {
        val n = colors.size
        val scaled = p * n
        val i = scaled.toInt() % n
        val j = (i + 1) % n
        val frac = scaled - scaled.toInt()
        val (r1, g1, b1) = colors[i]
        val (r2, g2, b2) = colors[j]
        return Triple(
            r1 + (r2 - r1) * frac,
            g1 + (g2 - g1) * frac,
            b1 + (b2 - b1) * frac,
        )
    }

    /** Hold-and-crossfade over weight-sized segments. */
    private fun weightedSample(pos: Float): Rgb {
        val w = weights!!
        val n = colors.size
        var start = 0f
        var i = n - 1  // float-sum slack lands in the last segment
        for (k in w.indices) {
            if (pos < start + w[k] || k == n - 1) { i = k; break }
            start += w[k]
        }
        val end = start + w[i]
        val prevI = (i - 1 + n) % n
        val nextI = (i + 1) % n
        // Crossfade half-widths, clamped so a narrow segment cannot be swallowed
        // by its neighbours' fades.
        val xfIn = minOf(PALETTE_XFADE, 0.5f * w[i], 0.5f * w[prevI])
        val xfOut = minOf(PALETTE_XFADE, 0.5f * w[i], 0.5f * w[nextI])
        val cur = colors[i]
        val t: Float
        val other: Rgb
        if (xfIn > 0f && pos < start + xfIn) {
            // Finishing the fade from the previous colour; 0.5 at the boundary.
            t = 0.5f + 0.5f * (pos - start) / xfIn
            other = colors[prevI]
        } else if (xfOut > 0f && pos > end - xfOut) {
            // Starting the fade toward the next colour; 0.5 at the boundary.
            t = 1f - 0.5f * (1f - (end - pos) / xfOut)
            other = colors[nextI]
        } else {
            return cur
        }
        return Triple(
            other.first + (cur.first - other.first) * t,
            other.second + (cur.second - other.second) * t,
            other.third + (cur.third - other.third) * t,
        )
    }
}

/** The 16 static colour schemes (dynamic ones use album art). */
private val STATIC_SCHEMES = mapOf(
    ColorScheme.SUNSET to Palette(listOf(
        hsvToRgb(0.80f, 0.80f, 0.85f), hsvToRgb(0.92f, 0.85f, 1.0f), hsvToRgb(0.99f, 0.85f, 1.0f),
        hsvToRgb(0.045f, 0.85f, 1.0f), hsvToRgb(0.09f, 0.80f, 1.0f), hsvToRgb(0.12f, 0.70f, 1.0f))),
    ColorScheme.OCEAN to Palette(listOf(
        hsvToRgb(0.46f, 0.75f, 1.0f), hsvToRgb(0.50f, 0.85f, 1.0f), hsvToRgb(0.55f, 0.90f, 1.0f),
        hsvToRgb(0.60f, 0.90f, 1.0f), hsvToRgb(0.64f, 0.85f, 0.95f))),
    ColorScheme.FOREST to Palette(listOf(
        hsvToRgb(0.27f, 0.70f, 1.0f), hsvToRgb(0.33f, 0.80f, 1.0f),
        hsvToRgb(0.38f, 0.85f, 0.95f), hsvToRgb(0.44f, 0.80f, 0.95f))),
    ColorScheme.LAVENDER to Palette(listOf(
        hsvToRgb(0.72f, 0.55f, 1.0f), hsvToRgb(0.77f, 0.65f, 1.0f),
        hsvToRgb(0.83f, 0.60f, 1.0f), hsvToRgb(0.90f, 0.55f, 1.0f))),
    ColorScheme.EMBER to Palette(listOf(
        hsvToRgb(0.99f, 0.90f, 1.0f), hsvToRgb(0.02f, 0.90f, 1.0f), hsvToRgb(0.05f, 0.90f, 1.0f),
        hsvToRgb(0.09f, 0.85f, 1.0f), hsvToRgb(0.12f, 0.80f, 1.0f))),
    ColorScheme.AURORA to Palette(listOf(
        hsvToRgb(0.45f, 0.80f, 1.0f), hsvToRgb(0.36f, 0.75f, 1.0f), hsvToRgb(0.55f, 0.80f, 1.0f),
        hsvToRgb(0.72f, 0.75f, 1.0f), hsvToRgb(0.88f, 0.65f, 1.0f))),
    ColorScheme.RAINBOW to Palette(listOf(
        hsvToRgb(0.00f, 0.90f, 1.0f), hsvToRgb(0.083f, 0.90f, 1.0f), hsvToRgb(0.167f, 0.90f, 1.0f),
        hsvToRgb(0.333f, 0.85f, 1.0f), hsvToRgb(0.500f, 0.85f, 1.0f),
        hsvToRgb(0.667f, 0.85f, 1.0f), hsvToRgb(0.833f, 0.85f, 1.0f))),
    ColorScheme.TROPICAL to Palette(listOf(
        hsvToRgb(0.92f, 0.80f, 1.0f), hsvToRgb(0.85f, 0.72f, 1.0f), hsvToRgb(0.98f, 0.80f, 1.0f),
        hsvToRgb(0.04f, 0.82f, 1.0f), hsvToRgb(0.10f, 0.70f, 1.0f))),
    ColorScheme.SAVANNA to Palette(listOf(
        hsvToRgb(0.01f, 0.85f, 1.0f), hsvToRgb(0.05f, 0.85f, 1.0f), hsvToRgb(0.08f, 0.85f, 1.0f),
        hsvToRgb(0.11f, 0.80f, 1.0f), hsvToRgb(0.13f, 0.68f, 1.0f))),
    ColorScheme.BLOSSOM to Palette(listOf(
        hsvToRgb(0.95f, 0.42f, 1.0f), hsvToRgb(0.04f, 0.38f, 1.0f), hsvToRgb(0.13f, 0.33f, 1.0f),
        hsvToRgb(0.78f, 0.34f, 1.0f), hsvToRgb(0.55f, 0.28f, 1.0f))),
    ColorScheme.HONOLULU to Palette(listOf(
        hsvToRgb(0.95f, 0.85f, 1.0f), hsvToRgb(0.04f, 0.85f, 1.0f),
        hsvToRgb(0.80f, 0.80f, 1.0f), hsvToRgb(0.50f, 0.80f, 1.0f))),
    ColorScheme.GALAXY to Palette(listOf(
        hsvToRgb(0.66f, 0.90f, 1.0f), hsvToRgb(0.72f, 0.85f, 1.0f),
        hsvToRgb(0.78f, 0.85f, 1.0f), hsvToRgb(0.86f, 0.78f, 1.0f))),
    ColorScheme.NEON to Palette(listOf(
        hsvToRgb(0.505f, 0.94f, 0.99f), hsvToRgb(0.845f, 0.82f, 1.0f),
        hsvToRgb(0.32f, 0.92f, 1.0f), hsvToRgb(0.945f, 0.82f, 1.0f))),
    ColorScheme.PEACOCK to Palette(listOf(
        hsvToRgb(0.515f, 0.87f, 0.90f), hsvToRgb(0.60f, 0.88f, 1.0f),
        hsvToRgb(0.44f, 0.90f, 0.85f), hsvToRgb(0.12f, 0.80f, 1.0f))),
    ColorScheme.CITRUS to Palette(listOf(
        hsvToRgb(0.15f, 0.76f, 1.0f), hsvToRgb(0.24f, 0.80f, 0.92f),
        hsvToRgb(0.09f, 0.89f, 1.0f), hsvToRgb(0.005f, 0.64f, 1.0f))),
    ColorScheme.ROSEGOLD to Palette(listOf(
        hsvToRgb(0.04f, 0.24f, 1.0f), hsvToRgb(0.02f, 0.45f, 1.0f),
        hsvToRgb(0.04f, 0.58f, 0.88f), hsvToRgb(0.12f, 0.62f, 0.96f))),
)

/** Widened from `private` by the split: the engine's own default reads it. */
internal val FALLBACK_SCHEME = ColorScheme.SUNSET

fun getPalette(scheme: ColorScheme): Palette =
    if (scheme in setOf(ColorScheme.ALBUM_ART, ColorScheme.ALBUM_ART_V2, ColorScheme.SONG))
        STATIC_SCHEMES[FALLBACK_SCHEME]!!
    else
        STATIC_SCHEMES[scheme] ?: STATIC_SCHEMES[FALLBACK_SCHEME]!!
