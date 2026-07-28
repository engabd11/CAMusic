package com.engabd.sendpin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * The design uses **Manrope** (UI) + **JetBrains Mono** (numbers/quality). To keep
 * the build free of downloadable-font certs / bundled TTFs, we approximate with the
 * system sans + monospace and the design's weights/sizes (defined per-component in
 * [com.engabd.sendpin.ui.design]). Swap these for real Manrope / JetBrains Mono via
 * downloadable Google Fonts (add res/font certs) or bundled TTFs as a polish step.
 */
val AppFont: FontFamily = FontFamily.Default
val MonoFont: FontFamily = FontFamily.Monospace

val AppTypography = Typography()
