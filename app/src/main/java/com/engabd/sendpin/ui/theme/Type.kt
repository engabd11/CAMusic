package com.engabd.sendpin.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
 * | titleLarge        | Card/section titles ("Connection", etc.)  |
 * | titleMedium       | Subsection labels (bold 12sp headers)     |
 * | bodyLarge         | Primary body text (track titles, artists)  |
 * | bodyMedium        | Secondary text, helper labels             |
 * | bodySmall         | Faint descriptions, fine print             |
 * | labelLarge        | Buttons, pills, segmented toggle labels    |
 * | labelMedium       | Quality pill (mono), quality badge         |
 * | labelSmall        | Section labels (uppercase, tracked)        |
 *
 * ## Emphasized styles (M3 Expressive)
 *
 * The 15 emphasized styles have higher weight than their baseline counterparts.
 * They are used for:
 * - Now Playing track title (headlineLargeEmphasized)
 * - Primary action buttons (labelLargeEmphasized)
 * - Selected list items / nav items
 * - Headlines and editorial moments
 *
 * Weight deltas (baseline -> emphasized):
 * - Display/Headline/Body: 400 -> 500 (design uses ExtraBold -> Black)
 * - Title: Bold -> ExtraBold
 * - Label: Bold -> ExtraBold
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppTypography = Typography(
    // ── Baseline styles (15) ──────────────────────────────────────────────

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

    // ── Emphasized styles (15 — M3 Expressive) ────────────────────────────
    // Higher weight for key UI moments: Now Playing title, primary actions,
    // selected items, headlines. Sizes/spacing/line-heights match baseline.

    // Now Playing track title, screen hero titles
    headlineLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
    ),
    headlineSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
    ),
    // Emphasized section titles — for selected state or key sections
    titleLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
    ),
    // Emphasized subsection labels — selected toggle group headers
    titleMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
    ),
    titleSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    // Emphasized body text — selected track title in lists
    bodyLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    bodyMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    bodySmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    // Emphasized labels — primary action buttons (Play, Connect)
    labelLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
    ),
    // Emphasized quality badge — selected state
    labelMediumEmphasized = TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
    ),
    // Emphasized section labels — active/selected section
    labelSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),
    // Display styles — used for large hero numbers or stats
    displayLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
    ),
    displayMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
    ),
    displaySmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
    ),
)