package com.engabd.sendpin.ui.screens

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.engabd.sendpin.audio.DeviceCapabilities
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaDspDetails
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
    onOpenSpeakers: () -> Unit = {},
    onBrowse: () -> Unit = {},
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Which overlay is open, if any. Null = just the player.
    var panel by remember { mutableStateOf<Panel?>(null) }
    var options by remember { mutableStateOf(false) }
    // The output picker. Local state rather than a nav route: switching speaker
    // should not take the user off the screen showing what's playing.
    var speakers by remember { mutableStateOf(false) }
    // Lyrics are a *mode* of the player, not an overlay: they take the cover's place.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // Hoisted out of the badge so the detail can be drawn as a sibling of the whole
    // player rather than inside the transport row — see QualityDetailOverlay.
    var showQuality by remember { mutableStateOf(false) }
    // What this phone's output can do. Hoisted for the same reason, and drawn as the
    // same kind of sibling — see DeviceDetailOverlay.
    var showDevice by remember { mutableStateOf(false) }

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

    // Position is driven by the ViewModel's server-anchored ticker — no local
    // interpolation loop needed. `scrubbing` freezes the bar so a poll landing
    // mid-drag can't fight the finger. After release, `seekTarget` holds the
    // target position until the server catches up (within 2s), preventing the
    // bar from snapping back during the seek round-trip.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableStateOf(0L) }
    var seekTarget by remember { mutableStateOf(-1L) }  // -1 = no hold
    val livePos by viewModel.positionMs.collectAsStateWithLifecycle()

    // Release the hold once the server position catches up within 2 seconds.
    LaunchedEffect(livePos, seekTarget) {
        if (seekTarget >= 0 && !scrubbing && kotlin.math.abs(livePos - seekTarget) < 2_000L) {
            seekTarget = -1L
        }
    }

    val pos = when {
        scrubbing -> scrubPos
        seekTarget >= 0 -> seekTarget
        else -> livePos
    }
    val dur = st.durationMs
    val progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

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
                .pointerInput(panel, options, speakers) {
                    if (panel != null || options || speakers) return@pointerInput
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragCancel = { travelled = 0f },
                        onDragEnd = { if (travelled < -queueRevealPx) panel = Panel.QUEUE },
                        onVerticalDrag = { _, amount -> travelled += amount },
                    )
                }
        ) {
            // The screen keeps its shape whether or not anything is playing: an
            // idle player shows the last track it had (dimmed, with a notice)
            // rather than swapping the whole screen for an empty state.
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
                    onTap = { if (st.isLocalSession) showDevice = true else speakers = true },
                )

                Spacer(Modifier.height(4.dp))

                // Album art fills the available space, shrinking on small screens
                // and growing on large ones — no scroll needed.
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

                // Secondary actions — track-scoped chips only; the quality badge
                // has moved to the transport row (between play and the seek bar).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconChip(Icons.Default.Lyrics, "Lyrics", active = showLyrics) {
                        showLyrics = !showLyrics
                    }
                    SleepTimerChip(viewModel)
                    IconChip(Icons.AutoMirrored.Filled.QueueMusic, "Queue", active = panel == Panel.QUEUE) {
                        panel = if (panel == Panel.QUEUE) null else Panel.QUEUE
                    }
                    IconChip(Icons.Default.Tune, "Player options", active = options) { options = !options }
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
                        IconChip(Icons.Default.GraphicEq, "DSP / Equalizer", active = panel == Panel.DSP) {
                            panel = if (panel == Panel.DSP) null else Panel.DSP
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
                        tint = if (currentItem == null) TextFaint else null,
                        onClick = if (currentItem == null) null else ({ viewModel.toggleFavorite() }),
                    )
                }

                Spacer(Modifier.height(14.dp))

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
                        st.artist, color = inkOn(0.62f), fontFamily = AppFont,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                if (st.composer.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        st.composer, color = TextFaint, fontFamily = AppFont, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                if (st.album.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        st.album, color = TextFaint, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(20.dp))

                HSlider(
                    progress,
                    onChange = { f -> scrubbing = true; scrubPos = (f * dur).toLong() },
                    onCommit = { f ->
                        scrubPos = (f * dur).toLong()
                        seekTarget = scrubPos
                        scrubbing = false
                        viewModel.seekTo(f)
                    },
                    label = { f -> fmtTime((f * dur).toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeText(fmtTime(pos))
                    TimeText(if (dur > 0) fmtTime(dur) else "--:--")
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, st.shuffle) { viewModel.toggleShuffle() }
                    TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }
                    // Quality badge floats above the play button. The Column is
                    // bottom-aligned so the play button lines up with the skip buttons.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TappableQualityChip(playing = st.quality) { showQuality = true }
                        PlayButton(st.isPlaying) { viewModel.playPause() }
                    }
                    TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
                    TransportIcon(
                        if (st.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat", 20.dp, st.repeatMode != "off",
                    ) { viewModel.cycleRepeat() }
                }

                Spacer(Modifier.height(18.dp))

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

            // The overlays sit in the same Box as the player, so the album wash
            // still reads behind their top edge. Anything still visible above a
            // sheet dismisses it, and so does system Back — otherwise Back would
            // leave the screen entirely with a panel open over it.
            if (panel != null || options || speakers) {
                BackHandler { panel = null; options = false; speakers = false }
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { panel = null; options = false; speakers = false }
                )
            }
            if (panel != null) {
                NowPlayingSheet(onClose = { panel = null }, viewModel = viewModel, panel = panel!!)
            }
            if (options) {
                PlayerOptionsSheet(onClose = { options = false }, viewModel = viewModel)
            }
            if (speakers) {
                SpeakerPickerSheet(onClose = { speakers = false })
            }
            // A sibling of the player column, not a child of it — this is what stops
            // the panel moving the album art. See QualityDetailOverlay.
            QualityDetailOverlay(
                visible = showQuality,
                playing = st.quality,
                source = st.sourceQuality,
                onDismiss = { showQuality = false },
                provider = st.streamProvider,
                localSession = st.isLocalSession,
                dsp = st.dsp,
                loudness = st.loudness,
                artworkUrl = st.artworkUrl,
                title = st.title,
                artist = st.artist,
            )
            DeviceDetailOverlay(
                visible = showDevice,
                onDismiss = { showDevice = false },
                playingRateHz = st.quality?.sampleRateHz ?: 0,
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
 */
@Composable
internal fun TopBar(
    playerName: String,
    isSelf: Boolean,
    groupSize: Int,
    localSession: Boolean,
    onTap: () -> Unit,
) {
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

/** A small badge at the corner indicating the source (MA or Navidrome). */
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
        )
    }
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
        Icon(icon, cd, tint = if (active) accent else inkOn(0.9f), modifier = Modifier.size(size))
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
            "Reconnecting…", color = Color(0xFFF2C574),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * Shown in place of nothing when the player is quiet. It sits inside the normal
 * layout so the screen never changes shape — the art above it is the last thing
 * that played, dimmed, and everything else stays where the user left it.
 */
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
            if (blank) "Nothing playing on $playerName - browse" else "Nothing playing - browse",
            color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
