package com.engabd.sendpin.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The current album-derived accent, provided down the tree. */
val LocalAccent = compositionLocalOf { DefaultAccent }

fun Color.a(alpha: Float): Color = copy(alpha = alpha)

private fun saturate(amount: Float) =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(amount) })

// --- artwork --------------------------------------------------------------

/**
 * Album art at full source resolution. Coil otherwise downsamples to the view
 * box, which visibly softens a 1000px cover blown up to a phone's full width —
 * these covers are the loudest thing on the screen, so they load unclamped.
 */
@Composable
fun rememberArtRequest(url: String?, pixels: Int? = null): ImageRequest? {
    val ctx = LocalContext.current
    if (url.isNullOrBlank()) return null
    return remember(url, pixels) {
        ImageRequest.Builder(ctx)
            .data(url)
            .crossfade(220)
            .apply { if (pixels != null) size(pixels).scale(Scale.FILL) }
            .build()
    }
}

/**
 * The full-bleed album wash behind Now Playing. The cover is blown up, blurred
 * past recognition and over-saturated, then drowned in a scrim that reaches
 * pure #000 before the bottom — so on an OLED panel the art has no edge, it just
 * stops emitting. Sampled small on purpose: it is never seen in focus.
 */
@Composable
fun BoxScope.MeltBackdrop(url: String?, intensity: Float = 1f) {
    val art = rememberArtRequest(url, pixels = 192)
    if (art != null) {
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = saturate(1.7f),
            modifier = Modifier
                .matchParentSize()
                .scale(1.3f)
                .blur(64.dp, BlurredEdgeTreatment.Unbounded)
                .alpha(0.62f * intensity.coerceIn(0f, 1f)),
        )
    }
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(
                0f to Ink.a(0.28f),
                0.46f to Ink.a(0.66f),
                0.88f to Ink,
                1f to Ink,
            )
        )
    )
}

/**
 * A colour bloom cast behind a surface. Compose has no multi-shadow and its
 * elevation shadows only tint, so the design's `0 40px 90px <album colour>` is
 * drawn: a blurred copy of the surface's silhouette, offset down.
 */
@Composable
fun BoxScope.CastGlow(color: Color, shape: Shape, blurRadius: Dp, alpha: Float, offsetY: Dp = 0.dp) {
    Box(
        Modifier
            .matchParentSize()
            .offset(y = offsetY)
            .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
            .background(color.a(alpha), shape)
    )
}

/**
 * The cover itself. Non-square art is letterboxed against a blurred, saturated
 * copy of itself rather than dead black, so the tile always reads as one object,
 * and a diagonal gloss sells it as glass under a light.
 *
 * Sized to the largest square that fits the slot it is given, measuring *both*
 * axes. `aspectRatio()` alone only satisfies one of them: given a weighted row in
 * a Column it takes the full width and returns a height to match, which silently
 * overflows the slot and paints over whatever sits above it.
 */
@Composable
fun AlbumArt(
    url: String?,
    glow: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 0.dp,
    glowAlpha: Float = 0.45f,
    placeholder: ImageVector? = null,
) {
    val art = rememberArtRequest(url)
    // The cover sits right on the background — no rounded corner, no border, no
    // forced square. Its aspect ratio is whatever the image itself has, so a
    // tall album cover is tall and a wide one is wide. The hue melts into the
    // backdrop without an outer frame.
    Box(modifier, contentAlignment = Alignment.Center) {
        if (art != null) {
            // The blurred wash behind the art — fills the available space and
            // bleeds past the art's edges so the colour melts into the background.
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = saturate(1.7f),
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.3f)
                    .blur(64.dp, BlurredEdgeTreatment.Unbounded)
                    .alpha(0.45f * glowAlpha.coerceIn(0f, 1f)),
            )
            // The actual cover — fit, not crop, so the full artwork is visible.
            AsyncImage(
                model = art, contentDescription = "Album art", contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .shadow(20.dp, RoundedCornerShape(radius)),
            )
        } else if (placeholder != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(radius))
                    .background(Ink2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(placeholder, null, tint = TextFaint, modifier = Modifier.fillMaxSize(0.28f))
            }
        }
    }
}

// --- text helpers ---------------------------------------------------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = TextFaint,
        style = MaterialTheme.typography.labelSmall,
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
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .border(1.dp, outline, RoundedCornerShape(radius)),
        content = content,
    )
}

