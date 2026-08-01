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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.dismissOnDragDown
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.theme.*

/**
 * What to do with one track, on long-press.
 *
 * Tapping a track has always meant "play this now", which replaces the queue — so
 * queueing a track up without losing what's playing had no gesture at all. The album
 * and artist screens grew "add to queue" chips for the *whole* release; this is the
 * per-track equivalent, on the one gesture a list row has spare.
 *
 * Drawn the same way as the other sheets in this app (`NowPlayingSheet`,
 * `PlayerOptionsSheet`) rather than as a `ModalBottomSheet`, so it matches.
 */
@Composable
fun BoxScope.TrackActionsSheet(
    track: MaItem,
    onClose: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
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
        Column(Modifier.fillMaxWidth().padding(bottom = navBarInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
                Text(
                    track.name, color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                track.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ActionRow(Icons.Default.PlayArrow, "Play now", "Replaces the queue") {
                onClose(); onPlayNow()
            }
            ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Play next", "After the current track") {
                onClose(); onPlayNext()
            }
            ActionRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", "At the end") {
                onClose(); onAddToQueue()
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = TextFaint, fontFamily = AppFont, fontSize = 11.sp)
        }
    }
}
