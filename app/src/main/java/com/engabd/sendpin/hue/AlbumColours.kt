package com.engabd.sendpin.hue

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min

/**
 * Album-art colour extraction for Light Sync, ported from syncoV2
 * `color/album_art.py`.
 *
 * Deliberately *not* the app's UI palette. That one exists to pick an accent
 * that stays legible against a black background: it ranks candidates by
 * `chroma * sqrt(population)`, so a small vivid splash beats a large flat field,
 * and it lifts lightness and saturation afterwards. Both are right for a UI
 * accent and wrong for a room — they surface colours the sleeve barely contains,
 * which is exactly the "wrong colours" symptom.
 *
 * Two extraction modes, matching syncoV2 exactly:
 *
 * - **v1** ([extractAlbumColoursV1]): accent/base separation. Vivid clusters
 *   ranked by `population × (0.25 + 0.75 × saturation)` so a small vivid splash
 *   can outrank a large dull field, plus up to 2 muted/dark theme bases that
 *   *are* the cover's atmosphere. No weights — uniform cyclic gradient. This is
 *   what `album_art` (the "even" option) uses.
 * - **v2** ([extractAlbumColours]): pure population ranking. Near-duplicate hues
 *   pool their share, and once `k` swatches are picked the remaining clusters
 *   fold into the nearest by hue, so the weights always describe the whole
 *   cover. This is what `album_art_v2` (the "weighted" option) uses.
 *
 * Deterministic: the k-means centroids are seeded along sorted lightness rather
 * than randomly, so the same sleeve always yields the same palette.
 */

/** Decode artwork to this size before clustering. */
private const val THUMB = 64

/** ~20°: below this two hues are the same colour and pool their share. */
private const val HUE_MIN_SEP = 0.055f

/** Below this saturation a swatch is a tinted white, not a colour. */
private const val NEUTRAL_SAT = 0.12f

/** Keep even the darkest swatch faintly visible. */
private const val VALUE_FLOOR = 0.15f

private val WARM_WHITE = floatArrayOf(1.0f, 0.84f, 0.60f)
private val COOL_WHITE = floatArrayOf(0.78f, 0.86f, 1.0f)
private val PLAIN_WHITE = floatArrayOf(1.0f, 0.92f, 0.82f)

/** Soft warm white for covers with no real colour (black & white art). */
private val NEUTRAL_WHITE = floatArrayOf(1.0f, 0.86f, 0.70f)

private const val KMEANS_ITERS = 14

// ── v1 swatch classification (matches syncoV2 album_art.py) ──────────────

/** A cluster needs at least this saturation to count as a vivid accent. */
private const val ACCENT_SAT = 0.35f

/** A cluster needs at least this value to count as a vivid accent. */
private const val ACCENT_VAL = 0.35f

/** A base must occupy at least this fraction of the cover, or it's noise. */
private const val BASE_MIN_POP = 0.10f

/** Fallback path only: floor for a rescued single dominant hue. */
private const val SAT_FLOOR = 0.40f

/** Colours of a cover with their population shares, ready for a weighted palette. */
data class AlbumColours(val colors: List<Rgb>, val weights: List<Float>)

/**
 * Up to [k] cover colours with their population weights.
 *
 * Returns null when the bitmap yields nothing usable, which the caller should
 * treat as "keep the palette you have" rather than as a reason to go dark.
 */
fun extractAlbumColours(bmp: Bitmap, k: Int = 4): AlbumColours? {
    val small = if (bmp.width == THUMB && bmp.height == THUMB) bmp
    else Bitmap.createScaledBitmap(bmp, THUMB, THUMB, true)
    val px = IntArray(THUMB * THUMB)
    small.getPixels(px, 0, THUMB, 0, 0, THUMB, THUMB)
    if (small !== bmp) small.recycle()
    return extractAlbumColours(px, k)
}

/**
 * v1 extraction from a [Bitmap]: accent/base separation, uniform palette.
 *
 * Use when the colour scheme is `album_art` (the "even" option). The palette
 * has no weights — the engine interpolates evenly across the colours.
 */
fun extractAlbumColoursV1(bmp: Bitmap, k: Int = 5): AlbumColours? {
    val small = if (bmp.width == THUMB && bmp.height == THUMB) bmp
    else Bitmap.createScaledBitmap(bmp, THUMB, THUMB, true)
    val px = IntArray(THUMB * THUMB)
    small.getPixels(px, 0, THUMB, 0, 0, THUMB, THUMB)
    if (small !== bmp) small.recycle()
    return extractAlbumColoursV1(px, k)
}