/**
 * Drag-down-to-dismiss, for a sheet drawn inside another screen's own Box.
 *
 * The sheet has to *own* the gesture. It sits over a Now Playing cover that is
 * itself draggable, and with nothing consuming the drag here a swipe aimed at the
 * sheet pulled the whole player down behind it and left the sheet hanging. Drags
 * the sheet's own content hasn't already taken — a list already at its top, or the
 * grabber at the sheet's head — move the sheet and go no further.
 */
@Composable
fun Modifier.dismissOnDragDown(onDismiss: () -> Unit, threshold: Dp = 110.dp): Modifier {
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return this
        .offset { IntOffset(0, offsetY.value.roundToInt()) }
        .pointerInput(Unit) {
            val height = size.height.toFloat()
            detectVerticalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (offsetY.value > thresholdPx) {
                            // Slide it the rest of the way out before the caller drops
                            // it, so the sheet leaves rather than blinking away.
                            offsetY.animateTo(height, tween(180))
                            onDismiss()
                        } else {
                            offsetY.animateTo(0f, tween(180))
                        }
                    }
                },
                onDragCancel = { scope.launch { offsetY.animateTo(0f, tween(180)) } },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    scope.launch { offsetY.snapTo((offsetY.value + dy).coerceAtLeast(0f)) }
                },
            )
        }
}

/**
 * Positions a [androidx.compose.ui.window.Popup] in the middle of the window,
 * wherever the thing that opened it happens to sit.
 *
 * Compose's alignment-based popups position against their *anchor*, which for a
 * badge wedged into a transport row puts the panel half off the screen. An info
 * popup belongs in the middle and owes nothing to what opened it.
 */
object WindowCenterPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = (windowSize.height - popupContentSize.height) / 2,
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
            style = MaterialTheme.typography.labelLarge,
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
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A segmented switch — the selected half rides on an accent lozenge. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .then(if (on) Modifier.shadow(12.dp, RoundedCornerShape(100), ambientColor = accent, spotColor = accent) else Modifier)
                    .clip(RoundedCornerShape(100))
                    .background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    color = if (on) Ink else TextMuted,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1,
                )
            }
        }
    }
}

