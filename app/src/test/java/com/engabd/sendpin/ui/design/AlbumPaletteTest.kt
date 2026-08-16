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

    // ─── the lift ──────────────────────────────────────────────────────────
    //
    // Testable at all only since it moved to OKLCh: it used to go through
    // androidx.core.graphics.ColorUtils, which needs Android.

    private fun lchOf(c: androidx.compose.ui.graphics.Color): FloatArray =
        oklabToOklch(rgbToOklab(c.red, c.green, c.blue))

    private fun extractionOf(vararg colours: FloatArray, achromatic: Boolean = false) =
        Extraction(lead = colours[0], swatches = colours.toList(), achromatic = achromatic)

    @Test
    fun `the accent stays inside the legible band`() {
        // The band is the *only* thing the lift is allowed to impose on lightness.
        // This replaces an assertion that every hue arrives at exactly 0.78, which
        // was the previous contract and turned out to be the bug: sRGB's red lives
        // at L 0.63 and its blue at L 0.45, so one target for all of them asked both
        // to be lighter than they can be — and, because the gamut narrows away from
        // a hue's own peak, cut their chroma on the way. Red arrived as light red
        // and violet as faded violet.
        val hues = listOf(
            rgb(200, 30, 30),    // red
            rgb(30, 200, 30),    // green
            rgb(30, 30, 200),    // blue
            rgb(200, 200, 30),   // yellow
            rgb(150, 30, 200),   // violet
            rgb(30, 190, 200),   // cyan
        )
        hues.forEach { source ->
            val l = lchOf(liftedPalette(extractionOf(source)).accent)[0]
            assertTrue("accent lightness $l fell out of the legible band", l in 0.54f..0.89f)
        }
    }

    @Test
    fun `a saturated red is not lightened into pink`() {
        // The reported symptom, as a test: "what's red seems like light red".
        // sRGB red sits at OKLab L 0.63, so an accent that comes back much lighter
        // than its source has been pushed there by the lift rather than read off the
        // cover.
        val source = rgb(220, 30, 30)
        val sourceL = lchOf(androidx.compose.ui.graphics.Color(source[0], source[1], source[2]))[0]
        val accent = lchOf(liftedPalette(extractionOf(source)).accent)
        assertTrue(
            "accent lightness ${accent[0]} drifted far above the cover's own $sourceL",
            accent[0] - sourceL < 0.08f,
        )
    }

    @Test
    fun `keeping the cover's lightness keeps more of its chroma`() {
        // The two halves compound. A red held at its own lightness can hold roughly
        // twice the chroma sRGB allows at 0.78, so the fix for "faded" is the same
        // change as the fix for "too light" rather than a second one on top of it.
        val hue = lchOf(androidx.compose.ui.graphics.Color(220 / 255f, 30 / 255f, 30 / 255f))[2]
        assertTrue(
            "red should hold more chroma at its own lightness than at the old fixed target",
            maxChromaFor(0.63f, hue) > maxChromaFor(0.78f, hue) * 1.5f,
        )
    }

    @Test
    fun `white does not out-vote a colour it is a minority of`() {
        // "Whites sometimes overwhelm the colours of the album art, even if the
        // amount of white in the album is a lot less than other colours."
        //
        // The cause was an asymmetry rather than a weighting: accent clusters are
        // deduplicated by hue, so several shades of one red collapse into a single
        // swatch, and nothing did the same for neutrals — so several shades of
        // off-white each kept a slot. A cover that is mostly red could come back as
        // one red and three whites, and the whites are the brightest things in the
        // set.
        val reds = listOf(rgb(200, 40, 40), rgb(170, 30, 30), rgb(230, 70, 60), rgb(140, 25, 25))
        val whites = listOf(rgb(250, 250, 250), rgb(238, 238, 240), rgb(228, 230, 228))
        val pixels = (0 until 700).flatMap { reds } + (0 until 200).flatMap { whites }
        val out = kmeansPalette(pixels)!!

        val neutrals = out.swatches.count { satOf(it) < 0.06f }
        assertTrue("got $neutrals colourless swatches out of ${out.swatches.size}", neutrals <= 1)
        assertTrue(
            "the lead should be the red the cover is mostly made of, not a white",
            satOf(out.lead) > 0.3f,
        )
    }

    @Test
    fun `the cover's hue survives the lift`() {
        // Hue is what extraction is most confident about, so it is the one thing the
        // lift must never trade away — including when gamut mapping has to give
        // something up, which is why that reduces chroma rather than clipping RGB.
        listOf(rgb(200, 30, 30), rgb(30, 30, 200), rgb(150, 30, 200), rgb(30, 190, 200))
            .forEach { source ->
                val want = oklabToOklch(rgbToOklab(source[0], source[1], source[2]))[2]
                val got = lchOf(liftedPalette(extractionOf(source)).accent)[2]
                assertTrue(
                    "hue moved from $want to $got",
                    hueDist(want, got) < 2f,
                )
            }
    }

    @Test
    fun `a muted cover stays muted and a vivid one stays vivid`() {
        // The old lift clamped saturation into 0.50..0.72, so a watercolour sleeve and
        // a neon one arrived at nearly the same intensity — most of why the palette
        // felt limited. Chroma is now the cover's own, floored but never capped below
        // the gamut boundary.
        val vivid = lchOf(liftedPalette(extractionOf(rgb(0, 90, 255))).accent)[1]
        val muted = lchOf(liftedPalette(extractionOf(rgb(96, 120, 150))).accent)[1]
        assertTrue(
            "a vivid cover ($vivid) should out-colour a muted one ($muted) by a clear margin",
            vivid > muted * 1.5f,
        )
    }

    @Test
    fun `a grey cover is lifted without being given a hue`() {
        // The other half of the old "everything falls back to gold" problem: a chroma
        // floor applied blindly turns a warm grey into amber.
        val accent = liftedPalette(extractionOf(rgb(130, 130, 130), achromatic = true)).accent
        val lch = lchOf(accent)
        assertTrue("a grey cover gained chroma ${lch[1]}", lch[1] <= 0.03f)
        assertTrue("a grey cover should still land in the legible band", lch[0] in 0.54f..0.89f)
    }

    @Test
    fun `gamut mapping gives up chroma rather than hue`() {
        // Asking for more chroma than sRGB can hold at this lightness. Clipping the
        // channels instead — the naive coerceIn(0f, 1f) — moves them by different
        // amounts and swings the hue: saturated blue drifts toward violet.
        val hue = 264f
        val rgb = oklchToRgbInGamut(l = 0.78f, c = 0.35f, hDeg = hue)
        val got = oklabToOklch(rgbToOklab(rgb[0], rgb[1], rgb[2]))
        assertTrue("hue drifted to ${got[2]} under gamut mapping", hueDist(hue, got[2]) < 2f)
        assertEquals("lightness should be held too", 0.78f, got[0], 0.02f)
        assertTrue("chroma should have been reduced to fit", got[1] < 0.35f)
    }

    @Test
    fun `companions sit below the accent so the lead still leads`() {
        val palette = liftedPalette(extractionOf(rgb(0, 90, 255), rgb(220, 60, 40), rgb(40, 200, 120)))
        val accentL = lchOf(palette.accent)[0]
        palette.swatches.drop(1).forEach {
            assertTrue("a companion outshone the lead", lchOf(it)[0] < accentL)
        }
    }
}
