package com.engabd.sendpin.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** The current album-derived accent, provided down the tree. */
val LocalAccent = compositionLocalOf { DefaultAccent }

/**
 * The gap between a title and the subtitle under it.
 *
 * Two `Text`s stacked in a `Column` sit on their own line boxes with nothing
 * between them, which packs a bold 15sp title against an 11sp subtitle tightly
 * enough that the pair reads as one smudged block rather than two lines. It shows up
 * everywhere the app puts a label over a detail: speaker rows, settings rows, track
 * rows, the Lights tab, the mini player.
 *
 * One token rather than a hand-placed `Spacer` at each site, so the whole app moves
 * together and there is a single thing to turn if it wants to be looser still.
 * Applied as the `Column`'s `verticalArrangement`, which also spaces any third line
 * consistently instead of only the first gap.
 */
val TitleGap = 3.dp

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
    // Recorded as the backdrop for every glass surface on this screen — see Backdrop.kt.
    // Applied here rather than at each of the five call sites so any screen that has a
    // wash gets real glass by having one, and a screen without one leaves the layer
    // unrecorded and its panels fall back to a flat fill. The wrapping Box is what gives
    // the recording a single surface to capture: the art and its scrim are two draws, and
    // glass wants the composite of both.
    Box(Modifier.matchParentSize().backdropSource()) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = saturate(2.7f),
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
 * The cover, following [url], turning the page whenever [flipKey] changes album.
 *
 * The convenience form: it owns its own [SettledArt] and nothing else can see it.
 * Fine for anywhere the cover is the only thing painted from the artwork. Now
 * Playing paints it twice — the sleeve and the wash behind it — so it hoists the
 * state and calls the overload below.
 */
@Composable
fun AlbumArt(
    url: String?,
    glow: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 0.dp,
    glowAlpha: Float = 0.45f,
    placeholder: ImageVector? = null,
    /**
     * Shared-element key for the cover, or null for no flight.
     *
     * Applied to the cover itself rather than to the [modifier] the caller
     * passes, because that one lands on the outer Box — which is the whole
     * letterboxed slot including the blurred wash bleeding past its edges. The
     * thing that flies between two screens is the artwork, not the wash.
     */
    sharedArtKey: String? = null,
    /**
     * Identity of the *album* on show, or null to never flip.
     *
     * When this changes to a different non-null value the cover turns over like a
     * page — see [FLIP_MS]. Deliberately not [url]: a server that hands out per-track
     * artwork changes the url between two tracks of the same record, and flipping
     * there turns the page onto the identical sleeve, which reads as a glitch rather
     * than a transition. `album|artist` is the strongest key available — Music
     * Assistant tracks carry no album id at all.
     */
    flipKey: Any? = null,
) = AlbumArt(
    art = rememberSettledArt(url, flipKey),
    glow = glow,
    modifier = modifier,
    radius = radius,
    glowAlpha = glowAlpha,
    placeholder = placeholder,
    sharedArtKey = sharedArtKey,
)

/**
 * The cover itself. Non-square art is letterboxed against a blurred, saturated
 * copy of itself rather than dead black, so the tile always reads as one object.
 *
 * Sized to the largest square that fits the slot it is given, measuring *both*
 * axes. `aspectRatio()` alone only satisfies one of them: given a weighted row in
 * a Column it takes the full width and returns a height to match, which silently
 * overflows the slot and paints over whatever sits above it.
 *
 * Takes its [art] hoisted, so a caller that paints the same picture somewhere else
 * — the full-bleed wash behind Now Playing — can stay in step with the half-turn
 * reveal instead of changing a quarter of a second early. Callers with nothing to
 * keep in step use the [url]/[flipKey] overload above.
 */
@Composable
fun AlbumArt(
    art: SettledArt,
    glow: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 0.dp,
    glowAlpha: Float = 0.45f,
    placeholder: ImageVector? = null,
    sharedArtKey: String? = null,
) {
    val request = rememberArtRequest(art.url)
    val shape = remember(radius) { RoundedCornerShape(radius) }
    // The cover sits right on the background — no rounded corner, no border, no
    // forced square. Its aspect ratio is whatever the image itself has, so a
    // tall album cover is tall and a wide one is wide. The hue melts into the
    // backdrop without an outer frame.
    Box(modifier, contentAlignment = Alignment.Center) {
        if (request != null) {
            // The blurred wash behind the art — fills the available space and
            // bleeds past the art's edges so the colour melts into the background.
            //
            // Its alpha rides the turn: the room dims as the page passes edge-on and
            // comes back up as the new sleeve lands. Set inside a graphicsLayer rather
            // than via Modifier.alpha, so reading the progress recomposes nothing.
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = saturate(1.7f),
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.3f)
                    .blur(64.dp, BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer {
                        val t = art.turn()
                        alpha = 0.45f * glowAlpha.coerceIn(0f, 1f) *
                            (1f - FLIP_WASH_DIP * sin(t * PI).toFloat())
                    },
            )
            // The actual cover — fit, not crop, so the full artwork is visible.
            //
            // The flip lives here rather than on the outer Box on purpose: the
            // blurred wash behind is ambient light in the room, not part of the
            // sleeve, and turning it over with the cover looks like the whole screen
            // pivoting instead of a page.
            AsyncImage(
                model = request, contentDescription = "Album art", contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // Before the layer, so the flight animates the artwork's own
                    // bounds. After it, the shared element would carry a
                    // shadow-casting render node through the transition and the
                    // interpolated bounds would include its spread.
                    .let { if (sharedArtKey != null) it.sharedArt(sharedArtKey) else it }
                    // Attached unconditionally, even at rest. Added and removed around
                    // each turn, it was a structural change to the modifier chain at
                    // the two moments the cover could least afford one.
                    .graphicsLayer {
                        // 0 -> 90, then -90 -> 0. Never past edge-on, so no back
                        // face is ever shown and the new cover does not need
                        // un-mirroring — and it reads as the next page swinging in
                        // from the other side rather than one card spinning round.
                        val t = art.turn()
                        rotationY = if (t <= 0.5f) t * 180f else (t - 1f) * 180f
                        // A page hinged along an edge rolls as it comes over. Under two
                        // degrees: enough to take the rigidity out, not enough to read
                        // as a motion of its own.
                        rotationZ = FLIP_TILT * sin(t * 2 * PI).toFloat()
                        // Compose defaults to 8f, which is a heavy fisheye at 90
                        // degrees. This is a sleeve at arm length, not a billboard.
                        cameraDistance = FLIP_CAMERA_DISTANCE * density
                        val dip = 1f - FLIP_SCALE_DIP * sin(t * PI).toFloat()
                        scaleX = dip
                        scaleY = dip
                        // The shadow the sleeve casts, swelling as the page lifts and
                        // settling as it lands. Here rather than in a Modifier.shadow
                        // because that allocates a second render node and rebuilds it
                        // whenever the elevation changes — which, for something that
                        // moves every frame, is every frame.
                        shadowElevation = FLIP_SHADOW_DP * density *
                            (1f + FLIP_SHADOW_LIFT * sin(t * PI).toFloat())
                        this.shape = shape
                        clip = radius > 0.dp
                    },
            )
        } else if (placeholder != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Ink2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(placeholder, null, tint = TextFaint, modifier = Modifier.fillMaxSize(0.28f))
            }
        }
    }
}