/** A 34dp square glass control — the row of secondary actions under the cover. */
@Composable
fun IconChip(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .size(34.dp)
            .clip(shape)
            .background(if (active) accent.a(0.14f) else Glass)
            .border(1.dp, if (active) accent.a(0.4f) else Hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription,
            tint = tint ?: if (active) accent else TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The quality badge: what is actually coming out of the pipe. Sample rate and
 * bit depth are set in mono so the digits line up as the track changes, and a
 * hi-res stream lights the pill up rather than adding another chip.
 */
@Composable
fun QualityPill(
    text: String,
    modifier: Modifier = Modifier,
    hiRes: Boolean = false,
    lossless: Boolean = true,
    compact: Boolean = false,
) {
    val accent = LocalAccent.current
    val shape = if (compact) RoundedCornerShape(50) else RoundedCornerShape(11.dp)
    val tint = if (lossless) accent else TextMuted
    val hPad = if (compact) 8.dp else 13.dp
    val vPad = if (compact) 5.dp else 9.dp
    val dotSize = if (compact) 4.dp else 6.dp
    val fontSize = if (compact) 9.sp else 11.sp
    Row(
        modifier
            .then(if (hiRes) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (lossless) tint.a(if (hiRes) 0.16f else 0.12f) else Glass)
            .border(1.dp, tint.a(if (hiRes) 0.55f else 0.35f), shape)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        Box(
            Modifier
                .size(dotSize)
                .then(if (hiRes) Modifier.shadow(8.dp, CircleShape, ambientColor = tint, spotColor = tint) else Modifier)
                .clip(CircleShape)
                .background(tint)
        )
        Text(
            text,
            color = if (lossless) Color.White.a(0.92f) else TextMuted,
            style = if (compact) TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    else MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/**
 * The transport's primary key: an accent disc with a coloured bloom under it and
 * a top-edge highlight, so it sits above the art rather than on it.
 */
@Composable
fun PlayButton(playing: Boolean, size: Dp = 68.dp, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val fill by animateColorAsState(accent, tween(400), label = "playFill")
    Box(Modifier.size(size)) {
        CastGlow(fill, CircleShape, blurRadius = 26.dp, alpha = 0.55f, offsetY = 10.dp)
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(fill)
                .background(Brush.verticalGradient(0f to Color.White.a(0.28f), 0.55f to Color.Transparent))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "Pause" else "Play",
                tint = Ink,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

/** A circular determinate ring — download progress in the library. */
@Composable
fun RingProgress(progress: Float, modifier: Modifier = Modifier, size: Dp = 20.dp, stroke: Dp = 2.4.dp) {
    val accent = LocalAccent.current
    Box(
        modifier.size(size).drawBehind {
            val w = stroke.toPx()
            val inset = w / 2f
            drawArc(
                color = Color.White.a(0.12f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
                style = Stroke(width = w),
            )
            drawArc(
                color = accent, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    )
}

/** An artist's initial on a two-tone album-derived disc. */
@Composable
fun GradientAvatar(letter: String, index: Int, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val palette = LocalPalette.current
    val from = palette.swatch(index)
    val to = palette.swatch(index + 1).a(0.55f)
    Box(
        modifier
            .size(size)
            .shadow(14.dp, CircleShape, ambientColor = from, spotColor = from)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(from, to))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.take(1).uppercase(),
            color = Ink, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.33f).sp,
        )
    }
}

// --- slider ---------------------------------------------------------------

/**
 * Horizontal slider (tap or drag). [value] and [onChange] are 0f..1f. The filled
 * track glows in the accent and the knob stays white, so the scrubber reads
 * against any album colour.
 *
 * [onChange] fires continuously and should only move local UI state. [onCommit],
 * if given, fires once on release and is where a network seek belongs — sending
 * one per touch-move made the scrubber fight the server's replies and snap
 * backwards. While a drag is in progress the knob follows the finger and ignores
 * [value] entirely, so a late poll can't yank it out from under the user.
 *
 * [label] turns on the magnifier: a bubble above the knob showing where a release
 * would land, e.g. the timestamp being scrubbed to.
 */
@Composable
fun HSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    knob: Dp = 13.dp,
    accented: Boolean = true,
    onCommit: ((Float) -> Unit)? = null,
    label: ((Float) -> String)? = null,
) {
    val accent = LocalAccent.current
    var width by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val v = (if (dragging) dragValue else value).coerceIn(0f, 1f)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val f = (o.x / width).coerceIn(0f, 1f)
                    onChange(f)
                    commit(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragValue = (o.x / width).coerceIn(0f, 1f)
                        onChange(dragValue)
                    },
                    onDragEnd = { dragging = false; commit(dragValue) },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragValue = (change.position.x / width).coerceIn(0f, 1f)
                    onChange(dragValue)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50)).background(Color.White.a(0.14f)))
        Box(
            Modifier.fillMaxWidth(v).height(trackHeight)
                .then(if (accented) Modifier.shadow(10.dp, RoundedCornerShape(50), ambientColor = accent, spotColor = accent) else Modifier)
                .clip(RoundedCornerShape(50))
                .background(
                    if (accented) Brush.horizontalGradient(listOf(accent.a(0.5f), accent))
                    else Brush.horizontalGradient(listOf(Color.White.a(0.62f), Color.White.a(0.62f)))
                )
        )
        Box(
            Modifier
                .offset { IntOffset((v * width - knob.toPx() / 2f).roundToInt(), 0) }
                .then(if (accented) Modifier.shadow(10.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
                .size(if (dragging) knob * 1.35f else knob).clip(CircleShape).background(Color.White)
        )

        // Magnifier — rides above the knob while dragging, clamped so it stays on
        // screen at both ends of the track.
        //
        // `wrapContentSize(unbounded = true)` is load-bearing. This Box's parent is the
        // slider, which is a fixed 18.dp tall, and Box hands that down as a maximum: the
        // bubble was squashed to 18.dp, its 5.dp padding left the text an 8.dp band, and
        // the rounded clip cut the glyphs into stubs that read as underscores. The
        // `offset` only moves the bubble up; it grants it no height.
        if (dragging && label != null) {
            val text = label(v)
            Box(
                Modifier
                    .wrapContentSize(align = Alignment.BottomStart, unbounded = true)
                    .offset {
                        val half = BubbleWidth.toPx() / 2f
                        val x = (v * width - half).coerceIn(0f, (width - BubbleWidth.toPx()).coerceAtLeast(0f))
                        IntOffset(x.roundToInt(), -(BubbleLift.toPx()).roundToInt())
                    }
                    .width(BubbleWidth)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Ink3)
                    .border(1.dp, accent.a(0.55f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, color = TextPrimary, fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val BubbleWidth = 72.dp
private val BubbleLift = 38.dp
