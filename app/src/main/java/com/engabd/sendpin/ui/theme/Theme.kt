package com.engabd.sendpin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp

/**
 * The complete OLED-black M3 color scheme.
 *
 * Every M3 color role is filled from the Sendspin palette so that standard M3
 * components (OutlinedTextField, Switch, Slider, Button, CircularProgressIndicator,
 * etc.) inherit the OLED look automatically — no per-call color overrides needed.
 *
 * The album-derived accent is still applied dynamically per-track via [LocalAccent]
 * (see ui.design); the M3 scheme carries the *base* theme, and the accent layers
 * on top.
 */
private val OledScheme = darkColorScheme(
    // Primary — the default amber-gold accent. M3 components that use primary
    // (buttons, active states, sliders) pick this up.
    primary = DefaultAccent,
    onPrimary = Ink,
    primaryContainer = DefaultAccent.copy(alpha = 0.14f),
    onPrimaryContainer = DefaultAccent,

    // Secondary — mirrors primary so segmented controls and secondary buttons
    // share the accent identity rather than introducing a unrelated hue.
    secondary = DefaultAccent,
    onSecondary = Ink,
    secondaryContainer = GlassStrong,
    onSecondaryContainer = TextSecondary,

    // Tertiary — a teal companion from the fallback palette, giving M3 components
    // that use tertiary (e.g. tertiary button variants) a distinct but harmonious hue.
    tertiary = FallbackPalette[2],          // teal
    onTertiary = Ink,
    tertiaryContainer = FallbackPalette[2].copy(alpha = 0.14f),
    onTertiaryContainer = TextSecondary,

    // Background / surface — true OLED black.
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = GlassStrong,
    onSurfaceVariant = TextMuted,
    surfaceTint = DefaultAccent,

    // Inverse — used by Snackbar and inset surfaces. On a black app the inverse
    // flips to white so snackbars are readable.
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,

    // Error
    error = ErrorRed,
    onError = Ink,
    errorContainer = ErrorRed.copy(alpha = 0.14f),
    onErrorContainer = ErrorRed,

    // Outlines — the hairline borders used by glass cards and dividers.
    outline = Hairline,
    outlineVariant = HairlineSoft,

    scrim = Ink,
)

/**
 * M3 shape scale mapped to the design system's corner radii.
 *
 * | M3 role      | Radius | Used by                          |
 * |--------------|--------|----------------------------------|
 * | small        | 9.dp   | chips, toggle chips              |
 * | medium       | 11.dp  | icon chips, quality pills         |
 * | large        | 16.dp  | glass cards, settings sections   |
 * | extraLarge   | 28.dp  | bottom sheets, dialogs           |
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SendspinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OledScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
