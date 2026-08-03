package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.HideBottomChrome
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.systemNavInset
import com.engabd.sendpin.ui.theme.*

/**
 * What to do with one library item, on long-press.
 *
 * Tapping anything has always meant "play this now", which replaces the queue — so
 * queueing something up without losing what's playing had no gesture at all. The
 * album and artist screens grew "add to queue" chips for the release they were
 * showing; this is the same three choices on the one gesture a list row has spare,
 * for any media type.
 *
 * Music Assistant takes an artist, album or playlist uri on `play_media` exactly as
 * it takes a track's, so all three actions mean the same thing whatever [item] is;
 * only the wording changes. On Navidrome the library resolves a container to its
 * tracks first.
 *
 * Drawn the same way as the other sheets in this app (`NowPlayingSheet`,
 * `PlayerOptionsSheet`) rather than as a `ModalBottomSheet`, so it matches.
 */
@Composable
fun BoxScope.MediaActionsSheet(
    item: MaItem,
    onClose: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    /** Null leaves the row out — nothing on the MA backend can be downloaded. */
    onDownload: (() -> Unit)? = null,
    /** Null leaves the row out. Only ever passed for a playlist the server owns. */
    onDelete: (() -> Unit)? = null,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)

    // Dismiss scrim. Sits inside the caller's Box, so it covers the screen behind.
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() }
    )

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .dismissOnDragDown(onClose)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        // The system inset only: the sheet is drawn over the tab bar and the mini
        // player, and `HideBottomChrome` takes them off screen while it is up.
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
                Text(
                    item.name, color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // The type is worth saying out loud: an album and its title track
                // often share a name, and the three actions mean rather different
                // amounts of music depending on which one this is.
                val caption = listOfNotNull(
                    typeLabel(item.mediaType),
                    item.subtitle?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (caption.isNotBlank()) {
                    Text(
                        caption, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val whole = item.mediaType != "track" && item.mediaType != "radio"
            ActionRow(
                Icons.Default.PlayArrow, "Play now",
                if (whole) "Replaces the queue with all of it" else "Replaces the queue",
            ) { onClose(); onPlayNow() }
            ActionRow(
                Icons.AutoMirrored.Filled.PlaylistAdd, "Play next",
                "After the current track",
            ) { onClose(); onPlayNext() }
            ActionRow(
                Icons.AutoMirrored.Filled.QueueMusic, "Add to queue",
                "At the end",
            ) { onClose(); onAddToQueue() }
            onDownload?.let { dl ->
                ActionRow(
                    Icons.Default.Download, "Download",
                    if (whole) "Every track, for offline" else "For offline",
                ) { onClose(); dl() }
            }
            onDelete?.let { del ->
                var confirming by remember { mutableStateOf(false) }
                // Confirm in place rather than stacking a dialog on a sheet: this
                // deletes on the server, for every client, and cannot be undone.
                ActionRow(
                    Icons.Default.DeleteOutline,
                    if (confirming) "Tap again to delete" else "Delete playlist",
                    if (confirming) "This can't be undone" else "Removes it from the server",
                    tint = if (confirming) ErrorRed else null,
                ) { if (confirming) { onClose(); del() } else confirming = true }
            }
        }
    }
}

/** How a listener would name a media type, or null for one not worth captioning. */
private fun typeLabel(mediaType: String): String? = when (mediaType) {
    "album" -> "Album"
    "artist" -> "Artist"
    "playlist" -> "Playlist"
    "radio" -> "Radio"
    "track" -> null          // a row in a track list is obviously a track
    else -> null
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint ?: accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = TextFaint, fontFamily = AppFont, fontSize = 11.sp)
        }
    }
}
