package com.engabd.sendpin.ui.screens

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.engabd.sendpin.audio.DeviceCapabilities
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.Crossfeed
import com.engabd.sendpin.audio.LocalDsp
import com.engabd.sendpin.audio.QualityTier
import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaDspDetails
import com.engabd.sendpin.ma.MaOutputProcessing
import com.engabd.sendpin.ma.MaQueueProcessing
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLoudness
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

/**
 * The album, and everything else out of its way. Art fills the panel edge to
 * edge as a blurred wash that resolves to true #000 before the bottom, so on an
 * OLED screen the cover has no frame — it just stops. Every accent on the
 * screen (controls, glows, badge, scrubber) is sampled from that same cover.
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel = viewModel(),
    /**
     * The *shared* library view model, passed in rather than resolved with
     * `viewModel()` here: inside a `NavHost` destination that would build a second
     * instance scoped to this back-stack entry, with its own Navidrome connection and
     * its own idea of what is downloaded. The download chip has to read the same index
     * the Library tab writes.
     */
    libraryViewModel: LibraryViewModel,
    /**
     * The cover on show and how far through a page turn it is, owned by `App`.
     *
     * Built here once, which was fine while the sleeve and the wash behind it were
     * the only things painted from it. The album *palette* is painted from it too,
     * and that is computed above this screen — so the holder has to be created above
     * this screen as well, or the colour changes on the raw url while this screen is
     * still turning the old sleeve over. See `rememberAlbumPalette`.
     */
    art: SettledArt,
    onBrowse: () -> Unit = {},
    /** Long-press the album art → "Go to album"/"Go to artist". No-op if unset. */
    onAlbumClick: (MaItem) -> Unit = {},
    onArtistClick: (MaItem) -> Unit = {},
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val favouritable by viewModel.favouritableItem.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val visualizerByDefault by settings.showVisualizer.collectAsStateWithLifecycle<Boolean?>(initialValue = null)

    // Which sheet or card is up, if any. Shared with the overlay layout so the two
    // cannot drift — see PlayerSheetState.
    val sheets = rememberPlayerSheets()
    val airPlayVm: com.engabd.sendpin.ui.viewmodel.AirPlayViewModel = viewModel()
    // The cover's slot: the sleeve, lyrics, or the live visualizer — a mode of the
    // player, not an overlay, so whichever is active takes the cover's place.
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
    // Downloading from the player is a library action, so its replies ("Wi-Fi only",
    // "Downloaded X") come from the library — but on its own channel. The library's
    // general `toast` already has a collector in LibraryScreen, which in the overlay
    // layout is composed underneath this: collecting it here too would announce every
    // library action twice, once as a snackbar and once as a Toast.
    LaunchedEffect(Unit) {
        libraryViewModel.playerToast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // App.kt already derives the palette from this same artwork and provides it to
    // the whole tree, so the rest of the app is tinted to match. Re-extracting here
    // would just do the same clustering work twice.
    val palette = LocalPalette.current
    val accent = palette.accent

    val scrubber = rememberScrubber(viewModel)

    // `art` is a parameter now, hoisted all the way to `App`. It is painted three
    // times, not twice: as the sleeve, as the wash behind it, and — a level above
    // this screen — as the palette the whole app is tinted with. One holder keeps
    // all three on the same clock through a page turn, and stops any of them
    // reloading when two tracks of one record arrive with different urls for the
    // same picture.
    val artDim = idleFade(st.idle, 0.55f)
    val artGlow = idleFade(st.idle, 0.18f, 0.45f)
    val washDim = idleFade(st.idle, 0.5f)

    // How far up a swipe has to travel before it means "show me the queue".
    val queueRevealPx = with(LocalDensity.current) { 56.dp.toPx() }
    // How far sideways a swipe has to travel before it means "skip". Same settings
    // instance SeekRow already builds locally for the seek-bar style read.
    val swipeToSkip by settings.swipeToSkip.collectAsStateWithLifecycle(initialValue = false)
    val skipThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }

    CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink)
                // Swipe up anywhere on the player to pull the queue over it. The
                // sheets handle their own dismissal, so this stands down while one
                // is open rather than reopening what the user just swiped away.
                .pointerInput(sheets.sheetOpen) {
                    if (sheets.sheetOpen) return@pointerInput
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragCancel = { travelled = 0f },
                        onDragEnd = { if (travelled < -queueRevealPx) sheets.panel = Panel.QUEUE },
                        onVerticalDrag = { _, amount -> travelled += amount },
                    )
                }
                // Swipe right/left to skip, gated behind its own Appearance setting.
                // A second, independent pointerInput rather than folding into the
                // vertical one above — Compose arbitrates which axis wins a drag via
                // each detector's own oriented touch-slop, so this coexists with the
                // queue-reveal gesture without either fighting the other.
                .pointerInput(swipeToSkip, sheets.sheetOpen) {
                    if (!swipeToSkip || sheets.sheetOpen) return@pointerInput
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
        ) {
            // The screen keeps its shape whether or not anything is playing: an
            // idle player shows the last track it had (dimmed, with a notice)
            // rather than swapping the whole screen for an empty state.
            // Fed the *settled* url rather than the raw one, so it neither reloads
            // within an album nor changes a quarter of a second before the cover does.
            // The wash and the bloom over it, in one composable that takes the fade
            // as State rather than as a value — see [AlbumWash]. Read here, the
            // fade recomposed this whole player on every frame of every transition
            // between playing and idle.
            val chameleonBloom by settings.chameleonBloom.collectAsStateWithLifecycle(initialValue = false)
            AlbumWash(art.url, palette, chameleonBloom, washDim)

            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp)
                    .padding(top = 8.dp, bottom = navBarInset() + 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!connected && !st.isLocalSession) OfflineBanner()

                TopBar(
                    playerName = st.playerName,
                    isSelf = st.isSelf,
                    groupSize = st.groupSize,
                    localSession = st.isLocalSession,
                    onTap = { if (st.isLocalSession) sheets.device = true else sheets.speakers = true },
                    // MA, Navidrome, Offline, or the streaming provider the track came
                    // from — on the same line as the speaker pill, not floating over it.
                    source = st.source,
                    leading = { AirPlayButton(airPlayVm) { sheets.airPlay = true } },
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space, shrinking on small screens
                // and growing on large ones — no scroll needed.
                // The weight lives on the container, not on either branch, so the
                // two can never disagree about how tall this slot is. The cover is the
                // only flexible child of this Column and anything that grows beside it
                // takes the difference out of the artwork.
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
                                // Tap anywhere on the visualizer to bring the cover back. Named for
                                // TalkBack, which otherwise met a nameless full-screen button.
                                .semantics { contentDescription = "Visualiser" }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Show the album art",
                                ) { coverSlot = CoverSlot.ART },
                        )
                        CoverSlot.ART -> Box(Modifier.fillMaxSize()) {
                            AlbumArt(
                                art = art,
                                glow = palette.swatch(0),
                                modifier = Modifier
                                    .fillMaxSize()
                                    // graphicsLayer rather than Modifier.alpha: the State is
                                    // read in the layer block, so easing the dim costs no
                                    // recomposition for the length of the transition.
                                    .graphicsLayer { alpha = artDim.value }
                                    // Tap → the live visualizer takes the cover's place.
                                    // Long-press → quick actions (go to album/artist, share).
                                    // Named, with both gestures spelled out: to TalkBack this was a
                                    // nameless square that did something on a double tap.
                                    .semantics { contentDescription = "Album art" }
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClickLabel = "Show the visualiser",
                                        onLongClickLabel = "Track actions",
                                        onClick = { coverSlot = CoverSlot.VISUALIZER },
                                        onLongClick = if (!st.idle) {
                                            { sheets.actions = true }
                                        } else null,
                                    ),
                                glowAlpha = artGlow.value,
                                placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                            )
                            // The cover has been tappable since the visualizer landed and
                            // has never said so - there is no affordance on it at all, and
                            // a full-bleed square of artwork does not read as a button.
                            // This is the smallest thing that fixes that: a glyph in the
                            // corner for a couple of seconds after the track changes, then
                            // gone. Long enough to be noticed once, short enough that it is
                            // never sitting on the artwork while you look at it.
                            CoverTapHint(trackId = st.currentQueueItemId)
                        }
                    }
                }

                // Secondary actions — track-scoped chips only; the quality badge
                // has moved to the transport row (between play and the seek bar).
                //
                // Scrollable, because this row can hold eight chips: Lyrics, Sleep,
                // Queue, Options, Download, DSP, Radio and Favourite. As a plain Row
                // that was a fixed 19.dp apart, the last of them simply fell off a
                // narrow phone with nothing to say they were there. `widthIn(min =
                // maxWidth)` is what keeps the common case centred exactly as before:
                // the Row is at least as wide as the screen when the chips fit, and
                // grows past it - and scrolls - when they do not.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val rowMinWidth = maxWidth
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .widthIn(min = rowMinWidth)
                            // 7.dp above and below make every chip a 48.dp target, to TalkBack as
                            // well as to a finger: Compose widens a small button's touch area by
                            // itself, but reports it only as far as its parent reaches, which
                            // was this 34.dp row - so each chip was announced as 40.dp tall.
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(Icons.Default.Lyrics, "Lyrics", active = coverSlot == CoverSlot.LYRICS) {
                            coverSlot = if (coverSlot == CoverSlot.LYRICS) CoverSlot.ART else CoverSlot.LYRICS
                        }
                        SleepTimerChip(viewModel)
                        IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = sheets.panel == Panel.QUEUE) {
                            sheets.panel = if (sheets.panel == Panel.QUEUE) null else Panel.QUEUE
                        }
                        IconChip(Icons.Default.Tune, "Player options", active = sheets.options) { sheets.options = !sheets.options }
                        // Only on the local player: an MA speaker is fed by the server and
                        // there is no file here to keep. Hidden rather than disabled when
                        // there is nothing behind the track Navidrome could serve.
                        DownloadChip(libraryViewModel)
                        // Always here now. It used to be hidden on a local session,
                        // because the only equaliser that existed was Music Assistant's
                        // server-side pipeline and opening it while Navidrome played
                        // showed - and would have saved - settings for a completely
                        // different player. There is a local one now, and the sheet
                        // routes to whichever engine owns the session.
                        IconChip(
                            Icons.Default.GraphicEq,
                            if (st.isLocalSession) "Equaliser" else "DSP / Equalizer",
                            active = sheets.panel == Panel.DSP,
                        ) {
                            sheets.panel = if (sheets.panel == Panel.DSP) null else Panel.DSP
                        }
                        // Radio mode is a parameter of play_media, so it colours what you
                        // play *next* rather than the queue already running — the toast
                        // on toggle says so. Hidden on the local player, which has no
                        // radio generation behind it.
                        if (!st.isLocalSession) {
                            IconChip(
                                Icons.Default.Radio,
                                if (st.radioMode) "Radio mode on" else "Radio mode off",
                                active = st.radioMode,
                            ) { viewModel.toggleRadioMode() }
                        }
                        // Disabled until MA tells us which library item is playing —
                        // without an item_id there is nothing to favourite.
                        IconChip(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (favorite) "Remove from favourites" else "Add to favourites",
                            active = favorite,
                            // Gated on `favouritableItem`, not `currentItem`: the
                            // latter is null for the whole of a local-library session,
                            // which greyed this out on Navidrome and Jellyfin even though
                            // both implement starring. See NowPlayingViewModel.
                            enabled = favouritable != null,
                            onClick = { viewModel.toggleFavorite() },
                        )
                    }
                }

                if (st.idle) {
                    // Room for the chips' 48.dp targets above and this pill's own: with
                    // the two flush, the pill's claimed the chips' lower 8.dp.
                    Spacer(Modifier.height(7.dp))
                    IdleNotice(st.playerName, st.blank, onBrowse)
                    Spacer(Modifier.height(12.dp))
                }

                TrackTitleBlock(st)

                Spacer(Modifier.height(14.dp))

                SeekRow(scrubber, st.durationMs, playing = st.isPlaying)

                Spacer(Modifier.height(10.dp))

                TransportRow(st, viewModel) { sheets.quality = true }

                Spacer(Modifier.height(18.dp))

                VolumeRow(st.volume) { viewModel.setVolume(it); airPlayVm.setVolume(it) }
            }

            // Every sheet and card that sits over the player, in one call so the two
            // layouts cannot disagree about their order — see PlayerOverlays.
            PlayerOverlays(
                st, sheets, viewModel,
                favouritable = favouritable,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                coverUrl = art.url,
            )
        }
    }
}

