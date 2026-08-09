package com.engabd.sendpin.hue

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
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
}
