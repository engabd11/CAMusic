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
import com.engabd.sendpin.ui.theme.DefaultAccent
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

/**
 * The skin for [kind], on the current theme's inks. Every kind has one: the three
 * streaming accounts in their own brand palettes, the self-hosted servers in
 * theirs (Jellyfin's purple-to-cyan, Emby's green, Plex's gold on charcoal,
 * Navidrome's and Music Assistant's blues), and the phone's own libraries in the
 * app's accent, since they are not someone else's product.
 *
 * Only the accent, the hero band, the corners and the display type change for the
 * server kinds; the inks stay the app's, because a Jellyfin page is still this
 * app's settings page. The streaming accounts go further and take the service's
 * surfaces too, because those pages stand in for an app the user already knows.
 */
@Composable
fun providerSkin(kind: ServerKind): ProviderSkin {
    val base = LocalSendspinColors.current
    val light = base.isLight
    val sans = FontFamily.SansSerif
    fun display(weight: FontWeight, spacing: Float = 0f, family: FontFamily = sans) =
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = 30.sp, letterSpacing = spacing.sp)
    fun label(spacing: Float = 1.4f, family: FontFamily = sans) =
        TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = spacing.sp)
    /** A server kind's skin: the app's inks with the brand's accent, band and corners. */
    fun server(
        accent: Color, accent2: Color, corner: Dp, hero: List<Color>, tagline: String,
        display: TextStyle = display(FontWeight.ExtraBold, -0.4f), label: TextStyle = label(), pill: Boolean = false,
    ) = ProviderSkin(kind, accent, accent2, base, corner, pill, display, label, hero + base.ink, tagline)
    return when (kind) {
        ServerKind.MUSIC_ASSISTANT -> server(
            accent = Color(0xFF2FA8E6), accent2 = Color(0xFF7CD6F5), corner = 16.dp,
            hero = listOf(Color(0xFF0E5C8C), Color(0xFF082E47)),
            tagline = "One queue, every speaker in the house.",
        )
        ServerKind.NAVIDROME -> server(
            accent = Color(0xFF3B8DE0), accent2 = Color(0xFF8BC1F5), corner = 14.dp,
            hero = listOf(Color(0xFF1A4F86), Color(0xFF0B2540)),
            tagline = "Your music, on your own server.",
        )
        ServerKind.SUBSONIC -> server(
            accent = Color(0xFF4F7FD9), accent2 = Color(0xFF9DB9F0), corner = 14.dp,
            hero = listOf(Color(0xFF2A3F80), Color(0xFF121C3B)),
            tagline = "Any server that speaks Subsonic.",
        )
        ServerKind.JELLYFIN -> server(
            accent = Color(0xFF00A4DC), accent2 = Color(0xFFAA5CC3), corner = 18.dp,
            hero = listOf(Color(0xFFAA5CC3), Color(0xFF00A4DC), Color(0xFF0B1A33)),
            tagline = "The free software media system.",
            display = display(FontWeight.SemiBold, 0.2f),
        )
        ServerKind.EMBY -> server(
            accent = Color(0xFF52B54B), accent2 = Color(0xFFA5E29F), corner = 12.dp,
            hero = listOf(Color(0xFF2E7A2A), Color(0xFF11300F)),
            tagline = "Your media, your way.",
        )
        ServerKind.PLEX -> server(
            accent = Color(0xFFE5A00D), accent2 = Color(0xFFF5C55A), corner = 10.dp,
            hero = listOf(Color(0xFF3D3D3F), Color(0xFF282A2D), Color(0xFF1B1C1E)),
            tagline = "Stream everything, everywhere.",
            display = display(FontWeight.Black, -0.6f),
        )
        ServerKind.MPD -> server(
            accent = Color(0xFF7CB342), accent2 = Color(0xFFC5E1A5), corner = 4.dp,
            hero = listOf(Color(0xFF1F2A1A), Color(0xFF0E140C)),
            tagline = "The music player daemon.",
            display = display(FontWeight.Medium, 0f, FontFamily.Monospace),
            label = label(1.0f, FontFamily.Monospace),
        )
        ServerKind.FOOBAR2000 -> server(
            accent = Color(0xFF3C87E0), accent2 = Color(0xFF9CC3F2), corner = 6.dp,
            hero = listOf(Color(0xFF244E7A), Color(0xFF10233A)),
            tagline = "Playing on the desktop, driven from here.",
            display = display(FontWeight.Medium, 0f, FontFamily.Monospace),
            label = label(1.0f, FontFamily.Monospace),
        )
        ServerKind.LOCAL -> server(
            accent = DefaultAccent, accent2 = DefaultAccent.copy(alpha = 0.7f), corner = 16.dp,
            hero = listOf(DefaultAccent.copy(alpha = 0.55f), DefaultAccent.copy(alpha = 0.18f)),
            tagline = "Music already on this phone.",
        )
        ServerKind.DOWNLOADS -> server(
            accent = Color(0xFF2FBF71), accent2 = Color(0xFF8EE0B4), corner = 16.dp,
            hero = listOf(Color(0xFF1C6B40), Color(0xFF0C2E1B)),
            tagline = "Kept on this phone, for wherever there is no network.",
        )
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
        // A kind that is listed but not built yet has no page to dress; the app's own
        // accent keeps the picker row honest until it does.
        else -> server(
            accent = DefaultAccent, accent2 = DefaultAccent.copy(alpha = 0.7f), corner = 16.dp,
            hero = listOf(DefaultAccent.copy(alpha = 0.45f), DefaultAccent.copy(alpha = 0.12f)),
            tagline = kind.blurb,
        )
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
