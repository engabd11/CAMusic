package com.engabd.sendpin.ui.design

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.ui.theme.DefaultAccent
import com.engabd.sendpin.ui.theme.FallbackPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours a cover melts into: a bright [accent] plus ranked companion
 * [swatches] used for glows, gradients and avatars.
 *
 * Extraction uses **CIELAB k-means clustering** (ported from the syncoV2 Hue
 * Music Sync integration) instead of the old hue-binning approach. CIELAB
 * distance matches perceived colour difference, so similar hues group and
 * distinct ones separate correctly — a white album no longer produces green
 * blooms. The algorithm also separates **vivid accents** (small colourful
 * splashes) from **theme bases** (muted/dark/dominant swatches that set the
 * mood), and renders near-neutral swatches as tinted whites (warm/cool/neutral)
 * rather than inventing hues that aren't on the cover.
 */
@Immutable
data class AlbumPalette(
    val accent: Color = DefaultAccent,
    val swatches: List<Color> = FallbackPalette,
) {
    /** Companion swatch [i], wrapping around. */
    fun swatch(i: Int): Color = swatches[((i % swatches.size) + swatches.size) % swatches.size]
}

val LocalPalette = compositionLocalOf { AlbumPalette() }

private const val SAMPLE = 64      // decode artwork to 64×64 before clustering (matches syncoV2)

private const val CLUSTERS = 8           // k-means++ separates hues, so fewer are needed
private const val KMEANS_ITERS = 16

/**
 * Chroma (CIELAB `hypot(a, b)`) below which a colour is genuinely grey.
 *
 * Everything about the "gold everywhere" problem comes down to this line being drawn
 * in the wrong place, or in the wrong space. Saturation in HSV is not perceptual — a
 * dark navy and a pale cream can share it — so the old fixed `s >= 0.35` gate threw
 * real colours into the neutral bucket, where they were rendered as a *warm* white and
 * then had their saturation forced back up to 0.5, arriving as gold.
 */
private const val ACHROMATIC_C = 6f

/** A cluster needs at least this much chroma to be an accent, whatever the image. */
private const val MIN_ACCENT_C = 12f

/**
 * …and at least this fraction of the image's own strongest chroma. Adaptive because a
 * muted, tasteful sleeve has real colour that a fixed threshold calls grey, while a
 * neon one has so much that a fixed threshold calls everything an accent.
 */
private const val ACCENT_C_FRACTION = 0.42f

/**
 * The band the accent's lightness is allowed to land in.
 *
 * **The accent keeps the cover's own lightness, clamped into this band.** It used to
 * be pinned to a single perceptual value — one number for every hue, on the reasoning
 * that equal OKLab L is equal apparent brightness and so no hue is quietly favoured.
 * That reasoning is correct and the consequence was still wrong, because sRGB's hues
 * do not live at the same lightness:
 *
 * | Fully saturated sRGB | OKLab L |
 * |---|---|
 * | blue  `#0000FF` | 0.45 |
 * | red   `#FF0000` | 0.63 |
 * | green `#00FF00` | 0.87 |
 *
 * Pinning all three at 0.78 asks red to be much lighter than red can be and blue
 * much lighter still — so each arrives as a pale tint of itself. Worse, it takes
 * their chroma with it: the sRGB gamut narrows sharply as lightness moves away from
 * a hue's own peak, so the ceiling the chroma is capped at collapses at the same
 * time. Red came out light *and* washed; blue and violet came out worse. Green was
 * nearly unaffected, which is why greens looked right and everything else looked
 * faded — the same asymmetry the HSL lift had, arrived at from the opposite
 * direction.
 *
 * So the band is a legibility constraint, not a target. [ACCENT_L_MIN] is where a
 * colour still carries text and glows against true black; [ACCENT_L_MAX] is where
 * one starts reading as tinted white. Between them the cover decides, which is what
 * "an accurate representation of album art colour" has to mean.
 */
private const val ACCENT_L_MIN = 0.55f
private const val ACCENT_L_MAX = 0.88f