/**
 * The same extraction over raw ARGB pixels.
 *
 * Split out from the [Bitmap] entry point so the selection can be exercised
 * from a JVM test — the clustering and weighting is the part worth pinning, and
 * it needs no Android graphics at all.
 */
internal fun extractAlbumColours(px: IntArray, k: Int = 4): AlbumColours? {
    // Drop only the true extremes — matte black and paper white. Greys and dark
    // tones are kept, because a mostly-dark sleeve is still mostly dark and the
    // room should say so.
    val body = ArrayList<FloatArray>(px.size)
    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        if (luma in 0.04f..0.98f) body.add(floatArrayOf(r, g, b))
    }
    if (body.size < 6) return lowColourFallback(px)

    val lab = body.map { rgbToLab(it[0], it[1], it[2]) }
    val nClusters = min(14, min(body.size, max(2 * k, 8)))
    val labels = kmeans(lab, nClusters)

    // One swatch per non-empty cluster, most populous first.
    data class Swatch(var pop: Float, val h: Float, val s: Float, val rgb: Rgb)
    val swatches = ArrayList<Swatch>(nClusters)
    val total = body.size.toFloat()
    for (c in 0 until nClusters) {
        var n = 0
        var sr = 0f; var sg = 0f; var sb = 0f
        for (i in body.indices) if (labels[i] == c) { n++; sr += body[i][0]; sg += body[i][1]; sb += body[i][2] }
        if (n == 0) continue
        val mean = floatArrayOf(sr / n, sg / n, sb / n)
        val hsv = rgbToHsv(mean[0], mean[1], mean[2])
        swatches.add(Swatch(n / total, hsv[0], hsv[1], renderSwatch(mean, hsv[0], hsv[1], hsv[2])))
    }
    if (swatches.isEmpty()) return lowColourFallback(px)
    swatches.sortByDescending { it.pop }

    // Greedy pick-and-merge. Same-class swatches within HUE_MIN_SEP pool their
    // share; past k picks, everything else folds into its nearest pick by hue —
    // so no part of the cover is simply discarded from the weighting.
    val picked = ArrayList<Swatch>(k)
    for (sw in swatches) {
        var merged = false
        for (slot in picked) {
            val sameClass = (sw.s < NEUTRAL_SAT) == (slot.s < NEUTRAL_SAT)
            if (sameClass && hueDistance(sw.h, slot.h) < HUE_MIN_SEP) {
                slot.pop += sw.pop
                merged = true
                break
            }
        }
        if (merged) continue
        if (picked.size < k) {
            picked.add(sw)
        } else {
            picked.minByOrNull { hueDistance(sw.h, it.h) }!!.pop += sw.pop
        }
    }

    // Hue order, so the cyclic gradient drifts between related colours.
    picked.sortBy { rgbToHsv(it.rgb.first, it.rgb.second, it.rgb.third)[0] }
    return AlbumColours(picked.map { it.rgb }, picked.map { it.pop })
}

/** A cluster mean as a lighting colour. */
private fun renderSwatch(mean: FloatArray, h: Float, s: Float, v: Float): Rgb =
    if (s < NEUTRAL_SAT) tintedWhite(mean, v)
    else hsvToRgbTriple(h, s, max(VALUE_FLOOR, v))

/**
 * A near-neutral swatch as a white tinted by its own colour cast.
 *
 * Bulbs cannot show "silver" or "charcoal", but a dim cool white reads as silver
 * and a dim warm white as gold — keeping the value carries the mood across.
 */
private fun tintedWhite(mean: FloatArray, value: Float): Rgb {
    val r = mean[0]
    val b = mean[2]
    val tint = when {
        r - b > 0.02f -> WARM_WHITE
        b - r > 0.02f -> COOL_WHITE
        else -> PLAIN_WHITE
    }
    val v = value.coerceIn(VALUE_FLOOR, 1f)
    return Triple(tint[0] * v, tint[1] * v, tint[2] * v)
}

/** Faithful fallback for a cover with almost no usable colour (v2 path). */
private fun lowColourFallback(px: IntArray): AlbumColours? {
    if (px.isEmpty()) return null
    var sr = 0f; var sg = 0f; var sb = 0f
    for (p in px) {
        sr += ((p shr 16) and 0xFF) / 255f
        sg += ((p shr 8) and 0xFF) / 255f
        sb += (p and 0xFF) / 255f
    }
    val n = px.size.toFloat()
    val mean = floatArrayOf(sr / n, sg / n, sb / n)
    val v = rgbToHsv(mean[0], mean[1], mean[2])[2]
    return AlbumColours(listOf(tintedWhite(mean, max(0.55f, v))), listOf(1f))
}

// ── v1 extraction (accent/base separation, no weights) ──────────────────

