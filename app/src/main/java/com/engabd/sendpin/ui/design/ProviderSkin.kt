package com.engabd.sendpin.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ui.theme.LocalSendspinColors
import com.engabd.sendpin.ui.theme.SendspinColors

/**
 * How a streaming account's settings page dresses itself: the service's own colours,
 * corners and type, so opening the Spotify page feels like Spotify and the Tidal
 * page like Tidal rather than three copies of the same grey card.
 *
 * Applied by [ProviderTheme], which swaps the design tokens the page's components
 * already read — [LocalSendspinColors] for inks, glass and text, [LocalAccent] for
 * the accent, Material shapes for corners — so nothing below has to know it is
 * being skinned. That is also why the light theme keeps working: every service
 * here is dark by design, and on a light page the skin keeps its accent and its
 * corners but stays on the app's light inks.
 *
 * Colours are each brand's published ones: Spotify green on the app's near-black
 * (#1ED760 / #121212, cards #282828); Qobuz's Havelock blue with its harvest-gold
 * Hi-Res accent on black (#61A1DE / #DEAB6E); Tidal's cyan on pure black
 * (#00FFFF). No logo is tinted or recoloured — see `ServerKindIcon.kt`.
 */
@Immutable
data class ProviderSkin(
    val kind: ServerKind,
    val accent: Color,
    /** A second, quieter brand colour: the Hi-Res gold on Qobuz, a deeper cyan on Tidal. */
    val accent2: Color,
    val colors: SendspinColors,
    /** Card corners. Spotify's soft 8 dp, Tidal's hard square, Qobuz's editorial 4 dp. */
    val cornerRadius: Dp,
    /** Whether the primary button is a full pill. */
    val pillButtons: Boolean,
    /** The page's display title style — the one place the type changes character. */
    val display: TextStyle,
    /** Small caps-style section labels. */
    val label: TextStyle,
    /** The hero band behind the logo, top to bottom. */
    val hero: List<Color>,
    /** One line of the service's own voice for the hero. */
    val tagline: String,
)

/** The skin for [kind], on the current theme's inks. Null for a kind that has none. */
@Composable
fun providerSkin(kind: ServerKind): ProviderSkin? {
    val base = LocalSendspinColors.current
    val light = base.isLight
    return when (kind) {
        ServerKind.SPOTIFY -> ProviderSkin(
            kind = kind,
            accent = Color(0xFF1ED760),
            accent2 = Color(0xFF1DB954),
            colors = if (light) base else base.copy(
                ink = Color(0xFF121212),
                ink2 = Color(0xFF181818),
                ink3 = Color(0xFF212121),
                glass = Color(0xFF282828),
                glassStrong = Color(0xFF333333),
                hairline = Color(0x1FFFFFFF),
                hairlineSoft = Color(0x14FFFFFF),
                textSecondary = Color(0xFFB3B3B3),
                textMuted = Color(0xFFA7A7A7),
            ),
            cornerRadius = 8.dp,
            pillButtons = true,
            display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = (-0.8).sp),
            label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp),
            hero = listOf(Color(0xFF1ED760), Color(0xFF0D6B32), Color(0xFF121212)),
            tagline = "Music for everyone.",
        )
        ServerKind.QOBUZ -> ProviderSkin(
            kind = kind,
            accent = Color(0xFF61A1DE),
            accent2 = Color(0xFFDEAB6E),
            colors = if (light) base else base.copy(
                ink = Color(0xFF000000),
                ink2 = Color(0xFF0A0A0A),
                ink3 = Color(0xFF111111),
                glass = Color(0xFF141414),
                glassStrong = Color(0xFF1C1C1C),
                hairline = Color(0x2EFFFFFF),
                hairlineSoft = Color(0x1AFFFFFF),
            ),
            cornerRadius = 4.dp,
            pillButtons = false,
            display = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 30.sp, letterSpacing = 0.2.sp),
            label = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 2.sp),
            hero = listOf(Color(0xFF1B3A5C), Color(0xFF0B1826), Color(0xFF000000)),
            tagline = "The sound of your music, as the studio heard it.",
        )
        ServerKind.TIDAL -> ProviderSkin(
            kind = kind,
            accent = Color(0xFF00FFFF),
            accent2 = Color(0xFF63F2F2),
            colors = if (light) base else base.copy(
                ink = Color(0xFF000000),
                ink2 = Color(0xFF000000),
                ink3 = Color(0xFF0A0A0A),
                glass = Color(0xFF0F0F0F),
                glassStrong = Color(0xFF1A1A1A),
                hairline = Color(0x33FFFFFF),
                hairlineSoft = Color(0x1FFFFFFF),
            ),
            cornerRadius = 0.dp,
            pillButtons = false,
            display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 30.sp, letterSpacing = 6.sp),
            label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 3.sp),
            hero = listOf(Color(0xFF00FFFF), Color(0xFF004C4C), Color(0xFF000000)),
            tagline = "Sound, in high fidelity.",
        )
        else -> null
    }
}

/**
 * Dress [content] in [skin]: the page's inks, glass, text and accent become the
 * service's, and Material's medium shape takes its corners. Everything the settings
 * components draw with reads through these, so the skin reaches every card, field
 * and toggle underneath without any of them knowing.
 */
@Composable
fun ProviderTheme(skin: ProviderSkin, content: @Composable () -> Unit) {
    val shapes = MaterialTheme.shapes
    val corner = RoundedCornerShape(skin.cornerRadius)
    CompositionLocalProvider(
        LocalSendspinColors provides skin.colors,
        LocalAccent provides skin.accent,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = skin.accent,
                secondary = skin.accent2,
                outline = skin.colors.hairline,
            ),
            shapes = Shapes(
                extraSmall = corner,
                small = corner,
                medium = corner,
                large = shapes.large,
                extraLarge = shapes.extraLarge,
            ),
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
