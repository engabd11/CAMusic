package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaQueueItem
import com.engabd.sendpin.ma.MaSimilarTrack
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load

/**
 * Which panel the sheet is showing. Lyrics moved into the player itself (it takes
 * the album art's place) and sonic similarity moved to the Library, where finding
 * something to play belongs — neither was ever really "the queue button".
 */
enum class Panel(val label: String) { QUEUE("Queue") }

/**
 * The panel sheet: everything about the *track* that doesn't belong on the
 * player itself. It slides over the bottom of Now Playing so the cover stays
 * visible behind it — you're still looking at what's playing while you work on
 * what's next.
 *
 * Not a ModalBottomSheet: this is drawn inside the screen's own Box so the album
 * wash reads through the top edge, which a Material scrim would flatten.
 */
@Composable
fun BoxScope.NowPlayingSheet(
    onClose: () -> Unit,
    viewModel: NowPlayingViewModel,
) {
    val accent = LocalAccent.current

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            // Swallow taps so the dismiss scrim behind the sheet only ever sees
            // taps that actually landed outside it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Grabber + tabs.
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queue", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }

            QueuePanel(viewModel, accent)
        }
    }
}

// --- queue ----------------------------------------------------------------

@Composable
private fun ColumnScope.QueuePanel(viewModel: NowPlayingViewModel, accent: Color) {
    val load by viewModel.queueItems.collectAsState()
    val st by viewModel.state.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (load is Load.Idle) viewModel.loadQueue() }

    // Queue-wide actions.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${st.queueSize} ${if (st.queueSize == 1) "track" else "tracks"}",
            color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        SmallAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Save as playlist") { naming = true; playlistName = "" }
        SmallAction(Icons.Default.DeleteSweep, if (confirmClear) "Sure?" else "Clear") {
            if (confirmClear) { viewModel.clearQueue(); confirmClear = false } else confirmClear = true
        }
    }

    if (naming) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    viewModel.saveQueueAsPlaylist(playlistName); naming = false
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
                    unfocusedBorderColor = Hairline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Save", true) { viewModel.saveQueueAsPlaylist(playlistName); naming = false }
                Pill("Cancel", false) { naming = false }
            }
        }
    }

    when (val l = load) {
        is Load.Loading, Load.Idle -> PanelSpinner()
        is Load.Failed -> PanelMessage(Icons.Default.CloudOff, l.message)
        is Load.Ready -> {
            if (l.value.isEmpty()) {
                PanelMessage(Icons.AutoMirrored.Filled.QueueMusic, "The queue is empty.")
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = navBarInset() + 16.dp),
                ) {
                    itemsIndexed(l.value, key = { _, it -> it.queueItemId }) { i, item ->
                        QueueRow(
                            item = item,
                            playing = item.queueItemId == st.currentQueueItemId,
                            first = i == 0,
                            last = i == l.value.lastIndex,
                            onPlay = { viewModel.playQueueIndex(item.index) },
                            onUp = { viewModel.moveQueueItem(item, -1) },
                            onDown = { viewModel.moveQueueItem(item, 1) },
                            onRemove = { viewModel.removeQueueItem(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: MaQueueItem,
    playing: Boolean,
    first: Boolean,
    last: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = LocalAccent.current
    var open by remember(item.queueItemId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(13.dp))
            .background(if (playing) accent.a(0.10f) else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Glass), contentAlignment = Alignment.Center) {
                if (item.image != null) {
                    AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.matchParentSize())
                } else {
                    Icon(Icons.Default.MusicNote, null, tint = TextFaint, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = if (playing) accent else if (item.available) TextPrimary else TextFaint,
                    fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!item.artist.isNullOrBlank()) {
                    Text(
                        item.artist, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.duration?.takeIf { it > 0 }?.let {
                Text(
                    "%d:%02d".format(it / 60, it % 60),
                    color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.MoreVert, null,
                tint = TextFaint, modifier = Modifier.size(16.dp),
            )
        }
        // Row actions stay folded away: a queue is mostly for reading.
        if (open) {
            Row(
                Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallAction(Icons.Default.PlayArrow, "Play") { onPlay() }
                if (!first) SmallAction(Icons.Default.ArrowUpward, "Up") { onUp() }
                if (!last) SmallAction(Icons.Default.ArrowDownward, "Down") { onDown() }
                SmallAction(Icons.Default.Delete, "Remove") { onRemove() }
            }
        }
    }
}

// --- shared bits ----------------------------------------------------------

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        Text(label, color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ColumnScope.PanelSpinner() {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalAccent.current)
    }
}

@Composable
private fun ColumnScope.PanelMessage(icon: ImageVector, message: String) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = TextFaint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            message, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
