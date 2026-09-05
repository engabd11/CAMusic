package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animate
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaQueueItem
import com.engabd.sendpin.ma.MaSimilarTrack
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.viewmodel.DspViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load
import com.engabd.sendpin.ui.viewmodel.formatListeningTime

/**
 * Which panel the sheet is showing. Lyrics moved into the player itself (it takes
 * the album art's place), and sonic *search* — "rainy sunday piano" — moved to the
 * Library, where going looking for something to play belongs; neither was ever
 * really "the queue button".
 *
 * [SIMILAR] is the half of that which stayed. "What else sounds like this" is a
 * question about the record that is on, so it is asked where the record is, off the
 * long-press sheet rather than a chip of its own: once a song, not once a session.
 */
enum class Panel(val label: String) { QUEUE("Queue"), DSP("Equaliser"), SIMILAR("More like this") }

/**
 * The DSP view model, scoped to the Activity rather than to the sheet.
 *
 * The sheet is composed and thrown away every time it opens, and a view model owned
 * by it would refetch the player's whole DSP config on each pass. Hoisting it means
 * the config outlives the sheet, so reopening is instant and an edit made before a
 * dismissal is still there afterwards.
 */
@Composable
private fun dspViewModel(): DspViewModel = viewModel()

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
    /** Which panel is showing. Its label is the sheet's title. */
    panel: Panel = Panel.QUEUE,
    /**
     * Whether this phone is doing the decoding.
     *
     * Decides which equaliser the DSP panel shows: Music Assistant's server-side
     * pipeline for a session it owns, this phone's own for one it does not. They
     * are different engines on different machines, and showing either in the
     * other's place would edit settings for a player that is not playing.
     */
    localSession: Boolean = false,
) {
    HideBottomChrome()
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
                    if (panel == Panel.DSP && !localSession) "DSP & Equalizer" else panel.label,
                    color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }

            when (panel) {
                Panel.QUEUE -> QueuePanel(viewModel, accent)
                // DSP used to be a whole separate destination, which meant leaving the
                // player — and losing sight of what you were tuning — to move a slider.
                // Same body as the standalone screen, hung off the player instead.
                // Two engines, one chip. Music Assistant's DSP is a server-side
                // pipeline configured per MA player; the local one is a processor in
                // this phone's own sink. Neither can describe the other.
                Panel.DSP ->
                    if (localSession) LocalEqBody(accent)
                    else DspBody(dspViewModel(), accent)
                Panel.SIMILAR -> SimilarPanel(viewModel)
            }
        }
    }
}

// --- queue ----------------------------------------------------------------

