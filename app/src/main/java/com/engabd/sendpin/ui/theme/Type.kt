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
 * Emphasized typography styles (M3 Expressive).
 *
 * material3 1.4.0 stable does not expose the 30-param Typography constructor —
 * the emphasized parameters are internal. These styles are defined here as plain
 * TextStyle values so they can be used directly via `style = HeadlineLargeEmphasized`
 * in any Text() call, without going through the M3 Typography container.
 *
 * When material3 1.5.0 stabilises and the 30-param constructor becomes public,
 * these can be folded into AppTypography and accessed via
 * `MaterialTheme.typography.headlineLargeEmphasized`.
 *
 * Weight deltas (baseline -> emphasized):
 * - Display/Headline: ExtraBold -> Black
 * - Title: Bold -> ExtraBold
 * - Label: Bold -> ExtraBold
 * - Body: Normal -> Medium
 */

// Now Playing track title, screen hero titles
val HeadlineLargeEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Black,
    fontSize = 26.sp,
    letterSpacing = (-0.5).sp,
)
val HeadlineMediumEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 24.sp,
)
val HeadlineSmallEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 22.sp,
)

// Emphasized section titles — for selected state or key sections
val TitleLargeEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 14.sp,
)
// Emphasized subsection labels — selected toggle group headers
val TitleMediumEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 12.sp,
)
val TitleSmallEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
)

// Emphasized body text — selected track title in lists
val BodyLargeEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
)
val BodyMediumEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
)
val BodySmallEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

// Emphasized labels — primary action buttons (Play, Connect)
val LabelLargeEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 12.sp,
)
// Emphasized quality badge — selected state
val LabelMediumEmphasized = TextStyle(
    fontFamily = MonoFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 11.sp,
)
// Emphasized section labels — active/selected section
val LabelSmallEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 10.sp,
    letterSpacing = 1.4.sp,
)

// Display styles — used for large hero numbers or stats
val DisplayLargeEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Black,
    fontSize = 26.sp,
)
val DisplayMediumEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.Black,
    fontSize = 22.sp,
)
val DisplaySmallEmphasized = TextStyle(
    fontFamily = AppFont,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 18.sp,
)

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
 * | titleLarge        | Card/section titles ("Connection", etc.)  |
 * | titleMedium       | Subsection labels (bold 12sp headers)     |
 * | bodyLarge         | Primary body text (track titles, artists)  |
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