/**
 * How far a companion is held below the accent, so the lead still leads a gradient.
 *
 * Applied as a ceiling on an otherwise faithful lightness rather than as a second
 * fixed target: a companion darker than this is left where the cover put it.
 */
private const val COMPANION_L_HEADROOM = 0.06f

/** A companion may not fall below this, or it disappears into the page. */
private const val COMPANION_L_MIN = 0.38f

/**
 * Chroma floor, as a fraction of what sRGB can hold at that lightness and hue.
 *
 * Relative rather than absolute because the gamut is wildly uneven — sRGB reaches
 * far more chroma in yellow than in blue — so one absolute floor would over-drive
 * the hues that can reach it and leave the others looking washed. This is the only
 * push applied to chroma: there is no ceiling below the gamut boundary, so a vivid
 * cover stays vivid and a muted one stays muted.
 */
private const val MIN_ACCENT_C_FRACTION = 0.40f

/** OKLCh chroma below which a *swatch* is treated as grey, matching [ACHROMATIC_C]'s intent in OKLab's scale. */
private const val ACHROMATIC_OKLCH_C = 0.025f

/** How much chroma a neutral may keep — enough for a warm or cool cast, not a hue. */
private const val NEUTRAL_MAX_C = 0.02f

private const val VALUE_FLOOR = 0.15f    // keep even the darkest swatch faintly visible
private const val HUE_MIN_SEP_DEG = 22f  // reject near-duplicate accent hues
private const val MAX_ACCENTS = 4        // leave a slot for a populous muted tone

/**
 * How many genuinely colourless swatches the palette may contain.
 *
 * This is why white used to take over a cover it was a minority of. Accent clusters
 * are deduplicated by hue ([HUE_MIN_SEP_DEG]), so three shades of one red collapse
 * into a single swatch — and nothing did the same for neutrals, so three shades of
 * off-white each kept a slot of their own. A sleeve that was 60% red and 25% white
 * could therefore end up one red and three whites, and since the whites are the
 * brightest things in the set, the glows and gradients built from them read as a
 * white cover.
 *
 * One is enough to say "there is white here" without letting it outvote the colour.
 */
private const val MAX_NEUTRAL_SWATCHES = 1

/** Tinted whites for the genuinely-colourless case (from syncoV2 album_art.py). */
private val WARM_WHITE = floatArrayOf(1.0f, 0.93f, 0.86f)
private val COOL_WHITE = floatArrayOf(0.86f, 0.92f, 1.0f)
private val PLAIN_WHITE = floatArrayOf(0.96f, 0.96f, 0.96f)

// ─── Colour space conversions ────────────────────────────────────────────

/** sRGB (0..1) → CIELAB, vectorised over a flat [N×3] array. */
private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
    fun lin(c: Float) = if (c > 0.04045f) ((c + 0.055f) / 1.055f).let { it * it * it } else c / 12.92f
    val lr = lin(r); val lg = lin(g); val lb = lin(b)
    val x = (lr * 0.4124f + lg * 0.3576f + lb * 0.1805f) / 0.95047f
    val y = (lr * 0.2126f + lg * 0.7152f + lb * 0.0722f)
    val z = (lr * 0.0193f + lg * 0.1192f + lb * 0.9505f) / 1.08883f
    fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    val fx = f(x); val fy = f(y); val fz = f(z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

/** RGB → HSV, returns [h, s, v] with h in degrees (0..360). */
private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val d = mx - mn
    val v = mx
    val s = if (mx > 1e-6f) d / mx else 0f
    var h = 0f
    if (d != 0f) {
        h = when {
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
    }
    if (h < 0f) h += 360f
    return floatArrayOf(h, s, v)
}

private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return floatArrayOf(r + m, g + m, b + m)
}

// RGB → HSL and its inverse used to live here, for a lift that worked in HSL. Both
// went with it: OKLCh does the same job in a space where the numbers mean what they
// look like. Removing them also removes this file's last `androidx.core.graphics`
// call, which is what kept the lift off the unit-test suite.

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

