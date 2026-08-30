package com.engabd.sendpin.ui.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Shared between the provider picker (`LibrariesSettings`) and the Library screen's
 * own badge (`LibraryHeader.BackendTag`) — the same glyph should name the same server
 * wherever it shows up.
 */
internal fun serverKindIcon(kind: ServerKind): ImageVector = when (kind) {
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
}