/**
 * The split player control: a glass half naming what's playing where, welded to
 * an accent half that opens the speaker picker. The design's minimize/overflow
 * icons are dropped — this app is tab-based (nothing to minimize to) and their
 * menu items already live in the chip row under the cover.
 *
 * Its trailing icon is the promise about what the tap does, so it has to follow
 * [localSession]: with Music Assistant behind the player the pill picks *which
 * speaker to drive*, and the chain link says so. On the Navidrome and offline paths
 * there is exactly one player and nothing to pick, so the same tap opens the device
 * card instead and the icon becomes an ⓘ. Offering a link there was a promise the
 * screen could not keep.
 *
 * [source] — where the playback is coming from — rides in this row rather than being
 * pinned to the screen's top-right corner. Absolutely positioned it had to guess its
 * own top inset, and the guess was a fixed 48dp against a pill whose position depends
 * on the status bar, the optional offline banner and this row's own padding: the two
 * were never on the same line. Here the layout puts them on one, on any phone and in
 * either now-playing layout. The weighted sides are what keep the pill centred on the
 * screen rather than centred in what is left over beside the badge.
 */
@Composable
internal fun TopBar(
    playerName: String,
    isSelf: Boolean,
    groupSize: Int,
    localSession: Boolean,
    onTap: () -> Unit,
    /** The source badge's text, or null to leave the corner empty. */
    source: String? = null,
    /** The top-left corner: the AirPlay control, when this build has the sender. */
    leading: @Composable () -> Unit = {},
) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { leading() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp))
                    .background(GlassStrong)
                    .clickable(onClick = onTap)
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isSelf) Icons.Default.Smartphone else Icons.Default.Speaker, null,
                    tint = inkOn(0.85f), modifier = Modifier.size(14.dp),
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
                    .clickable(onClick = onTap)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Icon(
                    if (localSession) Icons.Default.Info else Icons.Default.Link,
                    if (localSession) "Output details" else "Speakers",
                    tint = Ink, modifier = Modifier.size(14.dp),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            // Never blank when there is a session at all — see State.source.
            if (!source.isNullOrBlank()) SourceBadge(source)
        }
    }
}