/** How far the ambient wash dims as the page passes edge-on. */
private const val FLIP_WASH_DIP = 0.35f

/** How much deeper the cast shadow gets at edge-on, as a fraction of its resting depth. */
private const val FLIP_SHADOW_LIFT = 0.9f

// --- layout ---------------------------------------------------------------

/**
 * Lets this content spill [horizontal] past the padding its parent imposed, on both
 * sides.
 *
 * For a horizontal scroller inside a padded vertical one. A shelf that stops at the
 * screen margin reads as having ended there, so the row wants to run edge to edge
 * with the margin expressed as its own `contentPadding` — tiles then scroll *under*
 * the margin rather than stopping at it. Compose has no negative padding, and the
 * grid item has already been measured inside the parent's insets by the time it is
 * composed, so the width has to be handed back by measuring wider and placing left.
 */
fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    // Nothing to bleed back out of. An unbounded parent never imposed a margin, and
    // widening infinity is not a width — it is a crash a few frames later.
    if (constraints.maxWidth == Constraints.Infinity) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val inset = horizontal.roundToPx()
    val width = constraints.maxWidth + inset * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    // Reports the original width, so the parent's own layout is untouched — only the
    // painting and the touch area extend past it.
    layout(constraints.maxWidth, placeable.height) { placeable.place(-inset, 0) }
}

// --- colour ---------------------------------------------------------------

/**
 * The album accent, eased into rather than cut to.
 *
 * [LocalAccent] itself is deliberately *not* animated at the provider. It is a plain
 * `compositionLocalOf` read at several hundred sites, so handing the tree a new
 * colour on every frame of a transition would recompose all of them for the length
 * of it — a recomposition storm, on every change of album, to smooth a colour most
 * of those sites only use at 12% alpha.
 *
 * So the easing is done by the loud consumers instead: the handful of components
 * drawn in a solid or near-solid fill of the accent, where a jump between two album
 * colours is a visible cut. Everything faint enough to get away with it keeps
 * reading [LocalAccent] straight.
 *
 * A colour, so an `effects` spec — a spatial one overshoots the target hue on the
 * way in and reads as a flicker. See [Motion].
 */
@Composable
fun rememberAccent(): Color {
    val target = LocalAccent.current
    val eased by animateColorAsState(target, Motion.effects(), label = "accent")
    return eased
}

// --- gesture feedback -----------------------------------------------------

/**
 * A surface that takes a little weight under the finger. Held together so the scale
 * and the [interactions] driving it cannot drift apart — a caller that forgets to
 * hand the source to its own `clickable` gets a card that never responds, which is
 * exactly the bug this replaces.
 */
@Stable
class PressScale internal constructor(
    val interactions: MutableInteractionSource,
    private val scale: State<Float>,
) {
    /** Read inside a `graphicsLayer`, never in composition. */
    fun scale(): Float = scale.value
}

/**
 * Gesture feedback for a tappable surface.
 *
 * Scale only — never alpha, which belongs on an `effects` spec (see [Motion]). One
 * primitive rather than the same six lines in every tile: the cover tiles and the
 * library rows had none at all while the category cards beside them responded, and
 * the inert ones read as the parts of the grid that had not finished loading.
 */
@Composable
fun rememberPressScale(down: Float = 0.97f): PressScale {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) down else 1f,
        animationSpec = Motion.spatialFast(),
        label = "press",
    )
    return remember(interactions, scale) { PressScale(interactions, scale) }
}

/** Applies [press] to this surface. Goes first in the chain, so the whole card moves. */
fun Modifier.pressScale(press: PressScale) = graphicsLayer {
    val s = press.scale()
    scaleX = s
    scaleY = s
}

/**
 * The chevron on a row that opens and closes.
 *
 * One composable rather than the three hand-rolled versions this replaces, which had
 * drifted into two different behaviours that were wrong in the same way: the chevron
 * was the only part of a disclosure that did not animate. Two of them swapped the
 * glyph outright — `if (open) ExpandLess else ExpandMore` — which is a hard cut
 * between two different drawings; the third rotated on
 * `Modifier.rotate(if (expanded) 180f else 0f)`, which snaps the arrow through half
 * a turn in one frame. In all three the panel below glided open on a spring while
 * the arrow that opened it jumped, and a control that moves less smoothly than the
 * thing it controls reads as the control having been missed.
 *
 * Not for the two dropdown indicators in `DspScreen` that use the same glyph. Those
 * open a `DropdownMenu` in its own window rather than disclosing anything in place,
 * so there is nothing below them for the arrow to be out of step with, and they
 * never pointed the other way to begin with.
 *
 * So: always [Icons.Default.ExpandMore], turned. The glyph never changes, which is
 * what makes the rotation legible as the *same* arrow pointing the other way.
 *
 * On a spatial spec, because a rotation is a movement — see [Motion]. The overshoot
 * is the point: the arrow settles a few degrees past 180° and comes back, which is
 * what makes a half-turn read as a flick rather than a servo.
 *
 * `graphicsLayer` rather than `Modifier.rotate` so the angle is read at draw time.
 * `rotate` is `graphicsLayer` underneath, but it takes the angle by value, so it is
 * read during composition and every frame of the turn recomposes this Icon. Read in
 * the lambda instead, the same turn costs one layer invalidation per frame and no
 * recomposition at all.
 *
 * Under reduced motion the spec still applies and Compose's duration scale collapses
 * it to a single frame, which lands the arrow at the correct angle immediately —
 * this is finite motion, so it needs no [LocalReducedMotion] handling of its own.
 */
