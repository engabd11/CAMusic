package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Colour: the wire conversion, and the palette's sense of proportion.
 */
class ColourTest {

    // ── RGB → xy ──────────────────────────────────────────────────────────

    /**
     * Chromaticity computed straight from the spec's own formulas, as a
     * reference the implementation has to agree with.
     */
    private fun referenceXy(r: Float, g: Float, b: Float): Pair<Float, Float> {
        fun gam(c: Float) = if (c > 0.04045f) Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() else c / 12.92f
        val rr = gam(r); val gg = gam(g); val bb = gam(b)
        val x = rr * 0.4124f + gg * 0.3576f + bb * 0.1805f
        val y = rr * 0.2126f + gg * 0.7152f + bb * 0.0722f
        val z = rr * 0.0193f + gg * 0.1192f + bb * 0.9505f
        val t = x + y + z
        return (x / t) to (y / t)
    }

    private fun inTriangle(x: Float, y: Float, g: List<Pair<Float, Float>>): Boolean {
        fun cross(o: Pair<Float, Float>, q: Pair<Float, Float>, p: Pair<Float, Float>) =
            (q.first - o.first) * (p.second - o.second) - (q.second - o.second) * (p.first - o.first)
        val p = x to y
        val d1 = cross(g[0], g[1], p)
        val d2 = cross(g[1], g[2], p)
        val d3 = cross(g[2], g[0], p)
        val neg = d1 < -1e-5f || d2 < -1e-5f || d3 < -1e-5f
        val pos = d1 > 1e-5f || d2 > 1e-5f || d3 > 1e-5f
        return !(neg && pos)
    }

    @Test
    fun `conversion matches the philips sRGB D65 formula`() {
        // The matrix was the legacy "Wide-RGB D65" one from the 2013 docs, which
        // the current Colour Conversion page has superseded with the standard
        // sRGB transform. They disagree most on saturated greens and blues.
        // White is the sharpest check: it must land on the D65 point.
        val (wx, wy) = rgbToXy(1f, 1f, 1f, GAMUT_C)
        assertTrue(
            abs(wx - 0.3127f) < 0.002f && abs(wy - 0.3290f) < 0.002f,
            "white should sit at the D65 point (0.3127, 0.3290), got ($wx, $wy)",
        )
    }

    @Test
    fun `unclamped colours match the reference formula`() {
        // Anything already inside Gamut C passes the clamp untouched, so the
        // conversion itself can be compared directly.
        for (c in listOf(
            Triple(1f, 1f, 1f),
            Triple(0.8f, 0.6f, 0.4f),
            Triple(0.5f, 0.5f, 0.6f),
            Triple(0.9f, 0.85f, 0.7f),
        )) {
            val (x, y) = rgbToXy(c.first, c.second, c.third, GAMUT_C)
            val (rx, ry) = referenceXy(c.first, c.second, c.third)
            if (!inTriangle(rx, ry, GAMUT_C)) continue  // clamped, not comparable
            assertTrue(
                abs(x - rx) < 1e-3f && abs(y - ry) < 1e-3f,
                "$c converted to ($x, $y), spec says ($rx, $ry)",
            )
        }
    }