/**
 * A brief "you can tap this" glyph in the corner of the album art.
 *
 * Shown for [HINT_MS] after each track change and then faded out. Keyed on the
 * track rather than shown once per session: the gesture is worth re-offering when
 * the record changes, and never worth leaving on screen while someone is looking
 * at a sleeve.
 *
 * Not a clickable target of its own - the whole cover already is one, and a second
 * hit area inside it would only be somewhere the long-press did not work.
 */
@Composable
internal fun BoxScope.CoverTapHint(trackId: String?) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(trackId) {
        visible = true
        kotlinx.coroutines.delay(HINT_MS)
        visible = false
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0.5f else 0f,
        animationSpec = Motion.effects(),
        label = "coverTapHint",
    )
    if (alpha <= 0.01f) return
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(100))
            .background(Ink.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Default.GraphicEq,
            null,
            tint = TextSecondary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "Tap",
            color = TextSecondary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
    }
}

internal const val HINT_MS = 2_600L

/**
 * Keep the track that is playing.
 *
 * Downloading used to mean leaving the player, finding the track again in the library
 * and long-pressing it — which is the wrong way round: the moment you decide you want
 * to keep a song is the moment you are listening to it.
 *
 * Absent rather than greyed when there is nothing to download. A chip that can never
 * do anything is worse than no chip: on a Music Assistant speaker there is no file on
 * this phone to keep, and the row is long enough to scroll already.
 */
@Composable
fun DownloadChip(libraryViewModel: LibraryViewModel) {
    val state by libraryViewModel.currentTrackDownload.collectAsStateWithLifecycle()
    if (state == LibraryViewModel.TrackDownload.UNAVAILABLE) return

    // Two taps to delete, the same as "Delete analyses" in Light Sync — this throws
    // away a file the user deliberately kept, and one stray tap should not.
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state) { confirmDelete = false }

    when (state) {
        LibraryViewModel.TrackDownload.READY ->
            IconChip(Icons.Default.Download, "Download for offline") {
                libraryViewModel.downloadCurrentTrack()
            }
        LibraryViewModel.TrackDownload.IN_FLIGHT ->
            IconChip(Icons.Default.Downloading, "Downloading", active = true, tint = TextMuted)
        LibraryViewModel.TrackDownload.DONE ->
            IconChip(
                if (confirmDelete) Icons.Default.DeleteOutline else Icons.Default.DownloadDone,
                if (confirmDelete) "Tap again to delete the offline copy" else "Downloaded - tap to remove",
                active = true,
                tint = if (confirmDelete) ErrorRed else null,
            ) {
                if (confirmDelete) {
                    libraryViewModel.deleteCurrentTrackDownload()
                    confirmDelete = false
                } else confirmDelete = true
            }
        LibraryViewModel.TrackDownload.UNAVAILABLE -> Unit
    }
}

/**
 * The tappable quality badge: what's actually playing, and a way in to the detail.
 *
 * The badge only reports the tap. Where the detail is *drawn* is the screen's
 * business, and it matters more than it looks — see [QualityDetailOverlay].
 */
@Composable
fun TappableQualityChip(
    playing: StreamQuality?,
    onClick: () -> Unit,
) {
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        if (playing == null) QualityPill("-", lossless = false, compact = true)
        else QualityPill(playing.label, hiRes = playing.hiRes, lossless = playing.lossless, compact = true)
    }
}

/**
 * The quality detail, drawn as an overlay sibling in the player's root `Box`.
 *
 * Where this lives is load-bearing. The album cover is the only `weight(1f)` child of
 * the player's column, so it absorbs every dp any sibling gains — and this panel has
 * now moved twice because of it. Drawn inline it grew the badge's own column, which
 * grew the transport row, which shrank the art. Moved into a *focusable* `Popup` it
 * took window focus, put the activity through its soft-input resize path, and shrank
 * the art again. A non-focusable `Popup` fixed that, but it is still a second window
 * with its own insets — a fix by avoidance rather than by construction.
 *
 * An overlay sibling inside a `Box` cannot change any sibling's measured size, and
 * there is no window involved to have opinions about focus or insets. The class of bug
 * is gone rather than dodged.
 */
@Composable
fun BoxScope.QualityDetailOverlay(
    visible: Boolean,
    playing: StreamQuality?,
    source: StreamQuality?,
    onDismiss: () -> Unit,
    provider: String? = null,
    /** This phone is decoding, so its ReplayGain setting is the one in effect. */
    localSession: Boolean = false,
    /**
     * MPD (or another remote player) is decoding, not this phone — even
     * though [localSession] is also true for that case (see
     * [NowPlayingViewModel.State.serverPlayer]'s doc). Gates the notes that
     * are specifically about this phone's own decode chain (bit-perfect,
     * mixer resample), which describe nothing when nothing is being decoded
     * here.
     */
    serverPlayer: Boolean = false,
    /** What MA's per-player DSP did to this stream; null on the local path. */
    dsp: MaDspDetails? = null,
    loudness: MaLoudness = MaLoudness(),
    artworkUrl: String? = null,
    title: String = "",
    artist: String = "",
    /** Every player a grouped Music Assistant stream is fed to; empty when solo. */
    groupOutputs: List<NowPlayingViewModel.GroupOutput> = emptyList(),
    /** Music Assistant's shared processing and this player's own leg of it. */
    processing: MaQueueProcessing? = null,
    outputProcessing: MaOutputProcessing? = null,
    /** This phone is the one making the sound, so its own mixer is in the path. */
    decodesHere: Boolean = false,
    /** This phone's own chain while it plays a library itself; null on the MA path. */
    localChain: NowPlayingViewModel.LocalChain? = null,
) {
    if (visible) BackHandler { onDismiss() }

    // Two visibilities off one flag: the scrim only fades, because scaling a
    // full-bleed wash drags its edges in off the screen.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()),
        exit = fadeOut(Motion.effects()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), 0.92f),
        exit = fadeOut(Motion.effects()) + scaleOut(Motion.effects(), 0.96f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        // Swallow taps on the card so it doesn't dismiss under its own content.
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
        ) {
            QualityDetailCard(
                playing = playing,
                source = source,
                provider = provider,
                localSession = localSession,
                serverPlayer = serverPlayer,
                dsp = dsp,
                loudness = loudness,
                artworkUrl = artworkUrl,
                title = title,
                artist = artist,
                groupOutputs = groupOutputs,
                processing = processing,
                outputProcessing = outputProcessing,
                decodesHere = decodesHere,
                localChain = localChain,
            )
        }
    }
}

/**
 * Everything knowable about what is coming out of the pipe, lit by the album.
 *
 * The pill it opens from is a boast; this is the receipt behind it. Three blocks —
 * what the format is, what the output chain did to it, and what happened to the level
 * and where the bytes came from — because a flat list of eleven grey lines is a
 * spec sheet, and this is meant to be worth opening twice.
 */
