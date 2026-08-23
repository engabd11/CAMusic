package com.engabd.sendpin.ui.screens

import android.content.Context
import android.content.Intent
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
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaDspDetails
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
    val scope = rememberCoroutineScope()

    // Which sheet or card is up, if any. Shared with the overlay layout so the two
    // cannot drift — see PlayerSheetState.
    val sheets = rememberPlayerSheets()
    // Lyrics are a *mode* of the player, not an overlay: they take the cover's place.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

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

    // Hoisted, because the artwork is painted twice on this screen: as the sleeve and
    // as the wash behind it. Sharing one holder is what keeps the two on the same
    // clock through a page turn, and what stops either of them reloading when two
    // tracks of one record arrive with different urls for the same picture.
    val art = rememberSettledArt(st.artworkUrl, albumFlipKey(st))
    val artDim = idleFade(st.idle, 0.55f)
    val artGlow = idleFade(st.idle, 0.18f, 0.45f)
    val washDim = idleFade(st.idle, 0.5f)

    // How far up a swipe has to travel before it means "show me the queue".
    val queueRevealPx = with(LocalDensity.current) { 56.dp.toPx() }

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
        ) {
            // The screen keeps its shape whether or not anything is playing: an
            // idle player shows the last track it had (dimmed, with a notice)
            // rather than swapping the whole screen for an empty state.
            // Fed the *settled* url rather than the raw one, so it neither reloads
            // within an album nor changes a quarter of a second before the cover does.
            MeltBackdrop(art.url, intensity = washDim.value)

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
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space, shrinking on small screens
                // and growing on large ones — no scroll needed.
                // The weight lives on the container, not on either branch, so the
                // two can never disagree about how tall this slot is. The cover is the
                // only flexible child of this Column and anything that grows beside it
                // takes the difference out of the artwork.
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        (fadeIn(Motion.fadeThroughIn()) togetherWith fadeOut(Motion.fadeThroughOut()))
                            .using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = "lyricsOrArt",
                ) { lyrics ->
                    if (lyrics) {
                        LyricsPane(
                            viewModel = viewModel,
                            positionMs = scrubber.positionMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AlbumArt(
                            art = art,
                            glow = palette.swatch(0),
                            modifier = Modifier
                                .fillMaxSize()
                                // graphicsLayer rather than Modifier.alpha: the State is
                                // read in the layer block, so easing the dim costs no
                                // recomposition for the length of the transition.
                                .graphicsLayer { alpha = artDim.value }
                                // Long-press → quick actions (go to album/artist, share).
                                // No ripple: a tap here has never done anything, and
                                // adding one now would read as a new, absent affordance.
                                // Nothing to act on with an empty player.
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClick = if (favouritable != null) {
                                        { sheets.actions = true }
                                    } else null,
                                ),
                            glowAlpha = artGlow.value,
                            placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Secondary actions — track-scoped chips only; the quality badge
                // has moved to the transport row (between play and the seek bar).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(19.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconChip(Icons.Default.Lyrics, "Lyrics", active = showLyrics) {
                        showLyrics = !showLyrics
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
                    // DSP is Music Assistant's server-side pipeline, configured per MA
                    // player over `config/players/dsp/*`. The local player decodes on
                    // this phone and MA has never heard of it, so opening this while
                    // Navidrome is playing showed — and would have saved — settings for
                    // a completely different player.
                    if (!st.isLocalSession) {
                        IconChip(Icons.Default.GraphicEq, "DSP / Equalizer", active = sheets.panel == Panel.DSP) {
                            sheets.panel = if (sheets.panel == Panel.DSP) null else Panel.DSP
                        }
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
                        tint = if (favouritable == null) TextFaint else null,
                        onClick = if (favouritable == null) null else ({ viewModel.toggleFavorite() }),
                    )
                }

                Spacer(Modifier.height(6.dp))

                if (st.idle) {
                    IdleNotice(st.playerName, st.blank, onBrowse)
                    Spacer(Modifier.height(12.dp))
                }

                TrackTitleBlock(st)

                Spacer(Modifier.height(14.dp))

                SeekRow(scrubber, st.durationMs, playing = st.isPlaying)

                Spacer(Modifier.height(10.dp))

                TransportRow(st, viewModel) { sheets.quality = true }

                Spacer(Modifier.height(18.dp))

                VolumeRow(st.volume) { viewModel.setVolume(it) }
            }

            // Every sheet and card that sits over the player, in one call so the two
            // layouts cannot disagree about their order — see PlayerOverlays.
            PlayerOverlays(st, sheets, viewModel)

            // The long-press quick-actions sheet off the album art. Not folded into
            // PlayerOverlays: it needs the resolved MaItem and the nav callbacks,
            // neither of which that shared function has a reason to carry.
            if (sheets.actions) {
                favouritable?.let { item ->
                    MediaActionsSheet(
                        item = item,
                        onClose = { sheets.actions = false },
                        onPlayNow = {},
                        onPlayNext = {},
                        onAddToQueue = {},
                        onGoToAlbum = {
                            scope.launch {
                                viewModel.resolveAlbum(st.album)?.let(onAlbumClick)
                            }
                        },
                        onGoToArtist = {
                            scope.launch {
                                viewModel.resolveArtist(st.artist)?.let(onArtistClick)
                            }
                        },
                        onShare = {
                            val text = listOf(item.name, st.artist, st.album)
                                .filter { it.isNotBlank() }
                                .joinToString(", ")
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "Share"))
                        },
                    )
                }
            }
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
) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.weight(1f))
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
 * Keep the track that is playing.
 *
 * Downloading used to mean leaving the player, finding the track again in the library
 * and long-pressing it — which is the wrong way round: the moment you decide you want
 * to keep a song is the moment you are listening to it.
 *
 * Absent rather than greyed when there is nothing to download. A chip that can never
 * do anything is worse than no chip: on a Music Assistant speaker there is no file on
 * this phone to keep, and the row is already six chips long.
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
    /** What MA's per-player DSP did to this stream; null on the local path. */
    dsp: MaDspDetails? = null,
    loudness: MaLoudness = MaLoudness(),
    artworkUrl: String? = null,
    title: String = "",
    artist: String = "",
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
                dsp = dsp,
                loudness = loudness,
                artworkUrl = artworkUrl,
                title = title,
                artist = artist,
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
    dsp: MaDspDetails? = null,
    loudness: MaLoudness = MaLoudness(),
    artworkUrl: String? = null,
    title: String = "",
    artist: String = "",
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
    val transcoded = playing != null && source != null && !source.sameFormatAs(playing)
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

            // ── Output ────────────────────────────────────────────────────
            // What happens to the stream after the format is decided: this phone's
            // own 24-bit setting, whether the Android mixer is resampling under it,
            // and — on a Music Assistant player — whether MA's DSP chain ran.
            val deviceRate = remember(bitPerfect) { FormatNegotiator.deviceOutputQuality(bitPerfect) }
            val dspState = dsp?.state
            val showOutput = localSession || dspState != null
            if (showOutput) {
                QualityBlock("Output") {
                    if (localSession) {
                        QualityNote(if (bitPerfect) "Bit-perfect on - 24-bit requested where the source has it" else "Bit-perfect off - 16-bit output")
                        MixerNote(playingRateHz = playing?.sampleRateHz ?: 0, mixerRateHz = deviceRate.sampleRateHz)
                    }
                    // Whether MA's own equaliser is in the path. Worth saying here
                    // because the DSP panel can be full of carefully set bands that
                    // the server is not applying, and nothing else would tell you.
                    if (dsp != null && dspState != null && !localSession) {
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
            val gainLine = source?.gainLabel?.let { tag ->
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
            val loudnessLine = loudness.summary?.takeIf { !localSession }
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
        ) { DeviceDetailCard(playingRateHz = playingRateHz) }
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
private fun DeviceDetailCard(playingRateHz: Int) {
    val accent = LocalAccent.current
    val palette = LocalPalette.current
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
                    MixerNote(playingRateHz = playingRateHz, mixerRateHz = mixerRate)
                    QualityNote(
                        if (bitPerfect) "Bit-perfect on - 24-bit requested where the source has it"
                        else "Bit-perfect off - 16-bit output",
                    )
                    frames?.let { QualityNote("Mixer buffer $it frames") }
                    route.bluetoothCodecNote?.let { QualityNote(it) }
                }
            }
        }
    }
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

