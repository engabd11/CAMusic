package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three corrections `ui/design/AlbumPalette.kt` made and `hue/AlbumColours.kt`
 * never received.
 *
 * [AlbumColoursTest] next door pins what the extraction is *for* — dominance,
 * pooling, greyscale neutrality, determinism — and all of it still holds. These are
 * the separate question of whether the clusters it ranks are the right clusters, and
 * each case is the shape of one failure that turns real colour into grey before
 * ranking ever gets a say:
 *
 * 1. **Lightness-seeded k-means** converges to brightness bands rather than colours,
 *    so a sleeve's red and its blue land in one mid-tone cluster whose mean is grey.
 * 2. **A plain cluster mean** averages a highlight with the shadow beside it, which
 *    is how a red becomes brown.
 * 3. **Uncapped neutral swatches** let several off-whites each take a slot — and,
 *    because v2 weights by population, most of the dwell time with them.
 */
class AlbumColoursSeedingTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A 64×64 cover made of [parts] as (fraction, colour). */
    private fun cover(vararg parts: Pair<Float, Int>): IntArray {
        val n = 64 * 64
        val px = IntArray(n)
        var at = 0
        for ((frac, colour) in parts) {
            repeat((frac * n).toInt()) { if (at < n) px[at++] = colour }
        }
        while (at < n) px[at++] = parts.last().second
        return px
    }

    private fun satOf(c: Rgb): Float {
        val mx = max(c.first, max(c.second, c.third))
        val mn = min(c.first, min(c.second, c.third))
        return if (mx > 1e-6f) (mx - mn) / mx else 0f
    }

    /**
     * Whether a swatch came out of the neutral branch.
     *
     * Not a saturation test, which is the trap: a near-neutral cluster is rendered as
     * a *tinted white* — a bulb cannot show "silver", but a dim cool white reads as
     * silver — and those tints sit around 0.4 saturation by construction. Checking the
     * level would call every one of them a colour, and quietly pass a cap test that
     * had counted nothing.
     *
     * The tints are three fixed ratios scaled by the cluster's value, so normalising
     * by the brightest channel and comparing against them identifies one exactly.
     */
    private fun isTintedWhite(c: Rgb): Boolean {
        val mx = max(c.first, max(c.second, c.third))
        if (mx <= 1e-6f) return true
        val r = c.first / mx
        val g = c.second / mx
        val b = c.third / mx
        val tints = listOf(
            Triple(1.0f, 0.84f, 0.60f),   // WARM_WHITE
            Triple(0.78f, 0.86f, 1.0f),   // COOL_WHITE
            Triple(1.0f, 0.92f, 0.82f),   // PLAIN_WHITE
        )
        return tints.any { (tr, tg, tb) ->
            abs(r - tr) < 0.02f && abs(g - tg) < 0.02f && abs(b - tb) < 0.02f
        }
    }

    private fun hueOf(c: Rgb): Float {
        val mx = max(c.first, max(c.second, c.third))
        val mn = min(c.first, min(c.second, c.third))
        val d = mx - mn
        if (d < 1e-6f) return -1f
        val h = when (mx) {
            c.first -> ((c.second - c.third) / d) % 6f
            c.second -> (c.third - c.first) / d + 2f
            else -> (c.first - c.second) / d + 4f
        } / 6f
        return if (h < 0f) h + 1f else h
    }

    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 1f
        return min(d, 1f - d)
    }

    @Test
    fun `two hues of the same brightness stay two hues`() {
        // The lightness-seeding failure in its purest form. These two are deliberately
        // close in luma and far apart in hue, which is exactly the pair a
        // brightness-banded clustering merges into a grey mid-tone.
        val out = extractAlbumColours(cover(0.5f to argb(190, 40, 40), 0.5f to argb(40, 70, 190)))
        assertNotNull(out)
        val hues = out.colors.filter { satOf(it) > 0.25f }.map { hueOf(it) }
        assertTrue(hues.size >= 2, "one or both hues were lost entirely: ${out.colors}")
        assertTrue(
            hues.any { h -> hues.any { hueGap(it, h) > 0.15f } },
            "the red and the blue were merged: $hues",
        )
    }

    @Test
    fun `a colour lit and shadowed does not average to brown`() {
        // A single hue at three exposures, as any real photographed sleeve has. A plain
        // cluster mean pulls toward the middle of that range and desaturates; averaging
        // the most chromatic quarter does not.
        val out = extractAlbumColours(
            cover(
                0.34f to argb(210, 60, 40),
                0.33f to argb(140, 40, 26),
                0.33f to argb(80, 22, 15),
            ),
        )
        assertNotNull(out)
        val best = out.colors.maxByOrNull { satOf(it) }!!
        assertTrue(satOf(best) > 0.55f, "the sleeve's red came back muddy: $best (sat ${satOf(best)})")
    }

    @Test
    fun `several off-whites take one slot between them, not three`() {
        // Off-whites are high-population almost by definition — paper, sky, a studio
        // backdrop. Uncapped they crowd out the colour and, being heavy, hold the room
        // pale for most of the cycle.
        val out = extractAlbumColours(
            cover(
                0.25f to argb(250, 250, 248),
                0.25f to argb(244, 246, 250),
                0.24f to argb(252, 248, 243),
                0.26f to argb(215, 45, 95),
            ),
        )
        assertNotNull(out)
        val neutrals = out.colors.count { isTintedWhite(it) }
        assertTrue(neutrals == 1, "kept $neutrals neutral swatches, expected exactly 1: ${out.colors}")
        assertTrue(
            out.colors.any { !isTintedWhite(it) && satOf(it) > 0.3f },
            "the one real colour on the sleeve was outvoted: ${out.colors}",
        )
    }

    @Test
    fun `folding the extra neutrals in keeps the weighting honest`() {
        // Capping must not *discard* those pixels — three quarters of this cover is
        // off-white, and a palette that says otherwise is lying about the sleeve.
        val out = extractAlbumColours(
            cover(
                0.25f to argb(250, 250, 248),
                0.25f to argb(244, 246, 250),
                0.24f to argb(252, 248, 243),
                0.26f to argb(215, 45, 95),
            ),
        )
        assertNotNull(out)
        assertTrue(abs(out.weights.sum() - 1f) < 0.02f, "weights summed to ${out.weights.sum()}")
        val neutralWeight = out.colors.indices
            .filter { isTintedWhite(out.colors[it]) }
            .sumOf { out.weights[it].toDouble() }
        assertTrue(neutralWeight > 0.5f, "the merged neutrals lost their share: $neutralWeight")
    }

    @Test
    fun `v1 does not spend both theme bases on white either`() {
        val out = extractAlbumColoursV1(
            cover(
                0.4f to argb(246, 246, 244),
                0.35f to argb(238, 240, 245),
                0.25f to argb(230, 120, 30),
            ),
        )
        assertNotNull(out)
        assertTrue(out.colors.count { isTintedWhite(it) } <= 1, "v1 kept two whites: ${out.colors}")
    }
}