@Composable
private fun QualityDetailCard(
    playing: StreamQuality?,
    source: StreamQuality?,
    provider: String? = null,
    localSession: Boolean = false,
    serverPlayer: Boolean = false,
    dsp: MaDspDetails? = null,
    loudness: MaLoudness = MaLoudness(),
    artworkUrl: String? = null,
    title: String = "",
    artist: String = "",
    /** Every player a grouped Music Assistant stream is fed to; empty when solo. */
    groupOutputs: List<NowPlayingViewModel.GroupOutput> = emptyList(),
    /** Music Assistant's shared processing and this player's own leg of it. */
    processing: MaQueueProcessing? = null,
    outputProcessing: MaOutputProcessing? = null,
    /** This phone is the one making the sound, so its own mixer is in the path. */
    decodesHere: Boolean = false,
    /** This phone's own chain while it plays a library itself; null on the MA path. */
    localChain: NowPlayingViewModel.LocalChain? = null,
) {
    val accent = LocalAccent.current
    val palette = LocalPalette.current
    // Only meaningful on the local path — Music Assistant does its own gain
    // server-side, so the app's setting has no say in what a speaker plays.
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val gainMode by settings.replayGainMode.collectAsState(initial = ReplayGain.ALBUM)
    val bitPerfect by settings.bitPerfect24Bit.collectAsState(initial = false)
    // Both rows always render when both readings exist, and the line underneath says
    // whether they differ. Hiding "Source" on a match was the old behaviour, and it
    // meant the common case — a direct, untranscoded stream — showed a single
    // unexplained row that read as the card having failed to load the other one.
    val transcoded = playing != null && source != null && !source.matches(playing)
    val shape = RoundedCornerShape(20.dp)
    val art = rememberArtRequest(artworkUrl, pixels = 96)

    Box(Modifier.padding(24.dp)) {
        // The album's own colour under the card rather than a grey drop shadow, the
        // same way the play button and the hi-res pill are lit.
        CastGlow(palette.swatch(0), shape, blurRadius = 40.dp, alpha = 0.30f, offsetY = 10.dp)
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Ink2, accent.a(0.10f))))
                .border(1.dp, accent.a(0.30f), shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Whose receipt this is. Without it the card is a set of numbers that
            // could belong to any track on the queue.
            if (title.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (art != null) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                        Text(
                            title, color = TextPrimary, fontFamily = AppFont,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotBlank()) {
                            Text(
                                artist, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val dspState = dsp?.state
            // ── Signal Path Inspector (Audiophile Transparency) ────────────
            val sourceLabel = (source ?: playing)?.label ?: "PCM"
            val maSteps = maProcessingSteps(processing, outputProcessing)
            val localGainDb = when {
                !localSession -> null
                // What the player is actually applying, tags or our own measurement.
                localChain != null -> localChain.gain?.db
                else -> source?.let { ReplayGain.decibels(it, gainMode) }
            }
            val localSteps = localChain?.let { localProcessingSteps(it, localGainDb) }.orEmpty()
            val dspLabel = when {
                // Music Assistant's own report of what it did, when it sends one.
                processing != null || outputProcessing != null -> when {
                    maSteps.isEmpty() -> null
                    maSteps.size == 1 -> maSteps.single()
                    else -> "${maSteps.size} steps"
                }
                localChain != null -> localSteps.let { steps ->
                    when {
                        steps.isEmpty() -> null
                        steps.size == 1 -> steps.single()
                        else -> "${steps.size} steps"
                    }
                }
                dspState == "enabled" -> "EQ / DSP"
                gainMode != ReplayGain.OFF && localSession -> "ReplayGain"
                else -> null
            }
            val engineLabel = when {
                localChain?.signal?.usb != null -> "USB driver"
                localChain?.signal?.aaudioBitperfect == true -> "AAudio direct"
                localChain?.signal?.floatEngaged == true -> "Float"
                bitPerfect && localChain == null -> "24-bit Direct"
                localSession -> "AAudio/Track"
                else -> "Sendspin"
            }
            val devName = when {
                serverPlayer -> "Remote Player"
                localChain?.signal?.usb != null -> localChain.signal.usb.dacName
                localChain != null -> localChain.outputLabel
                else -> "This Device"
            }
            SignalPathDiagram(
                sourceFormat = sourceLabel,
                dspInfo = dspLabel,
                outputEngine = engineLabel,
                hardwareDevice = devName,
                accent = accent,
            )

            // ── Format ────────────────────────────────────────────────────
            QualityBlock("Format") {
                QualityRow("Playing", playing, accent)
                if (source != null) QualityRow("Source", source, accent)
                if (playing != null && source != null) {
                    QualityNote(
                        if (transcoded) "Transcoded from ${source.label} to ${playing.label}"
                        else "Direct - no transcoding",
                    )
                }
                val facts = listOfNotNull(
                    (playing ?: source)?.channelLabel,
                    (source ?: playing)?.sizeBytes?.takeIf { it > 0 }?.let { fileSize(it) },
                )
                if (facts.isNotEmpty()) QualityNote(facts.joinToString(" • "))
            }

            // ── Processing ────────────────────────────────────────────────
            // Every stage Music Assistant reports between the file and this player,
            // in its own order: the shared stage, then this player's leg.
            if (!localSession && (processing != null || outputProcessing != null)) {
                QualityBlock("Processing") {
                    MaProcessingSteps(processing, outputProcessing, accent)
                    if (maSteps.isEmpty()) {
                        QualityNote(
                            "Direct signal path - Music Assistant applies no normalisation, " +
                                "crossfade, speed change, overlay or DSP",
                        )
                    }
                }
            }

            // ── This phone's own chain ────────────────────────────────────
            // A library this phone plays itself: every stage of its own player, then
            // the one output it feeds, read from the chain rather than the settings.
            if (localChain != null) {
                QualityBlock("Processing") {
                    LocalProcessingSteps(localChain, localGainDb, accent)
                    if (localSteps.isEmpty()) {
                        QualityNote("Direct signal path - nothing of this app's changes the audio")
                    }
                }
                QualityBlock("Outputs") {
                    LocalOutputRow(localChain, playing ?: source, accent)
                }
            }

            // ── Outputs ───────────────────────────────────────────────────
            // What Music Assistant feeds each player, as its own pipeline view lists
            // them: one source, then every player with its own format and its own
            // grade. A lone player is listed too, so its grade is always shown.
            if (groupOutputs.isNotEmpty()) {
                QualityBlock("Outputs") {
                    groupOutputs.forEach { GroupOutputRow(it, accent) }
                }
            }

            // ── Output ────────────────────────────────────────────────────
            // What happens to the stream after the format is decided: this phone's
            // own 24-bit setting, whether the Android mixer is resampling under it,
            // and — on a Music Assistant player — whether MA's DSP chain ran.
            val deviceRate = remember(bitPerfect) { FormatNegotiator.deviceOutputQuality(bitPerfect) }
            val decodingHere = localSession && !serverPlayer && localChain == null
            // A Music Assistant stream this phone plays still goes through its mixer.
            val maHere = !localSession && decodesHere
            val showOutput = decodingHere || maHere || (dspState != null && outputProcessing == null)
            if (showOutput) {
                QualityBlock("Output") {
                    if (decodingHere) {
                        QualityNote(if (bitPerfect) "Bit-perfect on - 24-bit requested where the source has it" else "Bit-perfect off - 16-bit output")
                        MixerNote(playingRateHz = playing?.sampleRateHz ?: 0, mixerRateHz = deviceRate.sampleRateHz)
                    }
                    if (maHere) {
                        QualityNote("This phone plays it through its low-latency output, no effects")
                        MixerNote(playingRateHz = playing?.sampleRateHz ?: 0, mixerRateHz = deviceRate.sampleRateHz)
                    }
                    // Whether MA's own equaliser is in the path. Worth saying here
                    // because the DSP panel can be full of carefully set bands that
                    // the server is not applying, and nothing else would tell you.
                    if (dsp != null && dspState != null && !localSession && outputProcessing == null) {
                        QualityNote(
                            when (dspState) {
                                "enabled" -> "DSP active" +
                                    if (dsp.filterCount > 0) " - ${dsp.filterCount} filters" else ""
                                "disabled" -> "DSP not applied to this stream"
                                else -> "DSP ${dspState.replace('_', ' ')}"
                            },
                            warn = !dsp.active,
                        )
                    }
                }
            }

            // ── Level and origin ──────────────────────────────────────────
            val gainLine = localChain?.gain?.let { g ->
                when (g.origin) {
                    ReplayGain.Origin.TAGS -> "ReplayGain %+.1f dB applied, from the file's tags".format(g.db)
                    ReplayGain.Origin.MEASURED ->
                        "ReplayGain %+.1f dB applied, from this phone's loudness scan".format(g.db)
                    ReplayGain.Origin.FIXED -> "Untagged level %+.1f dB applied".format(g.db)
                }
            } ?: source?.gainLabel?.let { tag ->
                val applied = if (localSession) ReplayGain.decibels(source, gainMode) else null
                when {
                    !localSession -> "ReplayGain $tag - applied by Music Assistant"
                    applied == null -> "ReplayGain $tag - not applied"
                    else -> "ReplayGain %+.1f dB applied".format(applied)
                }
            }
            // Where Music Assistant pulled the bytes from — `StreamDetails.provider`.
            // Named in full rather than as "From X": "From Subsonic" on a screen whose
            // player is MA reads as a claim about the library or the player, and it is
            // neither. MA's own library is backed by providers, and this says which one
            // held the file — the difference between a local rip and a lossy stream.
            val providerLine = provider?.takeIf { !localSession }
                ?.let { "Music Assistant fetched this from $it" }
            // What MA did to the level, which the card has never been able to say.
            // A carefully mastered record pulled to a target LUFS looked exactly like
            // one left alone, and the difference is the whole reason someone opens
            // this card twice.
            // Covered by the Processing block when Music Assistant reports its pipeline.
            val loudnessLine = loudness.summary?.takeIf { !localSession && processing == null }
            if (gainLine != null || providerLine != null || loudnessLine != null) {
                QualityBlock("Level and origin") {
                    loudnessLine?.let { QualityNote(it) }
                    gainLine?.let { QualityNote(it) }
                    providerLine?.let { QualityNote(it) }
                }
            }
        }
    }
}

/**
 * What the Android mixer is doing to the rate underneath everything else.
 *
 * Shared by the quality card and the device card, because they are two views of the
 * same fact and the worst outcome would be one of them saying a 96 kHz file plays
 * untouched while the other says the mixer is running at 48.
 */
@Composable
private fun MixerNote(playingRateHz: Int, mixerRateHz: Int) {
    if (mixerRateHz <= 0) return
    if (playingRateHz > 0 && playingRateHz != mixerRateHz) {
        QualityNote(
            "Resampled ${StreamQuality.khz(playingRateHz)} - ${StreamQuality.khz(mixerRateHz)} kHz by the Android mixer",
            warn = true,
        )
    } else {
        QualityNote("Device output ${StreamQuality.khz(mixerRateHz)} kHz")
    }
}

/**
 * What this phone's output chain can do, drawn where the speaker picker would be.
 *
 * On Music Assistant the pill at the top of the player names a *player*, and tapping
 * it to change which speaker you are driving is the whole point. On the Navidrome and
 * offline paths there is only ever one player — this phone — so that tap opened a
 * picker with a single entry and nothing to pick. The interesting question there is
 * the one the app could never answer: this phone plays 44.1/16 through its own
 * speaker, and something else entirely through a USB DAC or a Bluetooth dongle, and
 * *what the far end can take* is what decides whether a hi-res file was worth
 * downloading.
 *
 * Drawn as an overlay sibling in the player's root `Box`, for the reason recorded at
 * length on [QualityDetailOverlay]: the album cover is the only `weight(1f)` child of
 * the player's column, so it absorbs every dp any sibling gains, and both an inline
 * panel and a focusable `Popup` shrank the artwork. Do not move this.
 */
@Composable
fun BoxScope.DeviceDetailOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    /** The rate actually being decoded, so the card can say if the mixer resamples it. */
    playingRateHz: Int = 0,
    /**
     * MPD, not this phone, is decoding — see
     * [NowPlayingViewModel.State.serverPlayer]'s doc. Switches the whole card
     * to the server's own output rather than this phone's `AudioManager`,
     * which has nothing to do with where the sound is actually going.
     */
    serverPlayer: Boolean = false,
    /** MPD's configured output name, when it said. */
    serverOutputDeviceName: String? = null,
    /** MPD's live decode format, when it said. */
    serverOutputFormat: RemoteAudioFormat? = null,
    /** This phone's own chain, so "Right now" reports it rather than the settings. */
    localChain: NowPlayingViewModel.LocalChain? = null,
    /** What arrived, which caps the output's grade exactly as on the quality card. */
    arrived: StreamQuality? = null,
) {
    if (visible) BackHandler { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()),
        exit = fadeOut(Motion.effects()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), 0.92f),
        exit = fadeOut(Motion.effects()) + scaleOut(Motion.effects(), 0.96f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
        ) {
            DeviceDetailCard(
                playingRateHz = playingRateHz,
                serverPlayer = serverPlayer,
                serverOutputDeviceName = serverOutputDeviceName,
                serverOutputFormat = serverOutputFormat,
                localChain = localChain,
                arrived = arrived,
            )
        }
    }
}

