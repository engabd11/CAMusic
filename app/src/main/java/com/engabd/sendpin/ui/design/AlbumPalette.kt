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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The colours a cover melts into: a bright [accent] plus ranked companion
 * [swatches] used for glows, gradients and avatars.
 *
 * Extraction is a direct port of the Claude Design prototype's live sampler, so
 * the app lands on the same colours the design does: pixels are binned by hue,
 * each bin's hue averaged *circularly* (so reds either side of 0° don't cancel),
 * and bins ranked by saturation-weighted mass. Android's Palette API is not used
 * here — it clusters in RGB space and picks a single swatch, which loses the
 * ranked secondary colours the design's glows are built from.
 */
data class AlbumPalette(
    val accent: Color = DefaultAccent,
    val swatches: List<Color> = FallbackPalette,
) {
    /** Companion swatch [i], wrapping around. */
    fun swatch(i: Int): Color = swatches[((i % swatches.size) + swatches.size) % swatches.size]
}

val LocalPalette = compositionLocalOf { AlbumPalette() }

private const val SAMPLE = 40      // the design samples a 40×40 downscale
private const val BINS = 18        // 20° hue buckets

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, l)))

/** One hue bucket's running sums. */
private class Bin {
    var w = 0f; var s = 0f; var l = 0f; var sin = 0f; var cos = 0f
}

private class Ranked(val weight: Float, val h: Float, val s: Float, val l: Float)

/** Bin [bmp] by hue and return the ranked palette, or null if it has no usable colour. */
internal fun paletteOf(bmp: Bitmap): AlbumPalette? {
    val small = if (bmp.width == SAMPLE && bmp.height == SAMPLE) bmp
    else Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, true)

    val px = IntArray(SAMPLE * SAMPLE)
    small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
    if (small !== bmp) small.recycle()

    val bins = Array(BINS) { Bin() }
    var total = 0f

    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        val l = (hi + lo) / 2f
        // Skip near-black and near-white: they carry no hue and would wash the accent out.
        if (l < 0.06f || l > 0.96f) continue

        var h = 0f
        var s = 0f
        if (d != 0f) {
            s = d / (1f - abs(2f * l - 1f))
            h = when (hi) {
                r -> 60f * ((((g - b) / d) % 6f))
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
        }
        if (h < 0f) h += 360f

        val w = max(s, 0.05f)
        val rad = h * Math.PI.toFloat() / 180f
        total += w

        val bin = bins[min(BINS - 1, floor(h / 20f).toInt())]
        bin.w += w
        bin.s += s * w
        bin.l += l * w
        bin.sin += sin(rad) * w
        bin.cos += cos(rad) * w
    }
    if (total <= 0f) return null

    val ranked = bins.mapNotNull { bin ->
        if (bin.w <= 0f) return@mapNotNull null
        var h = atan2(bin.sin, bin.cos) * 180f / Math.PI.toFloat()
        if (h < 0f) h += 360f
        val s = bin.s / bin.w
        val l = bin.l / bin.w
        Ranked(weight = bin.w * (0.35f + s), h = h, s = s, l = l)
    }.sortedByDescending { it.weight }.take(5)

    if (ranked.isEmpty()) return null

    val top = ranked.first()
    // The accent is pushed brighter and more saturated than the art so it stays
    // legible as a control colour against true black.
    val accent = hsl(top.h, (top.s + 0.24f).coerceIn(0.5f, 0.72f), (top.l + 0.12f).coerceIn(0.58f, 0.70f))
    val rest = ranked.drop(1).map { hsl(it.h, (it.s + 0.1f).coerceIn(0.36f, 0.58f), (it.l + 0.16f).coerceIn(0.58f, 0.72f)) }

    return AlbumPalette(accent = accent, swatches = listOf(accent) + rest)
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
