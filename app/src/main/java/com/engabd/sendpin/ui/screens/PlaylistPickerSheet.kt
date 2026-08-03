package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Which playlist to file something into.
 *
 * Drawn like the other sheets in this app rather than as a `ModalBottomSheet`, and
 * capped in height so a library with two hundred playlists scrolls instead of
 * pushing its own list off the screen.
 */
@Composable
fun BoxScope.PlaylistPickerSheet(
    itemName: String,
    playlists: List<MaItem>,
    onClose: () -> Unit,
    onPick: (MaItem) -> Unit,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)
    val accent = LocalAccent.current

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
        Column(Modifier.fillMaxWidth().padding(bottom = systemNavInset() + 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp)) {
                Text(
                    "Add to playlist", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                )
                Text(
                    itemName, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet — create one from the Playlists list first.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(playlists, key = { it.provider + "|" + it.itemId }) { pl ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl) }
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic, null,
                                tint = accent, modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pl.name, color = TextPrimary, fontFamily = AppFont,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                pl.subtitle?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
