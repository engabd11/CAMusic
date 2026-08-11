package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Album colours have to describe the cover, not the most eye-catching thing on it.
 *
 * This is the difference between the two extractions in this app. The UI one
 * ranks candidates by `chroma * sqrt(population)` so a small vivid splash beats a
 * large flat field — right for choosing an accent that reads against black, wrong
 * for lighting a room, and the reason the lights showed colours the sleeve barely
 * contains. This one ranks purely by how much of the cover a colour occupies,
 * because the weights are dwell time.
 *
 * Ported from syncoV2 `color/album_art.py::_kmeans_palette_v2`.
 */
class AlbumColoursTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A 64×64 cover made of [parts] as (fraction, colour) — fractions summing to 1. */
    private fun cover(vararg parts: Pair<Float, Int>): IntArray {
        val n = 64 * 64
        val px = IntArray(n)
        var at = 0
        for ((frac, colour) in parts) {
            val count = (frac * n).toInt()
            for (i in 0 until count) {
                if (at >= n) break
                px[at++] = colour
            }
        }
        while (at < n) px[at++] = parts.last().second
        return px
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

    private fun isGreenish(c: Rgb) = c.second > c.first && c.second > c.third
    private fun isRedish(c: Rgb) = c.first > c.second && c.first > c.third

    @Test
    fun `a mostly-green cover comes back mostly green`() {
        // The headline property, and the reported defect. A vivid red detail on a
        // large green field must not take over the room.
        val px = cover(0.9f to argb(30, 180, 60), 0.1f to argb(230, 30, 30))
        val out = extractAlbumColours(px)!!
        val greenWeight = out.colors.indices
            .filter { isGreenish(out.colors[it]) }
            .sumOf { out.weights[it].toDouble() }
            .toFloat()
        assertTrue(greenWeight > 0.7f, "green held only $greenWeight of the palette, expected ~0.9")
    }

    @Test
    fun `a small vivid detail does not outrank a large flat field`() {
        // Exactly what the UI extraction is designed to do, and exactly what a
        // room must not do. A 5% neon splash on a muted field is a detail.
        val px = cover(0.95f to argb(70, 80, 110), 0.05f to argb(255, 0, 200))
        val out = extractAlbumColours(px)!!
        val heaviest = out.weights.indices.maxByOrNull { out.weights[it] }!!
        assertTrue(
            !isRedish(out.colors[heaviest]) || out.colors[heaviest].third < 0.5f,
            "the neon splash became the dominant colour: ${out.colors[heaviest]}",
        )
        assertTrue(out.weights[heaviest] > 0.5f, "no colour dominated a 95% field")
    }

    @Test
    fun `weights describe the whole cover`() {
        // Nothing is discarded: clusters beyond k fold their share into the
        // nearest pick, so the weights always sum to the entire image.
        val px = cover(
            0.4f to argb(200, 40, 40),
            0.3f to argb(40, 200, 40),
            0.2f to argb(40, 40, 200),
            0.1f to argb(220, 200, 40),
        )
        val out = extractAlbumColours(px)!!
        val sum = out.weights.sum()
        assertTrue(abs(sum - 1f) < 0.02f, "weights summed to $sum, expected 1")
        assertTrue(out.weights.all { it > 0f }, "a colour was given zero dwell")
    }

    @Test
    fun `near-duplicate hues are pooled rather than repeated`() {
        // Two shades of the same colour should be one entry holding both shares,
        // not two entries splitting the room's time between near-identical hues.
        val px = cover(0.5f to argb(30, 170, 60), 0.5f to argb(40, 190, 70))
        val out = extractAlbumColours(px)!!
        val greens = out.colors.count { isGreenish(it) }
        assertTrue(greens <= 1, "two shades of green produced $greens separate entries")
    }

    @Test
    fun `a greyscale cover stays neutral instead of inventing a hue`() {
        // The "everything falls back to gold" failure. A black-and-white sleeve
        // has no colour, and a bulb must not be told otherwise.
        val px = cover(0.5f to argb(40, 40, 40), 0.5f to argb(200, 200, 200))
        val out = extractAlbumColours(px)!!
        for (c in out.colors) {
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            assertTrue(mx - mn < 0.25f, "a greyscale cover produced a saturated colour: $c")
        }
    }

    @Test
    fun `extraction is deterministic`() {
        // Centroids are seeded along sorted lightness rather than randomly, so
        // the same sleeve lights the room the same way every time it plays.
        val px = cover(0.6f to argb(180, 60, 200), 0.4f to argb(40, 160, 190))
        val a = extractAlbumColours(px)!!
        val b = extractAlbumColours(px)!!
        assertTrue(a.colors == b.colors && a.weights == b.weights, "extraction varied between runs")
    }

    @Test
    fun `output is usable by the encoder`() {
        val px = cover(0.7f to argb(12, 90, 140), 0.3f to argb(240, 180, 20))
        val out = extractAlbumColours(px)!!
        assertTrue(out.colors.isNotEmpty(), "no colours extracted")
        assertTrue(out.colors.size == out.weights.size, "colours and weights disagree in length")
        for (c in out.colors) {
            assertTrue(
                c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f,
                "colour outside unit range: $c",
            )
        }
    }

    @Test
    fun `a weighted palette built from a cover holds its dominant colour`() {
        // End to end: extraction into the palette the engine actually samples.
        val px = cover(0.85f to argb(20, 120, 200), 0.15f to argb(230, 120, 20))
        val out = extractAlbumColours(px)!!
        val palette = Palette(out.colors, out.weights)
        var blueish = 0
        val n = 1000
        for (i in 0 until n) {
            val c = palette.sample(i / n.toFloat())
            if (c.third > c.first) blueish++
        }
        assertTrue(blueish.toFloat() / n > 0.6f, "the dominant colour held only ${blueish / n.toFloat()} of the cycle")
    }

    // ── v1 extraction (album_art / "even") ───────────────────────────────

    @Test
    fun `v1 extracts vivid accents from a colourful cover`() {
        // A bright red/blue cover: v1 should surface both as vivid accents,
        // not rank them by population (they're 50/50 anyway).
        val px = cover(0.5f to argb(255, 20, 20), 0.5f to argb(20, 20, 255))
        val out = extractAlbumColoursV1(px)!!
        assertTrue(out.colors.size >= 2, "expected at least 2 colours from a two-colour cover, got ${out.colors.size}")
        // Both should be vivid (saturation >= 0.5)
        for (c in out.colors) {
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
            assertTrue(sat >= 0.5f, "a vivid cover produced a desaturated colour: $c (sat=$sat)")
        }
    }

    @Test
    fun `v1 preserves dark values instead of forcing full brightness`() {
        // A uniformly dark vivid cover: v1 keeps the value, doesn't pump it to 1.
        val px = cover(1f to argb(100, 5, 10))  // dark deep red
        val out = extractAlbumColoursV1(px)!!
        for (c in out.colors) {
            val v = max(c.first, max(c.second, c.third))
            assertTrue(v <= 0.6f, "a dark cover produced a bright colour: $c (v=$v)")
        }
    }

    @Test
    fun `v1 includes muted theme bases on a dark-silver-gold cover`() {
        // The syncoV2 test_palette_extract scenario: deep purple, dark silver,
        // muted gold. v1 should keep the purple accent AND a base (silver/gold),
        // not just return one colour.
        val px = cover(
            0.4f to argb(90, 25, 128),    // deep purple, dark (v≈0.5)
            0.35f to argb(184, 186, 199),  // dark silver, near-neutral cool
            0.25f to argb(115, 89, 31),    // muted dark gold
        )
        val out = extractAlbumColoursV1(px)!!
        assertTrue(out.colors.size >= 2, "a three-tone cover produced only ${out.colors.size} colours")
        // A near-neutral base should be present as a tinted white (low saturation)
        val hasNeutral = out.colors.any { c ->
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            (mx - mn) < 0.25f
        }
        assertTrue(hasNeutral, "no neutral/tinted base in the palette for a silver-containing cover")
    }

    @Test
    fun `v1 monochrome cover stays neutral`() {
        val px = cover(0.5f to argb(40, 40, 40), 0.5f to argb(200, 200, 200))
        val out = extractAlbumColoursV1(px)!!
        for (c in out.colors) {
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            assertTrue(mx - mn < 0.25f, "a greyscale cover produced a saturated colour: $c")
        }
    }

    @Test
    fun `v1 is deterministic`() {
        val px = cover(0.6f to argb(180, 60, 200), 0.4f to argb(40, 160, 190))
        val a = extractAlbumColoursV1(px)!!
        val b = extractAlbumColoursV1(px)!!
        assertTrue(a.colors == b.colors, "v1 extraction varied between runs")
    }

    @Test
    fun `v1 vivid splash outranks large dull field`() {
        // The v1 vividness weighting: a 10% vivid accent should appear even
        // though 90% of the cover is a dull muted field. v1's
        // `pop × (0.25 + 0.75 × sat)` lets the splash through.
        val px = cover(0.9f to argb(70, 80, 110), 0.1f to argb(255, 0, 200))
        val out = extractAlbumColoursV1(px)!!
        // The vivid magenta should be in the output
        val hasVivid = out.colors.any { c ->
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
            sat > 0.6f
        }
        assertTrue(hasVivid, "a vivid splash was not surfaced by v1 extraction")
    }

    @Test
    fun `v1 colours are in unit range`() {
        val px = cover(0.7f to argb(12, 90, 140), 0.3f to argb(240, 180, 20))
        val out = extractAlbumColoursV1(px)!!
        for (c in out.colors) {
            assertTrue(
                c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f,
                "colour outside unit range: $c",
            )
        }
    }

    // ── syncoV2 parity ───────────────────────────────────────────────────

    /**
     * The expected values below are not hand-written: they are what syncoV2's
     * own `_kmeans_palette` and `_kmeans_palette_v2` return for these exact
     * covers. They exist because "close enough" is not the requirement here —
     * the two implementations drive the same lights from the same artwork and
     * are supposed to be indistinguishable.
     *
     * A qualitative assertion would not have caught what these do. The port
     * cubed the sRGB transfer function instead of raising it to 2.4, which
     * undershot every linear value by up to 57%, moved every CIELAB point, and
     * changed which clusters formed — yet still produced plausibly "vivid"
     * colours that a saturation check waves through.
     *
     * Tolerance is 1e-4: the reference runs in float64, this runs in float32.
     */
    private fun assertPalette(expected: List<Rgb>, actual: List<Rgb>, label: String) {
        assertEquals(expected.size, actual.size, "$label: wrong number of colours ($actual)")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertTrue(
                abs(e.first - a.first) < 1e-4f &&
                    abs(e.second - a.second) < 1e-4f &&
                    abs(e.third - a.third) < 1e-4f,
                "$label: colour $i is $a, syncoV2 gives $e",
            )
        }
    }

    private val redBlue get() = cover(0.5f to argb(255, 20, 20), 0.5f to argb(20, 20, 255))
    private val purpleSilverGold get() = cover(
        0.4f to argb(90, 25, 128),
        0.35f to argb(184, 186, 199),
        0.25f to argb(115, 89, 31),
    )
    private val vividSplash get() = cover(0.9f to argb(70, 80, 110), 0.1f to argb(255, 0, 200))

    @Test
    fun `v1 matches syncoV2 on a two-colour cover`() {
        assertPalette(
            listOf(
                Triple(1.0f, 0.078431f, 0.078431f),
                Triple(0.078431f, 0.078431f, 1.0f),
            ),
            extractAlbumColoursV1(redBlue)!!.colors,
            "v1/redBlue",
        )
    }

    @Test
    fun `v1 matches syncoV2 on a base-plus-accent cover`() {
        assertPalette(
            listOf(
                Triple(0.450980f, 0.349020f, 0.121569f),
                Triple(0.608706f, 0.671137f, 0.780392f),
                Triple(0.352941f, 0.098039f, 0.501961f),
            ),
            extractAlbumColoursV1(purpleSilverGold)!!.colors,
            "v1/purpleSilverGold",
        )
    }

    @Test
    fun `v1 matches syncoV2 on a vivid splash cover`() {
        assertPalette(
            listOf(
                Triple(0.274510f, 0.313725f, 0.431373f),
                Triple(1.0f, 0.0f, 0.784314f),
            ),
            extractAlbumColoursV1(vividSplash)!!.colors,
            "v1/vividSplash",
        )
    }

    @Test
    fun `v2 matches syncoV2 colours and weights`() {
        assertPalette(
            listOf(
                Triple(0.450980f, 0.349020f, 0.121569f),
                Triple(0.608706f, 0.671137f, 0.780392f),
                Triple(0.352941f, 0.098039f, 0.501961f),
            ),
            extractAlbumColours(purpleSilverGold)!!.colors,
            "v2/purpleSilverGold",
        )
        // syncoV2 normalises; this returns raw population shares that sum to 1,
        // and Palette normalises again on the way in. Same numbers either way.
        val w = extractAlbumColours(purpleSilverGold)!!.weights
        val expected = listOf(0.250244f, 0.349854f, 0.399902f)
        for (i in expected.indices) {
            assertTrue(
                abs(expected[i] - w[i]) < 1e-4f,
                "v2/purpleSilverGold: weight $i is ${w[i]}, syncoV2 gives ${expected[i]}",
            )
        }
    }

    @Test
    fun `v2 weights track the cover split as syncoV2 does`() {
        val out = extractAlbumColours(vividSplash)!!
        assertPalette(
            listOf(
                Triple(0.274510f, 0.313725f, 0.431373f),
                Triple(1.0f, 0.0f, 0.784314f),
            ),
            out.colors,
            "v2/vividSplash",
        )
        assertTrue(abs(out.weights[0] - 0.899902f) < 1e-4f, "expected 0.8999, got ${out.weights[0]}")
        assertTrue(abs(out.weights[1] - 0.100098f) < 1e-4f, "expected 0.1001, got ${out.weights[1]}")
    }

    @Test
    fun `colourless art falls back to syncoV2's neutral, both versions`() {
        // Below the luma floor everywhere, so both extractions take the shared
        // low-colour fallback. syncoV2 returns the NEUTRAL constant at full
        // value — not scaled by the cover's own darkness.
        val black = cover(1f to argb(0, 0, 0))
        val neutral = listOf(Triple(1.0f, 0.86f, 0.70f))
        assertPalette(neutral, extractAlbumColoursV1(black)!!.colors, "v1/black")
        assertPalette(neutral, extractAlbumColours(black)!!.colors, "v2/black")
    }
}