    @Test
    fun `every colour lands inside the target gamut`() {
        val rnd = java.util.Random(3)
        for (i in 0 until 2000) {
            val (x, y) = rgbToXy(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), GAMUT_C)
            assertTrue(inTriangle(x, y, GAMUT_C), "($x, $y) fell outside Gamut C")
        }
    }

    @Test
    fun `a narrower per-light gamut is respected`() {
        // Gamut A — the old LivingColors triangle — is much smaller. Clamping to
        // C and sending that to an A lamp asks for a colour it cannot make, and
        // the bridge then snaps it somewhere unpredictable.
        val gamutA = listOf(0.704f to 0.296f, 0.2151f to 0.7106f, 0.138f to 0.08f)
        val rnd = java.util.Random(9)
        for (i in 0 until 1000) {
            val (x, y) = rgbToXy(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), gamutA)
            assertTrue(inTriangle(x, y, gamutA), "($x, $y) fell outside Gamut A")
        }
        // The two gamuts have to produce different answers where they actually
        // differ, or the per-light plumbing is doing nothing. sRGB's primaries
        // all land inside both triangles, so the difference only shows on a
        // colour one of them cannot reach: a narrow triangle around white must
        // pull a saturated colour a long way in.
        val narrow = listOf(0.40f to 0.35f, 0.28f to 0.38f, 0.30f to 0.26f)
        val wideGreen = rgbToXy(0f, 1f, 0f, GAMUT_C)
        val narrowGreen = rgbToXy(0f, 1f, 0f, narrow)
        assertTrue(inTriangle(narrowGreen.first, narrowGreen.second, narrow), "clamp missed the narrow gamut")
        assertTrue(
            wideGreen != narrowGreen,
            "a saturated green converted identically for a wide and a narrow gamut",
        )
    }

    @Test
    fun `black converts without dividing by zero`() {
        val (x, y) = rgbToXy(0f, 0f, 0f, GAMUT_C)
        assertTrue(!x.isNaN() && !y.isNaN(), "black produced NaN")
    }

    // ── Weighted palettes ─────────────────────────────────────────────────

    @Test
    fun `a weighted palette holds each colour for its share`() {
        // The point of album-art v2: a cover that is 90% green and 10% red
        // should spend 90% of the cycle green, not half of it. Sampled evenly
        // over a full cycle, the dwell should follow the weights.
        val green = Triple(0f, 1f, 0f)
        val red = Triple(1f, 0f, 0f)
        val palette = Palette(listOf(green, red), listOf(0.9f, 0.1f))

        var greenish = 0
        val n = 1000
        for (i in 0 until n) {
            val c = palette.sample(i / n.toFloat())
            if (c.second > c.first) greenish++
        }
        val share = greenish / n.toFloat()
        assertTrue(share > 0.8f, "green held only $share of the cycle, expected ~0.9")
    }

    @Test
    fun `an unweighted palette spreads evenly`() {
        val palette = Palette(listOf(Triple(0f, 1f, 0f), Triple(1f, 0f, 0f)))
        var greenish = 0
        val n = 1000
        for (i in 0 until n) {
            val c = palette.sample(i / n.toFloat())
            if (c.second > c.first) greenish++
        }
        val share = greenish / n.toFloat()
        assertTrue(abs(share - 0.5f) < 0.05f, "even palette gave green $share of the cycle")
    }

    @Test
    fun `a weighted palette crossfades rather than snapping`() {
        // Holding a colour is the point, but the boundary still has to be a fade
        // or the room hard-cuts between colours.
        val palette = Palette(listOf(Triple(0f, 1f, 0f), Triple(1f, 0f, 0f)), listOf(0.5f, 0.5f))
        var mixed = 0
        val n = 2000
        for (i in 0 until n) {
            val c = palette.sample(i / n.toFloat())
            if (c.first > 0.05f && c.second > 0.05f) mixed++
        }
        assertTrue(mixed > 0, "the weighted palette snapped between colours with no crossfade")
        assertTrue(mixed < n / 4, "the crossfade swallowed the hold — $mixed of $n frames were mixed")
    }

    @Test
    fun `malformed weights fall back to even spacing`() {
        // A mismatched or empty weight list must not crash or produce a
        // degenerate palette.
        val colors = listOf(Triple(0f, 1f, 0f), Triple(1f, 0f, 0f), Triple(0f, 0f, 1f))
        for (w in listOf(null, emptyList(), listOf(1f), listOf(0f, 0f, 0f))) {
            val p = Palette(colors, w)
            for (i in 0 until 100) {
                val c = p.sample(i / 100f)
                assertTrue(
                    c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f,
                    "weights $w produced $c",
                )
            }
        }
    }

    @Test
    fun `sampling wraps cleanly in both directions`() {
        val p = Palette(listOf(Triple(1f, 0f, 0f), Triple(0f, 1f, 0f)), listOf(0.7f, 0.3f))
        assertEquals(p.sample(0.25f), p.sample(1.25f))
        assertEquals(p.sample(0.25f), p.sample(-0.75f))
    }

    @Test
    fun `a single colour palette is stable`() {
        val p = Palette(listOf(Triple(0.2f, 0.4f, 0.6f)), listOf(1f))
        for (i in 0 until 20) assertEquals(Triple(0.2f, 0.4f, 0.6f), p.sample(i / 20f))
    }
}
