package com.engabd.sendpin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design uses **Manrope** (UI) + **JetBrains Mono** (numbers/quality). To keep
 * the build free of downloadable-font certs / bundled TTFs, we approximate with the
 * system sans + monospace and the design's weights/sizes (defined per-component in
 * [com.engabd.sendpin.ui.design]). Swap these for real Manrope / JetBrains Mono via
 * downloadable Google Fonts (add res/font certs) or bundled TTFs as a polish step.
 */
val AppFont: FontFamily = FontFamily.Default
val MonoFont: FontFamily = FontFamily.Monospace

/**
 * The M3 type scale populated with the Sendspin design's actual specifications.
 *
 * Each role maps to a concrete style that was previously hardcoded per `Text` call
 * across the 20+ screen files. Centralising them here means:
 * - M3 text components (Text, ListItem, Card title, etc.) pick up the right font
 *   automatically.
 * - One place to change when real Manrope / JetBrains Mono fonts are added.
 * - Consistent typography across the app — no more per-screen drift.
 *
 * The mapping was derived by scanning every `Text(...)` call in the codebase:
 *
 * | M3 role           | Design usage                              |
 * |-------------------|-------------------------------------------|
 * | headlineLarge     | Screen titles ("Settings", "Library")     |
 * | titleMedium       | Card section titles ("Connection", etc.)  |
 * | bodyLarge         | Primary body text                         |
 * | bodyMedium        | Secondary text, helper labels             |
 * | bodySmall         | Faint descriptions, fine print             |
 * | labelLarge        | Buttons, pills, segmented toggle labels    |
 * | labelMedium       | Quality pill (mono), quality badge         |
 * | labelSmall        | Section labels (uppercase, tracked)        |
 */
val AppTypography = Typography(
    // Screen titles: "Settings" (26sp ExtraBold, -0.5 letterSpacing)
    headlineLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Section/card titles within screens: "Connection", "Player", etc. (14sp Bold)
    titleLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),
    // Subsection labels: bold 12sp headers above toggle groups
    titleMedium = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    ),
    // Primary body text — track titles, artist names
    bodyLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    // Secondary body text — 13sp, used in descriptions and status lines
    bodyMedium = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    // Fine print — 11sp / 15sp lineHeight, used for descriptions under controls
    bodySmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    // Buttons, pills, segmented toggle labels — 12sp Bold
    labelLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    ),
    // Quality pill / badge text — mono 11sp Bold
    labelMedium = TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    // Section labels — uppercase 10sp Bold with 1.4sp tracking
    labelSmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),
)
