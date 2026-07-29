package com.engabd.sendpin.ui.design

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
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
data class AlbumPalette(
    val accent: Color = DefaultAccent,
    val swatches: List<Color> = FallbackPalette,
) {
    /** Companion swatch [i], wrapping around. */
    fun swatch(i: Int): Color = swatches[((i % swatches.size) + swatches.size) % swatches.size]
}

val LocalPalette = compositionLocalOf { AlbumPalette() }

private const val SAMPLE = 64      // decode artwork to 64×64 before clustering (matches syncoV2)

// Swatch classification thresholds (from syncoV2 const.py / album_art.py)
private const val ACCENT_SAT = 0.35f      // vivid clusters need s >= this
private const val ACCENT_VAL = 0.35f     // vivid clusters need v >= this
private const val NEUTRAL_SAT = 0.12f    // below this a swatch is a tinted white
private const val BASE_MIN_POP = 0.10f   // a base must be >= 10% of the cover
private const val VALUE_FLOOR = 0.15f    // keep even the darkest swatch faintly visible
private const val HUE_MIN_SEP = 0.055f   // ~20°: reject near-duplicate accent hues

// Tinted whites for near-neutral theme swatches (from syncoV2 album_art.py)
private val WARM_WHITE = floatArrayOf(1.0f, 0.84f, 0.60f)
private val COOL_WHITE = floatArrayOf(0.78f, 0.86f, 1.0f)
private val PLAIN_WHITE = floatArrayOf(1.0f, 0.92f, 0.82f)
private val NEUTRAL_WHITE = floatArrayOf(1.0f, 0.86f, 0.70f)

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

/**
 * RGB → HSL, returns [h, s, l] with h in degrees. Distinct from [rgbToHsv]: the
 * final swatches are *lifted* in HSL, where lightness is symmetric about 0.5, so
 * pushing a colour brighter doesn't wash its saturation out the way raising HSV's
 * value does.
 */
private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val out = FloatArray(3)
    fun b8(c: Float) = (c * 255f).toInt().coerceIn(0, 255)
    ColorUtils.RGBToHSL(b8(r), b8(g), b8(b), out)
    return out
}

private fun hslColor(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, l)))

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

// ─── K-means in CIELAB ─────────────────────────────────────────────────────

private data class Cluster(
    val meanRgb: FloatArray,     // mean RGB (0..1) of members
    val meanHsv: FloatArray,     // mean HSV of members
    val population: Float,      // fraction of total pixels
)

