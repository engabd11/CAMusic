package com.engabd.sendpin.tv.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.ColorScheme
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.getPalette
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

/**
 * Ambient visual wash for the TV Now Playing screen.
 *
 * A slow, full-screen gradient in the colours of whatever is on, drifting across the
 * screen over twenty seconds and held down to a dark wash so the cover and the
 * transport in front of it stay crisp. A television is watched from across the room
 * with nobody's hand on it, which is the one place in this app where a screen has
 * nothing better to do than be atmospheric.
 *
 * The colours are the Light Sync colour setting's, answered the same way the room's
 * lights answer it: the album's own palette on a dynamic scheme, the chosen static
 * scheme otherwise. [rememberAlbumPalette] is the phone's own extraction — the same
 * one behind every album-tinted page — so this is a second reader of a palette the
 * app already works out, not a second way of working one out.
 */
@Composable
fun TvAmbientBackground(viewModel: NowPlayingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ambientColors(state.artworkUrl)
    if (colors.isEmpty()) return

    val phase by rememberInfiniteTransition(label = "ambient").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    // At least two anchors: one colour is not a gradient, and the platform shader
    // behind it refuses a single stop outright.
    val ramp = if (colors.size == 1) colors + colors else colors

    Box(
        Modifier
            .fillMaxSize()
            // Drawn rather than composed, and [phase] is read here rather than in the
            // body above, so the drift costs a redraw per frame instead of a
            // recomposition. The gradient is also sized in pixels — the geometry only
            // exists at draw time, which is why it is measured off `size` here and not
            // written as the fractions it reads like.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colorStops = ramp.mapIndexed { index, color ->
                            ((index + phase) / ramp.size).coerceIn(0f, 1f) to color
                        }.toTypedArray(),
                        center = Offset(size.width * (0.3f + 0.4f * phase), size.height * 0.5f),
                        radius = size.maxDimension * 0.9f,
                    ),
                )
                // Low alpha over the top so the foreground cover and controls remain
                // readable: this is a wash behind a screen, not the screen.
                drawRect(Color.Black.copy(alpha = 0.65f))
            },
    )
}

/**
 * The colours to wash with: the artwork's own, or the static scheme the listener
 * pinned for their lights.
 *
 * [getPalette] answers the Sunset fallback for the dynamic schemes, which is what the
 * lights fall back to as well when there is no artwork — but the album's palette has
 * its own fallback, so the dynamic branch never needs it.
 */
@Composable
private fun ambientColors(artworkUrl: String?): List<Color> {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context.applicationContext) }
    val wire by settings.lightSyncColor.collectAsStateWithLifecycle(
        initialValue = ColorScheme.ALBUM_ART_V2.wire,
    )
    val scheme = ColorScheme.fromWire(wire)
    val album = rememberAlbumPalette(artworkUrl)
    return remember(scheme, album) {
        if (scheme.isDynamic) album.swatches else getPalette(scheme).colors.map { it.toColor() }
    }
}

private fun Rgb.toColor(): Color = Color(
    red = first.coerceIn(0f, 1f),
    green = second.coerceIn(0f, 1f),
    blue = third.coerceIn(0f, 1f),
)
