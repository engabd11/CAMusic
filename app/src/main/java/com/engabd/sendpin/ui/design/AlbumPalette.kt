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

private const val VALUE_FLOOR = 0.15f    // keep even the darkest swatch faintly visible
private const val HUE_MIN_SEP_DEG = 22f  // reject near-duplicate accent hues
private const val MAX_ACCENTS = 4        // leave a slot for a populous muted tone

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
    val bases = clusters
        .filter { it.chroma < accentFloor }
        .sortedByDescending { it.population }
        .take((k - accents.size).coerceAtLeast(0))
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
    val leadHsl = rgbToHsl(extracted.lead[0], extracted.lead[1], extracted.lead[2])
    val accent = if (extracted.achromatic) {
        hslColor(leadHsl[0], leadHsl[1].coerceAtMost(0.10f), (leadHsl[2] + 0.18f).coerceIn(0.66f, 0.78f))
    } else {
        hslColor(
            leadHsl[0],
            (leadHsl[1] + 0.24f).coerceIn(0.5f, 0.72f),
            (leadHsl[2] + 0.12f).coerceIn(0.58f, 0.70f),
        )
    }

    // Companions keep the cover's hue order (so gradients drift rather than jump)
    // but are lifted to the same legible band. The lead leads the list: swatch(0)
    // is what the glows are built from. A companion that is itself near-grey keeps
    // its low saturation rather than being pushed into a hue of its own.
    val companions = extracted.swatches
        .filter { it !== extracted.lead }
        .map { rgb ->
            val hsl = rgbToHsl(rgb[0], rgb[1], rgb[2])
            val sat = if (hsl[1] < 0.08f) hsl[1] else (hsl[1] + 0.1f).coerceIn(0.36f, 0.58f)
            hslColor(hsl[0], sat, (hsl[2] + 0.16f).coerceIn(0.58f, 0.72f))
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