// ─── OKLab / OKLCh ─────────────────────────────────────────────────────────
//
// Björn Ottosson's OKLab, in full, because the *lift* — turning an extracted
// colour into one the app can actually paint with — is where accuracy was being
// lost, not the extraction.
//
// The lift used to work in HSL, and HSL lightness is not perceptual. Hold it
// constant and hues do not stay equally bright: at the L = 0.64, S = 0.6 the old
// code targeted, a green lands at relative luminance 0.55 and a blue at 0.19 —
// the same number on the dial, nearly three times the light. That is the whole of
// "green covers look great and the rest look muddy". OKLab is built so that equal
// L *is* equal perceived lightness across hue, and equal C is equal colourfulness,
// which is exactly the guarantee the lift needs and the one HSL cannot give.
//
// Pure Kotlin, deliberately: it replaces `androidx.core.graphics.ColorUtils`, whose
// Android dependency is why `AlbumPaletteTest` says the lift "is not covered here".
// It is covered now.

private fun srgbToLinear(c: Float): Float =
    if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f

private fun linearToSrgb(c: Float): Float =
    if (c > 0.0031308f) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c

/** sRGB (0..1) → OKLab `[L, a, b]`. */
internal fun rgbToOklab(r: Float, g: Float, b: Float): FloatArray {
    val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
    val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
    val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
    val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

/** OKLab → sRGB (0..1), **unclamped** — out-of-gamut input returns out-of-range channels. */
internal fun oklabToRgbRaw(lab: FloatArray): FloatArray {
    val lp = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
    val mp = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
    val sp = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
    val l = lp * lp * lp; val m = mp * mp * mp; val s = sp * sp * sp
    return floatArrayOf(
        linearToSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
        linearToSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
        linearToSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s),
    )
}

/** OKLab `[L, a, b]` → OKLCh `[L, C, h°]`. */
internal fun oklabToOklch(lab: FloatArray): FloatArray {
    val c = kotlin.math.sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    var h = Math.toDegrees(kotlin.math.atan2(lab[2], lab[1]).toDouble()).toFloat()
    if (h < 0f) h += 360f
    return floatArrayOf(lab[0], c, h)
}

private fun oklchToOklab(l: Float, c: Float, hDeg: Float): FloatArray {
    val rad = Math.toRadians(hDeg.toDouble())
    return floatArrayOf(l, (c * kotlin.math.cos(rad)).toFloat(), (c * kotlin.math.sin(rad)).toFloat())
}

/**
 * OKLCh → the closest sRGB colour **of the same lightness and hue**.
 *
 * Chroma is what gives, by bisection, and nothing else does. Clipping the RGB
 * channels instead — which is what a naive `coerceIn(0f, 1f)` does, and what the
 * old path did implicitly — moves the channels by different amounts and so shifts
 * the hue: a saturated blue clips toward violet, a deep red toward orange. The
 * cover's hue is the one thing extraction is most confident about, so it is the
 * last thing that should be traded away. Lightness holds for the same reason the
 * lift exists at all: legibility against the page is a lightness property.
 *
 * 20 bisections resolve chroma far finer than an 8-bit channel can express, so the
 * result is exact as far as anything downstream can tell.
 */
internal fun oklchToRgbInGamut(l: Float, c: Float, hDeg: Float): FloatArray {
    fun inGamut(rgb: FloatArray) = rgb.all { it >= -1e-4f && it <= 1f + 1e-4f }
    val direct = oklabToRgbRaw(oklchToOklab(l, c, hDeg))
    if (inGamut(direct)) return direct.map { it.coerceIn(0f, 1f) }.toFloatArray()
    var lo = 0f
    var hi = c
    repeat(20) {
        val mid = (lo + hi) / 2f
        if (inGamut(oklabToRgbRaw(oklchToOklab(l, mid, hDeg)))) lo = mid else hi = mid
    }
    return oklabToRgbRaw(oklchToOklab(l, lo, hDeg)).map { it.coerceIn(0f, 1f) }.toFloatArray()
}

