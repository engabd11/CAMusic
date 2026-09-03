package com.engabd.sendpin.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.CoverPaletteOverride
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A small colour editor that lets the user override the palette the light-sync
 * engine extracts from this album's artwork.
 *
 * Offered from Now Playing's long-press sheet. It pre-fills the current
 * extracted colours, lets the user change up to four of them, and saves the
 * result to [AppSettings.coverPaletteOverrides] under a stable album key.
 */
@Composable
fun BoxScope.CoverPaletteEditor(
    /** The album this editor is for. Its name and artist build the stable key. */
    albumName: String,
    artistName: String?,
    /** The cover art URL to seed the initial palette from. */
    coverUrl: String?,
    /** Called when the user saves a new override, or null to clear it. */
    onSave: (CoverPaletteOverride?) -> Unit,
    onClose: () -> Unit,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The four colours being edited, as Compose Color objects.
    var colors by remember { mutableStateOf(List(4) { Color.DarkGray }) }
    var pickingIndex by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Seed from the cover's real palette when the sheet opens.
    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrBlank()) { loaded = true; return@LaunchedEffect }
        val seed = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val result = context.imageLoader.execute(request)
                val bmp = (result as? SuccessResult)?.drawable?.toBitmap()
                    ?: return@withContext null
                extractPreviewColors(bmp)
            } catch (_: Exception) { null }
        }
        seed?.let { colors = it }
        loaded = true
    }

    if (pickingIndex != null) {
        ColorPickerDialog(
            initial = colors[pickingIndex!!],
            onDismiss = { pickingIndex = null },
            onSelect = { color ->
                colors = colors.toMutableList().apply { set(pickingIndex!!, color) }
                pickingIndex = null
            },
        )
    }

    // Dismiss scrim.
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = systemNavInset() + 12.dp)
                .padding(horizontal = 18.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Light Sync colours",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Override the colours this album lights the room with.",
                color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                colors.forEachIndexed { i, color ->
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(2.dp, if (pickingIndex == i) LocalAccent.current else Hairline, CircleShape)
                            .clickable { pickingIndex = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OledButton(
                    text = "Reset to cover",
                    accent = LocalAccent.current,
                    outline = true,
                    modifier = Modifier.weight(1f),
                ) {
                    onSave(null)
                    onClose()
                }
                OledButton(
                    text = if (loaded) "Save" else "Loading…",
                    accent = LocalAccent.current,
                    enabled = loaded,
                    modifier = Modifier.weight(1f),
                ) {
                    val argbs = colors.map { it.toArgb() }
                    onSave(CoverPaletteOverride(argbs, CoverPaletteOverride.Mode.OVERRIDE))
                    onClose()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Pick the most saturated and brightest colours for the editor preview. */
private fun extractPreviewColors(bmp: Bitmap): List<Color> {
    val scaled = Bitmap.createScaledBitmap(bmp, 64, 64, true)
    val px = IntArray(64 * 64)
    scaled.getPixels(px, 0, 64, 0, 0, 64, 64)
    if (scaled !== bmp) scaled.recycle()

    // Group by hue bucket, then pick the most vivid colour in each bucket.
    // Each entry is (vividness, ARGB pixel) — vividness only orders the bucket,
    // the pixel is what the swatch shows.
    val buckets = Array(4) { mutableListOf<Pair<Float, Int>>() }
    for (p in px) {
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max > 0f) (max - min) / max else 0f
        val hue = rgbHue(r, g, b)
        val bucket = ((hue / 360f) * 4).toInt().coerceIn(0, 3)
        buckets[bucket].add((sat * max) to p)
    }

    return buckets.map { list ->
        val chosen = list.maxByOrNull { it.first }?.second ?: return@map Color.DarkGray
        Color(chosen)
    }
}

private fun rgbHue(r: Float, g: Float, b: Float): Float {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> ((g - b) / d + 6f) % 6f
        max == g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return if (h < 0f) h + 360f else h
}

@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onSelect: (Color) -> Unit,
) {
    // A deliberately simple picker: a grid of preset swatches plus a custom
    // HSV slider. Full HSV colour wheels belong in a dedicated component; this
    // keeps the sheet small and the touch targets large.
    val presets = remember {
        listOf(
            Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00), Color(0xFF00FF00),
            Color(0xFF0000FF), Color(0xFF4B0082), Color(0xFF9400D3), Color(0xFFFF1493),
            Color(0xFF00FFFF), Color(0xFFFFA500), Color(0xFF32CD32), Color(0xFF1E90FF),
            Color(0xFFFFFFFF), Color(0xFF808080), Color(0xFF000000), Color(0xFF8B4513),
        )
    }
    var customHue by remember { mutableStateOf(0f) }
    var customSat by remember { mutableStateOf(1f) }
    var customVal by remember { mutableStateOf(1f) }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.a(0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Ink2)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Pick a colour",
                color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
            )
            Spacer(Modifier.height(16.dp))

            val preview = hsvToComposeColor(customHue, customSat, customVal)
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(2.dp, Hairline, CircleShape),
            )

            Spacer(Modifier.height(16.dp))
            Text("Hue", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customHue / 360f,
                onChange = { customHue = it * 360f },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text("Saturation", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customSat,
                onChange = { customSat = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text("Brightness", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
            HSlider(
                value = customVal,
                onChange = { customVal = it },
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OledButton("Cancel", LocalAccent.current, outline = true, modifier = Modifier.weight(1f)) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                OledButton("Choose this", LocalAccent.current, modifier = Modifier.weight(1f)) { onSelect(preview) }
            }
        }
    }
}

private fun hsvToComposeColor(h: Float, s: Float, v: Float): Color {
    val i = (h / 60f).toInt() % 6
    val f = h / 60f - (h / 60f).toInt()
    val p = (v * (1f - s) * 255f).toInt()
    val q = (v * (1f - f * s) * 255f).toInt()
    val t = (v * (1f - (1f - f) * s) * 255f).toInt()
    val vv = (v * 255f).toInt()
    val (r, g, b) = when (i) {
        0 -> Triple(vv, t, p)
        1 -> Triple(q, vv, p)
        2 -> Triple(p, vv, t)
        3 -> Triple(p, q, vv)
        4 -> Triple(t, p, vv)
        else -> Triple(vv, p, q)
    }
    return Color(0xFF000000 or ((r.coerceIn(0, 255) shl 16).toLong()) or ((g.coerceIn(0, 255) shl 8).toLong()) or b.coerceIn(0, 255).toLong())
}

@Composable
private fun OledButton(
    text: String,
    accent: Color,
    enabled: Boolean = true,
    outline: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = if (outline) Glass else accent
    val border = if (outline) Hairline else Color.Transparent
    val label = if (outline) TextMuted else Ink
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (enabled) fill else Glass)
            .border(1.dp, if (enabled) border else Hairline, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) label else TextMuted,
            fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
        )
    }
}
