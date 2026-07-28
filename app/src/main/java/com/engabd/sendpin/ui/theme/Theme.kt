package com.engabd.sendpin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always OLED-dark (the Sendspin design is a pure-black player). Accent is applied
// dynamically per-track via LocalAccent (see ui/design), not through the M3 scheme.
private val OledScheme = darkColorScheme(
    primary = DefaultAccent,
    onPrimary = Ink,
    secondary = DefaultAccent,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = GlassStrong,
    onSurfaceVariant = TextMuted,
    error = ErrorRed,
    onError = Ink,
    outline = Hairline,
)

@Composable
fun SendspinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OledScheme,
        typography = AppTypography,
        content = content,
    )
}