/** The most chroma sRGB can hold at this lightness and hue — the gamut boundary. */
internal fun maxChromaFor(l: Float, hDeg: Float): Float {
    var lo = 0f
    var hi = 0.4f   // beyond any sRGB colour in OKLab
    repeat(20) {
        val mid = (lo + hi) / 2f
        val rgb = oklabToRgbRaw(oklchToOklab(l, mid, hDeg))
        if (rgb.all { it >= -1e-4f && it <= 1f + 1e-4f }) lo = mid else hi = mid
    }
    return lo
}

private fun oklchColor(l: Float, c: Float, hDeg: Float): Color {
    val rgb = oklchToRgbInGamut(l, c, hDeg)
    return Color(rgb[0], rgb[1], rgb[2])
}

// ─── K-means in CIELAB ─────────────────────────────────────────────────────

private data class Cluster(
    /** The colour that *represents* this cluster — see [representative]. */
    val rgb: FloatArray,
    val hsv: FloatArray,
    /** The representative's CIELAB chroma: how colourful this cluster really is. */
    val chroma: Float,
    /** Fraction of the sampled pixels. */
    val population: Float,
)

/** CIELAB chroma — distance from the neutral axis, i.e. colourfulness. */
private fun chromaOf(lab: FloatArray): Float = kotlin.math.sqrt(lab[1] * lab[1] + lab[2] * lab[2])

/**
 * The colour that stands for a cluster: the mean of its **most chromatic quarter**,
 * not the mean of all of it.
 *
 * A plain mean is what washed multi-coloured covers out. Even with good clustering a
 * cluster spans a range, and averaging across it pulls toward grey — average a red
 * highlight with the shadow beside it and the answer is brown. Averaging only the
 * colourful quarter keeps a cluster that *has* a colour vivid, and leaves a cluster
 * that genuinely is grey exactly where it was.
 */
private fun representative(members: List<FloatArray>, labs: List<FloatArray>): FloatArray {
    if (members.size <= 3) {
        return floatArrayOf(
            members.map { it[0] }.average().toFloat(),
            members.map { it[1] }.average().toFloat(),
            members.map { it[2] }.average().toFloat(),
        )
    }
    val cutoff = labs.map { chromaOf(it) }.sorted()[(labs.size * 3) / 4]
    val top = members.indices.filter { chromaOf(labs[it]) >= cutoff }
    val pick = if (top.isEmpty()) members.indices.toList() else top
    var r = 0f; var g = 0f; var b = 0f
    for (i in pick) { r += members[i][0]; g += members[i][1]; b += members[i][2] }
    val n = pick.size.toFloat()
    return floatArrayOf(r / n, g / n, b / n)
}

/**
 * Deterministic k-means in CIELAB, seeded with **k-means++**.
 *
 * The seeding is the whole point. Centroids used to be spread along the lightness
 * axis alone, which makes k-means converge to brightness bands: a cover's red and its
 * blue land in the same "mid-tone" cluster, whose mean is grey. k-means++ picks each
 * seed with probability proportional to its squared distance from the seeds already
 * chosen, in full L/a/b — so distinct hues get their own clusters and survive to be
 * ranked. [rng] is seeded from the pixels, so a given cover always yields the same
 * palette.
 */
