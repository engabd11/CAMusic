package com.engabd.sendpin.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color

/**
 * M3 `OutlinedTextField` colors with the dynamic album accent applied to the
 * focused state and the OLED palette for everything else.
 *
 * Before Phase 2, every `OutlinedTextField` in the app passed a full
 * `OutlinedTextFieldDefaults.colors(...)` block with 6–7 overrides per call.
 * The text color and unfocused border overrides were redundant once the M3
 * `colorScheme` was complete (Phase 1) — they matched `colorScheme.onSurface`
 * and `colorScheme.outline` exactly. This helper keeps only the accent-based
 * overrides, which come from [accent] (the album-derived `LocalAccent`) and
 * can't be served by the static scheme.
 *
 * Usage:
 * ```
 * OutlinedTextField(
 *     ...,
 *     colors = accentTextFieldColors(accent),
 * )
 * ```
 */
fun accentTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    cursorColor = accent,
    focusedLabelColor = accent,
    // Unfocused and text colors inherit from MaterialTheme.colorScheme:
    //   onSurface = TextPrimary, outline = Hairline, onSurfaceVariant = TextMuted.
)