package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Backdrop blur — what makes a "glass" panel actually glass.
 *
 * The design has called these surfaces glass since the beginning and they were a flat
 * translucent fill. The v0.9 plan proposed fixing that by "extending the `Modifier.blur`
 * infrastructure already proven in `MeltBackdrop`/`CastGlow`". That does not work, and it
 * is worth saying why, because it is the obvious thing to reach for: **`Modifier.blur`
 * blurs the composable it is applied to.** `MeltBackdrop` blurs an image it draws itself
 * and `CastGlow` blurs a silhouette it draws itself — both are blurring their own content
 * deliberately. Put the same modifier on a glass card and it blurs the card: its text and
 * its icons, while whatever sits behind stays perfectly sharp. That is the opposite of
 * glass.
 *
 * Real glass needs the *backdrop*, which Compose gives no direct access to. So:
 *
 *  1. [backdropSource] records the content behind into a [GraphicsLayer], and publishes it
 *     with its position on [LocalBackdrop].
 *  2. [glassSurface] draws that layer back — blurred once, translated so the region under
 *     *this* surface lands under this surface, and clipped to the surface's own shape.
 *
 * **The recursion trap, and why the source is a modifier rather than a wrapper.** A
 * surface must never sample a layer it is itself recorded into: it would draw a blurred
 * copy of its own previous frame, every frame, and smear into feedback within a second.
 * Keeping the source a modifier means the caller applies it to the *background* composable
 * specifically — the album wash — rather than to a subtree that would swallow the
 * foreground with it.
 *
 * **One blur, not one per surface.** The effect is set on the shared layer, so a screen
 * with a dozen glass chips pays for a single blur pass; each surface pays only for a
 * translated draw of the already-blurred result.
 *
 * **Why the fallback is not a compromise.** [glassSurface] falls back to a flat fill when
 * [LocalBackdrop] is null, and on the screens where that happens the two are
 * indistinguishable: blurring a flat `Ink` background returns flat `Ink`. Backdrop blur
 * only shows where there is something behind the panel to smear — the album wash behind
 * Now Playing, chiefly — which is exactly where the source is applied.
 */
class BackdropState internal constructor(internal val layer: GraphicsLayer) {
    /** Where the recorded region sits in root coordinates, so consumers can align to it. */
    internal var originInRoot by mutableStateOf(Offset.Zero)

    /** False until a frame has actually been recorded — drawing the layer before that is empty. */
    internal var recorded by mutableStateOf(false)
}

/** The backdrop a [glassSurface] samples, or null where there is nothing behind it. */
val LocalBackdrop = compositionLocalOf<BackdropState?> { null }

/**
 * Creates a backdrop and provides it to [content].
 *
 * Deliberately not a layout — it wraps nothing and introduces no `Box`, so it cannot
 * change how the caller's tree measures. Apply [backdropSource] inside [content] to
 * whatever should be blurred.
 */
@Composable
fun ProvideBackdrop(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val state = remember(layer) { BackdropState(layer) }
    CompositionLocalProvider(LocalBackdrop provides state, content = content)
}

/**
 * Record this composable's drawn output as the backdrop for any [glassSurface] below it.
 *
 * The content is still drawn normally — recording captures, it does not redirect — so the
 * backdrop stays visible in its own right.
 */
fun Modifier.backdropSource(): Modifier = composed {
    val state = LocalBackdrop.current ?: return@composed this
    // Leaving composition has to clear the flag, or a screen with no backdrop of its own
    // goes on sampling the last one recorded — the previous screen's album art, frozen,
    // showing through this screen's panels.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { state.recorded = false }
    }
    this
        .onGloballyPositioned { state.originInRoot = it.positionInRoot() }
        .drawWithContent {
            state.layer.record(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                this@drawWithContent.drawContent()
            }
            state.recorded = true
            drawLayer(state.layer)
        }
}

/**
 * Paint this surface as glass: the backdrop behind it, blurred, under a tint and hairline.
 *
 * [tint] goes *over* the blur rather than instead of it. A blur alone reads as a smear —
 * it keeps the average brightness of what it samples, so text over it fights the cover's
 * own contrast wherever the cover happens to be bright. The tint is what gives the panel a
 * consistent tone to sit on, which is the job the flat fill was doing by itself.
 */
fun Modifier.glassSurface(
    shape: Shape,
    tint: Color,
    blurRadius: Dp = 24.dp,
    border: Color? = null,
): Modifier = composed {
    val state = LocalBackdrop.current
    val bordered = { m: Modifier -> if (border != null) m.border(1.dp, border, shape) else m }

    // Nothing behind worth sampling — see the class doc on why this is not a downgrade on
    // the screens where it happens.
    if (state == null) return@composed bordered(this.clip(shape).background(tint))

    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    state.layer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    bordered(
        this
            // Clips everything drawn after it in the chain, which is what keeps the
            // sampled backdrop inside the surface's own silhouette.
            .clip(shape)
            .onGloballyPositioned { positionInRoot = it.positionInRoot() }
            .drawWithContent {
                if (state.recorded) {
                    val delta = state.originInRoot - positionInRoot
                    translate(delta.x, delta.y) { drawLayer(state.layer) }
                }
                drawRect(tint)
                drawContent()
            }
    )
}