private fun kmeansClusters(pixels: List<FloatArray>, k: Int, rng: kotlin.random.Random): List<Cluster> {
    val n = pixels.size
    if (n == 0) return emptyList()
    val kk = min(k, n)

    val lab = pixels.map { rgbToLab(it[0], it[1], it[2]) }

    // ── k-means++ seeding ────────────────────────────────────────────────
    val centroids = ArrayList<FloatArray>(kk)
    centroids.add(lab[rng.nextInt(n)].copyOf())
    val best = FloatArray(n) { Float.MAX_VALUE }
    while (centroids.size < kk) {
        var total = 0.0
        val last = centroids.last()
        for (i in 0 until n) {
            val d = sqDist(lab[i], last)
            if (d < best[i]) best[i] = d
            total += best[i].toDouble()
        }
        if (total <= 0.0) break
        var target = rng.nextDouble() * total
        var chosen = n - 1
        for (i in 0 until n) {
            target -= best[i].toDouble()
            if (target <= 0.0) { chosen = i; break }
        }
        centroids.add(lab[chosen].copyOf())
    }

    // ── Lloyd iterations ─────────────────────────────────────────────────
    val kFinal = centroids.size
    val labels = IntArray(n)
    for (iter in 0 until KMEANS_ITERS) {
        var moved = false
        for (i in 0 until n) {
            var bestJ = 0; var bestD = Float.MAX_VALUE
            for (j in 0 until kFinal) {
                val d = sqDist(lab[i], centroids[j])
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (labels[i] != bestJ) { labels[i] = bestJ; moved = true }
        }
        if (!moved && iter > 0) break
        for (j in 0 until kFinal) {
            var cnt = 0; var sumL = 0f; var sumA = 0f; var sumB = 0f
            for (i in 0 until n) {
                if (labels[i] == j) { cnt++; sumL += lab[i][0]; sumA += lab[i][1]; sumB += lab[i][2] }
            }
            if (cnt > 0) centroids[j] = floatArrayOf(sumL / cnt, sumA / cnt, sumB / cnt)
        }
    }

    // ── Build the clusters ───────────────────────────────────────────────
    val buckets = Array(kFinal) { mutableListOf<Int>() }
    for (i in 0 until n) buckets[labels[i]].add(i)
    val total = n.toFloat()
    return (0 until kFinal).mapNotNull { j ->
        val idx = buckets[j]
        if (idx.isEmpty()) return@mapNotNull null
        val rgb = representative(idx.map { pixels[it] }, idx.map { lab[it] })
        Cluster(
            rgb = rgb,
            hsv = rgbToHsv(rgb[0], rgb[1], rgb[2]),
            chroma = chromaOf(rgbToLab(rgb[0], rgb[1], rgb[2])),
            population = idx.size / total,
        )
    }
}

private fun sqDist(a: FloatArray, b: FloatArray): Float {
    val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
    return dl * dl + da * da + db * db
}

/**
 * A genuinely colourless cover, rendered as a white carrying its own faint cast.
 *
 * Only reached when the *whole image* is achromatic. It used to be reached whenever a
 * cluster's HSV saturation came out low, which on a washed-out mean was most of them —
 * and the tints were strongly warm, so the accent lift turned them into gold. They are
 * near-white now, and the lift leaves a neutral lead neutral.
 */
private fun tintedWhite(rgb: FloatArray, v: Float): FloatArray {
    val r = rgb[0]; val b = rgb[2]
    val tint = if (r - b > 0.02f) WARM_WHITE
        else if (b - r > 0.02f) COOL_WHITE
        else PLAIN_WHITE
    val vv = max(VALUE_FLOOR, min(1f, v))
    return floatArrayOf(tint[0] * vv, tint[1] * vv, tint[2] * vv)
}

/**
 * The extraction's answers: the colour the cover *leads* with, the companion set, and
 * whether the cover had any real colour at all.
 *
 * [achromatic] exists so the accent lift can tell "this is grey because the sleeve is
 * grey" from "this is muted but real". Boosting the first into a hue is how a
 * black-and-white photo used to come out gold.
 */
internal class Extraction(
    val lead: FloatArray,
    val swatches: List<FloatArray>,
    /**
     * Share of the cover each swatch came from, parallel to [swatches]. Light
     * Sync spends time on a colour in proportion to this, so the room reads like
     * the sleeve rather than cycling every colour equally.
     */
    val populations: List<Float> = emptyList(),
    val achromatic: Boolean = false,
)

/** Faithful fallback for covers with almost no colour. */
private fun lowColourFallback(pixels: List<FloatArray>): Extraction {
    val mean = floatArrayOf(
        pixels.map { it[0] }.average().toFloat(),
        pixels.map { it[1] }.average().toFloat(),
        pixels.map { it[2] }.average().toFloat(),
    )
    val v = rgbToHsv(mean[0], mean[1], mean[2])[2]
    val only = tintedWhite(mean, max(0.55f, v))
    return Extraction(only, listOf(only), achromatic = true)
}

/**
 * Extract up to [k] theme-faithful colours from RGB pixels using CIELAB k-means.
 *
 * `internal` rather than private so the selection can be exercised from a JVM unit
 * test — [paletteOf] takes an Android `Bitmap` and can't be.
 */
internal fun kmeansPalette(pixels: List<FloatArray>, k: Int = 5): Extraction? {
    if (pixels.isEmpty()) return null

    // Drop only the true extremes (matte black, paper white); keep greys and dark tones
    val body = pixels.filter {
        val luma = 0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2]
        luma >= 0.04f && luma <= 0.98f
    }
    if (body.size < 6) return lowColourFallback(pixels)

    // Deterministic per cover: the same art must always give the same palette, or the
    // whole app's accent would drift between launches.
    val seed = body.fold(17L) { acc, p ->
        acc * 31 + ((p[0] * 255).toInt() shl 16 or ((p[1] * 255).toInt() shl 8) or (p[2] * 255).toInt())
    }
    val clusters = kmeansClusters(body, min(CLUSTERS, body.size), kotlin.random.Random(seed))
    if (clusters.isEmpty()) return lowColourFallback(pixels)

    // How colourful is this cover at its most colourful? Everything else is judged
    // relative to that, so a muted sleeve keeps its muted colours and a neon one
    // doesn't have every last cluster promoted to "accent".
    val peakChroma = clusters.maxOf { it.chroma }
    if (peakChroma < ACHROMATIC_C) return lowColourFallback(body)

    val accentFloor = max(MIN_ACCENT_C, peakChroma * ACCENT_C_FRACTION)

    // Rank by colourfulness weighted by how much of the cover it is. The square root
    // keeps a small vivid splash competitive with a large field without letting a
    // handful of stray pixels win outright.
    val ranked = clusters
        .filter { it.chroma >= accentFloor }
        .sortedByDescending { it.chroma * kotlin.math.sqrt(it.population) }

    // Each pick carries the share of the cover it came from. Light Sync uses
    // those as dwell weights, so a sleeve that is mostly one colour reads mostly
    // that colour instead of cycling all its colours equally.
    val accents = mutableListOf<Pair<FloatArray, Float>>()
    val pickedHues = mutableListOf<Float>()
    for (c in ranked) {
        if (accents.size >= MAX_ACCENTS) break
        val h = c.hsv[0]
        // Distinct hues only, so a cover with three real colours yields three rather
        // than three shades of the loudest one.
        if (pickedHues.all { hueDistance(h, it) >= HUE_MIN_SEP_DEG }) {
            pickedHues.add(h)
            accents.add(hsvToRgb(h, c.hsv[1], max(VALUE_FLOOR, c.hsv[2])) to c.population)
        }
    }

    // Fill the remaining slots with the most populous non-accent clusters: the muted
    // and dark tones that set the mood. They come *after* the accents rather than
    // before, so a colourful cover spends its slots on its colours.
    //
    // Split by whether they have any colour at all, and the colourless ones capped —
    // see [MAX_NEUTRAL_SWATCHES]. Accents are deduplicated by hue and neutrals were
    // not, so a cover's three off-whites each took a slot while its three reds
    // collapsed into one. Taking the tinted ones first means a second shade of a
    // colour the cover actually has always beats a third shade of its background.
    val nonAccent = clusters.filter { it.chroma < accentFloor }
    val remaining = (k - accents.size).coerceAtLeast(0)
    val tinted = nonAccent.filter { it.chroma >= ACHROMATIC_C }.sortedByDescending { it.population }
    val neutral = nonAccent.filter { it.chroma < ACHROMATIC_C }.sortedByDescending { it.population }
    // Deliberately allowed to come up short. A palette of three real colours is a
    // better description of a cover than the same three padded out with greys, and
    // `AlbumPalette.swatch` wraps, so nothing downstream needs a fixed count.
    val bases = (tinted + neutral.take(MAX_NEUTRAL_SWATCHES))
        .take(remaining)
        .map { c ->
            val rgb = if (c.chroma < ACHROMATIC_C) tintedWhite(c.rgb, c.hsv[2])
            else hsvToRgb(c.hsv[0], c.hsv[1], max(VALUE_FLOOR, c.hsv[2]))
            rgb to c.population
        }

    val out = accents + bases
    if (out.isEmpty()) return lowColourFallback(body)

    // The lead is the top-ranked accent. With none, the most *chromatic* cluster leads
    // rather than the most populous — on a sleeve whose colour is a small detail
    // against a big flat field, the field is not what the cover is about.
    val lead = accents.firstOrNull()?.first
        ?: clusters.maxByOrNull { it.chroma }!!.let { hsvToRgb(it.hsv[0], it.hsv[1], max(VALUE_FLOOR, it.hsv[2])) }

    // The set is hue-ordered so the cyclic gradient drifts between related hues.
    val ordered = out.sortedBy { rgbToHsv(it.first[0], it.first[1], it.first[2])[0] }
    return Extraction(
        lead = lead,
        swatches = ordered.map { it.first },
        populations = ordered.map { it.second },
        achromatic = accents.isEmpty(),
    )
}