/** Deterministic k-means in CIELAB space. Returns labelled clusters. */
private fun kmeansClusters(pixels: List<FloatArray>, k: Int): List<Cluster> {
    val n = pixels.size
    if (n == 0) return emptyList()
    val kk = min(k, n)

    // Convert all pixels to LAB
    val lab = pixels.map { rgbToLab(it[0], it[1], it[2]) }.toTypedArray()

    // Seed centroids spread along lightness (first LAB axis) for stability
    val order = lab.indices.sortedBy { lab[it][0] }
    val seeds = (0 until kk).map { i ->
        val idx = (i.toFloat() / (kk - 1).coerceAtLeast(1) * (n - 1)).toInt()
        order[idx]
    }
    var centroids = seeds.map { lab[it].copyOf() }.toTypedArray()
    var labels = IntArray(n)

    for (iter in 0 until 14) {
        var moved = false
        // Assign
        for (i in 0 until n) {
            var bestJ = 0; var bestD = Float.MAX_VALUE
            for (j in 0 until kk) {
                val dl = lab[i][0] - centroids[j][0]
                val da = lab[i][1] - centroids[j][1]
                val db = lab[i][2] - centroids[j][2]
                val d = dl * dl + da * da + db * db
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (labels[i] != bestJ) { labels[i] = bestJ; moved = true }
        }
        if (!moved && iter > 0) break
        // Update centroids
        for (j in 0 until kk) {
            var cnt = 0; var sumL = 0f; var sumA = 0f; var sumB = 0f
            for (i in 0 until n) {
                if (labels[i] == j) {
                    cnt++; sumL += lab[i][0]; sumA += lab[i][1]; sumB += lab[i][2]
                }
            }
            if (cnt > 0) {
                val nc = floatArrayOf(sumL / cnt, sumA / cnt, sumB / cnt)
                if (!nc.contentEquals(centroids[j])) moved = true
                centroids[j] = nc
            }
        }
    }

    // Build clusters with mean RGB + HSV, in one accumulation pass over the pixels.
    val counts = IntArray(kk)
    val sums = Array(kk) { FloatArray(3) }
    for (i in 0 until n) {
        val j = labels[i]
        counts[j]++
        sums[j][0] += pixels[i][0]; sums[j][1] += pixels[i][1]; sums[j][2] += pixels[i][2]
    }
    val total = n.toFloat()
    return (0 until kk).mapNotNull { j ->
        val cnt = counts[j]
        if (cnt == 0) return@mapNotNull null
        val rgb = floatArrayOf(sums[j][0] / cnt, sums[j][1] / cnt, sums[j][2] / cnt)
        Cluster(rgb, rgbToHsv(rgb[0], rgb[1], rgb[2]), cnt / total)
    }
}

/** A near-neutral swatch as a white tinted by its own colour cast. */
private fun tintedWhite(rgb: FloatArray, v: Float): FloatArray {
    val r = rgb[0]; val b = rgb[2]
    val tint = if (r - b > 0.02f) WARM_WHITE
        else if (b - r > 0.02f) COOL_WHITE
        else PLAIN_WHITE
    val vv = max(VALUE_FLOOR, min(1f, v))
    return floatArrayOf(tint[0] * vv, tint[1] * vv, tint[2] * vv)
}

/** Faithful fallback for covers with almost no colour. */
private fun lowColourFallback(pixels: List<FloatArray>): Extraction {
    // Find colourful pixels (sat >= 0.12)
    val colourful = pixels.filter { rgbToHsv(it[0], it[1], it[2])[1] >= 0.12f }
    val only = if (colourful.size >= 4) {
        val meanR = colourful.map { it[0] }.average().toFloat()
        val meanG = colourful.map { it[1] }.average().toFloat()
        val meanB = colourful.map { it[2] }.average().toFloat()
        val hsv = rgbToHsv(meanR, meanG, meanB)
        hsvToRgb(hsv[0], max(hsv[1], 0.40f), max(0.3f, hsv[2]))
    } else {
        NEUTRAL_WHITE
    }
    return Extraction(only, listOf(only))
}

/**
 * The extraction's two answers: the single colour the cover *leads* with, and the
 * companion set. syncoV2 only needs the set (it spreads it round a room as a
 * cyclic gradient, hue-ordered so neighbouring bulbs relate); the app also needs
 * one lead colour for controls, and that has to be the most vivid swatch rather
 * than whichever happens to sit lowest on the hue wheel.
 */
private class Extraction(val lead: FloatArray, val swatches: List<FloatArray>)

/**
 * Extract up to [k] theme-faithful colours from RGB pixels using CIELAB k-means.
 * Ported from syncoV2's `_kmeans_palette` (album_art.py).
 */
private fun kmeansPalette(pixels: List<FloatArray>, k: Int = 5): Extraction? {
    if (pixels.isEmpty()) return null

    // Drop only the true extremes (matte black, paper white); keep greys and dark tones
    val body = pixels.filter {
        val luma = 0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2]
        luma >= 0.04f && luma <= 0.98f
    }
    if (body.size < 6) return lowColourFallback(pixels)

    val nClusters = min(14, body.size)
    val clusters = kmeansClusters(body, nClusters)

    val accents = mutableListOf<Triple<Float, FloatArray, FloatArray>>()  // (score, hsv, rgb)
    val bases = mutableListOf<Pair<Float, Cluster>>()  // (population, cluster)

    for (c in clusters) {
        val s = c.meanHsv[1]; val v = c.meanHsv[2]; val pop = c.population
        if (s >= ACCENT_SAT && v >= ACCENT_VAL) {
            // Vividness-weighted population: a small vivid splash can outrank a large dull field
            accents.add(Triple(pop * (0.25f + 0.75f * s), c.meanHsv, c.meanRgb))
        } else {
            bases.add(pop to c)
        }
    }

    // Theme bases first: the dominant muted/dark swatches that set the mood
    bases.sortByDescending { it.first }
    val baseOut = mutableListOf<FloatArray>()
    for ((pop, cluster) in bases) {
        if (baseOut.size >= 2 || pop < BASE_MIN_POP) break
        val s = cluster.meanHsv[1]; val v = cluster.meanHsv[2]
        if (s < NEUTRAL_SAT) {
            baseOut.add(tintedWhite(cluster.meanRgb, v))
        } else {
            baseOut.add(hsvToRgb(cluster.meanHsv[0], s, max(VALUE_FLOOR, v)))
        }
    }

    // Vivid accents fill remaining slots, hue-diverse
    accents.sortByDescending { it.first }
    val accentOut = mutableListOf<FloatArray>()
    val pickedHues = mutableListOf<Float>()
    for ((_, hsv, _) in accents) {
        if (baseOut.size + accentOut.size >= k) break
        val h = hsv[0]
        if (pickedHues.all { hueDistance(h, it) >= HUE_MIN_SEP * 360f }) {
            pickedHues.add(h)
            accentOut.add(hsvToRgb(h, hsv[1], max(VALUE_FLOOR, hsv[2])))
        }
    }

    val out = baseOut + accentOut
    if (out.isEmpty()) return lowColourFallback(pixels)

    // The lead is the top-scoring vivid accent; a cover with none (a sepia photo,
    // a monochrome sleeve) leads with its dominant base instead.
    val lead = accentOut.firstOrNull() ?: baseOut.first()
    // The set is hue-ordered so the cyclic gradient drifts between related hues.
    return Extraction(lead, out.sortedBy { rgbToHsv(it[0], it[1], it[2])[0] })
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

    // The accent is pushed brighter and more saturated than the art so it stays
    // legible as a control colour against true black.
    val leadHsl = rgbToHsl(extracted.lead[0], extracted.lead[1], extracted.lead[2])
    val accent = hslColor(
        leadHsl[0],
        (leadHsl[1] + 0.24f).coerceIn(0.5f, 0.72f),
        (leadHsl[2] + 0.12f).coerceIn(0.58f, 0.70f),
    )

    // Companions keep the cover's hue order (so gradients drift rather than jump)
    // but are lifted to the same legible band. The lead leads the list: swatch(0)
    // is what the glows are built from.
    val companions = extracted.swatches
        .filter { it !== extracted.lead }
        .map { rgb ->
            val hsl = rgbToHsl(rgb[0], rgb[1], rgb[2])
            hslColor(hsl[0], (hsl[1] + 0.1f).coerceIn(0.36f, 0.58f), (hsl[2] + 0.16f).coerceIn(0.58f, 0.72f))
        }

    return AlbumPalette(accent = accent, swatches = listOf(accent) + companions)
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