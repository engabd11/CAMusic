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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.engabd.sendpin.ma.LibraryViewModel
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
    /** The shared instance — see the same parameter on [NowPlayingScreen]. */
    libraryViewModel: LibraryViewModel,
    onBrowse: () -> Unit = {},
    expanded: Boolean,
    onCollapse: () -> Unit,
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Shared with the tab layout so the two cannot drift — see PlayerSheetState.
    val sheets = rememberPlayerSheets()
    // Lyrics are a *mode* of the player, not an overlay: they take the cover's place.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Player-initiated library actions only — see LibraryViewModel.playerToast.
    LaunchedEffect(Unit) {
        libraryViewModel.playerToast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Provided by App.kt from this same artwork, so every other screen matches.
    val palette = LocalPalette.current
    val accent = palette.accent

    val scrubber = rememberScrubber(viewModel)

    // Drag-to-minimize: the overlay tracks the finger's vertical offset and snaps
    // to expanded or collapsed when released. The offset is kept in *pixels* so the
    // drag delta (already px) needs no conversion.
    //
    // The live drag offset is plain state, mutated synchronously in the gesture
    // callback. It used to be an `Animatable` written with `snapTo` from a coroutine
    // launched per pointer sample — and `Animatable` serialises through a
    // `MutatorMutex`, so each sample cancelled the one before it and read a value
    // captured at coroutine-start rather than at event time. Deltas were dropped and
    // double-counted, which is the jitter under a fast flick. The `Animatable` is now
    // only used for the release settle, where there really is one animation at a time.
    val dragScope = rememberCoroutineScope()
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val collapseOffset = screenHeightPx * 0.82f   // how far down before it's "minimized"
    // How far up the cover has to travel before the queue is what you meant.
    val queueRevealPx = screenHeightPx * 0.06f
    var dragPx by remember { mutableFloatStateOf(0f) }
    /**
     * How far this gesture has travelled upward. Tracked separately from [dragPx]
     * because an upward swipe must not move the cover at all — it only decides whether
     * the queue should come up on release.
     */
    var upTravel by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    val offsetPx = if (settling) settle.value else dragPx

    // Read through a snapshot so the gesture detector doesn't have to be re-created
    // when a sheet opens — tearing it down mid-gesture loses the release event.
    val gestureBlocked by rememberUpdatedState(!expanded || sheets.sheetOpen)

    LaunchedEffect(expanded) {
        if (expanded) { dragPx = 0f; settling = false }
    }

    suspend fun settleTo(target: Float, durationMs: Int) {
        settle.snapTo(dragPx)
        settling = true
        settle.animateTo(target, tween(durationMs))
        dragPx = target
        settling = false
    }

    CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                // The cover answers vertical swipes both ways: down minimizes it,
                // up brings the queue over it. While a sheet is open the sheet owns
                // the gesture instead — dragging the player out from under an open
                // queue is never what the swipe meant.
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { if (!gestureBlocked) { dragPx = 0f; upTravel = 0f } },
                        onDragEnd = {
                            if (gestureBlocked) return@detectVerticalDragGestures
                            dragScope.launch {
                                when {
                                    // Animate first, collapse after — otherwise the parent
                                    // drops this composable mid-slide and the gesture ends
                                    // with a jump. Lower threshold (0.25) makes it easier
                                    // to minimize.
                                    dragPx > collapseOffset * 0.25f -> {
                                        settleTo(collapseOffset, 250)
                                        onCollapse()
                                    }
                                    upTravel < -queueRevealPx -> {
                                        // Show the queue *first*, then drop the cover back.
                                        // Awaiting a 200ms slide down before setting the
                                        // panel is what read as "snaps up, bounces back,
                                        // then the queue appears". The sheet is what should
                                        // animate in; the cover just returns to rest.
                                        sheets.panel = Panel.QUEUE
                                        dragPx = 0f
                                        upTravel = 0f
                                        settling = false
                                    }
                                    else -> settleTo(0f, 250)
                                }
                            }
                        },
                        onDragCancel = { dragScope.launch { settleTo(0f, 200) } },
                        onVerticalDrag = { change, dragAmount ->
                            if (gestureBlocked) return@detectVerticalDragGestures
                            change.consume()
                            settling = false
                            // Down is a real slide towards the mini bar. Up moves
                            // nothing: the cover staying put while the queue rises over
                            // it is the whole effect, and lifting it as a "hint" just
                            // read as the screen jumping. The upward travel is still
                            // accumulated below so the release threshold can use it.
                            upTravel = (upTravel + dragAmount).coerceAtMost(0f)
                            dragPx = (dragPx + dragAmount).coerceAtLeast(0f)
                        },
                    )
                }
                .background(Ink)
        ) {
            // Album wash — always present, dimmed when idle.
            MeltBackdrop(st.artworkUrl, intensity = if (st.idle) 0.5f else 1f)

            // Source badge at the top-right corner — MA, Navidrome, Offline, or the
            // streaming provider the track came from. Never blank: see State.source.
            SourceBadge(
                source = st.source,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp),
            )

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

                if (!connected && !st.isLocalSession) OfflineBanner()

                TopBar(
                    playerName = st.playerName,
                    isSelf = st.isSelf,
                    groupSize = st.groupSize,
                    localSession = st.isLocalSession,
                    onTap = { if (st.isLocalSession) sheets.device = true else sheets.speakers = true },
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space.
                if (showLyrics) {
                    LyricsPane(
                        viewModel = viewModel,
                        positionMs = scrubber.positionMs,
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

                // Track/player chips, in one line above the title — the same place the
                // tab layout puts them, so switching layouts does not move the controls.
                // Below the transport they also fell below the fold on shorter phones,
                // which is a poor home for the queue and the lyrics.
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
                    SleepTimerChip(viewModel)
                    // Lyrics — swaps the cover for the words, in place.
                    IconChip(Icons.Default.Lyrics, "Lyrics", active = showLyrics) {
                        showLyrics = !showLyrics
                    }
                    // Queue
                    IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = sheets.panel == Panel.QUEUE) {
                        sheets.panel = if (sheets.panel == Panel.QUEUE) null else Panel.QUEUE
                    }
                    // Playback speed + player options
                    IconChip(Icons.Default.Tune, "Player options", active = sheets.options) { sheets.options = !sheets.options }
                    // Local-player only: see the note on the same chip in NowPlayingScreen.
                    DownloadChip(libraryViewModel)
                    // MA-only: see the note on the same chip in NowPlayingScreen.
                    if (!st.isLocalSession) {
                        IconChip(Icons.Default.GraphicEq, "DSP / Equalizer", active = sheets.panel == Panel.DSP) {
                            sheets.panel = if (sheets.panel == Panel.DSP) null else Panel.DSP
                        }
                    }
                }

                // Matches the gap above, so the chips sit centred in the space between
                // the cover and the title rather than crowding the title.
                Spacer(Modifier.height(16.dp))

                // Title + artist + album.
                if (st.idle) {
                    IdleNotice(st.playerName, st.blank, onBrowse)
                    Spacer(Modifier.height(12.dp))
                }

                // No composer line here: a fourth line of metadata pushes the transport
                // under the fold on a short phone, which the tab layout has room for.
                TrackTitleBlock(st, showComposer = false)

                Spacer(Modifier.height(16.dp))

                SeekRow(scrubber, st.durationMs)

                Spacer(Modifier.height(14.dp))

                TransportRow(st, viewModel) { sheets.quality = true }

                Spacer(Modifier.height(24.dp))

                VolumeRow(st.volume) { viewModel.setVolume(it) }
            }

            PlayerOverlays(st, sheets, viewModel)
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
    val st by viewModel.state.collectAsStateWithLifecycle()

    val accent = LocalAccent.current

    run {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2)
                .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
                .clickable(onClick = onExpand)
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Album thumbnail. Sized off the bar rather than pinned, so it grows with
            // it — see [MiniBarHeight] for why the bar is as tall as it is.
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    if (st.blank) "Nothing playing" else st.title,
                    color = if (st.blank) TextSecondary else TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Falls back to the player name so an idle bar still says *where*.
                val sub = if (st.blank) st.playerName else st.artist
                if (sub.isNotBlank()) {
                    Text(
                        sub, color = TextMuted, fontFamily = AppFont,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Transport — nothing to drive when the queue is empty, so the bar becomes
            // a plain tap-to-open target instead of offering dead buttons.
            //
            // Skip is here because the bar is now tall enough to hold it, and skipping
            // is the thing most often wanted from a minimised player: it used to cost
            // opening the full screen, skipping, and swiping back down.
            if (!st.blank) {
                Icon(
                    Icons.Default.SkipPrevious, "Previous",
                    tint = TextSecondary,
                    modifier = Modifier.size(30.dp).clip(CircleShape)
                        .clickable { viewModel.previous() }.padding(4.dp),
                )
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(Glass)
                        .clickable { viewModel.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (st.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (st.isPlaying) "Pause" else "Play",
                        tint = accent, modifier = Modifier.size(24.dp),
                    )
                }
                Icon(
                    Icons.Default.SkipNext, "Next",
                    tint = TextSecondary,
                    modifier = Modifier.size(30.dp).clip(CircleShape)
                        .clickable { viewModel.next() }.padding(4.dp),
                )
            }
        }
    }
}

// --- Helpers (shared between overlay and tab versions) -------------------

