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
    ServerKind.SMB, ServerKind.WEBDAV, ServerKind.PCLOUD, ServerKind.LOCAL, ServerKind.DOWNLOADS,
    ServerKind.MPD,
    -> null
}

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
        Image(
            painterResource(logoRes),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(serverKindIcon(kind), contentDescription = null, tint = tint, modifier = modifier)
    }
}