@Composable
fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = TextMuted,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spatial(),
        label = "chevron",
    )
    Icon(
        Icons.Default.ExpandMore,
        contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = turn },
    )
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

/**
 * A highlight sweeping across a loading placeholder.
 *
 * Overlaid on whatever fill the caller already drew rather than replacing it, so a
 * skeleton keeps its own tone and this only adds the movement. Drawn as a soft band
 * on a diagonal, which reads as light passing over a surface; a hard-edged or purely
 * horizontal sweep reads as a scanning bar instead.
 *
 * **Returns the receiver untouched when motion is off**, and that is the whole reason
 * `LocalReducedMotion` exists rather than a duration multiplier. A shimmer is an
 * [androidx.compose.animation.core.InfiniteTransition], and Compose *suspends* one of
 * those at a duration scale of 0 rather than ending it — so a scaled-to-zero shimmer
 * would freeze as a gradient stopped part-way across the tile, which looks like a
 * rendering fault rather than a placeholder. Off, the caller's flat fill is exactly
 * the right answer, so this gets out of the way and lets it show.
 */
@Composable
fun Modifier.shimmer(highlight: Color = Color.White.copy(alpha = 0.055f)): Modifier {
    if (LocalReducedMotion.current) return this
    val sweep = rememberInfiniteTransition(label = "shimmer")
    val progress by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SHIMMER_PERIOD_MS, easing = LinearEasing),
            // Restart, not reverse: light travels one way. Reversing makes the band
            // walk back across the tile, which reads as a scrubber rather than a wait.
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
    return drawWithContent {
        drawContent()
        val band = size.width * SHIMMER_BAND_FRACTION
        // Travels from fully off the leading edge to fully off the trailing one, so
        // there is a clean gap between passes instead of a band always on screen.
        val x = -band + progress * (size.width + 2f * band)
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to highlight,
                1f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + band, size.height),
            )
        )
    }
}

/** One full sweep. Slow enough to read as breathing rather than as activity. */
private const val SHIMMER_PERIOD_MS = 1400

/** Band width as a fraction of the placeholder's own width. */
private const val SHIMMER_BAND_FRACTION = 0.55f

// --- containers -----------------------------------------------------------

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    fill: Color = Glass,
    content: @Composable BoxScope.() -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(radius)
    Box(
        // Real glass wherever a backdrop has been provided, the flat fill everywhere
        // else — and on those screens the two are the same thing, since blurring a flat
        // Ink background returns flat Ink. See Backdrop.kt.
        modifier.glassSurface(shape, tint = fill, border = outline),
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
 *
 * [enabled] turns the *gesture* off without taking the modifier out of the chain,
 * which matters for a sheet that pages between two views: dropping the modifier
 * conditionally would drop the entry animation's state with it and replay the
 * slide-up every time the sheet changed page. A page that stacks horizontal
 * sliders — [com.engabd.sendpin.ui.screens.CoverPaletteEditor]'s colour picker —
 * passes false, because a drag that leaves the slider's own axis is a missed
 * slider adjustment there, not a request to throw the sheet away.
 */
@Composable
fun Modifier.dismissOnDragDown(
    onDismiss: () -> Unit,
    threshold: Dp = 110.dp,
    enabled: Boolean = true,
): Modifier {
    val scope = rememberCoroutineScope()
    /** How far the finger has dragged the sheet down, in pixels. */
    val offsetY = remember { Animatable(0f) }

    // Slide up on first appearance. Every sheet already left beautifully — dragged
    // under a finger, or thrown the rest of the way — and then arrived by simply
    // existing, one frame absent and the next frame whole.
    //
    // Expressed as a *fraction of the sheet's own height* rather than a pixel count,
    // and applied in `graphicsLayer`, where `size` is the measured size at draw time.
    // The first attempt at this started at a fixed large pixel offset and waited for
    // `onSizeChanged` to report the real height before animating — which strands the
    // sheet far off-screen for good if that report never arrives, and it didn't: the
    // sheets stopped appearing entirely. There is no chicken-and-egg here, because
    // nothing has to know the height before layout has run.
    var shown by remember { mutableStateOf(false) }
    val enter by animateFloatAsState(
        targetValue = if (shown) 0f else 1f,
        animationSpec = Motion.spatial(),
        label = "sheetEnter",
    )
    LaunchedEffect(Unit) { shown = true }

    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }

    return this
        // Both the entry and the drag ride here: a translation is a draw-time property,
        // so dragging a sheet does not re-layout the list inside it on every frame.
        .graphicsLayer { translationY = offsetY.value + enter * size.height }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val height = size.height.toFloat()
            detectVerticalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (offsetY.value > thresholdPx) {
                            // Slide it the rest of the way out before the caller drops
                            // it, so the sheet leaves rather than blinking away.
                            //
                            // On [Motion.dismissOffsetPx], not `spatial()`, and for both
                            // of the reasons that spec exists. `onDismiss()` waits on
                            // this call returning, and a generic `spring<Float>()`
                            // settles against a 0.01px threshold — over a travel of the
                            // sheet's whole height that is a long tail spent correcting
                            // error nobody can see, with the caller still holding a
                            // sheet that has visibly gone. And a dismissal should not
                            // overshoot: `spatial()`'s ζ = 0.8 walks the sheet back up
                            // toward the viewer on its way out.
                            offsetY.animateTo(height, Motion.dismissOffsetPx())
                            onDismiss()
                        } else {
                            // Springs back rather than easing back: the sheet has just
                            // been thrown by a finger, and a spring is what carries the
                            // momentum of that gesture into the settle. Overshoot is
                            // right here — the sheet is arriving, not leaving — so this
                            // is [Motion.spatialOffsetPx] rather than the dismissal
                            // spec, but it needs the same pixel-scale threshold.
                            offsetY.animateTo(0f, Motion.spatialOffsetPx())
                        }
                    }
                },
                onDragCancel = { scope.launch { offsetY.animateTo(0f, Motion.spatialOffsetPx()) } },
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

