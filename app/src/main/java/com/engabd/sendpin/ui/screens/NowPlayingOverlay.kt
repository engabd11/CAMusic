package com.engabd.sendpin.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The overlay now-playing experience: a full-screen cover that slides up over the
 * app content and minimizes into a compact bar when swiped down (or via the chevron).
 *
 * Speaker group control stays at the top of the cover. Track actions (favourite,
 * sleep timer, lyrics, similar, queue, playback speed, player options) sit at the
 * bottom as icon chips — one row, no panels stacked over the art.
 *
 * Light Sync stays in its own tab — it's not part of the cover.
 */
@Composable
fun NowPlayingOverlay(
    viewModel: NowPlayingViewModel = viewModel(),
    onOpenSpeakers: () -> Unit = {},
    onBrowse: () -> Unit = {},
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    val st by viewModel.state.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val favorite by viewModel.favorite.collectAsState()
    val currentItem by viewModel.currentItem.collectAsState()
    val sleepTimerMin by viewModel.sleepTimerMin.collectAsState()
    val context = LocalContext.current

    var panel by remember { mutableStateOf<Panel?>(null) }
    var options by remember { mutableStateOf(false) }
    // Lyrics are a *mode* of the player, not an overlay: they take the cover's place.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Provided by App.kt from this same artwork, so every other screen matches.
    val palette = LocalPalette.current
    val accent = palette.accent

    // Smoothly interpolate the position between 2s polls. `scrubbing` freezes the
    // interpolation so a poll landing mid-drag can't fight the finger.
    var pos by remember { mutableStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(st.positionMs, st.isPlaying, st.title, scrubbing) {
        if (scrubbing) return@LaunchedEffect
        pos = st.positionMs
        while (st.isPlaying) { kotlinx.coroutines.delay(250); pos += 250 }
    }
    val dur = st.durationMs
    val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    // Drag-to-minimize: the overlay tracks the finger's vertical offset and snaps
    // to expanded or collapsed when released. The offset is kept in *pixels* so the
    // drag delta (already px) needs no conversion.
    val dragScope = rememberCoroutineScope()
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val collapseOffset = screenHeightPx * 0.82f   // how far down before it's "minimized"
    val dragOffset = remember { Animatable(0f) }

    LaunchedEffect(expanded) {
        if (expanded) dragOffset.animateTo(0f, tween(280))
    }

    CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                .pointerInput(expanded) {
                    if (expanded) detectVerticalDragGestures(
                        onDragEnd = {
                            // Animate first, collapse after — otherwise the parent drops
                            // this composable mid-slide and the gesture ends with a jump.
                            dragScope.launch {
                                if (dragOffset.value > collapseOffset * 0.4f) {
                                    dragOffset.animateTo(collapseOffset, tween(200))
                                    onCollapse()
                                } else {
                                    dragOffset.animateTo(0f, tween(200))
                                }
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            dragScope.launch {
                                dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(0f))
                            }
                        },
                    )
                }
                .background(Ink)
        ) {
            // Album wash — always present, dimmed when idle.
            MeltBackdrop(st.artworkUrl, intensity = if (st.idle) 0.5f else 1f)

            Column(
                Modifier
                    .fillMaxSize()
                    // systemBars, not statusBars: the overlay covers the app's own
                    // nav bar, so nothing else is reserving room for the gesture
                    // bar and the volume row lands underneath it.
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 14.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle + speaker group at the very top.
                Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(100)).background(Hairline)
                    )
                }

                if (!connected) OfflineBanner()

                TopBar(
                    playerName = st.playerName,
                    isSelf = st.isSelf,
                    groupSize = st.groupSize,
                    onOpenSpeakers = onOpenSpeakers,
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space.
                if (showLyrics) {
                    LyricsPane(
                        viewModel = viewModel,
                        positionMs = pos,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    AlbumArt(
                        url = st.artworkUrl,
                        glow = palette.swatch(0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .alpha(if (st.idle) 0.55f else 1f),
                        glowAlpha = if (st.idle) 0.18f else 0.45f,
                        placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Title + artist + album.
                if (st.idle) {
                    IdleNotice(st.playerName, st.blank, onBrowse)
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    if (st.blank) "Nothing playing" else st.title,
                    color = if (st.idle) TextSecondary else TextPrimary,
                    fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 27.sp, letterSpacing = (-0.5).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                if (st.artist.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        st.artist, color = Color.White.a(0.62f), fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                if (st.album.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        st.album, color = TextFaint, fontFamily = AppFont, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Progress bar.
                HSlider(
                    progress,
                    onChange = { f -> scrubbing = true; pos = (f * dur).toLong() },
                    onCommit = { f -> pos = (f * dur).toLong(); scrubbing = false; viewModel.seekTo(f) },
                    label = { f -> fmtTime((f * dur).toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeText(fmtTime(pos))
                    TimeText(if (dur > 0) fmtTime(dur) else "--:--")
                }

                Spacer(Modifier.height(14.dp))

                // Transport row.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, st.shuffle) { viewModel.toggleShuffle() }
                    TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }
                    PlayButton(st.isPlaying) { viewModel.playPause() }
                    TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
                    TransportIcon(
                        if (st.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat", 20.dp, st.repeatMode != "off",
                    ) { viewModel.cycleRepeat() }
                }

                Spacer(Modifier.height(16.dp))

                // Bottom action row — all the track/player chips in one line.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Favourite
                    IconChip(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (favorite) "Remove from favourites" else "Add to favourites",
                        active = favorite,
                        tint = if (currentItem == null) TextFaint else null,
                        onClick = if (currentItem == null) null else ({ viewModel.toggleFavorite() }),
                    )
                    // Sleep timer
                    IconChip(
                        Icons.Default.Bedtime,
                        if (sleepTimerMin > 0) "Sleep timer: ${sleepTimerMin}m" else "Sleep timer",
                        active = sleepTimerMin > 0,
                        onClick = {
                            // Cycle through common presets: 0 → 15 → 30 → 45 → 60 → 0
                            val next = when (sleepTimerMin) {
                                0 -> 15; 15 -> 30; 30 -> 45; 45 -> 60; else -> 0
                            }
                            viewModel.setSleepTimer(next)
                        },
                    )
                    // Lyrics — swaps the cover for the words, in place.
                    IconChip(Icons.Default.Lyrics, "Lyrics", active = showLyrics) {
                        showLyrics = !showLyrics
                    }
                    // Queue
                    IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = panel == Panel.QUEUE) {
                        panel = if (panel == Panel.QUEUE) null else Panel.QUEUE
                    }
                    // Quality badge
                    QualityChip(st.quality)
                    // Playback speed + player options
                    IconChip(Icons.Default.Tune, "Player options", active = options) { options = !options }
                }

                Spacer(Modifier.height(8.dp))

                // Volume slider (compact).
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = TextMuted, modifier = Modifier.size(16.dp))
                    HSlider(
                        st.volume.coerceIn(0f, 1f), { viewModel.setVolume(it) },
                        modifier = Modifier.weight(1f), knob = 12.dp, accented = false,
                    )
                    Text(
                        "${(st.volume * 100).toInt()}", color = TextMuted, fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.width(22.dp), textAlign = TextAlign.End,
                    )
                }
            }

            // Panels + options sheets — same as the tab version.
            if (panel != null || options) {
                BackHandler { panel = null; options = false }
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { panel = null; options = false }
                )
            }
            if (panel != null) {
                NowPlayingSheet(onClose = { panel = null }, viewModel = viewModel)
            }
            if (options) {
                PlayerOptionsSheet(onClose = { options = false }, viewModel = viewModel)
            }
        }
    }
}

