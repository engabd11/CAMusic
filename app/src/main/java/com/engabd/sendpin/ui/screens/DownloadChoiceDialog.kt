package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

/**
 * How a playlist should be downloaded: as a playlist, or as songs.
 *
 * The question is worth asking rather than assuming, in both directions. Keeping the
 * playlist is what almost everyone means — the alternative was silently flattening it
 * to its tracks, so the files arrived filed under their own albums and the thing that
 * was asked for stopped existing. But "just the songs" is a real answer too: someone
 * downloading a friend's 200-track mix to cherry-pick from it does not want a copy of
 * that mix in their Downloads library forever.
 *
 * Neither choice changes what lands on disk. A downloaded playlist stores an ordering
 * and nothing else — the same audio files, the same bytes, still showing up under
 * their own albums and artists. That is worth saying on the dialog, because "download
 * the playlist" otherwise sounds like it might cost twice the space.
 *
 * Shown on every download of a playlist, with the playlist option preselected. No
 * remembered preference: it is one tap either way, and a remembered choice is a
 * setting someone has to find and undo the first time they want the other one.
 */
@Composable
internal fun DownloadChoiceDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onChoose: (keepPlaylist: Boolean) -> Unit,
) {
    val accent = LocalAccent.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                "Download playlist",
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    playlistName,
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DownloadChoiceRow(
                    icon = Icons.Default.PlaylistPlay,
                    title = "Keep it as a playlist",
                    subtitle = "Appears under Downloads › Playlists, in order",
                    accent = accent,
                    highlighted = true,
                ) { onChoose(true) }
                DownloadChoiceRow(
                    icon = Icons.Default.MusicNote,
                    title = "Just the songs",
                    subtitle = "Filed under their albums and artists only",
                    accent = accent,
                    highlighted = false,
                ) { onChoose(false) }
                Text(
                    "Either way the same files are downloaded once — a playlist stores the " +
                        "order, not another copy.",
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}

/**
 * One option. The recommended one is filled with the accent rather than merely
 * listed first — a dialog of two identical rows makes the reader do the deciding.
 */
@Composable
private fun DownloadChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) accent.a(0.14f) else Color.Transparent)
            .border(1.dp, if (highlighted) accent.a(0.5f) else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (highlighted) accent else accent.a(0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlighted) Ink else accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = TextSecondary,
                fontFamily = AppFont,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
