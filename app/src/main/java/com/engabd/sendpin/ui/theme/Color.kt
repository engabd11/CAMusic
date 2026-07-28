package com.engabd.sendpin.ui.theme

import androidx.compose.ui.graphics.Color

// OLED-black Sendspin palette (from the Claude Design "Sendspin" system).
// Ink is a true #000 so the panel emits nothing at all and the album art
// genuinely melts into the bezel on an OLED screen.
val Ink = Color(0xFF000000)
val Ink2 = Color(0xFF07080A)  // art letterbox / cover backing
val Ink3 = Color(0xFF0A0B0D)  // grid tile backing

val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xB3FFFFFF) // ~0.70
val TextMuted = Color(0x73FFFFFF)     // ~0.45
val TextFaint = Color(0x52FFFFFF)     // ~0.32

val Hairline = Color(0x17FFFFFF)      // ~0.09 borders
val HairlineSoft = Color(0x12FFFFFF)  // ~0.07
val Glass = Color(0x0AFFFFFF)         // ~0.04 panel fill
val GlassStrong = Color(0x12FFFFFF)   // ~0.07 panel fill

// Album-derived accent falls back to this amber-gold.
val DefaultAccent = Color(0xFFD9A85C)

/** Fallback companion swatches when an album yields no usable hues. */
val FallbackPalette = listOf(
    DefaultAccent,
    Color(0xFF7A8FD9),
    Color(0xFF5EC8C0),
    Color(0xFFD95C8F),
    Color(0xFFE0A848),
)

val PositiveGreen = Color(0xFF3ECF7A)
val WarnAmber = Color(0xFFE8A42C)
val ErrorRed = Color(0xFFE05656)