/**
 * The device receipt: where the sound is going, what that thing accepts, and what is
 * happening to it on the way.
 *
 * Read once per opening rather than observed. Plugging a DAC in while this card is on
 * screen is rare enough that closing and reopening it is a fair price for not holding
 * an [android.media.AudioDeviceCallback] here — the same trade the Settings output
 * picker makes.
 */
@Composable
private fun DeviceDetailCard(
    playingRateHz: Int,
    serverPlayer: Boolean = false,
    serverOutputDeviceName: String? = null,
    serverOutputFormat: RemoteAudioFormat? = null,
    localChain: NowPlayingViewModel.LocalChain? = null,
    arrived: StreamQuality? = null,
) {
    val accent = LocalAccent.current
    val palette = LocalPalette.current

    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.padding(24.dp)) {
        CastGlow(palette.swatch(0), shape, blurRadius = 40.dp, alpha = 0.30f, offsetY = 10.dp)
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Ink2, accent.a(0.10f))))
                .border(1.dp, accent.a(0.30f), shape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (serverPlayer) {
                ServerDeviceDetail(accent, serverOutputDeviceName, serverOutputFormat)
            } else {
                LocalDeviceDetail(accent, playingRateHz, localChain, arrived)
            }
        }
    }
}

/**
 * The card's content when MPD, not this phone, is decoding: MPD's own
 * configured output name and live format — read from the server, never from
 * this phone's `AudioManager`, which has nothing to do with where the sound
 * is actually going. See [DeviceDetailCard.serverPlayer].
 */
