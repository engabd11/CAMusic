package com.engabd.sendpin.tv.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.hue.ColorScheme
import com.engabd.sendpin.hue.Palette
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.getPalette

/**
 * Ambient visual wash for the TV Now Playing screen.
 *
 * Renders a slow, full-screen animated gradient drawn from the current Light Sync
 * palette: album-art colours when a dynamic scheme is selected, otherwise the chosen
 * static scheme. It is deliberately subtle (low alpha) so the artwork and transport
 * controls on top remain readable, while still giving the TV something atmospheric to
 * show when the listener is across the room.
 *
 * The palette is sampled from [TvAmbientViewModel] so this stays reactive to scheme
 * changes and cover-art overrides without touching [DirectLightSync]'s internals.
 */
@Composable
fun TvAmbientBackground(palette: Palette = rememberTvAmbientPalette()) {
    val phase by rememberInfiniteTransition(label = "ambient").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    val colors = palette.colors
    if (colors.isEmpty()) return

    // Build a moving sweep/gradient that shifts with phase. Two offset gradients
    // layered give a slow colour drift rather than a single static wash.
    val brush = Brush.radialGradient(
        colorStops = colors.mapIndexed { index, rgb ->
            ((index + phase) / colors.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) to rgb.toColor()
        }.toTypedArray(),
        center = Offset(0.3f + 0.4f * phase, 0.5f),
        radius = 1.5f,
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(brush)
            // Low alpha so the foreground cover and controls remain crisp.
            .background(Color.Black.copy(alpha = 0.65f)),
    )
}

@Composable
private fun rememberTvAmbientPalette(): Palette {
    // Default to the Sunset fallback until the ViewModel is wired in.
    return getPalette(ColorScheme.SUNSET)
}

private fun Rgb.toColor(): Color = Color(
    red = first.coerceIn(0f, 1f),
    green = second.coerceIn(0f, 1f),
    blue = third.coerceIn(0f, 1f),
)