/**
 * A soft radial glow (the design's "bloom"). Place inside a Box behind content. */
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

/**
 * Chameleon Canvas & Bloom: the album's colours bleeding across the player canvas.
 *
 * Painted *over* [MeltBackdrop] rather than under it. The backdrop is the cover
 * blurred to 64dp and drowned in a scrim that reaches solid black from two-thirds
 * of the way down — and the first cut of this effect painted its blooms behind
 * that scrim, so switching the setting on changed nothing anyone could see. From
 * over the scrim the same blooms are the room's light: a large wash of the album's
 * primary swatch off the top-left, its companion off the top-right, and two faint
 * pools low in the corners so the whole canvas reads as lit by the record rather
 * than tinted at the top edge only.
 *
 * Never over the content: this is a sibling of the player column, drawn before it,
 * so text and controls keep their contrast however bright the album is. Colours
 * ride [Motion.effects()] so a track change melts the room to the new palette
 * instead of snapping, and the level fades with [washDim] so an idle player keeps
 * the canvas dark.
 *
 * Placed after the backdrop in the caller's [BoxScope] — `Box` children paint in
 * declaration order, so "before the content" is the whole job.
 */
@Composable
fun BoxScope.ChameleonBloom(palette: AlbumPalette, washDim: Float) {
    val level = washDim.coerceIn(0f, 1f)
    if (level <= 0f) return

    // Palette colours animate rather than snap: this layer is the most exposed
    // colour in the app — full-bleed, over the scrim — so a track change must
    // melt, not flip.
    val primary by animateColorAsState(
        palette.swatch(0), Motion.effects(), label = "chameleonPrimary",
    )
    val secondary by animateColorAsState(
        palette.swatch(1), Motion.effects(), label = "chameleonSecondary",
    )

    Bloom(primary, 540.dp, (-120).dp, (-180).dp, 0.50f * level)
    Bloom(secondary, 460.dp, (-60).dp, (-40).dp, 0.34f * level)
    Bloom(secondary, 420.dp, (-140).dp, 340.dp, 0.20f * level)
    Bloom(primary, 380.dp, 40.dp, 320.dp, 0.24f * level)
}

/**
 * The wash a full-screen page wears over [com.engabd.sendpin.ui.theme.Ink]: the
 * playing album's own colour, bloomed out of the top corner.
 *
 * Named rather than repeated because it is the thing that makes the app feel like
 * one app. Every destination is the same near-black ground, and the only reason
 * two of them look different is the record that is on — so a page that reaches for
 * some *other* colour, or for none, reads as a screen from a different application
 * bolted on. Light Sync was exactly that: its wash was the ambient accent, which is
 * a fixed colour whenever the listener has chosen one, and it went flat grey the
 * moment the show was switched off.
 *
 * [LocalPalette]'s first swatch and not [LocalAccent], for that reason: the accent
 * is a *preference* that only follows the artwork on one of its three settings,
 * while the palette always does. A page that wants to say something about its own
 * state should say it in [alpha] — brighter while something is running — and leave
 * the hue to the music.
 */
@Composable
fun PageBloom(
    alpha: Float = 0.30f,
    size: Dp = 460.dp,
    x: Dp = (-60).dp,
    y: Dp = (-70).dp,
) {
    Bloom(LocalPalette.current.swatch(0), size, x, y, alpha)
}

/**
 * A small static badge marking a feature as not finished.
 *
 * Not a [Pill]: this is not a control and must not look like one — nothing about it
 * is tappable, and the whole point is that it sits beside a title without competing
 * with it. Amber rather than the accent, because the accent is the app's "this is
 * on" colour and an experimental feature is not making a claim about its state.
 */