@Composable
private fun ServerDeviceDetail(
    accent: Color,
    outputDeviceName: String?,
    format: RemoteAudioFormat?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Dns, null, tint = accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                outputDeviceName ?: "MPD", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "MPD server", color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    QualityBlock("Playing through") {
        QualityRow("Output", outputDeviceName ?: "MPD's configured output", accent, lit = true)
        QualityNote("Reported by the MPD server, not this phone")
    }

    if (format != null && (format.sampleRateHz > 0 || format.bitDepth > 0 || format.channels > 0)) {
        QualityBlock("Right now") {
            if (format.sampleRateHz > 0 && format.bitDepth > 0) {
                QualityRow("Format", "${StreamQuality.khz(format.sampleRateHz)}/${format.bitDepth}", accent)
            } else if (format.sampleRateHz > 0) {
                QualityRow("Rate", "${StreamQuality.khz(format.sampleRateHz)}kHz", accent)
            }
            format.channels.takeIf { it > 0 }?.let {
                QualityRow("Channels", remoteChannelLabel(it), accent)
            }
            format.bitrateKbps.takeIf { it > 0 }?.let { QualityRow("Bitrate", "$it kb/s", accent) }
        }
    } else {
        QualityBlock("Right now") {
            QualityNote("MPD didn't report a live format.", warn = true)
        }
    }
}

/** Same channel-count wording as [StreamQuality.channelLabel], for a raw count with no format to attach it to. */
private fun remoteChannelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels channels"
}

/**
 * The card's content when this phone itself is decoding — the original
 * card, unchanged in behaviour.
 */
@Composable
private fun LocalDeviceDetail(
    accent: Color,
    playingRateHz: Int,
    localChain: NowPlayingViewModel.LocalChain? = null,
    arrived: StreamQuality? = null,
) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val bitPerfect by settings.bitPerfect24Bit.collectAsState(initial = false)
    val preferredId by settings.preferredAudioDeviceId.collectAsState(initial = "")

    val am = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // Keyed on the pinned id so choosing a different output in Settings is reflected
    // the next time the card is opened rather than the next time the app is started.
    val route = remember(preferredId) { DeviceCapabilities.activeRoute(am, preferredId) }
    val mixerRate = remember { DeviceCapabilities.mixerRateHz() }
    val frames = remember { DeviceCapabilities.framesPerBuffer(am) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (route?.isUsb == true) Icons.Default.Usb
            else if (route?.isBluetooth == true) Icons.Default.Bluetooth
            else Icons.Default.Smartphone,
            null, tint = accent, modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                "This phone", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text(
                android.os.Build.MODEL, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (route == null) {
        // The platform declined to list its outputs. Saying so is better than
        // an empty card that reads as a failure to load.
        QualityBlock("Output") {
            QualityNote("Android didn't report an output device.", warn = true)
        }
    } else {
        // ── Playing through ───────────────────────────────────────
        QualityBlock("Playing through") {
            QualityRow("Output", route.label, accent, lit = true)
            QualityNote(
                if (route.pinned) "Pinned to this output in Settings"
                else "Chosen by Android - normally the last thing you plugged in",
            )
        }

        // ── What it accepts ───────────────────────────────────────
        // The whole reason this card exists: the phone's own speaker and a
        // USB DAC are different instruments, and until now nothing said so.
        val accepts = listOfNotNull(
            route.sampleRateLabel,
            route.bitDepthLabel,
            route.channelLabel,
        )
        if (accepts.isNotEmpty()) {
            QualityBlock("What it accepts") {
                route.sampleRateLabel?.let { QualityRow("Rates", it, accent) }
                route.bitDepthLabel?.let { QualityRow("Depth", it, accent) }
                route.channelLabel?.let { QualityRow("Channels", it, accent) }
            }
        }

        // ── Right now ─────────────────────────────────────────────
        QualityBlock("Right now") {
            // The same row the quality card's Outputs shows, so the two cards can
            // never tell different stories about one output.
            if (localChain != null && (localChain.signal.usb != null || localChain.signal.sink.known)) {
                LocalOutputRow(localChain, arrived, accent)
            } else {
                MixerNote(playingRateHz = playingRateHz, mixerRateHz = mixerRate)
                QualityNote(
                    if (bitPerfect) "Bit-perfect on - 24-bit requested where the source has it"
                    else "Bit-perfect off - 16-bit output",
                )
            }
            if (localChain?.signal?.usb == null) frames?.let { QualityNote("Mixer buffer $it frames") }
            route.bluetoothCodecNote?.let { QualityNote(it) }
        }
    }
}

@Composable
private fun SignalPathDiagram(
    sourceFormat: String,
    dspInfo: String?,
    outputEngine: String,
    hardwareDevice: String,
    accent: Color,
) {
    QualityBlock("Signal Path Inspector") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ink3.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SignalStageNode(title = "SOURCE", subtitle = sourceFormat, accent = accent, active = true)
            SignalArrow(accent)
            SignalStageNode(title = "DSP", subtitle = dspInfo ?: "Direct", accent = accent, active = dspInfo != null)
            SignalArrow(accent)
            SignalStageNode(title = "ENGINE", subtitle = outputEngine, accent = accent, active = true)
            SignalArrow(accent)
            SignalStageNode(title = "OUTPUT", subtitle = hardwareDevice, accent = accent, active = true)
        }
    }
}

@Composable
private fun SignalStageNode(title: String, subtitle: String, accent: Color, active: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = if (active) accent else TextFaint,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
        )
        Text(
            subtitle,
            color = if (active) TextPrimary else TextMuted,
            fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SignalArrow(accent: Color) {
    Text("→", color = accent.a(0.6f), fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
}

/** An eyebrow and the lines under it, so the card reads as sections not a list. */
@Composable
private fun QualityBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(title)
        content()
    }
}

/** One explanatory line. [warn] for the ones that mean something is not happening. */
@Composable
private fun QualityNote(text: String, warn: Boolean = false) {
    Text(
        text,
        color = if (warn) WarnAmber else TextMuted,
        fontFamily = AppFont, fontSize = 11.sp, lineHeight = 15.sp,
    )
}

/** `4.2 MB`, `812 KB`. */
private fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

/**
 * The short names of this phone's own stages that change the audio, for the signal
 * path's middle box. [gainDb] is the ReplayGain actually applied, or null.
 */
