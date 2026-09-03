package com.engabd.sendpin.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.titleMarquee
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.coroutines.cancellation.CancellationException

/**
 * How much of the collapse a back gesture previews before it is committed.
 *
 * Predictive back is a *hint* at where the gesture leads while the finger can still
 * change its mind, not the dismissal itself: previewing the whole travel would leave
 * the commit with nothing left to animate and the cancel a long way to climb back.
 */
private const val BACK_PREVIEW_FRACTION = 0.28f

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingOverlay(
    viewModel: NowPlayingViewModel = viewModel(),
    /** The shared instance — see the same parameter on [NowPlayingScreen]. */
    libraryViewModel: LibraryViewModel,
    /** The cover on show — see the same parameter on [NowPlayingScreen]. */
    art: SettledArt,
    onBrowse: () -> Unit = {},
    expanded: Boolean,
    onCollapse: () -> Unit,
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val favouritable by viewModel.favouritableItem.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val visualizerByDefault by settings.showVisualizer.collectAsStateWithLifecycle<Boolean?>(initialValue = null)

    // Shared with the tab layout so the two cannot drift — see PlayerSheetState.
    val sheets = rememberPlayerSheets()
    // The cover's slot: the sleeve, lyrics, or the live visualizer — see NowPlayingScreen.
    var coverSlot by rememberSaveable { mutableStateOf(CoverSlot.ART) }

    // The setting is read from DataStore, which resolves *after* the first
    // composition, so it cannot seed `rememberSaveable` directly: the initializer
    // would always see the pre-load value and, being an initializer, would never
    // run again — which left the toggle doing nothing at all. Apply it once it
    // lands, and only while the slot is still where it started, so it can never
    // override a choice the listener has already made or a restored slot.
    var defaultApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visualizerByDefault) {
        val want = visualizerByDefault ?: return@LaunchedEffect
        if (defaultApplied) return@LaunchedEffect
        defaultApplied = true
        if (want && coverSlot == CoverSlot.ART) coverSlot = CoverSlot.VISUALIZER
    }

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

    // One holder for the sleeve, the wash behind it and the app's palette — a
    // parameter now, owned by App.kt. See NowPlayingScreen.
    val artDim = idleFade(st.idle, 0.55f)
    val artGlow = idleFade(st.idle, 0.18f, 0.45f)
    val washDim = idleFade(st.idle, 0.5f)

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
    // Starts where the mini bar is, not where the full player ends up. The expand used
    // to be a bare `if (overlayExpanded)` in App.kt around a composable whose offset was
    // already 0 — so the full player simply existed, fully placed, on its first frame.
    // Collapse animated and expand did not, which is the asymmetry that read as a snap.
    var dragPx by remember { mutableFloatStateOf(collapseOffset) }
    /**
     * How far this gesture has travelled upward. Tracked separately from [dragPx]
     * because an upward swipe must not move the cover at all — it only decides whether
     * the queue should come up on release.
     */
    var upTravel by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(collapseOffset) }
    var settling by remember { mutableStateOf(false) }
    val offsetPx = if (settling) settle.value else dragPx

    // Read through a snapshot so the gesture detector doesn't have to be re-created
    // when a sheet opens — tearing it down mid-gesture loses the release event.
    val gestureBlocked by rememberUpdatedState(!expanded || sheets.sheetOpen)

    // Swipe right/left to skip, gated behind its own Appearance setting.
    val swipeToSkip by settings.swipeToSkip.collectAsStateWithLifecycle(initialValue = false)
    val skipThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }

    // Motion.spatialOffsetPx(), not spatial(): this settles a ~2000px drag, and the
    // generic Float spring's default 0.01px visibility threshold made the coroutine
    // keep running (correcting sub-pixel error) well after the slide looked finished
    // — the settled player "stuck" at the bottom for a moment before onCollapse()
    // actually fired and swapped it for the mini bar. See Motion.spatialOffsetPx's
    // own doc for the full explanation.
    suspend fun settleTo(target: Float, spec: FiniteAnimationSpec<Float> = Motion.spatialOffsetPx()) {
        settle.snapTo(dragPx)
        settling = true
        settle.animateTo(target, spec)
        dragPx = target
        settling = false
    }

    /**
     * Back, driven through the same offset the drag uses.
     *
     * This used to be a plain `BackHandler` in `App.kt` that set `overlayExpanded =
     * false`, which drops this composable between two frames: pressing back made the
     * cover vanish instantly while the swipe-down of the very same dismissal animated.
     * A `BackHandler` also *opts the surface out* of the system's predictive preview —
     * the manifest sets `enableOnBackInvokedCallback="true"`, so every screen that does
     * not intercept back already previews, and the most-used screen in the app was the
     * one that didn't.
     *
     * The preview follows the gesture at [BACK_PREVIEW_FRACTION] of the full travel
     * rather than all of it: predictive back is meant to *hint* at the destination
     * while the finger can still change its mind, and a preview that arrives all the
     * way leaves the commit with nothing to do and the cancel with a long way back.
     * Committing settles the remainder on the dismissal spring and hands over exactly
     * as a swipe does; cancelling returns to rest on the arrival spring.
     */
    PredictiveBackHandler(enabled = expanded && !sheets.sheetOpen) { progress ->
        try {
            progress.collect { event ->
                settling = false
                dragPx = collapseOffset * BACK_PREVIEW_FRACTION * event.progress
            }
            settleTo(collapseOffset, Motion.dismissOffsetPx())
            onCollapse()
        } catch (_: CancellationException) {
            settleTo(0f)
        }
    }

    // Rise into place, the mirror of the collapse settle below and on the same spring.
    // `Motion.spatial()` rather than a tween because every other spatial move in the app
    // is a spring; a 250 ms tween here felt mechanical against them.
    LaunchedEffect(expanded) {
        if (expanded) {
            upTravel = 0f
            settleTo(0f)
        }
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
                                    //
                                    // The parent is now an `AnimatedVisibility` rather than
                                    // a bare `if`, which raised the question of whether it
                                    // holds the child through the exit and makes this gate
                                    // redundant. It does not: it is configured
                                    // `ExitTransition.None` — deliberately, because this
                                    // overlay owns its own exit motion — and an exit of no
                                    // duration holds nothing. The gate stays.
                                    dragPx > collapseOffset * 0.25f -> {
                                        // Motion.dismissOffsetPx(), not the default: this
                                        // is the one settle with something waiting on it.
                                        // onCollapse() cannot fire until animateTo returns
                                        // (see the comment above — releasing earlier lets
                                        // the parent drop this composable mid-slide), so
                                        // every millisecond of spring tail is a millisecond
                                        // the cover sits at the bottom looking arrived with
                                        // the mini bar not yet in its place. The default
                                        // spec's tail is ~490ms of exactly that; this one's
                                        // is ~240ms and has no overshoot to converge out of.
                                        // See Motion.dismissOffsetPx for the arithmetic.
                                        settleTo(collapseOffset, Motion.dismissOffsetPx())
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
                                    else -> settleTo(0f)
                                }
                            }
                        },
                        onDragCancel = { dragScope.launch { settleTo(0f) } },
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
                // A second, independent pointerInput for the horizontal skip gesture —
                // Compose arbitrates which axis wins a drag via each detector's own
                // oriented touch-slop, so this coexists with the vertical
                // minimize/queue-reveal gesture above without either fighting the other.
                .pointerInput(swipeToSkip, gestureBlocked) {
                    if (!swipeToSkip || gestureBlocked) return@pointerInput
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragCancel = { dx = 0f },
                        onDragEnd = {
                            if (dx < -skipThresholdPx) viewModel.next()
                            else if (dx > skipThresholdPx) viewModel.previous()
                        },
                        onHorizontalDrag = { change, amount -> change.consume(); dx += amount },
                    )
                }
                .background(Ink)
        ) {
            // Album wash — always present, dimmed when idle. The settled url, so it
            // stays in step with the cover and does not reload inside an album.
            MeltBackdrop(art.url, intensity = washDim.value)

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
                    // Where the playback is coming from, on the speaker pill's own
                    // line — see the note on TopBar.
                    source = st.source,
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space.
                // The container carries the weight so neither branch can resize the
                // Column under the artwork — see NowPlayingScreen.
                AnimatedContent(
                    targetState = coverSlot,
                    transitionSpec = {
                        (fadeIn(Motion.fadeThroughIn()) togetherWith fadeOut(Motion.fadeThroughOut()))
                            .using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = "coverSlot",
                ) { slot ->
                    when (slot) {
                        CoverSlot.LYRICS -> LyricsPane(
                            viewModel = viewModel,
                            positionMs = scrubber.positionMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CoverSlot.VISUALIZER -> AudioVisualizer(
                            modifier = Modifier
                                .fillMaxSize()
                                // Tap anywhere on the visualizer to bring the cover back.
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { coverSlot = CoverSlot.ART },
                        )
                        CoverSlot.ART ->
                            // No shared-element flight between the mini bar and this cover,
                            // deliberately. A shared element is drawn in the transition
                            // layout's own overlay, above and outside the composable it
                            // came from — so while the player slid up or down under the
                            // finger, the artwork was travelling on a separate spring, in
                            // separate coordinates, at a separate speed. It read as the
                            // cover coming *unstuck* from the player carrying it.
                            //
                            // Without the key the art is an ordinary child of the sliding
                            // Box: one surface, one movement, nothing to fall out of step.
                            // The bar's thumbnail simply cross-fades, which is what a
                            // control that is being replaced should do.
                            Box(Modifier.fillMaxSize()) {
                                AlbumArt(
                                    art = art,
                                    glow = palette.swatch(0),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = artDim.value }
                                        // Tap → the live visualizer takes the cover's place.
                                        // Long-press → quick actions (go to album/artist, share,
                                        // edit Light Sync colours).
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { coverSlot = CoverSlot.VISUALIZER },
                                            onLongClick = if (!st.idle) {
                                                { sheets.actions = true }
                                            } else null,
                                        ),
                                    glowAlpha = artGlow.value,
                                    placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                                )
                                CoverTapHint(trackId = st.currentQueueItemId)
                            }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Track/player chips, in one line above the title — the same place the
                // tab layout puts them, so switching layouts does not move the controls.
                // Below the transport they also fell below the fold on shorter phones,
                // which is a poor home for the queue and the lyrics.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val rowMinWidth = maxWidth
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .widthIn(min = rowMinWidth)
                            .padding(horizontal = 10.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Favourite
                        IconChip(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (favorite) "Remove from favourites" else "Add to favourites",
                            active = favorite,
                            // Gated on `favouritableItem`, not `currentItem`: the
                            // latter is null for the whole of a local-library session,
                            // which greyed this out on Navidrome and Jellyfin even though
                            // both implement starring. See NowPlayingViewModel.
                            tint = if (favouritable == null) TextFaint else null,
                            onClick = if (favouritable == null) null else ({ viewModel.toggleFavorite() }),
                        )
                        // Sleep timer
                        SleepTimerChip(viewModel)
                        // Lyrics — swaps the cover for the words, in place.
                        IconChip(Icons.Default.Lyrics, "Lyrics", active = coverSlot == CoverSlot.LYRICS) {
                            coverSlot = if (coverSlot == CoverSlot.LYRICS) CoverSlot.ART else CoverSlot.LYRICS
                        }
                        // Queue
                        IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = sheets.panel == Panel.QUEUE) {
                            sheets.panel = if (sheets.panel == Panel.QUEUE) null else Panel.QUEUE
                        }
                        // Playback speed + player options
                        IconChip(Icons.Default.Tune, "Player options", active = sheets.options) { sheets.options = !sheets.options }
                        // Local-player only: see the note on the same chip in NowPlayingScreen.
                        DownloadChip(libraryViewModel)
                        // Both engines: see the note on the same chip in NowPlayingScreen.
                        // This layout kept the old MA-only gate after the local equaliser
                        // landed, so Navidrome and Jellyfin had no equaliser here at all
                        // while the tab layout had one.
                        IconChip(
                            Icons.Default.GraphicEq,
                            if (st.isLocalSession) "Equaliser" else "DSP / Equalizer",
                            active = sheets.panel == Panel.DSP,
                        ) {
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

                SeekRow(scrubber, st.durationMs, playing = st.isPlaying)

                Spacer(Modifier.height(14.dp))

                TransportRow(st, viewModel) { sheets.quality = true }

                Spacer(Modifier.height(24.dp))

                VolumeRow(st.volume) { viewModel.setVolume(it) }
            }

            PlayerOverlays(
                st, sheets, viewModel,
                favouritable = favouritable,
                onAlbumClick = {},
                onArtistClick = {},
                coverUrl = art.url,
            )
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
    /**
     * Shrink to a strip, for a screen that needs the bottom of the phone back —
     * see [com.engabd.sendpin.ui.design.CompactMiniBarHeight] for who asks and why.
     *
     * What survives is what a minimised player is *for*: what is playing, whether
     * it is playing, and the way back into the full screen. The artist, the speaker
     * line and the skip buttons go, because at 56.dp there is no room to render
     * them at a size worth tapping, and half a skip button is worse than none.
     */
    compact: Boolean = false,
) {
    val st by viewModel.state.collectAsStateWithLifecycle()

    val accent = LocalAccent.current
    // Enough upward travel to mean it, in pixels. The full player can be dragged down
    // to minimise, and the gesture had no inverse: the bar was tap-only, so the one
    // interaction the user had just learned did not work in reverse.
    val expandThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }

    run {
        var upTravel by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2)
                .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
                .clickable(onClick = onExpand)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { upTravel = 0f },
                        onDragEnd = { if (upTravel < -expandThresholdPx) onExpand() },
                        onDragCancel = { upTravel = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        upTravel = (upTravel + dragAmount).coerceAtMost(0f)
                    }
                }
                .fillMaxHeight()
                .padding(
                    horizontal = if (compact) 10.dp else 14.dp,
                    vertical = if (compact) 6.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        ) {
            // Album thumbnail. Sized off the bar rather than pinned, so it grows with
            // it — see [MiniBarHeight] for why the bar is as tall as it is.
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(Glass),
                contentAlignment = Alignment.Center,
            ) {
                if (st.artworkUrl != null) {
                    // Through the shared builder like every other cover in the app:
                    // it carries the 220ms crossfade token and a size hint, where a
                    // raw String model got the loader's 100ms default and decoded at
                    // full resolution for a thumbnail the height of the mini bar.
                    AsyncImage(
                        model = rememberArtRequest(st.artworkUrl, pixels = 120),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
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
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 13.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Only the title. The bar is 14sp of text in a strip across the
                    // bottom of every screen, and two lines scrolling at once there
                    // would be movement the listener never asked to look at.
                    modifier = Modifier.titleMarquee(),
                )
                // Artist on its own line, then which speaker this is playing on below
                // it, parenthesised and quieter — the same thing the full player pins
                // at the top of the cover, so a minimised bar still says *where* this
                // is playing, not just *what*. The bar has the height to spare (see
                // MiniBarHeight), so this doesn't need to fight the artist for one
                // truncating line: each gets to be read in full.
                if (!compact && !st.blank && st.artist.isNotBlank()) {
                    Text(
                        st.artist, color = TextMuted, fontFamily = AppFont,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val speakerLabel = if (st.groupSize > 1) "${st.playerName} (${st.groupSize})" else st.playerName
                if (!compact && speakerLabel.isNotBlank()) {
                    Text(
                        "($speakerLabel)", color = TextFaint, fontFamily = AppFont,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                if (!compact) {
                    Icon(
                        Icons.Default.SkipPrevious, "Previous",
                        tint = TextSecondary,
                        modifier = Modifier.size(30.dp).clip(CircleShape)
                            .clickable { viewModel.previous() }.padding(4.dp),
                    )
                }
                Box(
                    Modifier.size(if (compact) 36.dp else 44.dp).clip(CircleShape)
                        .background(Glass)
                        .clickable { viewModel.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (st.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (st.isPlaying) "Pause" else "Play",
                        tint = accent, modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                    )
                }
                if (!compact) {
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
}

// --- Helpers (shared between overlay and tab versions) -------------------

