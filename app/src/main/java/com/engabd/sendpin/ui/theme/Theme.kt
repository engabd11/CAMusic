package com.engabd.sendpin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.engabd.sendpin.ui.design.LocalReducedMotion
import com.engabd.sendpin.ui.design.rememberReducedMotion
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Which page the app is painted on. Persisted as the string in [key].
 *
 * OLED is the default and the app's identity: a true-black page the album art melts
 * into. The others exist because that identity is a choice, and an LCD panel or a
 * daylit room can make it the wrong one.
 */
enum class ThemeChoice(val key: String, val label: String, val description: String) {
    OLED("oled", "OLED black", "True black. Album art melts into the screen."),
    DARK("dark", "Dark", "Softer charcoal. Kinder to LCD panels."),
    LIGHT("light", "Light", "A warm off-white page."),
    SYSTEM("system", "Follow system", "OLED black at night, light by day.");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: OLED
    }
}

/** Where the accent colour comes from. */
enum class AccentChoice(val key: String, val label: String, val description: String) {
    ALBUM("album", "Album art", "The accent is drawn from whatever is playing."),
    DYNAMIC("dynamic", "Wallpaper", "Material You — one accent, taken from your wallpaper."),
    FIXED("fixed", "Fixed colour", "One accent you pick, that never changes.");

    /**
     * The accent to actually use, given what the artwork yielded.
     *
     * [fromArt] is honoured only under [ALBUM]. [DYNAMIC] defers to [schemePrimary],
     * which the wallpaper palette has already filled; [FIXED] takes the user's colour.
     */
    fun resolve(fromArt: Color, fixed: Color, schemePrimary: Color): Color = when (this) {
        ALBUM -> fromArt
        DYNAMIC -> schemePrimary
        FIXED -> fixed
    }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALBUM
    }
}

/**
 * The page colour for a theme key, without a composition.
 *
 * Used by the Activity to paint the launch window before Compose exists — see
 * `AppSettings.bootTheme`.
 */
fun pageColorFor(themeKey: String, systemDark: Boolean): Color =
    when (ThemeChoice.from(themeKey)) {
        ThemeChoice.OLED -> OledColors.ink
        ThemeChoice.DARK -> DarkColors.ink
        ThemeChoice.LIGHT -> LightColors.ink
        ThemeChoice.SYSTEM -> if (systemDark) OledColors.ink else LightColors.ink
    }

/**
 * An ARGB hex string back to a colour, falling back to [DefaultAccent].
 *
 * Persisted as text because DataStore Preferences has no colour type and an Int key
 * would be read as a signed value, which makes a stored accent unreadable by eye when
 * debugging the file.
 */
fun parseAccent(hex: String): Color =
    hex.removePrefix("#").takeIf { it.length == 8 }
        ?.toLongOrNull(16)
        ?.let { Color(it.toInt()) }
        ?: DefaultAccent

/** The inverse of [parseAccent]. */
fun Color.toAccentHex(): String = "%08X".format(toArgb())

/**
 * The M3 colour scheme, built from a [SendspinColors] page and a seed accent.
 *
 * Every M3 role is filled so the stock components — OutlinedTextField, Switch, Slider,
 * CircularProgressIndicator — inherit the look without per-call overrides. The roles
 * are also what the design's own tokens map onto, so the two layers cannot drift.
 *
 * The album-derived accent is applied separately per track via `LocalAccent`; the seed
 * here is the *base* the scheme is built on.
 */
private fun schemeFor(c: SendspinColors, accent: Color): ColorScheme {
    val base = if (c.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = c.ink,
        primaryContainer = accent.copy(alpha = 0.14f),
        onPrimaryContainer = accent,

        // Secondary mirrors primary so segmented controls and secondary buttons share
        // the accent identity rather than introducing an unrelated hue.
        secondary = accent,
        onSecondary = c.ink,
        secondaryContainer = c.glassStrong,
        onSecondaryContainer = c.textSecondary,

        // A teal companion, so components that reach for tertiary get something
        // distinct but harmonious rather than a stock purple.
        tertiary = FallbackPalette[2],
        onTertiary = c.ink,
        tertiaryContainer = FallbackPalette[2].copy(alpha = 0.14f),
        onTertiaryContainer = c.textSecondary,

        background = c.ink,
        onBackground = c.textPrimary,
        surface = c.ink,
        onSurface = c.textPrimary,
        surfaceVariant = c.glassStrong,
        onSurfaceVariant = c.textMuted,
        surfaceTint = accent,

        // Inverse is what Snackbar sits on, so it flips against the page.
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.ink,

        error = ErrorRed,
        onError = c.ink,
        errorContainer = ErrorRed.copy(alpha = 0.14f),
        onErrorContainer = ErrorRed,

        outline = c.hairline,
        outlineVariant = c.hairlineSoft,
        scrim = Color.Black,
    )
}

/**
 * M3 shape scale mapped to the design system's corner radii.
 *
 * | M3 role      | Radius | Used by                          |
 * |--------------|--------|----------------------------------|
 * | small        | 9.dp   | chips, toggle chips              |
 * | medium       | 11.dp  | icon chips, quality pills        |
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
fun SendspinTheme(
    theme: ThemeChoice = ThemeChoice.OLED,
    accentChoice: AccentChoice = AccentChoice.ALBUM,
    /** The accent for [AccentChoice.FIXED], and the seed when nothing is playing. */
    seedAccent: Color = DefaultAccent,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (theme) {
        ThemeChoice.OLED -> OledColors
        ThemeChoice.DARK -> DarkColors
        ThemeChoice.LIGHT -> LightColors
        // Following the system picks the app's own black, not the softened dark:
        // someone who has not chosen a theme should see the app as designed.
        ThemeChoice.SYSTEM -> if (systemDark) OledColors else LightColors
    }

    val context = LocalContext.current
    val scheme = when (accentChoice) {
        // Material You. minSdk is 31, so the platform call needs no version guard —
        // and unlike the other two this replaces the *whole* scheme, because a
        // wallpaper palette is a set of harmonised roles, not one colour to graft on.
        AccentChoice.DYNAMIC -> remember(colors, context) {
            val dyn = if (colors.isLight) dynamicLightColorScheme(context)
            else dynamicDarkColorScheme(context)
            // The page stays the app's own: Material You supplies the hues, but an
            // OLED user chose true black and a wallpaper should not lift it to grey.
            dyn.copy(
                background = colors.ink,
                surface = colors.ink,
                onBackground = colors.textPrimary,
                onSurface = colors.textPrimary,
                outline = colors.hairline,
                outlineVariant = colors.hairlineSoft,
            )
        }
        else -> remember(colors, seedAccent) { schemeFor(colors, seedAccent) }
    }

    CompositionLocalProvider(
        LocalSendspinColors provides colors,
        // Read here so every surface shares one answer for the frame — see
        // LocalReducedMotion for why this is a design flag and not a duration scale.
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
