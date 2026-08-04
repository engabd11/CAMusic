package com.engabd.sendpin.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The surface and ink colours a Sendspin theme has to supply.
 *
 * These used to be top-level constants, which is why the app could only ever be one
 * colour. They are read at ~600 call sites, so rather than rewrite all of them the
 * *names* below stayed and became composable accessors over [LocalSendspinColors] —
 * `Ink` still reads as `Ink`, it just answers differently per theme now.
 *
 * Behind a `staticCompositionLocalOf`, so reads are free: nothing subscribes, and
 * changing the theme redraws the whole tree, which is exactly what a theme change
 * wants and is rare enough not to care about. (Contrast [com.engabd.sendpin.ui.design.LocalAccent],
 * which changes per album and so has to be a regular `compositionLocalOf`.)
 */
@Immutable
data class SendspinColors(
    /** The page itself. True #000 on OLED so the panel emits nothing at all. */
    val ink: Color,
    /** Art letterbox / cover backing — one step off the page. */
    val ink2: Color,
    /** Grid tile backing — two steps off the page. */
    val ink3: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    /** Card and divider borders. */
    val hairline: Color,
    val hairlineSoft: Color,
    /** Panel fills — the "glass" the design is built out of. */
    val glass: Color,
    val glassStrong: Color,
    /** True when this is a light theme, for callers that must branch (system bars). */
    val isLight: Boolean,
)

/**
 * The default. A true-black page so album art melts into an OLED bezel with no edge.
 */
val OledColors = SendspinColors(
    ink = Color(0xFF000000),
    ink2 = Color(0xFF07080A),
    ink3 = Color(0xFF0A0B0D),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xB3FFFFFF),
    textMuted = Color(0x73FFFFFF),
    textFaint = Color(0x52FFFFFF),
    hairline = Color(0x17FFFFFF),
    hairlineSoft = Color(0x12FFFFFF),
    glass = Color(0x0AFFFFFF),
    glassStrong = Color(0x12FFFFFF),
    isLight = false,
)

/**
 * Dark, but not black. On an LCD panel true black is a grey that shows every backlight
 * flaw, and the hairlines the design leans on disappear into it; lifting the page a few
 * points gives them something to sit against.
 */
val DarkColors = OledColors.copy(
    ink = Color(0xFF101114),
    ink2 = Color(0xFF16181C),
    ink3 = Color(0xFF1B1E23),
    hairline = Color(0x1FFFFFFF),
    hairlineSoft = Color(0x17FFFFFF),
    glass = Color(0x0FFFFFFF),
    glassStrong = Color(0x1AFFFFFF),
)

/**
 * Light. Warm off-white rather than pure #FFF, because the album accent is laid over
 * these surfaces and a neutral white makes every warm cover look dirty against it.
 *
 * The inks are black at low alpha for the same reason the dark themes use white at low
 * alpha: they compose over whatever the surface happens to be instead of being a fixed
 * grey that only works on one background.
 */
val LightColors = SendspinColors(
    ink = Color(0xFFFDFCFA),
    ink2 = Color(0xFFF4F2EE),
    ink3 = Color(0xFFEBE8E3),
    textPrimary = Color(0xFF0B0B0C),
    textSecondary = Color(0xB3000000),
    textMuted = Color(0x8A000000),
    textFaint = Color(0x5C000000),
    hairline = Color(0x1F000000),
    hairlineSoft = Color(0x14000000),
    glass = Color(0x0A000000),
    glassStrong = Color(0x14000000),
    isLight = true,
)

val LocalSendspinColors = staticCompositionLocalOf { OledColors }

// The design tokens, as they have always been written at the call sites. Composable
// accessors rather than constants so a theme switch moves them; `@ReadOnlyComposable`
// because they only read a CompositionLocal and never need their own recompose scope.
val Ink: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.ink
val Ink2: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.ink2
val Ink3: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.ink3
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.textSecondary
val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.textMuted
val TextFaint: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.textFaint
val Hairline: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.hairline
val HairlineSoft: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.hairlineSoft
val Glass: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.glass
val GlassStrong: Color @Composable @ReadOnlyComposable get() = LocalSendspinColors.current.glassStrong

/**
 * Ink at an arbitrary alpha — the theme-aware replacement for `Color.White.a(x)`.
 *
 * A literal white at low alpha is a hairline on a black page and invisible on a white
 * one. Call sites that meant "a faint mark on whatever the page is" use this.
 */
@Composable
@ReadOnlyComposable
fun inkOn(alpha: Float): Color =
    LocalSendspinColors.current.textPrimary.copy(alpha = alpha)

// --- theme-independent ----------------------------------------------------

// Album-derived accent falls back to this amber-gold. A plain constant: it seeds the
// colour schemes themselves and is read from non-composable code in AlbumPalette.
val DefaultAccent = Color(0xFFD9A85C)

/** Fallback companion swatches when an album yields no usable hues. */
val FallbackPalette = listOf(
    DefaultAccent,
    Color(0xFF7A8FD9),
    Color(0xFF5EC8C0),
    Color(0xFFD95C8F),
    Color(0xFFE0A848),
)

// Status colours. Constant across themes on purpose — these carry meaning, and a red
// that shifts with the background stops reading as "error" at a glance. All three are
// mid-tone enough to hold contrast on both a black and a near-white page.
val PositiveGreen = Color(0xFF3ECF7A)
val WarnAmber = Color(0xFFE8A42C)
val ErrorRed = Color(0xFFE05656)