private fun localProcessingSteps(c: NowPlayingViewModel.LocalChain, gainDb: Float?): List<String> = buildList {
    val s = c.signal
    if (s.usb != null && !s.usb.samplesUntouched) add("Volume")
    if (!s.processorsBypassed) {
        if (gainDb != null) add("ReplayGain")
        if (c.crossfeed != Crossfeed.Preset.OFF) add("Crossfeed")
        if (eqAltersSignal(c.eq)) add("EQ")
        addAll(c.effects.map { it.substringBefore(' ') })
    }
    if (c.speed != 1f) add("Speed")
    if (c.transition != null) add(c.transition.substringBefore(' '))
    if (s.truncating) add("16-bit")
}

/** The equaliser is on and something in it changes the sound. */
private fun eqAltersSignal(eq: LocalDsp.Config): Boolean =
    eq.enabled && (eq.effectivePreampDb() != 0f || eq.activeBands().any { it.altersSignal() })

/**
 * Every stage between what arrived and what Android (or the DAC) is handed, as this
 * phone's own player runs it — read from the chain, not from settings.
 */
@Composable
private fun LocalProcessingSteps(c: NowPlayingViewModel.LocalChain, gainDb: Float?, accent: Color) {
    val s = c.signal
    if (s.decoded.known) {
        ProcessingStep("Decoded", s.decoded.summary().removePrefix("Unknown"), accent, active = false)
        if (s.decoderLostBits) {
            QualityNote(
                "The file is ${s.source.depthLabel()} and this phone's decoder handed over " +
                    "${s.decoded.depthLabel()}; nothing later can put those bits back",
                warn = true,
            )
        }
    }
    if (s.processorsBypassed) {
        QualityNote(
            when {
                s.usb != null -> "CAMusic's USB driver takes the decoded audio straight to the DAC"
                s.aaudioBitperfect -> "Direct output: the decoded audio goes straight to the DAC"
                s.floatEngaged -> "Float output: media3 runs none of this app's processors"
                else -> "Exclusive output: nothing of this app's between decoder and DAC"
            } + ", so ReplayGain boost, crossfeed, the equaliser and the effects are out of the chain",
        )
    } else {
        gainDb?.let {
            val from = when (c.gain?.origin) {
                ReplayGain.Origin.TAGS -> " • from tags"
                ReplayGain.Origin.MEASURED -> " • from loudness scan"
                ReplayGain.Origin.FIXED -> " • untagged level"
                null -> ""
            }
            ProcessingStep("ReplayGain", "%+.1f dB".format(it) + from, accent, active = true)
        }
        if (c.crossfeed != Crossfeed.Preset.OFF) {
            ProcessingStep(
                "Crossfeed",
                "${c.crossfeed.label} • ${c.crossfeed.fcutHz} Hz, ${"%.1f".format(c.crossfeed.feedDb)} dB " +
                    "for ${c.outputLabel}",
                accent, active = true,
            )
        }
        if (eqAltersSignal(c.eq)) {
            val bands = c.eq.activeBands().count { it.altersSignal() }
            val preamp = c.eq.effectivePreampDb()
            ProcessingStep(
                "Equaliser",
                listOfNotNull(
                    "$bands band${if (bands == 1) "" else "s"}",
                    preamp.takeIf { it != 0f }?.let { "preamp %+.1f dB".format(it) },
                    "for ${c.outputLabel}",
                ).joinToString(" • "),
                accent, active = true,
            )
        }
        c.effects.forEach { ProcessingStep("Effect", it, accent, active = true) }
    }
    if (c.speed != 1f) ProcessingStep("Playback speed", "%.2fx".format(c.speed), accent, active = true)
    c.transition?.let { ProcessingStep("Between tracks", it, accent, active = true) }
    when {
        s.usb != null -> Unit // the output row says what the DAC is given
        s.floatEngaged -> ProcessingStep("Handed to Android", "32-bit float", accent, active = false)
        s.sink.known -> ProcessingStep("Handed to Android", s.sink.depthLabel() ?: "PCM", accent, active = s.truncating)
    }
    if (s.truncating) {
        QualityNote(
            "The decoder produced ${s.decoded.depthLabel()} and the sink carries " +
                "${s.sink.depthLabel()}. Turn on high-resolution output to keep it",
            warn = true,
        )
    }
}

/**
 * The one output this phone's own player feeds, graded the way Music Assistant grades
 * a player: by what reaches it, never better than what arrived.
 */
@Composable
private fun LocalOutputRow(c: NowPlayingViewModel.LocalChain, arrived: StreamQuality?, accent: Color) {
    val s = c.signal
    val usb = s.usb
    val name: String
    val format: StreamQuality?
    if (usb != null) {
        name = usb.dacName
        format = StreamQuality("pcm", usb.confirmedRateHz, usb.dacBits)
    } else {
        name = c.outputLabel
        val rate = s.mixerRateHz.takeIf { it > 0 } ?: s.sink.sampleRateHz ?: 0
        val depth = if (s.sink.isFloat) 32 else s.sink.bitDepth ?: 0
        format = if (s.sink.known) StreamQuality("pcm", rate, depth) else null
    }
    val tier = listOfNotNull(QualityTier.of(arrived), QualityTier.of(format)).minOrNull()
    if (format != null) GroupOutputRow(NowPlayingViewModel.GroupOutput(name, format, isSelf = false, tier = tier), accent)
    else QualityRow("Output", name, accent)
    when {
        usb != null -> QualityNote("CAMusic's USB driver, Android's mixer bypassed")
        s.aaudioBitperfect -> QualityNote("Direct output: decoded audio straight to the DAC")
        s.exclusive -> QualityNote("Exclusive output: nothing of this app's in the chain")
    }
    if (usb != null) {
        QualityNote("${usb.dacBits}-bit in a ${usb.slotBits}-bit slot, rate confirmed by the DAC")
        usb.convertedFromHz?.let {
            QualityNote("Converted from ${StreamQuality.khz(it)} to the DAC's ${StreamQuality.khz(usb.confirmedRateHz)} kHz", warn = true)
        }
        QualityNote(
            if (usb.samplesUntouched) "Bit-perfect: the file's own samples reach the DAC (${usb.volume})"
            else "Not bit-perfect: ${usb.volume}",
            warn = !usb.samplesUntouched,
        )
        if (usb.asyncClock) {
            QualityNote(
                "This DAC runs on its own clock and CAMusic streams on the nominal schedule, " +
                    "so a very long session can drift into a click",
                warn = true,
            )
        }
    } else if (s.resampling) {
        MixerNote(playingRateHz = s.sink.sampleRateHz ?: 0, mixerRateHz = s.mixerRateHz)
    } else if (s.mixerRateHz > 0) {
        QualityNote("Android's mixer at ${StreamQuality.khz(s.mixerRateHz)} kHz, no resampling")
    }
}

/**
 * The short names of the stages that change the audio, for the signal path's middle
 * box. Headroom is not one of them: it is how Music Assistant processes, not a change.
 */