/** Bin [bmp] via CIELAB k-means and return the ranked palette, or null if it has no usable colour. */
internal fun paletteOf(bmp: Bitmap): AlbumPalette? {
    val small = if (bmp.width == SAMPLE && bmp.height == SAMPLE) bmp
    else Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, true)

    val px = IntArray(SAMPLE * SAMPLE)
    small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
    if (small !== bmp) small.recycle()

    // Convert to list of RGB float arrays
    val pixels = ArrayList<FloatArray>(px.size)
    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        pixels.add(floatArrayOf(r, g, b))
    }

    val extracted = kmeansPalette(pixels, k = 5) ?: return null
    return liftedPalette(extracted)
}

/**
 * Turn an [Extraction] into the app's palette: legible against true black, without
 * inventing colour that isn't on the cover.
 *
 * The saturation floor is the second half of the gold problem. Forcing every lead to
 * at least 0.50 saturation is fine for a colour that *has* a hue and catastrophic for
 * one that doesn't — a grey with a faint warm cast becomes vivid amber, which is
 * exactly the "everything falls back to gold" symptom. So the floor applies only when
 * the extraction found real chroma; a colourless cover is lifted in lightness alone
 * and stays a soft silver.
 */
internal fun liftedPalette(extracted: Extraction): AlbumPalette {
    val accent = liftOne(extracted.lead, ACCENT_L_MIN, ACCENT_L_MAX, extracted.achromatic)
    val accentL = oklabToOklch(rgbToOklab(accent.red, accent.green, accent.blue))[0]

    // Companions keep the cover's hue order (so gradients drift rather than jump)
    // and are lifted the same way, held below the lead so it still leads. The lead
    // leads the list too: swatch(0) is what the glows are built from. A companion
    // that is itself near-grey keeps its greyness rather than being pushed into a
    // hue of its own — the same rule the accent follows, applied per swatch instead
    // of once for the whole cover.
    //
    // The ceiling is derived from the accent that was actually produced rather than
    // from a constant, because the accent's own lightness is now the cover's. A
    // fixed companion lightness under a variable accent would put companions *above*
    // the lead on any cover whose lead is dark.
    val companionCeiling = (accentL - COMPANION_L_HEADROOM).coerceAtLeast(COMPANION_L_MIN)
    val companions = extracted.swatches
        .filter { it !== extracted.lead }
        .map { rgb ->
            val chroma = oklabToOklch(rgbToOklab(rgb[0], rgb[1], rgb[2]))[1]
            liftOne(rgb, COMPANION_L_MIN, companionCeiling, achromatic = chroma < ACHROMATIC_OKLCH_C)
        }

    return AlbumPalette(accent = accent, swatches = listOf(accent) + companions)
}