/**
 * Faithful fallback for a cover with almost no colour (v1 path).
 *
 * Returns the cover's single dominant *actual* hue (from whatever colourful
 * pixels exist), or a neutral warm white for genuinely black-and-white art.
 * Deliberately does NOT invent extra hues — a near-monochrome cover should
 * drive a near-monochrome show, not a fabricated rainbow.
 */
private fun lowColourFallbackV1(px: IntArray): AlbumColours? {
    if (px.isEmpty()) return null
    // Find colourful pixels (saturation >= 0.12)
    var cr = 0f; var cg = 0f; var cb = 0f; var cn = 0
    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val sat = if (mx > 1e-6f) (mx - mn) / mx else 0f
        if (sat >= 0.12f) { cr += r; cg += g; cb += b; cn++ }
    }
    if (cn >= 4) {
        val mean = floatArrayOf(cr / cn, cg / cn, cb / cn)
        val hsv = rgbToHsv(mean[0], mean[1], mean[2])
        val s = max(hsv[1], SAT_FLOOR)
        val v = max(0.3f, hsv[2])
        val rgb = hsvToRgbTriple(hsv[0], s, v)
        return AlbumColours(listOf(rgb), listOf(1f))
    }
    // Genuinely black-and-white: neutral warm white
    var sr = 0f; var sg = 0f; var sb = 0f
    for (p in px) {
        sr += ((p shr 16) and 0xFF) / 255f
        sg += ((p shr 8) and 0xFF) / 255f
        sb += (p and 0xFF) / 255f
    }
    val n = px.size.toFloat()
    val mean = floatArrayOf(sr / n, sg / n, sb / n)
    val v = rgbToHsv(mean[0], mean[1], mean[2])[2]
    val rgb = Triple(NEUTRAL_WHITE[0] * max(VALUE_FLOOR, v),
                     NEUTRAL_WHITE[1] * max(VALUE_FLOOR, v),
                     NEUTRAL_WHITE[2] * max(VALUE_FLOOR, v))
    return AlbumColours(listOf(rgb), listOf(1f))
}

/**
 * v1 extraction: up to [k] theme-faithful lighting colours from RGB pixels.
 *
 * Ported from syncoV2's `album_art.py::_kmeans_palette`. Separates vivid
 * **accents** (ranked by `pop × (0.25 + 0.75 × sat)` so a small vivid splash
 * can outrank a large dull field) from muted/dark **theme bases** (the dominant
 * swatches that set the mood, with their real saturation and value preserved).
 * Near-neutral bases become tinted whites. Output is hue-ordered for smooth
 * drift. No weights — the palette is a uniform cyclic gradient.
 */
internal fun extractAlbumColoursV1(px: IntArray, k: Int = 5): AlbumColours? {
    // Drop only the true extremes — matte black and paper white.
    val body = ArrayList<FloatArray>(px.size)
    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        if (luma in 0.04f..0.98f) body.add(floatArrayOf(r, g, b))
    }
    if (body.size < 6) return lowColourFallbackV1(px)

    val lab = body.map { rgbToLab(it[0], it[1], it[2]) }
    val nClusters = min(14, min(body.size, max(2 * k, 8)))
    val labels = kmeans(lab, nClusters)

    // Classify each cluster as an accent or a base.
    data class AccCandidate(val score: Float, val h: Float, val s: Float, val v: Float)
    data class BaseCandidate(val pop: Float, val h: Float, val s: Float, val v: Float, val mean: FloatArray)

    val accents = ArrayList<AccCandidate>()
    val bases = ArrayList<BaseCandidate>()
    val total = body.size.toFloat()
    for (c in 0 until nClusters) {
        var n = 0
        var sr = 0f; var sg = 0f; var sb = 0f
        for (i in body.indices) if (labels[i] == c) { n++; sr += body[i][0]; sg += body[i][1]; sb += body[i][2] }
        if (n == 0) continue
        val mean = floatArrayOf(sr / n, sg / n, sb / n)
        val hsv = rgbToHsv(mean[0], mean[1], mean[2])
        val pop = n / total
        if (hsv[1] >= ACCENT_SAT && hsv[2] >= ACCENT_VAL) {
            // Vividness-weighted population: a small vivid splash can outrank
            // a large dull field.
            accents.add(AccCandidate(pop * (0.25f + 0.75f * hsv[1]), hsv[0], hsv[1], hsv[2]))
        } else {
            bases.add(BaseCandidate(pop, hsv[0], hsv[1], hsv[2], mean))
        }
    }

    // Theme bases first: the dominant muted/dark swatches that set the mood.
    bases.sortByDescending { it.pop }
    val baseOut = ArrayList<Rgb>()
    for (base in bases) {
        if (baseOut.size >= 2 || base.pop < BASE_MIN_POP) break
        if (base.s < NEUTRAL_SAT) {
            baseOut.add(tintedWhite(base.mean, base.v))
        } else {
            baseOut.add(hsvToRgbTriple(base.h, base.s, max(VALUE_FLOOR, base.v)))
        }
    }

    // Vivid accents fill the remaining slots, hue-diverse.
    accents.sortByDescending { it.score }
    val accentOut = ArrayList<Rgb>()
    val pickedHues = ArrayList<Float>()
    for (acc in accents) {
        if (baseOut.size + accentOut.size >= k) break
        if (pickedHues.all { hueDistance(acc.h, it) >= HUE_MIN_SEP }) {
            pickedHues.add(acc.h)
            accentOut.add(hsvToRgbTriple(acc.h, acc.s, max(VALUE_FLOOR, acc.v)))
        }
    }

    var out = baseOut + accentOut
    if (out.isEmpty()) return lowColourFallbackV1(px)

    // Order by hue so the cyclic gradient drifts smoothly between related hues
    // (tinted whites sort by their tint).
    out = out.sortedBy { rgbToHsv(it.first, it.second, it.third)[0] }

    // No weights — uniform cyclic gradient, matching syncoV2's v1 Palette.
    val w = List(out.size) { 1f / out.size }
    return AlbumColours(out, w)
}