// --- Mini player bar (shown when overlay is collapsed) -------------------

/**
 * A compact bar that sits above the nav bar showing what's playing, with
 * play/pause and a tap-to-expand action. The album art is a small thumbnail,
 * the title/artist is one line, and the play button is inline.
 *
 * It stays put when nothing is playing, showing an idle label instead. In overlay
 * mode this bar is the *only* way into the full player — there is no Playing tab —
 * so hiding it would strand the speaker picker and the idle browse prompt behind a
 * screen the user can no longer open.
 */
@Composable
fun MiniPlayerBar(
    viewModel: NowPlayingViewModel = viewModel(),
    onExpand: () -> Unit,
) {
    val st by viewModel.state.collectAsState()

    val accent = LocalAccent.current

    run {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2)
                .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Album thumbnail.
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(Glass),
                contentAlignment = Alignment.Center,
            ) {
                if (st.artworkUrl != null) {
                    coil.compose.AsyncImage(
                        model = st.artworkUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }

            // Title + artist.
            Column(Modifier.weight(1f)) {
                Text(
                    if (st.blank) "Nothing playing" else st.title,
                    color = if (st.blank) TextSecondary else TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Falls back to the player name so an idle bar still says *where*.
                val sub = if (st.blank) st.playerName else st.artist
                if (sub.isNotBlank()) {
                    Text(
                        sub, color = TextMuted, fontFamily = AppFont,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Play/pause — nothing to toggle when the queue is empty, so the bar
            // becomes a plain tap-to-open target instead of offering a dead button.
            if (!st.blank) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(Glass)
                        .clickable { viewModel.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (st.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (st.isPlaying) "Pause" else "Play",
                        tint = accent, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// --- Helpers (shared between overlay and tab versions) -------------------

@Composable
private fun TopBar(playerName: String, isSelf: Boolean, groupSize: Int, onOpenSpeakers: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp))
                    .background(GlassStrong)
                    .clickable(onClick = onOpenSpeakers)
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isSelf) Icons.Default.Smartphone else Icons.Default.Speaker, null,
                    tint = Color.White.a(0.85f), modifier = Modifier.size(14.dp),
                )
                Text(
                    if (groupSize > 1) "$playerName ($groupSize)" else playerName,
                    color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(topEnd = 100.dp, bottomEnd = 100.dp))
                    .background(accent)
                    .clickable(onClick = onOpenSpeakers)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) { Icon(Icons.Default.Link, "Speakers", tint = Ink, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun QualityChip(q: StreamQuality?) {
    if (q == null) QualityPill("—", lossless = false)
    else QualityPill(q.label, hiRes = q.hiRes, lossless = q.lossless)
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    size: androidx.compose.ui.unit.Dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) Bloom(accent, size * 1.8f, 0.dp, 0.dp, 0.5f)
        Icon(icon, cd, tint = if (active) accent else Color.White.a(0.9f), modifier = Modifier.size(size))
    }
}

@Composable
private fun TimeText(text: String) {
    Text(text, color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(WarnAmber.a(0.14f)).border(1.dp, WarnAmber.a(0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = WarnAmber, modifier = Modifier.size(15.dp))
        Text(
            "Reconnecting…", color = Color(0xFFF2C574), fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 12.sp,
        )
    }
}

@Composable
private fun IdleNotice(playerName: String, blank: Boolean, onBrowse: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onBrowse)
            .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.PauseCircleOutline, null, tint = TextMuted, modifier = Modifier.size(15.dp))
        Text(
            if (blank) "Nothing playing on $playerName — browse" else "Nothing playing — browse",
            color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