/**
 * One extracted colour → one the app can paint with, in OKLCh.
 *
 * Three rules:
 *
 *  1. **Hue is kept exactly.** It is what the cover most unambiguously *is*, and it
 *     survives both the lift and the gamut mapping untouched.
 *  2. **Lightness is the cover's own, clamped into `[minL, maxL]`.** It used to be
 *     pinned to one perceptual value for every hue. That is defensible in the
 *     abstract — equal OKLab L is equal apparent brightness — and it is why red came
 *     out as light red and violet as faded violet: sRGB's red lives at L 0.63 and its
 *     blue at L 0.45, so a target of 0.78 asked both to be lighter than they can be
 *     and, because the gamut narrows away from a hue's own peak, cut the chroma they
 *     could hold on the way. The clamp is now only what legibility against true black
 *     requires; inside it, the cover decides. See [ACCENT_L_MIN].
 *  3. **Chroma is the cover's own**, floored so a nearly-grey lead still reads as a
 *     colour, and capped only by what sRGB can actually hold at that lightness and
 *     hue. Deliberately *not* squeezed into a band: an older lift clamped saturation
 *     into `0.50..0.72`, so a watercolour sleeve and a neon one arrived at almost
 *     identical intensity. A restrained cover should look restrained.
 *
 * Rules 2 and 3 compound in the right direction now rather than the wrong one. A
 * saturated red keeps L 0.63, where sRGB holds roughly twice the chroma it holds at
 * 0.78 — so the same source colour comes out both darker and more colourful, which
 * together is the whole of "that is the red on the sleeve".
 *
 * A colour with no real hue is lifted in lightness alone and stays a soft neutral —
 * the second half of the old "everything falls back to gold" problem was forcing
 * chroma onto a grey, and a floor applied blindly would reintroduce exactly that.
 */
