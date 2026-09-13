package com.engabd.sendpin.ui.design

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.engabd.sendpin.R
import com.engabd.sendpin.library.ServerKind

/**
 * A glyph for each [ServerKind], so a server reads as "the Plex one" or "the Jellyfin
 * one" at a glance instead of every self-hosted media server sharing one generic
 * server-rack icon. Material has no actual provider logos, so these are the closest
 * distinct stand-ins rather than brand marks — chosen so no two kinds share a glyph.
 *
 * The `when` is exhaustive on purpose and has no `else`: a [ServerKind] added without
 * a line here is a compile error rather than a server that silently falls back to
 * every other unbuilt provider's icon.
 *
 * Not called directly outside this file — [ServerKindGlyph] is the entry point every
 * other file uses, falling back to this wherever [serverKindLogoRes] has nothing.
 */
private fun serverKindIcon(kind: ServerKind): ImageVector = when (kind) {
    ServerKind.MUSIC_ASSISTANT -> Icons.Default.Speaker
    ServerKind.NAVIDROME -> Icons.Default.Storage
    ServerKind.SUBSONIC -> Icons.Default.Dns
    ServerKind.JELLYFIN -> Icons.Default.Theaters
    ServerKind.EMBY -> Icons.Default.VideoLibrary
    ServerKind.PLEX -> Icons.Default.PlayCircleFilled
    // Fallbacks only: the streaming accounts have their brand marks below.
    ServerKind.SPOTIFY -> Icons.Default.Radio
    ServerKind.QOBUZ -> Icons.Default.Equalizer
    ServerKind.TIDAL -> Icons.Default.CloudSync
    ServerKind.AUDIOBOOKSHELF -> Icons.AutoMirrored.Filled.MenuBook
    ServerKind.KODI -> Icons.Default.Tv
    ServerKind.SMB -> Icons.Default.FolderShared
    ServerKind.WEBDAV -> Icons.Default.CloudUpload
    ServerKind.GOOGLE_DRIVE -> Icons.Default.Cloud
    ServerKind.ONEDRIVE -> Icons.Default.CloudQueue
    ServerKind.DROPBOX -> Icons.Default.CloudCircle
    ServerKind.BOX -> Icons.Default.Archive
    ServerKind.PCLOUD -> Icons.Default.CloudDone
    ServerKind.LOCAL -> Icons.Default.Smartphone
    ServerKind.DOWNLOADS -> Icons.Default.DownloadDone
    ServerKind.MPD -> Icons.AutoMirrored.Filled.QueueMusic
    ServerKind.FOOBAR2000 -> Icons.Default.GraphicEq
}

/**
 * The real brand mark for [kind], where one is available to reuse — sourced from
 * [dashboard-icons](https://github.com/walkxcode/dashboard-icons), a CC0 icon set
 * built for exactly this purpose (naming a self-hosted service in someone else's
 * UI). Null for a kind with no such asset, in which case [ServerKindGlyph] falls
 * back to [serverKindIcon]'s generic glyph.
 *
 * [ServerKind.SUBSONIC] covers several actual pieces of software (Gonic, Airsonic,
 * Astiga, Ampache) rather than one brand — the original Subsonic project has no
 * logo left to reuse, so this uses Airsonic's, since it is literally a Subsonic
 * fork and the most recognisable of the four today.
 */
private fun serverKindLogoRes(kind: ServerKind): Int? = when (kind) {
    ServerKind.MUSIC_ASSISTANT -> R.drawable.ic_logo_music_assistant
    ServerKind.NAVIDROME -> R.drawable.ic_logo_navidrome
    ServerKind.SUBSONIC -> R.drawable.ic_logo_subsonic
    ServerKind.JELLYFIN -> R.drawable.ic_logo_jellyfin
    ServerKind.EMBY -> R.drawable.ic_logo_emby
    ServerKind.PLEX -> R.drawable.ic_logo_plex
    ServerKind.KODI -> R.drawable.ic_logo_kodi
    ServerKind.AUDIOBOOKSHELF -> R.drawable.ic_logo_audiobookshelf
    ServerKind.GOOGLE_DRIVE -> R.drawable.ic_logo_google_drive
    ServerKind.ONEDRIVE -> R.drawable.ic_logo_onedrive
    ServerKind.DROPBOX -> R.drawable.ic_logo_dropbox
    ServerKind.BOX -> R.drawable.ic_logo_box
    ServerKind.SPOTIFY -> R.drawable.ic_logo_spotify
    ServerKind.QOBUZ -> R.drawable.ic_logo_qobuz
    ServerKind.TIDAL -> R.drawable.ic_logo_tidal
    ServerKind.SMB, ServerKind.WEBDAV, ServerKind.PCLOUD, ServerKind.LOCAL, ServerKind.DOWNLOADS,
    ServerKind.MPD,
    ServerKind.FOOBAR2000,
    -> null
}