@Composable
private fun ColumnScope.QueuePanel(viewModel: NowPlayingViewModel, accent: Color) {
    val load by viewModel.queueItems.collectAsStateWithLifecycle()
    val st by viewModel.state.collectAsStateWithLifecycle()
    val remainingMs by viewModel.queueRemainingMs.collectAsStateWithLifecycle()
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
            buildString {
                append("${st.queueSize} ${if (st.queueSize == 1) "track" else "tracks"}")
                remainingMs?.let { append(" · ${formatListeningTime(it)} left") }
            },
            color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        SmallAction(Icons.Default.Shuffle, "Shuffle") { viewModel.shuffleQueueNow() }
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
                colors = accentTextFieldColors(accent),
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

    val scope = rememberCoroutineScope()
    /**
     * The drop animation, held so a new drag can cancel it.
     *
     * Without this handle the settle below would still be writing [dragOffset] when
     * the next press zeroes it, and the newly grabbed row would start out already
     * displaced by whatever was left of the previous row's journey home.
     */
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun endDrag() {
        val id = draggingId ?: return
        val from = dragStartIndex
        val to = order.indexOfFirst { it.queueItemId == id }
        dragStartIndex = -1
        if (to >= 0 && from >= 0 && to != from) {
            items.firstOrNull { it.queueItemId == id }?.let { viewModel.moveQueueItem(it, to - from) }
        }
        // The row lands, rather than vanishing from under the finger and reappearing
        // in its slot. `dragOffset` is how far the row sits from the slot it now
        // occupies, and on release that was simply assigned 0 — so a row dropped
        // mid-swap jumped up to half a row's height in one frame, at the exact moment
        // the gesture ended and the eye was still on it.
        //
        // [draggingId] is deliberately still set for the length of this: it is what
        // keeps `translationY` applied to the row and keeps it drawn above its
        // neighbours, so the row settles *over* the list and then becomes part of it.
        settleJob = scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = 0f,
                animationSpec = Motion.spatialOffsetPx(),
            ) { value, _ -> dragOffset = value }
            dragOffset = 0f
            draggingId = null
        }
    }

    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = systemNavInset() + 16.dp),
        // Scrolling and dragging a row are different jobs; let the grip have the
        // gesture to itself rather than fighting the list for it.
        userScrollEnabled = draggingId == null,
    ) {
        itemsIndexed(
            order,
            key = { _, it -> it.queueItemId },
            contentType = { _, _ -> "queueRow" },
        ) { _, item ->
            val dragging = item.queueItemId == draggingId
            QueueRow(
                item = item,
                playing = item.queueItemId == currentId,
                dragging = dragging,
                modifier = Modifier
                    // The neighbours a dragged row displaces used to *teleport*. The
                    // swap below rewrites `order` the moment the row has travelled half
                    // of one row's height, and without a placement animation the row
                    // being passed simply appeared in its new slot on the next frame —
                    // so the one row under the finger moved smoothly and the list it
                    // was moving through jumped around it.
                    //
                    // `placementSpec = null` for the dragged row itself, which is the
                    // part that has to be opted out rather than tuned: its position is
                    // already being driven by `translationY` below, and animating its
                    // placement as well would have the row chasing a slot the finger
                    // has already left. Removals from the queue keep the default fades.
                    .animateItem(
                        placementSpec = if (dragging) null else Motion.itemPlacement(),
                    )
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .onSizeChanged { if (it.height > 0) rowHeight = it.height },
                onPlay = { viewModel.playQueueItem(item) },
                onRemove = { viewModel.removeQueueItem(item) },
                onDragStart = {
                    // Whatever the last row was still doing on its way home, it stops
                    // now — the settle writes `dragOffset`, and this press owns it.
                    settleJob?.cancel()
                    settleJob = null
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
                // Through the shared request builder, and cropped like every other
                // thumbnail in the app — a bare model here defaulted to Fit, which
                // letterboxed a non-square cover inside a 40dp square.
                AsyncImage(
                    model = rememberArtRequest(item.image, pixels = 120),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
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

// --- more like this -------------------------------------------------------

/**
 * What else sounds like the record that is on.
 *
 * Music Assistant answers this for the providers it knows about. Where it has no
 * answer of its own — a Subsonic or Jellyfin library on its own has no such service,
 * and that is exactly the library this app plays locally — the same list is worked
 * out from this phone's own offline scans instead; see [LocalSonicIndex]. The rows
 * look the same either way, deliberately: the question was "what else sounds like
 * this", not "who is answering".
 */
@Composable
private fun ColumnScope.SimilarPanel(viewModel: NowPlayingViewModel) {
    val load by viewModel.similar.collectAsStateWithLifecycle()

    // Idle-guarded like the queue's, and the view model puts this state back to idle
    // on every track change — so closing and reopening the sheet on the same song
    // shows what was already found, and the next song asks again.
    LaunchedEffect(Unit) { if (load is Load.Idle) viewModel.loadSimilar() }

    when (val l = load) {
        is Load.Loading, Load.Idle -> PanelSpinner()
        is Load.Failed -> PanelMessage(Icons.Default.CloudOff, l.message)
        is Load.Ready -> {
            if (l.value.isEmpty()) {
                // Two different nothings, said as one: no server answer, and nothing
                // close enough on this phone. The second has a cure, so it is the one
                // worth naming.
                PanelMessage(
                    Icons.Default.MusicNote,
                    "Nothing close enough to suggest. Tracks are compared by what the " +
                        "offline analysis makes of them, so a library that has never been " +
                        "read through has nothing to compare.",
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = systemNavInset() + 16.dp,
                    ),
                ) {
                    // Keyed by index as well as id: two providers can hand back the
                    // same track id, and a duplicate key is a hard crash in a lazy list.
                    itemsIndexed(
                        l.value,
                        key = { i, t -> "similar:$i:${t.itemId}" },
                        contentType = { _, _ -> "similarTrack" },
                    ) { _, track ->
                        SimilarRow(
                            track = track,
                            onPlay = { viewModel.playSimilar(track) },
                            onQueue = { viewModel.enqueue(track, "add") },
                        )
                    }
                }
            }
        }
    }
}

/** A suggestion: the row is the track, so tapping it plays it, as in the queue. */
@Composable
private fun SimilarRow(track: MaSimilarTrack, onPlay: () -> Unit, onQueue: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onPlay)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Glass), contentAlignment = Alignment.Center) {
            if (track.image != null) {
                AsyncImage(
                    model = rememberArtRequest(track.image, pixels = 120),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                track.name, color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!track.artist.isNullOrBlank()) {
                Text(
                    track.artist, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Tapping the row replaces the queue, which is not always what someone
        // browsing a list of suggestions wants to happen to the record they are
        // listening to. This is the other half of that choice.
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", tint = TextFaint,
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onQueue).padding(5.dp),
        )
    }
}

// --- sleep timer ----------------------------------------------------------

/**
 * The sleep-timer control: the chip and the picker.
 *
 * The chip used to cycle silently through 15/30/45/60 with nothing on screen to
 * show for it, so a running timer and a dead button looked exactly alike — which
 * is why the timer read as doing nothing. It now says what it set (active tint,
 * and the time left in its content description) and can be called off again. The
 * countdown used to also render as a visible Text beside the chip, but that widened
 * the chip row whenever the timer started and shifted every chip after it.
 */
@Composable
fun SleepTimerChip(viewModel: NowPlayingViewModel) {
    val minutes by viewModel.sleepTimerMin.collectAsStateWithLifecycle()
    val remainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    var picking by remember { mutableStateOf(false) }
    val running = minutes > 0

    // The countdown lives in the content description only — a visible Text here used
    // to widen this chip's Row whenever the timer started, shifting every chip after
    // it. The active tint already signals "running"; screen readers still get the time.
    IconChip(
        Icons.Default.Bedtime,
        if (running) "Sleep timer, ${countdown(remainingMs)} left" else "Sleep timer",
        active = running,
    ) { picking = true }

    if (picking) {
        // Not focusable. A focusable popup takes window focus, which puts the activity
        // through its soft-input resize path, and the album art behind — the only
        // weighted child of the player's column — absorbs the whole delta and visibly
        // shrinks. The quality card was moved out of a Popup entirely for this; this
        // one still needs its own window to escape the sheet, so it settles for not
        // taking focus, with a scrim for dismissal and a BackHandler for the gesture
        // focusability would otherwise have provided.
        BackHandler { picking = false }
        Popup(
            popupPositionProvider = WindowCenterPosition,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { picking = false },
                contentAlignment = Alignment.Center,
            ) {
            var sliderMinutes by remember { mutableStateOf(minutes.coerceAtLeast(5)) }
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink2)
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    // Swallow taps so the card doesn't dismiss under its own content.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Sleep timer", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                )
                Text(
                    if (running) "${countdown(remainingMs)} left - the music fades out over the last 10 seconds."
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
