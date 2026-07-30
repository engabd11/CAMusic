package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
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
            .dismissOnDragDown(onClose)
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
                QueueList(l.value, st.currentQueueItemId, viewModel)
            }
        }
    }
}

/**
 * The queue itself: a row is the track, so tapping one plays it. Reordering hangs
 * off the grip on the right — press it and the row follows the finger, with the
 * rest of the list opening up around it. Nothing is sent to the server until the
 * finger lifts, and then it's a single move by however far the row travelled.
 */
@Composable
private fun ColumnScope.QueueList(
    items: List<MaQueueItem>,
    currentId: String?,
    viewModel: NowPlayingViewModel,
) {
    val listState = rememberLazyListState()

    // The order shown right now. It tracks the server's list except while a drag is
    // in flight, when the finger owns it.
    var order by remember { mutableStateOf(items) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // How far the dragged row sits from the slot it currently occupies.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var rowHeight by remember { mutableIntStateOf(0) }

    // A server refresh mid-drag would yank the row out from under the finger.
    LaunchedEffect(items) { if (draggingId == null) order = items }

    fun endDrag() {
        val id = draggingId ?: return
        val from = dragStartIndex
        val to = order.indexOfFirst { it.queueItemId == id }
        draggingId = null
        dragOffset = 0f
        dragStartIndex = -1
        if (to >= 0 && from >= 0 && to != from) {
            items.firstOrNull { it.queueItemId == id }?.let { viewModel.moveQueueItem(it, to - from) }
        }
    }

    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = navBarInset() + 16.dp),
        // Scrolling and dragging a row are different jobs; let the grip have the
        // gesture to itself rather than fighting the list for it.
        userScrollEnabled = draggingId == null,
    ) {
        itemsIndexed(order, key = { _, it -> it.queueItemId }) { _, item ->
            val dragging = item.queueItemId == draggingId
            QueueRow(
                item = item,
                playing = item.queueItemId == currentId,
                dragging = dragging,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .onSizeChanged { if (it.height > 0) rowHeight = it.height },
                onPlay = { viewModel.playQueueItem(item) },
                onRemove = { viewModel.removeQueueItem(item) },
                onDragStart = {
                    draggingId = item.queueItemId
                    dragStartIndex = order.indexOfFirst { r -> r.queueItemId == item.queueItemId }
                    dragOffset = 0f
                },
                onDrag = { dy ->
                    dragOffset += dy
                    val h = rowHeight
                    if (h > 0) {
                        var index = order.indexOfFirst { r -> r.queueItemId == draggingId }
                        // Swap past a neighbour once the row has travelled half of
                        // one, and hand that distance back so the offset stays small.
                        while (dragOffset > h / 2f && index in 0 until order.lastIndex) {
                            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                            dragOffset -= h
                            index++
                        }
                        while (dragOffset < -h / 2f && index > 0) {
                            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                            dragOffset += h
                            index--
                        }
                    }
                },
                onDragEnd = { endDrag() },
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: MaQueueItem,
    playing: Boolean,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                when {
                    dragging -> GlassStrong
                    playing -> accent.a(0.10f)
                    else -> Color.Transparent
                }
            )
            // The row is the track: tapping it plays it. Everything else on the row
            // is a control with its own target.
            .clickable(onClick = onPlay)
            .padding(9.dp),
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
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.Close, "Remove from queue", tint = TextFaint,
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onRemove).padding(5.dp),
        )
        // The reorder grip. Dragging starts here and nowhere else, so a tap
        // anywhere on the row is unambiguously "play this".
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .pointerInput(item.queueItemId) {
                    detectVerticalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onVerticalDrag = { change, dy -> change.consume(); onDrag(dy) },
                    )
                }
                // Swallow taps: the grip is something you hold, and letting a tap on
                // it fall through to the row would play a track you meant to grab.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.DragHandle, "Reorder",
                tint = if (dragging) accent else TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// --- sleep timer ----------------------------------------------------------

/**
 * The sleep-timer control: the chip, a live countdown beside it, and the picker.
 *
 * The chip used to cycle silently through 15/30/45/60 with nothing on screen to
 * show for it, so a running timer and a dead button looked exactly alike — which
 * is why the timer read as doing nothing. It now says what it set and how long is
 * left, and can be called off again.
 */
@Composable
fun SleepTimerChip(viewModel: NowPlayingViewModel) {
    val minutes by viewModel.sleepTimerMin.collectAsState()
    val remainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
    val accent = LocalAccent.current
    var picking by remember { mutableStateOf(false) }
    val running = minutes > 0

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconChip(
            Icons.Default.Bedtime,
            if (running) "Sleep timer, ${countdown(remainingMs)} left" else "Sleep timer",
            active = running,
        ) { picking = true }
        if (running) {
            Text(
                countdown(remainingMs), color = accent, fontFamily = MonoFont,
                fontWeight = FontWeight.Bold, fontSize = 11.sp,
            )
        }
    }

    if (picking) {
        Popup(
            popupPositionProvider = WindowCenterPosition,
            onDismissRequest = { picking = false },
            properties = PopupProperties(focusable = true),
        ) {
            var sliderMinutes by remember { mutableStateOf(minutes.coerceAtLeast(5)) }
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink2)
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Sleep timer", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                )
                Text(
                    if (running) "${countdown(remainingMs)} left — the music fades out over the last 10 seconds."
                    else "Fades the music out, then pauses the player.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, lineHeight = 16.sp,
                )
                // Slider: 1–360 minutes (six hours), snapping to 5-minute steps past
                // the first hour — a 1-minute snap across six hours is a pixel per
                // step and impossible to land on.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        sleepLabel(sliderMinutes),
                        color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, modifier = Modifier.width(68.dp),
                    )
                    Slider(
                        value = sliderMinutes.toFloat(),
                        onValueChange = { sliderMinutes = snapSleepMinutes(it) },
                        valueRange = 1f..360f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (running) "Cancel timer" else "Off", false, Modifier.weight(1f)) {
                        viewModel.cancelSleepTimer(); picking = false
                    }
                    Pill("Start", true, Modifier.weight(1f)) {
                        viewModel.setSleepTimer(sliderMinutes); picking = false
                    }
                }
            }
        }
    }
}

/**
 * Where the sleep slider lands: every minute up to an hour, then every five. Fine
 * control is what you want for "stop after this album"; nobody sets a five-hour
 * timer to the minute.
 */
private fun snapSleepMinutes(raw: Float): Int {
    val m = raw.toInt().coerceIn(1, 360)
    return if (m <= 60) m else (Math.round(m / 5f) * 5).coerceAtMost(360)
}

/** `45m` under the hour, `2h 30m` over it — `150m` tells the user nothing. */
private fun sleepLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** mm:ss, or h:mm:ss once there's an hour on the clock. */
private fun countdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