/**
 * The one colour each [ServerKind] is known by, for surfaces that want to carry a
 * server's identity without drawing its whole logo: the library switcher's rows
 * bleed this through their glass, so the Jellyfin row is faintly Jellyfin-blue and
 * the Plex row faintly Plex-gold before the name is read.
 *
 * Brand colours where a brand has one (Spotify's green, Plex's gold, Emby's green,
 * Jellyfin's blue, Navidrome's blue, Kodi's cyan, the cloud drives' own blues);
 * for the kinds that are protocols rather than products — a phone's own files, an
 * MPD box, SMB — a hue chosen so no two neighbours in the picker share one.
 * Exhaustive on purpose, like [serverKindIcon]: a new kind must pick a colour.
 */
fun serverKindColor(kind: ServerKind): Color = when (kind) {
    ServerKind.MUSIC_ASSISTANT -> Color(0xFF2E9BEA)
    ServerKind.NAVIDROME -> Color(0xFF3C8DCC)
    ServerKind.SUBSONIC -> Color(0xFF5B7FE0)
    ServerKind.JELLYFIN -> Color(0xFF00A4DC)
    ServerKind.EMBY -> Color(0xFF52B54B)
    ServerKind.PLEX -> Color(0xFFE5A00D)
    ServerKind.SPOTIFY -> Color(0xFF1ED760)
    ServerKind.QOBUZ -> Color(0xFF61A1DE)
    ServerKind.TIDAL -> Color(0xFF00E0E0)
    ServerKind.AUDIOBOOKSHELF -> Color(0xFFC98A2B)
    ServerKind.KODI -> Color(0xFF17B2E7)
    ServerKind.SMB -> Color(0xFF8E9AAF)
    ServerKind.WEBDAV -> Color(0xFF7A8FA6)
    ServerKind.GOOGLE_DRIVE -> Color(0xFF4285F4)
    ServerKind.ONEDRIVE -> Color(0xFF0078D4)
    ServerKind.DROPBOX -> Color(0xFF0061FF)
    ServerKind.BOX -> Color(0xFF0061D5)
    ServerKind.PCLOUD -> Color(0xFF17BED0)
    ServerKind.LOCAL -> Color(0xFF9E9E9E)
    ServerKind.DOWNLOADS -> Color(0xFF4CAF7D)
    ServerKind.MPD -> Color(0xFFF26A21)
    ServerKind.FOOBAR2000 -> Color(0xFFE8B84A)
}

/**
 * Marks that are a single colour by design and ship here in white, so they read on
 * the dark surfaces every one of these services uses. On a light theme they take
 * the text colour instead — the only tinting a logo ever gets, and only because a
 * monochrome mark has no colour of its own to lose.
 */
private fun isMonochromeMark(kind: ServerKind): Boolean =
    kind == ServerKind.QOBUZ || kind == ServerKind.TIDAL

/**
 * [kind]'s visual identity, wherever it needs showing: the real logo from
 * [serverKindLogoRes] when there is one, else [serverKindIcon]'s generic glyph.
 *
 * A brand mark is never tinted — half the point of a real logo is that it carries
 * its own colour — so [tint] only ever reaches the fallback path. Callers size this
 * exactly like they would an `Icon`; [ContentScale.Fit] keeps a non-square asset
 * (Airsonic's wordmark, for one) centred rather than stretched to fill a square slot.
 */
@Composable
internal fun ServerKindGlyph(kind: ServerKind, tint: Color, modifier: Modifier = Modifier) {
    val logoRes = serverKindLogoRes(kind)
    if (logoRes != null) {
        val light = com.engabd.sendpin.ui.theme.LocalSendspinColors.current.isLight
        Image(
            painterResource(logoRes),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = if (isMonochromeMark(kind) && light) {
                androidx.compose.ui.graphics.ColorFilter.tint(com.engabd.sendpin.ui.theme.TextPrimary)
            } else null,
        )
    } else {
        Icon(serverKindIcon(kind), contentDescription = null, tint = tint, modifier = modifier)
    }
}