private fun maProcessingSteps(p: MaQueueProcessing?, o: MaOutputProcessing?): List<String> = buildList {
    if (p != null) {
        if (p.normalizationMode != null && p.normalizationMode !in PASSIVE_NORMALISATION) add("Normalised")
        if (p.crossfadeMode != null && p.crossfadeMode !in PASSIVE_CROSSFADE) add("Crossfade")
        if (p.playbackSpeed != 1f) add("Speed")
        if (p.overlayActive) add("Overlay")
    }
    if (o != null) {
        if (o.dspActive) add("EQ / DSP")
        if (o.sourceChannel == "FL" || o.sourceChannel == "FR") add("Channel")
    }
}

/** Normalisation modes that leave the level alone (or leave it to the source). */
private val PASSIVE_NORMALISATION = setOf("disabled", "measurement_only", "source", "unknown")
private val PASSIVE_CROSSFADE = setOf("disabled", "source", "unknown")

/** Music Assistant's processing stages for this player, one row each, worded as MA words them. */
@Composable
private fun MaProcessingSteps(p: MaQueueProcessing?, o: MaOutputProcessing?, accent: Color) {
    p?.pcmFormat?.let { f ->
        val float = f.codec.lowercase().trim().startsWith("f")
        ProcessingStep(
            "Processing headroom",
            if (float && f.bitDepth > 0) "${f.bitDepth}-bit float PCM" else f.label,
            accent, active = false,
        )
    }
    if (p != null) {
        p.normalizationMode?.takeIf { it != "disabled" && it != "unknown" }?.let { mode ->
            val detail = listOfNotNull(
                NORMALISATION_LABELS[mode] ?: mode.replace('_', ' '),
                p.appliedGainDb?.takeIf { kotlin.math.abs(it) >= 0.05f }?.let { "%+.1f dB".format(it) },
                p.targetLufs?.let { "target %.0f LUFS".format(it) },
                p.measuredLufs?.let { "measured %.1f LUFS".format(it) },
            ).joinToString(" • ")
            ProcessingStep("Volume normalisation", detail, accent, active = mode !in PASSIVE_NORMALISATION)
        }
        p.crossfadeMode?.takeIf { it !in PASSIVE_CROSSFADE }?.let {
            ProcessingStep("Crossfade", CROSSFADE_LABELS[it] ?: it.replace('_', ' '), accent, active = true)
        }
        if (p.playbackSpeed != 1f) ProcessingStep("Playback speed", "%.2fx".format(p.playbackSpeed), accent, active = true)
        if (p.overlayActive) ProcessingStep("Audio overlay", "Active", accent, active = true)
    }
    if (o != null) {
        when (o.dspState) {
            "enabled" -> {
                val detail = listOfNotNull(
                    o.filters.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { FILTER_LABELS[it] ?: it.replace('_', ' ') },
                    o.inputGainDb.takeIf { it != 0f }?.let { "in %+.1f dB".format(it) },
                    o.outputGainDb.takeIf { it != 0f }?.let { "out %+.1f dB".format(it) },
                    o.presetId?.let { "preset" },
                ).joinToString(" • ").ifBlank { "Active, no filters" }
                ProcessingStep("DSP", detail, accent, active = true)
            }
            "disabled_by_unsupported_group" -> QualityNote("DSP unavailable for this group", warn = true)
        }
        when (o.sourceChannel) {
            "FL" -> ProcessingStep("Channel", "Left only", accent, active = true)
            "FR" -> ProcessingStep("Channel", "Right only", accent, active = true)
        }
        when (o.bitPerfect) {
            true -> QualityNote("Bit-perfect: decoded source samples reach the output unchanged")
            false -> QualityNote("Not bit-perfect: format conversion or processing changed the samples")
            null -> Unit
        }
    }
}

private val NORMALISATION_LABELS = mapOf(
    "dynamic" to "Dynamic",
    "measurement_only" to "Measured only",
    "fallback_fixed_gain" to "Fallback fixed gain",
    "fixed_gain" to "Fixed gain",
    "fallback_dynamic" to "Fallback dynamic",
    "source" to "Applied by the source",
)
private val CROSSFADE_LABELS = mapOf(
    "smart_crossfade" to "Smart",
    "standard_crossfade" to "Standard",
    "voice_over" to "Voice-over, music ducked under it",
)
private val FILTER_LABELS = mapOf(
    "parametric_eq" to "Parametric EQ",
    "tone_control" to "Tone control",
    "gain" to "Gain",
    "balance" to "Balance",
    "transpose" to "Transpose",
    "safety_limiter" to "Safety limiter",
    "compressor" to "Compressor",
    "high_low_pass" to "High/low pass",
    "convolution" to "Convolution",
    "stereo_width" to "Stereo width",
)

/** One processing stage: its name, then what it did. [active] lights the dot for a stage that changes the audio. */
@Composable
private fun ProcessingStep(title: String, detail: String, accent: Color, active: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape)
                .background(if (active) accent else TextMuted)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, lineHeight = 17.sp,
            )
            Text(
                detail,
                color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
    }
}

/** One group member: its name, then the format Music Assistant feeds it. */
@Composable
private fun GroupOutputRow(output: NowPlayingViewModel.GroupOutput, accent: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape)
                .background(if (output.quality.lossless) accent else TextMuted)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (output.isSelf) "${output.name} (this phone)" else output.name,
                color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, lineHeight = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    output.quality.label,
                    color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, lineHeight = 17.sp,
                )
                (output.tier ?: QualityTier.of(output.quality))?.let { QualityTierBadge(it) }
            }
        }
    }
}

/**
 * Music Assistant's quality grade, drawn as its pipeline view draws it: the tier's own
 * colour in the dot, its code, and its name.
 */
@Composable
private fun QualityTierBadge(tier: QualityTier) {
    val colour = when (tier) {
        QualityTier.LOW -> Color(0xFFFFA500)       // orange
        QualityTier.STANDARD -> Color(0xFF90EE90)  // lightgreen
        QualityTier.LOSSLESS -> Color(0xFF2EB82E)  // green
        QualityTier.HI_RES -> Color(0xFF00E5FF)    // cyan
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, colour.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colour))
        Text(
            "${tier.code} ${tier.label}",
            color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1,
        )
    }
}

@Composable
private fun QualityRow(label: String, q: StreamQuality?, accent: Color) {
    QualityRow(label, q?.label ?: "-", accent, lit = q?.lossless == true)
}

/**
 * One labelled fact. [lit] puts the accent in the leading dot — reserved for the
 * readings worth noticing (a lossless stream, the output actually in use) so the dot
 * still means something when the card is a dozen rows long.
 */
@Composable
private fun QualityRow(label: String, value: String, accent: Color, lit: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape)
                .background(if (lit) accent else TextMuted)
        )
        Text(
            label,
            color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.width(62.dp),
        )
        Text(
            value,
            color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, lineHeight = 17.sp,
        )
    }
}

/** A small badge at the corner indicating the source (MA, Navidrome, Jellyfin, etc.). */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val isMa = source == "MA"
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(5.dp).clip(CircleShape)
                .background(if (isMa) accent else Color(0xFF5EC8C0))
        )
        Text(
            source,
            color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            // One line, whatever room is left. The badge shares its row with the
            // speaker pill, and a long player name on a narrow phone can leave it less
            // width than "Navidrome" wants — an ellipsis there is a far better answer
            // than the name wrapping the badge into two lines and pushing the pill.
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