/** Deterministic k-means over CIELAB; centroids seeded along sorted lightness. */
private fun kmeans(points: List<FloatArray>, kIn: Int): IntArray {
    val n = points.size
    val k = min(kIn, n)
    val labels = IntArray(n)
    if (k <= 0) return labels

    val order = (0 until n).sortedBy { points[it][0] }
    val centroids = Array(k) { i ->
        val idx = if (k == 1) 0 else (i.toLong() * (n - 1) / (k - 1)).toInt()
        points[order[idx]].copyOf()
    }

    repeat(KMEANS_ITERS) {
        for (i in 0 until n) {
            var best = 0
            var bestD = Float.MAX_VALUE
            for (c in 0 until k) {
                val d = sqDist(points[i], centroids[c])
                if (d < bestD) { bestD = d; best = c }
            }
            labels[i] = best
        }
        var moved = false
        for (c in 0 until k) {
            var cnt = 0
            var a0 = 0f; var a1 = 0f; var a2 = 0f
            for (i in 0 until n) if (labels[i] == c) { cnt++; a0 += points[i][0]; a1 += points[i][1]; a2 += points[i][2] }
            if (cnt == 0) continue
            val m = floatArrayOf(a0 / cnt, a1 / cnt, a2 / cnt)
            if (abs(m[0] - centroids[c][0]) > 1e-5f ||
                abs(m[1] - centroids[c][1]) > 1e-5f ||
                abs(m[2] - centroids[c][2]) > 1e-5f
            ) moved = true
            centroids[c] = m
        }
        if (!moved) return labels
    }
    return labels
}

private fun sqDist(a: FloatArray, b: FloatArray): Float {
    val d0 = a[0] - b[0]; val d1 = a[1] - b[1]; val d2 = a[2] - b[2]
    return d0 * d0 + d1 * d1 + d2 * d2
}

/** Distance on the hue wheel, 0..0.5, with hue expressed as a 0..1 fraction. */
private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 1f
    return min(d, 1f - d)
}

private fun rgbToLab(r: Float, g: Float, b: Float): FloatArray {
    fun lin(c: Float) = if (c > 0.04045f) ((c + 0.055f) / 1.055f).let { it * it * it } else c / 12.92f
    val lr = lin(r); val lg = lin(g); val lb = lin(b)
    val x = (lr * 0.4124f + lg * 0.3576f + lb * 0.1805f) / 0.95047f
    val y = lr * 0.2126f + lg * 0.7152f + lb * 0.0722f
    val z = (lr * 0.0193f + lg * 0.1192f + lb * 0.9505f) / 1.08883f
    fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
    val fx = f(x); val fy = f(y); val fz = f(z)
    return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
}

/** RGB to HSV with hue as a 0..1 fraction, matching Python's colorsys. */
private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val s = if (mx > 1e-6f) d / mx else 0f
    var h = 0f
    if (d > 1e-9f) {
        h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h < 0f) h += 1f
    }
    return floatArrayOf(h, s, mx)
}

private fun hsvToRgbTriple(h: Float, s: Float, v: Float): Rgb {
    val i = (h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1f - s)
    val q = v * (1f - s * f)
    val t = v * (1f - s * (1f - f))
    return when (i % 6) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
}