@Composable
fun ExperimentalBadge(modifier: Modifier = Modifier, label: String = "Experimental") {
    Box(
        modifier
            .clip(RoundedCornerShape(100))
            .background(WarnAmber.a(0.14f))
            .border(1.dp, WarnAmber.a(0.35f), RoundedCornerShape(100))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = WarnAmber,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

// --- buttons --------------------------------------------------------------

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = rememberAccent()
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
    val accent = rememberAccent()
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
    val accent = rememberAccent()
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
    val accent = rememberAccent()
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .size(34.dp)
            // The chip row sits directly over the album wash, so this is the surface
            // backdrop blur does the most for. A tighter radius than the default: at
            // 34dp square, a wide blur samples so far outside the chip that every chip
            // in the row ends up showing the same average and the glass reads as flat.
            .glassSurface(
                shape,
                tint = if (active) accent.a(0.14f) else Glass,
                blurRadius = 16.dp,
                border = if (active) accent.a(0.4f) else Hairline,
            )
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
    val vPad = if (compact) 7.dp else 9.dp
    val dotSize = if (compact) 4.dp else 6.dp
    val fontSize = if (compact) 10.sp else 11.sp
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
            color = if (lossless) inkOn(0.92f) else TextMuted,
            style = if (compact) TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            // The label grew a live bitrate on the MPD path, where it moves from
            // second to second. Clipping the tail of a long one is the right
            // failure on a narrow phone; pushing the transport icons apart is not.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The transport's primary key: an accent disc with a coloured bloom under it and
 * a top-edge highlight, so it sits above the art rather than on it.
 */
@Composable
fun PlayButton(playing: Boolean, size: Dp = 68.dp, onClick: () -> Unit) {
    val fill = rememberAccent()
    Box(Modifier.size(size)) {
        CastGlow(fill, CircleShape, blurRadius = 26.dp, alpha = 0.55f, offsetY = 10.dp)
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(fill)
                // Stays literal white in every theme: this is a specular highlight on a
                // coloured disc, not a surface tint. Inverting it on a light theme would
                // put a dark smear across the top of the play button.
                .background(Brush.verticalGradient(0f to Color.White.a(0.25f), 0.55f to Color.Transparent))
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
    val accent = rememberAccent()
    // Read in composition: the lambda below is a DrawScope, not a composable.
    val track = inkOn(0.12f)
    Box(
        modifier.size(size).drawBehind {
            val w = stroke.toPx()
            val inset = w / 2f
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
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

/**
 * A horizontal determinate bar.
 *
 * The flat counterpart to [RingProgress], for the places where the question is "how far
 * through" rather than "is this row busy" — a library sweep, a share of the disk. Both
 * the analysis card and the downloads storage panel drew their own before this; they
 * were three pixels tall in one place and four in the other.
 */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = LocalAccent.current,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(100))
            .background(inkOn(0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(100))
                .background(color),
        )
    }
}

/**
 * A cover for something that has none, drawn from the album palette.
 *
 * Not every playlist has artwork — a smart playlist has nothing to make one from, and
 * plenty of hand-made ones are never given one — and until now those fell to a small
 * grey glyph on a flat panel. In a grid of album covers that reads as a broken image
 * rather than as a design, and a shelf of them reads as several.
 *
 * So one is generated instead, in the language the rest of the app already speaks: the
 * two-swatch diagonal of [GradientAvatar], the wash-and-ghosted-glyph of the library's
 * category cards, and the same hue source as both — [LocalPalette], which follows
 * whatever is playing. The cover therefore belongs to the record on the player the way
 * every other coloured surface in the app does, and a page of them is one set rather
 * than a set of accidents.
 *
 * [seed] picks the swatches and must be stable for a given item — the playlist's id,
 * not its position in a list — or the tile changes colour as the shelf reloads.
 *
 * Deliberately *not* the artist fallback: an artist gets initials, because a name is
 * the thing that identifies them and there is room for it. A playlist's name is already
 * printed under the tile.
 */
@Composable
fun GeneratedCover(
    seed: Int,
    glyph: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
) {
    val palette = LocalPalette.current
    val light = LocalSendspinColors.current.isLight
    // The same asymmetry the category cards document: a swatch lifted onto black is a
    // colour, and the identical value on the off-white page is a tint with nothing
    // behind it — so the wash firms up and the mark comes down toward the ink.
    val from = palette.swatch(seed)
    val to = palette.swatch(seed + 2)
    val mark = if (light) lerp(from, Color.Black, 0.5f) else Color.White
    Box(
        modifier
            .clip(shape)
            // Opaque base *then* the wash, rather than the wash alone. The tile shows
            // up on four surfaces — a grid cell over Ink3, a shelf tile, a list row on
            // the bare page and a 200dp hero over the melt backdrop — and a
            // translucent-only fill would be a different colour on each of them. Two
            // background modifiers, drawn in chain order, cost one extra rect.
            .background(Ink3)
            .background(
                Brush.linearGradient(
                    listOf(from.a(if (light) 0.42f else 0.34f), to.a(if (light) 0.16f else 0.10f)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            // Over the fill, not under it: the tiles that have their own border draw it
            // on the parent, where a child filling the box paints straight over it.
            .border(1.dp, HairlineSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        // Sized as a fraction rather than in dp: the same composable is a 46dp list
        // thumbnail, a 116dp shelf tile and a 200dp detail header, and a fixed glyph
        // would be a blob at one end and a speck at the other.
        Icon(glyph, null, tint = mark.a(if (light) 0.55f else 0.42f), modifier = Modifier.fillMaxSize(0.36f))
    }
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
 * How long a committed value keeps the thumb, waiting for the caller to agree.
 *
 * Every slider in this app writes somewhere asynchronous — a DataStore edit and a
 * file write, a 200 ms-debounced Home Assistant call, a network seek. Until that
 * lands, the `value` handed back in is still the *pre-drag* one, and a slider that
 * trusted it on release snapped the thumb back to where the drag started and then
 * jumped forward a frame or two later. Holding the committed value across the gap is
 * what makes a release look like a release.
 *
 * The timeout is the other half: a write can legitimately never come back with what
 * was asked for (a clamped setting, a rejected seek), and a latch with no deadline
 * would freeze the slider on a value nothing agrees with.
 */
private const val SLIDER_LATCH_MS = 1_500L

/** Below a pixel on any track this app draws, so "caught up" means caught up. */
private const val SLIDER_LATCH_EPSILON = 0.004f

/**
 * The minimum height either slider claims for touch.
 *
 * The drawn track is 4 dp and the knob 13 dp; the boxes around them used to be 18 dp
 * ([HSlider]) and 26 dp ([WaveSeekBar]), which is the whole hit area there was. On a
 * small screen that is a genuinely hard target, and it also meant switching seek-bar
 * skins changed the row height by 8 dp. Both now claim the same 44 dp and draw
 * centred inside it.
 */
private val SliderTouchHeight = 44.dp

/** Drag state shared by [HSlider] and [WaveSeekBar]. See [SLIDER_LATCH_MS]. */
@Stable
private class SliderGesture {
    var dragging by mutableStateOf(false)
    var dragValue by mutableFloatStateOf(0f)

    /** The last committed value, held until the incoming value catches up. */
    var latched by mutableStateOf<Float?>(null)

    /** Bumped on every commit so the release effect restarts. */
    var latchSeq by mutableIntStateOf(0)

    fun begin(f: Float) { dragging = true; dragValue = f.coerceIn(0f, 1f) }

    fun move(f: Float) { dragValue = f.coerceIn(0f, 1f) }

    fun finish(f: Float) {
        dragging = false
        latched = f.coerceIn(0f, 1f)
        latchSeq++
    }

    /** What to paint for an incoming [value]. */
    fun display(value: Float): Float =
        (if (dragging) dragValue else latched ?: value).coerceIn(0f, 1f)
}

@Composable
private fun rememberSliderGesture(value: Float): SliderGesture {
    val gesture = remember { SliderGesture() }
    val incoming = rememberUpdatedState(value)
    LaunchedEffect(gesture.latchSeq) {
        val target = gesture.latched ?: return@LaunchedEffect
        withTimeoutOrNull(SLIDER_LATCH_MS) {
            snapshotFlow { incoming.value }.first { abs(it - target) <= SLIDER_LATCH_EPSILON }
        }
        gesture.latched = null
    }
    return gesture
}

/**
 * Tap and drag handling for both sliders.
 *
 * `onDragCancel` commits rather than discarding, which is the second half of the
 * snap-back fix: these sliders sit inside vertically scrolling screens, a drag with
 * any vertical component can be taken over by the scroll container, and throwing the
 * gesture away left the Lights screen showing a brightness the user had chosen and
 * that was never written anywhere.
 */
private fun Modifier.sliderInput(
    gesture: SliderGesture,
    width: () -> Int,
    onChange: (Float) -> Unit,
    commit: (Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures { o ->
            val f = (o.x / width()).coerceIn(0f, 1f)
            onChange(f)
            gesture.finish(f)
            commit(f)
        }
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { o ->
                gesture.begin(o.x / width())
                onChange(gesture.dragValue)
            },
            onDragEnd = { gesture.finish(gesture.dragValue); commit(gesture.dragValue) },
            onDragCancel = { gesture.finish(gesture.dragValue); commit(gesture.dragValue) },
        ) { change, _ ->
            gesture.move(change.position.x / width())
            onChange(gesture.dragValue)
        }
    }

/**
 * The magnifier bubble: where a release would land, e.g. the timestamp being
 * scrubbed to. Clamped so it stays on screen at both ends of the track.
 *
 * `wrapContentSize(unbounded = true)` is load-bearing. This Box parent is the
 * slider, which hands its own height down as a maximum: the bubble was squashed, its
 * 5.dp padding left the text a thin band, and the rounded clip cut the glyphs into
 * stubs that read as underscores. The `offset` only moves the bubble up; it grants it
 * no height.
 */
@Composable
private fun SliderMagnifier(text: String, fraction: Float, width: Int, accent: Color) {
    Box(
        Modifier
            .wrapContentSize(align = Alignment.BottomStart, unbounded = true)
            .offset {
                val half = BubbleWidth.toPx() / 2f
                val x = (fraction * width - half).coerceIn(0f, (width - BubbleWidth.toPx()).coerceAtLeast(0f))
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

/**
 * Horizontal slider (tap or drag). [value] and [onChange] are 0f..1f. The filled
 * track glows in the accent and the knob stays white, so the scrubber reads
 * against any album colour.
 *
 * [onChange] fires continuously and should only move local UI state. [onCommit],
 * if given, fires once on release and is where a network seek belongs — sending
 * one per touch-move made the scrubber fight the server replies and snap
 * backwards. While a drag is in progress the knob follows the finger and ignores
 * [value] entirely, and after release it holds the committed value until [value]
 * agrees — see [SLIDER_LATCH_MS].
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
    val accent = rememberAccent()
    var width by remember { mutableIntStateOf(1) }
    val gesture = rememberSliderGesture(value)
    val v = gesture.display(value)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(SliderTouchHeight)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .sliderInput(gesture, { width }, onChange, ::commit),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50)).background(inkOn(0.14f)))
        Box(
            Modifier.fillMaxWidth(v).height(trackHeight)
                .then(if (accented) Modifier.shadow(10.dp, RoundedCornerShape(50), ambientColor = accent, spotColor = accent) else Modifier)
                .clip(RoundedCornerShape(50))
                .background(
                    if (accented) Brush.horizontalGradient(listOf(accent.a(0.5f), accent))
                    else Brush.horizontalGradient(listOf(inkOn(0.62f), inkOn(0.62f)))
                )
        )
        Box(
            Modifier
                .offset { IntOffset((v * width - knob.toPx() / 2f).roundToInt(), 0) }
                .then(if (accented) Modifier.shadow(10.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
                // TextPrimary, not literal white: the knob has to stay visible against
                // its own track, which inverts with the theme.
                .size(if (gesture.dragging) knob * 1.35f else knob).clip(CircleShape).background(TextPrimary)
        )

        if (gesture.dragging && label != null) SliderMagnifier(label(v), v, width, accent)
    }
}

/**
 * The Now Playing seek bar alternate skin: the played stretch is a sine wave
 * instead of a straight line, wobbling gently while the track plays — like a water
 * surface rather than a ruler. Selected from Appearance settings
 * ([com.engabd.sendpin.data.AppSettings.seekBarStyle]); [HSlider] stays the default
 * and remains what every other slider in the app (volume, DSP, lyrics offset) uses.
 *
 * **The wave is only what has played.** The accent stretch behind the knob is the
 * sine; the remainder ahead of it is a straight muted rail, and the wave grows into
 * that rail as the knob advances. Both used to be clipped out of one full-width
 * curve, which left the part of the track nobody had heard yet wobbling as though it
 * had a shape of its own.
 *
 * The amplitude eases back to nothing across the last knob's-width before the knob,
 * so a crest never has to step down onto the rail: the curve arrives at centre
 * already flat and the join reads as one continuous line, while the flattening
 * itself happens under the knob where nothing can see it. That is also why the knob
 * no longer bobs — where the wave meets the rail is the centre line by construction.
 *
 * The curve is sampled every [WaveSampleStep] and joined through segment midpoints
 * with [Path.quadraticTo]. A polyline at a readable step visibly facets — and its
 * mitred joins throw small spikes that crawl along the line as the phase advances,
 * which is the "pixelated while moving" this replaces.
 *
 * The wobble is amplitude, not phase, that answers [playing]: the phase keeps
 * advancing regardless (cheap, and invisible at zero amplitude), and the amplitude
 * eases to flat on pause and back up on resume. Gating the *phase* instead would
 * either freeze mid-crest — a visibly different shape from the straight line the
 * paused bar is supposed to read as — or jump to it.
 */
@Composable
fun WaveSeekBar(
    value: Float,
    onChange: (Float) -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    knob: Dp = 13.dp,
    onCommit: ((Float) -> Unit)? = null,
    label: ((Float) -> String)? = null,
) {
    val accent = rememberAccent()
    var width by remember { mutableIntStateOf(1) }
    val gesture = rememberSliderGesture(value)
    val v = gesture.display(value)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    // An infinite transition is the exact case [LocalReducedMotion] exists for. The
    // wave still is a wave with it on — it simply stops travelling, rather than
    // freezing at whatever crest it happened to be at when the setting flipped.
    val reduced = LocalReducedMotion.current
    val infinite = rememberInfiniteTransition(label = "waveSeekBarPhase")
    val travelling by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MS, easing = LinearEasing)),
        label = "phase",
    )
    val phase = if (reduced) 0f else travelling
    val amplitudeDp by animateFloatAsState(
        targetValue = if (playing) WaveAmplitude.value else 0f,
        animationSpec = tween(WAVE_AMPLITUDE_MS),
        label = "waveAmplitude",
    )

    // Allocated once, not once per frame. The draw lambda runs at 60 Hz while the
    // phase advances, and a fresh Path and Brush each time is 120 objects a second
    // for a bar whose shape never changes.
    val wavePath = remember { Path() }
    val accentBrush = remember(accent) { Brush.horizontalGradient(listOf(accent.a(0.5f), accent)) }

    Box(
        modifier
            .fillMaxWidth()
            .height(SliderTouchHeight)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .sliderInput(gesture, { width }, onChange, ::commit),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fill = v.coerceIn(0f, 1f)
        // Resolved here, in composition, and only referenced (not called) inside
        // the Canvas draw lambda below: [inkOn] and [TextPrimary] are `@Composable`
        // (they read the current theme via a CompositionLocal), and a `Canvas`
        // draw lambda runs in `DrawScope` at draw time, not in composition — calling
        // either one directly from in there does not compile.
        val trackColor = inkOn(0.14f)
        val knobColor = TextPrimary
        val dragging = gesture.dragging
        Canvas(Modifier.matchParentSize()) {
            val centerY = size.height / 2f
            val strokePx = trackHeight.toPx()
            val fillWidthPx = size.width * fill
            val amplitudePx = amplitudeDp.dp.toPx()
            val wavelengthPx = WaveLength.toPx()
            // The wave has to arrive at the rail's height rather than stepping down
            // onto it from whatever crest it was on, and it has to do that without
            // looking damped: the amplitude is smoothstepped to nothing across
            // exactly the stretch the knob covers, so every part of the curve still
            // visible either side of it is at full height.
            val taperPx = knob.toPx().coerceAtLeast(1f)
            fun waveY(x: Float): Float {
                val t = ((fillWidthPx - x) / taperPx).coerceIn(0f, 1f)
                val envelope = t * t * (3f - 2f * t)
                return centerY + envelope * amplitudePx *
                    sin((2 * PI * x / wavelengthPx) + phase).toFloat()
            }

            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val gapPx = WaveGap.toPx()

            // What has played: the wave, in the accent, from the start to the knob.
            if (fillWidthPx > 0f) {
                val stepPx = WaveSampleStep.toPx().coerceAtLeast(1f)
                wavePath.reset()
                var prevX = 0f
                var prevY = waveY(0f)
                wavePath.moveTo(prevX, prevY)
                var x = stepPx
                while (x < fillWidthPx) {
                    val y = waveY(x)
                    // Control point at the previous sample, endpoint at the midpoint
                    // between it and this one — the standard midpoint smoothing, which
                    // passes through the curve rather than cornering on it.
                    wavePath.quadraticTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                    prevX = x
                    prevY = y
                    x += stepPx
                }
                // The envelope is zero at the knob, so this endpoint is centre.
                wavePath.quadraticTo(prevX, prevY, fillWidthPx, waveY(fillWidthPx))
                drawPath(wavePath, brush = accentBrush, style = stroke)
            }

            // What has not: a straight muted rail, picking up after the gap.
            val railStart = fillWidthPx + gapPx
            if (railStart < size.width) {
                drawLine(
                    color = trackColor,
                    start = Offset(railStart, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }

            // The knob rides the centre line: it is the seam between the two, and the
            // wave is already flat by the time it reaches here.
            val knobPx = knob.toPx()
            drawCircle(
                color = knobColor,
                radius = (if (dragging) knobPx * 1.35f else knobPx) / 2f,
                center = Offset(fillWidthPx, centerY),
            )
        }

        if (dragging && label != null) SliderMagnifier(label(v), v, width, accent)
    }
}

private val WaveLength = 34.dp
private val WaveAmplitude = 5.dp

/**
 * M3 Expressive Pill Seek Bar: a bold, modern segmented pill track with rounded caps,
 * tactile knob elevation and dynamic accent fill.
 */
@Composable
fun PillSeekBar(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 8.dp,
    knob: Dp = 16.dp,
    onCommit: ((Float) -> Unit)? = null,
    label: ((Float) -> String)? = null,
) {
    val accent = rememberAccent()
    var width by remember { mutableIntStateOf(1) }
    val gesture = rememberSliderGesture(value)
    val v = gesture.display(value)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(SliderTouchHeight)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .sliderInput(gesture, { width }, onChange, ::commit),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fillWidth = (v * width).coerceIn(0f, width.toFloat())
        // Background pill
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(inkOn(0.12f))
        )
        // Filled active pill with subtle gradient
        Box(
            Modifier
                .width((fillWidth / (LocalContext.current.resources.displayMetrics.density)).dp)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.a(0.7f), accent)
                    )
                )
        )
        // Expressive elevated thumb
        Box(
            Modifier
                .offset { IntOffset((v * width - knob.toPx() / 2f).roundToInt(), 0) }
                .shadow(12.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .size(if (gesture.dragging) knob * 1.35f else knob)
                .clip(CircleShape)
                .background(TextPrimary)
                .border(2.dp, accent, CircleShape)
        )

        if (gesture.dragging && label != null) SliderMagnifier(label(v), v, width, accent)
    }
}

/**
 * Audiophile Glow Seek Bar: the played stretch is a glowing beam of light, not a
 * painted line.
 *
 * Its sibling skins are ink on glass: [HSlider] a flat accent bar, [PillSeekBar] a
 * bold pill, [WaveSeekBar] a wobble. This one is the outlier on purpose — the
 * progress is drawn as emitted light: a soft outer bloom the full length of what has
 * played, a hot core inside it, a white-hot leading edge where the beam meets the
 * playhead, and a playhead that is a lamp rather than a dot, with its own radiance
 * and a slow breath while playing. The first cut of this skin differed from
 * [HSlider] by a 1dp track and an 8dp shadow — both invisible on OLED — and read as
 * the same line; every layer here is additive glow a flat bar cannot have.
 *
 * Drawn in one [Canvas] rather than layered blurred [Box]es: three stacked blur
 * passes per frame is real GPU work on the one control that redraws every frame
 * during playback, and a radial gradient painted directly reads as the same bloom
 * for none of the cost.
 */
@Composable
fun GlowSeekBar(
    value: Float,
    onChange: (Float) -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 3.dp,
    knob: Dp = 12.dp,
    onCommit: ((Float) -> Unit)? = null,
    label: ((Float) -> String)? = null,
) {
    val accent = rememberAccent()
    var width by remember { mutableIntStateOf(1) }
    val gesture = rememberSliderGesture(value)
    val v = gesture.display(value)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    val reduced = LocalReducedMotion.current
    val infinite = rememberInfiniteTransition(label = "glowPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    // The breath dims the playhead's radiance, not the beam: a paused beam is a
    // lamp that is still on, and the pulse was always about the playhead feeling
    // alive while the track runs.
    val effectiveGlow = if (reduced || !playing) 0.5f else pulseAlpha

    // Resolved in composition — theme reads are `@Composable`, and the Canvas lambda
    // runs in DrawScope. See the note on [WaveSeekBar]'s Canvas.
    val railColor = inkOn(0.10f)
    val dragScale = if (gesture.dragging) 1.6f else 1f

    Box(
        modifier
            .fillMaxWidth()
            .height(SliderTouchHeight)
            .onSizeChanged { width = if (it.width > 0) it.width else 1 }
            .sliderInput(gesture, { width }, onChange, ::commit),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val centerY = size.height / 2f
            val corePx = trackHeight.toPx()
            val fillPx = size.width * v.coerceIn(0f, 1f)
            val knobPx = knob.toPx()

            // ── Rail: the unplayed stretch, a fainter whisper of the beam. ──
            drawLine(
                color = railColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = corePx,
                cap = StrokeCap.Round,
            )

            if (fillPx > 0f) {
                // ── Outer bloom: the light thrown by the beam, falling off fast. ──
                val bloomPx = corePx * 6f * dragScale
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.14f),
                        0.7f to accent.copy(alpha = 0.30f * effectiveGlow.coerceIn(0f, 1f)),
                        1f to accent.copy(alpha = 0.55f * effectiveGlow.coerceIn(0f, 1f)),
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(fillPx, centerY),
                    strokeWidth = bloomPx,
                    cap = StrokeCap.Round,
                )

                // ── Inner halo: the lit gas around the core. ──
                val haloPx = corePx * 2.6f * dragScale
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.35f),
                        1f to accent.copy(alpha = 0.80f),
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(fillPx, centerY),
                    strokeWidth = haloPx,
                    cap = StrokeCap.Round,
                )

                // ── Hot core: the beam itself. ──
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.85f),
                        1f to lerp(accent, Color.White, 0.45f),
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(fillPx, centerY),
                    strokeWidth = corePx,
                    cap = StrokeCap.Round,
                )

                // ── White-hot leading edge: where the beam meets the playhead. ──
                drawLine(
                    brush = Brush.horizontalGradient(
                        (fillPx - knobPx * 4f) / size.width to Color.Transparent,
                        (fillPx - knobPx) / size.width to lerp(accent, Color.White, 0.25f),
                        1f to Color.White.copy(alpha = 0.95f),
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(fillPx, centerY),
                    strokeWidth = corePx,
                    cap = StrokeCap.Butt,
                )
            }

            // ── Playhead: a lamp, not a dot. Radiance first (drawn under), then
            //    the lamp: an accent ring around a white-hot filament core. ──
            val glowR = knobPx * 2.2f * dragScale
            drawCircle(
                brush = Brush.radialGradient(
                    0f to accent.copy(alpha = 0.75f * effectiveGlow.coerceIn(0f, 1f)),
                    0.4f to accent.copy(alpha = 0.30f * effectiveGlow.coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                ),
                radius = glowR,
                center = Offset(fillPx, centerY),
            )
            drawCircle(
                color = accent.copy(alpha = 0.9f),
                radius = knobPx / 2f,
                center = Offset(fillPx, centerY),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White,
                    0.7f to lerp(accent, Color.White, 0.6f),
                    1f to Color.Transparent,
                ),
                radius = knobPx * 0.38f,
                center = Offset(fillPx, centerY),
            )
        }

        if (gesture.dragging && label != null) SliderMagnifier(label(v), v, width, accent)
    }
}

/** ~17 samples per wavelength. Below about 8 the curve reads as a polygon. */
private val WaveSampleStep = 2.dp

/** The break between the played stretch and the remainder, as M3 own bars have. */
private val WaveGap = 4.dp
private const val WAVE_PERIOD_MS = 1600
private const val WAVE_AMPLITUDE_MS = 500

private val BubbleWidth = 72.dp
private val BubbleLift = 38.dp

/**
 * A small circular icon button used in screen headers — the same one
 * [SpeakersScreen] defines privately; hoisted here so [StatsScreen] and
 * other screens can share it without each keeping their own copy.
 */
@Composable
fun CircleIconButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = TextSecondary, modifier = Modifier.size(17.dp))
    }
}