private fun liftOne(rgb: FloatArray, minL: Float, maxL: Float, achromatic: Boolean): Color {
    val lch = oklabToOklch(rgbToOklab(rgb[0], rgb[1], rgb[2]))
    val hue = lch[2]
    // `coerceAtLeast` on the ceiling because a caller may hand a band that has
    // collapsed — a very dark accent leaves companions no room — and `coerceIn`
    // throws rather than clamping when the range is inverted.
    val targetL = lch[0].coerceIn(minL, maxL.coerceAtLeast(minL))
    if (achromatic) return oklchColor(targetL, lch[1].coerceAtMost(NEUTRAL_MAX_C), hue)
    // The floor is a fraction of what this hue can hold rather than an absolute, so
    // it means the same thing across the gamut: sRGB reaches far more chroma in
    // yellow than in blue, and one absolute number would over-drive the one it can
    // reach and under-drive the one it cannot.
    val ceiling = maxChromaFor(targetL, hue)
    return oklchColor(targetL, lch[1].coerceIn(ceiling * MIN_ACCENT_C_FRACTION, ceiling), hue)
}

/** Load [url] and derive the album's [AlbumPalette]. Falls back to the amber default. */
@Composable
fun rememberAlbumPalette(url: String?): AlbumPalette {
    val ctx = LocalContext.current
    var palette by remember { mutableStateOf(AlbumPalette()) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) { palette = AlbumPalette(); return@LaunchedEffect }
        val extracted = withContext(Dispatchers.IO) {
            val bmp = try {
                val res = ctx.imageLoader.execute(
                    ImageRequest.Builder(ctx).data(url).allowHardware(false).size(128).build()
                )
                (res as? SuccessResult)?.drawable?.toBitmap()
            } catch (_: Exception) { null } ?: return@withContext null
            try { paletteOf(bmp) } catch (_: Exception) { null }
        }
        palette = extracted ?: AlbumPalette()
    }
    return palette
}

/** The album's accent alone, for callers that don't need the companion swatches. */
@Composable
fun rememberAlbumAccent(url: String?): Color = rememberAlbumPalette(url).accent