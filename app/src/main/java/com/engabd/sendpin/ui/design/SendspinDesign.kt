package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The current album-derived accent, provided down the tree. */
val LocalAccent = compositionLocalOf { DefaultAccent }

fun Color.a(alpha: Float): Color = copy(alpha = alpha)

// --- album accent extraction ---------------------------------------------

private fun boostAccent(c: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(c.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.5f, 0.72f)
    hsl[2] = hsl[2].coerceIn(0.56f, 0.70f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Load [url] and derive a bright accent from its dominant/vibrant colour. */
@Composable
fun rememberAlbumAccent(url: String?): Color {
    val ctx = LocalContext.current
    var accent by remember { mutableStateOf(DefaultAccent) }
    LaunchedEffect(url) {
        accent = DefaultAccent
        if (url.isNullOrBlank()) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) {
            try {
                val res = ctx.imageLoader.execute(
                    ImageRequest.Builder(ctx).data(url).allowHardware(false).size(72).build()
                )
                (res as? SuccessResult)?.drawable?.toBitmap()
            } catch (_: Exception) { null }
        } ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bmp).maximumColorCount(16).generate() }
        val sw = palette.vibrantSwatch ?: palette.lightVibrantSwatch
            ?: palette.mutedSwatch ?: palette.dominantSwatch
        sw?.let { accent = boostAccent(Color(it.rgb)) }
    }
    return accent
}

// --- text helpers ---------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = TextFaint,
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

// --- containers -----------------------------------------------------------

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    fill: Color = Glass,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .border(1.dp, Hairline, RoundedCornerShape(radius)),
        content = content,
    )
}

/** A soft radial glow (the design's "bloom"). Place inside a Box behind content. */
@Composable
fun Bloom(color: Color, size: Dp, x: Dp, y: Dp, alpha: Float = 0.5f) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .background(
                Brush.radialGradient(listOf(color.a(alpha), Color.Transparent)),
                CircleShape,
            )
    )
}

// --- buttons --------------------------------------------------------------

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        modifier
            .clip(RoundedCornerShape(100))
            .background(if (selected) accent else Glass)
            .border(1.dp, if (selected) accent else Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Ink else TextSecondary,
            fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun ToggleChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accent.a(0.14f) else Glass)
            .then(if (selected) Modifier.border(1.dp, accent.a(0.5f), RoundedCornerShape(9.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) accent else TextMuted,
            fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        )
    }
}

@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier
            .clip(RoundedCornerShape(100))
            .background(accent.a(0.12f))
            .border(1.dp, accent.a(0.32f), RoundedCornerShape(100))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(text, color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

// --- slider ---------------------------------------------------------------

/** Horizontal slider (tap or drag). [value] and [onChange] are 0f..1f. */
@Composable
fun HSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    knob: Dp = 13.dp,
) {
    val accent = LocalAccent.current
    var width by remember { mutableStateOf(1) }
    Box(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .pointerInput(Unit) {
                detectTapGestures { o -> onChange((o.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50)).background(Color.White.a(0.14f)))
        Box(
            Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(accent.a(0.45f), accent)))
        )
        Box(
            Modifier
                .offset { IntOffset((value.coerceIn(0f, 1f) * width - knob.toPx() / 2f).roundToInt(), 0) }
                .size(knob).clip(CircleShape).background(accent)
        )
    }
}
