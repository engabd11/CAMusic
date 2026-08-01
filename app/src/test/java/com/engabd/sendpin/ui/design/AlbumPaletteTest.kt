package com.engabd.sendpin.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * The extraction, on synthetic covers with known answers.
 *
 * These pin the two failures behind "most albums fall back to gold": a multi-coloured
 * cover whose colours were averaged into grey and then re-tinted warm, and a genuinely
 * colourless cover being handed a hue it never had. `kmeansPalette` is pure Kotlin, so
 * it runs off-device; the lift on top of it uses `androidx.core.graphics.ColorUtils`
 * and is not covered here.
 */
class AlbumPaletteTest {

    private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

    /** [n] pixels of each colour, interleaved so no ordering assumption can help. */
    private fun cover(vararg colours: FloatArray, each: Int = 400): List<FloatArray> =
        (0 until each).flatMap { colours.toList() }

    private fun hueOf(c: FloatArray): Float {
        val mx = maxOf(c[0], c[1], c[2]); val mn = min(min(c[0], c[1]), c[2])
        val d = mx - mn
        if (d == 0f) return 0f
        var h = when (mx) {
            c[0] -> 60f * (((c[1] - c[2]) / d) % 6f)
            c[1] -> 60f * (((c[2] - c[0]) / d) + 2f)
            else -> 60f * (((c[0] - c[1]) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return h
    }

    private fun satOf(c: FloatArray): Float {
        val mx = maxOf(c[0], c[1], c[2]); val mn = min(min(c[0], c[1]), c[2])
        return if (mx <= 1e-6f) 0f else (mx - mn) / mx
    }

    private fun hueDist(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    @Test
    fun `a three-colour cover yields three distinct hues`() {
        val red = rgb(200, 40, 40)
        val green = rgb(40, 180, 60)
        val blue = rgb(50, 70, 210)
        val out = kmeansPalette(cover(red, green, blue))!!

        assertFalse("a cover with three strong colours is not achromatic", out.achromatic)
        val hues = out.swatches.map { hueOf(it) }
        assertTrue("expected at least three swatches, got ${out.swatches.size}", out.swatches.size >= 3)
        // Each source hue must be represented.
        for (source in listOf(red, green, blue)) {
            val h = hueOf(source)
            assertTrue(
                "no swatch near hue $h; got $hues",
                hues.any { hueDist(it, h) < 30f },
            )
        }
    }

    @Test
    fun `a colourful cover never leads with the amber fallback`() {
        // Teal and magenta: nowhere near the 30-50 degree amber band the old
        // neutral path collapsed to.
        val out = kmeansPalette(cover(rgb(20, 170, 170), rgb(190, 40, 160)))!!
        val leadHue = hueOf(out.lead)
        assertFalse(out.achromatic)
        assertTrue(
            "lead hue $leadHue landed in the amber band the gold fallback produces",
            hueDist(leadHue, 40f) > 25f,
        )
    }

    @Test
    fun `a small vivid splash on a dull field still wins the lead`() {
        // 95% muted olive-grey, 5% vivid orange — the cover "is" the splash.
        val field = List(3_800) { rgb(96, 98, 88) }
        val splash = List(200) { rgb(240, 110, 20) }
        val out = kmeansPalette(field + splash)!!
        assertTrue("expected the vivid splash to lead", satOf(out.lead) > 0.5f)
        assertTrue(hueDist(hueOf(out.lead), hueOf(rgb(240, 110, 20))) < 30f)
    }

    @Test
    fun `a monochrome cover is reported achromatic and stays unsaturated`() {
        val greys = (0 until 3_000).map { i ->
            val v = 0.15f + (i % 200) / 260f
            floatArrayOf(v, v, v)
        }
        val out = kmeansPalette(greys)!!
        assertTrue("a grey cover must be flagged achromatic", out.achromatic)
        assertTrue(
            "lead saturation ${satOf(out.lead)} — a grey cover must not be given a hue",
            satOf(out.lead) < 0.25f,
        )
    }

    @Test
    fun `a warm sepia cover does not become vivid gold`() {
        // Low-chroma warm tones: the exact input the old warm-white tint plus the
        // 0.5 saturation floor turned into amber.
        val sepia = (0 until 3_000).map { i ->
            val v = 0.25f + (i % 150) / 320f
            floatArrayOf(v * 1.06f, v, v * 0.93f)
        }
        val out = kmeansPalette(sepia)!!
        assertTrue(
            "sepia lead saturation ${satOf(out.lead)} should stay muted, not be forced to gold",
            satOf(out.lead) < 0.35f,
        )
    }

    @Test
    fun `extraction is deterministic for the same cover`() {
        val pixels = cover(rgb(200, 40, 40), rgb(40, 180, 60), rgb(50, 70, 210))
        val a = kmeansPalette(pixels)!!
        val b = kmeansPalette(pixels)!!
        assertEquals(a.swatches.size, b.swatches.size)
        a.swatches.zip(b.swatches).forEach { (x, y) ->
            assertTrue("swatches differ between runs", x.contentEquals(y))
        }
        assertTrue("lead differs between runs", a.lead.contentEquals(b.lead))
    }

    @Test
    fun `an empty cover yields nothing rather than a default`() {
        assertEquals(null, kmeansPalette(emptyList()))
    }
}
