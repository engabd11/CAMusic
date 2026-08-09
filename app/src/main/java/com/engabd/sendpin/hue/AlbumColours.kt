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
 * This ranks purely by **how much of the cover each colour occupies**, because
 * the weights are dwell time: a 90% green sleeve should light the room green 90%
 * of the time. Near-duplicate hues pool their share, and once `k` swatches are
 * picked the remaining clusters fold into the nearest by hue, so the weights
 * always describe the whole cover rather than a sample of it.
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

private const val KMEANS_ITERS = 14

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

/** Faithful fallback for a cover with almost no usable colour. */
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
