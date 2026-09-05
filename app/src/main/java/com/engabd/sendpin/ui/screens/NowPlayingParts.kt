package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.CoverPaletteOverride
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.titleMarquee
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What identifies the album on show, for [AlbumArt]'s page-turn.
 *
 * Album *name* plus artist, not the artwork url and not the album name alone. The url
 * changes between two tracks of one record wherever the server hands out per-track
 * artwork, and flipping there turns the page onto the same sleeve. The name alone
 * collides — every library has several "Greatest Hits". There is no third option:
 * `NowPlayingViewModel.State` carries no album id, because Music Assistant tracks do
 * not have one (see the album lookup in that file, which falls back to searching by
 * name for exactly this reason).
 *
 * Null while the player is idle, so coming back from nothing fades rather than flips.
 */
/**
 * A level that depends on whether the player is idle, eased rather than switched.
 *
 * The player dims when nothing is playing and comes back up when something does. Both
 * ends were written as literals in the layout, so the cover, its glow and the wash
 * behind all stepped between them in a single frame. On an `effects` spec, because
 * every one of them is an alpha — see [Motion].
 */
@Composable
internal fun idleFade(idle: Boolean, dimmed: Float, lit: Float = 1f): State<Float> =
    animateFloatAsState(if (idle) dimmed else lit, Motion.effects(), label = "idleFade")

internal fun albumFlipKey(st: NowPlayingViewModel.State): String? =
    if (st.idle || (st.album.isBlank() && st.artist.isBlank())) null
    else "${st.album}|${st.artist}"

/**
 * The parts both player layouts are built from.
 *
 * There are two players — [NowPlayingScreen] as a tab, [NowPlayingOverlay] as a cover
 * that slides over the app — and they were near-duplicates: the same scrub state, the
 * same seek bar, the same transport row, the same volume row, the same five helpers,
 * copied. Every player change had to be made twice, and the copies had already drifted
 * (the shared `TopBar` was two functions until recently).
 *
 * What stays *un*shared is deliberate. The two chip rows differ in order and in which
 * chips they carry, and the two skeletons differ in the whole reason the second layout
 * exists — one is a fixed column, the other is dragged. Folding those into one
 * parameterised composable would be a config object standing in for a design decision.
 * Everything below is identical between them, which is exactly the test for whether it
 * belongs here.
 */

// ── Scrubbing ─────────────────────────────────────────────────────────────

/**
 * The seek bar's position, and the two pieces of state that keep it honest.
 *
 * Position comes from the ViewModel's server-anchored ticker, so there is no local
 * interpolation loop. [scrubbing] freezes the bar while a finger is down, so a poll
 * landing mid-drag cannot fight it. After release, the target is held until the server
 * catches up — without that hold the bar snaps back to the old position for the
 * duration of the seek round-trip, which reads as the seek having failed.
 */
@Stable
class Scrubber internal constructor(
    private val onSeek: (Float) -> Unit,
) {
    internal var scrubbing by mutableStateOf(false)
    internal var scrubPos by mutableStateOf(0L)
    /** -1 means no hold is in force. */
    internal var seekTarget by mutableStateOf(-1L)
    internal var livePos by mutableStateOf(0L)

    /** Where the bar should be drawn, whoever is currently deciding that. */
    val positionMs: Long
        get() = when {
            scrubbing -> scrubPos
            seekTarget >= 0 -> seekTarget
            else -> livePos
        }

    internal fun onDrag(fraction: Float, durationMs: Long) {
        scrubbing = true
        scrubPos = (fraction * durationMs).toLong()
    }

    internal fun onRelease(fraction: Float, durationMs: Long) {
        scrubPos = (fraction * durationMs).toLong()
        seekTarget = scrubPos
        scrubbing = false
        onSeek(fraction)
    }
}

@Composable
fun rememberScrubber(viewModel: NowPlayingViewModel): Scrubber {
    val scrubber = remember(viewModel) { Scrubber(viewModel::seekTo) }
    val live by viewModel.positionMs.collectAsStateWithLifecycle()
    scrubber.livePos = live

    // A new track invalidates any seek hold left over from the track that just ended —
    // the hold exists to survive a seek's round trip, not a boundary that starts a
    // fresh track at 0. Without this, skipping right after a seek (before the server
    // confirms) pins the bar at the old position for the whole next track.
    val trackIdFlow = remember(viewModel) {
        viewModel.state.map { it.currentQueueItemId }.distinctUntilChanged()
    }
    val trackId by trackIdFlow.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(trackId) { scrubber.seekTarget = -1L }

    // Release the hold once the server position catches up within two seconds.
    LaunchedEffect(live, scrubber.seekTarget) {
        if (scrubber.seekTarget >= 0 && !scrubber.scrubbing &&
            kotlin.math.abs(live - scrubber.seekTarget) < 2_000L
        ) {
            scrubber.seekTarget = -1L
        }
    }
    return scrubber
}

// ── Rows ──────────────────────────────────────────────────────────────────

/** The seek bar and the two times under it. */
@Composable
fun SeekRow(scrubber: Scrubber, durationMs: Long, playing: Boolean = false) {
    val progress =
        if (durationMs > 0) (scrubber.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // "Wave" is an Appearance setting rather than a parameter callers pass in — one
    // read here, rather than every caller of a row this small threading a style
    // through from Settings on its own.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.engabd.sendpin.data.AppSettings(context) }
    val style by settings.seekBarStyle.collectAsStateWithLifecycle(initialValue = "line")
    when (style) {
        "wave" -> WaveSeekBar(
            progress,
            onChange = { scrubber.onDrag(it, durationMs) },
            playing = playing,
            onCommit = { scrubber.onRelease(it, durationMs) },
            label = { fmtTime((it * durationMs).toLong()) },
        )
        "pill" -> PillSeekBar(
            progress,
            onChange = { scrubber.onDrag(it, durationMs) },
            onCommit = { scrubber.onRelease(it, durationMs) },
            label = { fmtTime((it * durationMs).toLong()) },
        )
        "glow" -> GlowSeekBar(
            progress,
            onChange = { scrubber.onDrag(it, durationMs) },
            playing = playing,
            onCommit = { scrubber.onRelease(it, durationMs) },
            label = { fmtTime((it * durationMs).toLong()) },
        )
        else -> HSlider(
            progress,
            onChange = { scrubber.onDrag(it, durationMs) },
            onCommit = { scrubber.onRelease(it, durationMs) },
            label = { fmtTime((it * durationMs).toLong()) },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TimeText(fmtTime(scrubber.positionMs))
        TimeText(if (durationMs > 0) fmtTime(durationMs) else "--:--")
    }
}

/**
 * Shuffle, previous, play, next, repeat — with the quality badge floating above the
 * play button.
 *
 * The transport icons are aligned to the vertical center of the play button (not the
 * bottom of the column), so they sit on the same horizontal line. The quality badge
 * is positioned to be vertically centered between the play button and the seek line.
 */
@Composable
fun TransportRow(
    state: NowPlayingViewModel.State,
    viewModel: NowPlayingViewModel,
    onShowQuality: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Floating quality badge positioned above the PlayButton without shifting
        // the vertical alignment of the surrounding transport icons.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-32).dp),
        ) {
            TappableQualityChip(playing = state.quality, onClick = onShowQuality)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, state.shuffle) { viewModel.toggleShuffle() }
            TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }
            PlayButton(state.isPlaying) { viewModel.playPause() }
            TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
            TransportIcon(
                if (state.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                "Repeat", 20.dp, state.repeatMode != "off",
            ) { viewModel.cycleRepeat() }
        }
    }
}

/** The volume slider, and the number that says where it is. */
@Composable
fun VolumeRow(volume: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = TextMuted, modifier = Modifier.size(16.dp))
        HSlider(
            volume.coerceIn(0f, 1f), onChange,
            modifier = Modifier.weight(1f), knob = 12.dp, accented = false,
        )
        Text(
            "${(volume * 100).toInt()}", color = TextMuted, fontFamily = MonoFont,
            fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.width(22.dp), textAlign = TextAlign.End,
        )
    }
}

/**
 * Title, artist, composer, album.
 *
 * [showComposer] because only the tab layout has the height for it — a fourth line of
 * metadata on the overlay pushes the transport under the fold on a short phone.
 */
@Composable
fun TrackTitleBlock(state: NowPlayingViewModel.State, showComposer: Boolean = true) {
    // Each line is its own full-width row so the *box* is centred and the text inside it
    // is free to scroll. Centring the Text itself instead would make a long title jump
    // left the moment the marquee engaged — see [titleMarquee].
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            if (state.blank) "Nothing playing" else state.title,
            color = if (state.idle) TextSecondary else TextPrimary,
            fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
            fontSize = 27.sp, letterSpacing = (-0.5).sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.titleMarquee(),
        )
    }
    if (state.artist.isNotBlank()) {
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                state.artist, color = inkOn(0.62f), fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.titleMarquee(),
            )
        }
    }
    if (showComposer && state.composer.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            state.composer, color = TextFaint, fontFamily = AppFont, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
    if (state.album.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                state.album, color = TextFaint, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.titleMarquee(),
            )
        }
    }
}

// ── Everything drawn over the player ──────────────────────────────────────

/**
 * What the cover's own slot is showing — the sleeve, the lyrics, or the live
 * visualizer. All three take the slot's *place* rather than sitting over it, the
 * same way [PlayerSheetState] governs what sits over the rest of the player.
 * Shared between the tab and overlay layouts so a mode chosen in one reads the
 * same in the other.
 */
enum class CoverSlot { ART, LYRICS, VISUALIZER }

/** Which sheet is up, if any. Hoisted so the gesture handlers can stand down. */
@Stable
class PlayerSheetState {
    var panel by mutableStateOf<Panel?>(null)
    var options by mutableStateOf(false)
    var speakers by mutableStateOf(false)
    var quality by mutableStateOf(false)
    var device by mutableStateOf(false)
    /** The long-press quick-actions sheet off the album art. */
    var actions by mutableStateOf(false)
    /** Cover-palette editor for Light Sync. */
    var palette by mutableStateOf(false)

    /** A sheet — not an overlay card — is up, so swipes belong to it. */
    val sheetOpen: Boolean get() = panel != null || options || speakers || actions || palette

    fun closeSheets() { panel = null; options = false; speakers = false; actions = false; palette = false }
}

@Composable
fun rememberPlayerSheets(): PlayerSheetState = remember { PlayerSheetState() }

/**
 * The sheets and cards that sit over the player.
 *
 * All five in one place because the *order* matters — the dismiss scrim has to be
 * under the sheets and over the player — and getting that order right twice is
 * exactly the kind of thing that drifts between two copies of a screen.
 *
 * The quality and device cards are drawn as overlay siblings rather than as sheets;
 * see `QualityDetailOverlay` for the layout reason, which is load-bearing.
 */
@Composable
fun BoxScope.PlayerOverlays(
    state: NowPlayingViewModel.State,
    sheets: PlayerSheetState,
    viewModel: NowPlayingViewModel,
    /** The currently favouritable item, so the actions sheet can be rendered here too. */
    favouritable: MaItem? = null,
    /** Navigation callbacks for go-to-album / go-to-artist actions. */
    onAlbumClick: (MaItem) -> Unit = {},
    onArtistClick: (MaItem) -> Unit = {},
    /** The settled cover url, for the palette editor's seed. */
    coverUrl: String? = null,
) {
    if (sheets.sheetOpen) {
        // Anything still visible above a sheet dismisses it, and so does system Back —
        // otherwise Back would leave the screen entirely with a panel open over it.
        BackHandler { sheets.closeSheets() }
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { sheets.closeSheets() }
        )
    }
    sheets.panel?.let {
        // The session decides which equaliser the DSP panel shows. Read here rather
        // than inside the sheet so both layouts - tab and overlay - answer the same.
        val local = viewModel.state.collectAsStateWithLifecycle().value.isLocalSession
        NowPlayingSheet(
            onClose = { sheets.panel = null },
            viewModel = viewModel,
            panel = it,
            localSession = local,
        )
    }
    if (sheets.options) PlayerOptionsSheet(onClose = { sheets.options = false }, viewModel = viewModel)
    if (sheets.speakers) SpeakerPickerSheet(onClose = { sheets.speakers = false })

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { AppSettings(context) }

    if (sheets.actions) {
        // Not `favouritable?.let`. That gated the whole sheet — and with it the only
        // way to the Light Sync palette editor — on whether some server could *star*
        // this track, which is a question about favourites and nothing to do with
        // any action in here. On a local file with no library behind it
        // `favouritableItem` is null for the entire session, so the long-press did
        // nothing at all and the palette editor was unreachable.
        //
        // Everything this sheet actually does works off the player's own state:
        // go-to-album and go-to-artist resolve by name, share builds its text from
        // the same three fields, and the palette editor keys off the album and the
        // cover. So a describable item is enough, and one is synthesised when there
        // is no favouritable one. The heart on the player still reads
        // `favouritableItem`, which is the control that genuinely needs it.
        val item = favouritable ?: state.takeIf { !it.idle }?.let {
            MaItem(
                itemId = "",
                provider = "",
                name = it.title,
                uri = null,
                mediaType = "track",
                subtitle = it.artist.takeIf { a -> a.isNotBlank() },
                image = it.artworkUrl,
                duration = null,
            )
        }
        item?.let { subject ->
            MediaActionsSheet(
                item = subject,
                onClose = { sheets.actions = false },
                onGoToAlbum = {
                    scope.launch {
                        viewModel.resolveAlbum(state.album)?.let(onAlbumClick)
                    }
                },
                onGoToArtist = {
                    scope.launch {
                        viewModel.resolveArtist(state.artist)?.let(onArtistClick)
                    }
                },
                onMoreLikeThis = {
                    sheets.actions = false
                    sheets.panel = Panel.SIMILAR
                },
                onShare = {
                    val text = listOf(item.name, state.artist, state.album)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share"))
                },
                onEditLightSyncColours = {
                    sheets.actions = false
                    sheets.palette = true
                },
            )
        }
    }

    if (sheets.palette) {
        // The keys this album's override could be filed under, built once and used
        // for both halves — reading what is already saved, and writing the new one.
        // Two lists built separately is how the editor and the engine came to
        // disagree in the first place.
        //
        // `remember` with no key, inside this `if`, pins them to the album that was
        // playing when the sheet opened: this block enters composition on open and
        // leaves it on close, so the pin lasts exactly as long as the edit. Without
        // it the next track to start would re-target the sheet mid-edit — the seed
        // would be re-read from another cover and the save would land on the new
        // track's keys rather than the one whose colours are on screen.
        // Read off the *light sync source*, not off this screen's own state: the
        // engine builds its lookup keys from that object and nothing else, and
        // `LocalTrack.id` is not `MaItem.itemId` — so a save keyed on what Now
        // Playing happened to be displaying could miss on every key at once. This is
        // the same list `LightSyncScreen` builds, from the same fields.
        //
        // Collected rather than read as `.value`. The pin is what `remember` does,
        // not what the read does: a `.value` inside composition never observes the
        // flow at all, so the sheet would have been seeded from whatever the source
        // happened to hold on the frame it opened and would never have corrected
        // itself if that arrived a frame later — which on a backend handover it does.
        // (It is also the one thing `StateFlowValueCalledInComposition` exists to
        // catch, and `LightSyncScreen` carries a comment about losing this same
        // argument once already.)
        val lightSource by (LocalContext.current.applicationContext as SendpinApp)
            .activeLightSyncSource.collectAsStateWithLifecycle()
        val paletteKeys = remember {
            CoverPaletteOverride.keysFor(
                album = lightSource.paletteAlbum,
                artist = lightSource.paletteArtist,
                coverUrl = lightSource.artUrl ?: coverUrl,
                trackId = lightSource.scanTrack?.id,
            ).ifEmpty {
                // Nothing is driving the lights — the sheet is still usable, and the
                // save should still land somewhere the engine will find it when
                // something starts playing.
                CoverPaletteOverride.keysFor(
                    album = state.album,
                    artist = state.artist,
                    coverUrl = coverUrl,
                    trackId = favouritable?.itemId,
                )
            }
        }
        val pinnedAlbum = remember { state.album.takeIf { it.isNotBlank() } ?: favouritable?.name ?: "" }
        val pinnedArtist = remember { state.artist.takeIf { it.isNotBlank() } ?: favouritable?.subtitle }
        val pinnedCover = remember { lightSource.artUrl ?: coverUrl }
        val overrides by settings.coverPaletteOverrides.collectAsStateWithLifecycle(
            initialValue = emptyMap(),
        )
        CoverPaletteEditor(
            albumName = pinnedAlbum,
            artistName = pinnedArtist,
            coverUrl = pinnedCover,
            // Open showing what is actually on the room, so this is an editor for an
            // existing correction and not only a way to start a new one.
            existing = paletteKeys.firstNotNullOfOrNull { key ->
                overrides[key]?.takeIf { it.colors.isNotEmpty() }
            },
            onSave = { override ->
                // Every key the engine might look under, not just the best one.
                // Filing under one name while the engine reads another is a palette
                // that saves and never applies — see
                // AppSettings.setCoverPaletteOverrideForKeys.
                if (paletteKeys.isNotEmpty()) {
                    scope.launch { settings.setCoverPaletteOverrideForKeys(paletteKeys, override) }
                }
                sheets.palette = false
            },
            onClose = { sheets.palette = false },
        )
    }

    QualityDetailOverlay(
        visible = sheets.quality,
        playing = state.quality,
        source = state.sourceQuality,
        onDismiss = { sheets.quality = false },
        provider = state.streamProvider,
        localSession = state.isLocalSession,
        serverPlayer = state.serverPlayer,
        dsp = state.dsp,
        loudness = state.loudness,
        artworkUrl = state.artworkUrl,
        title = state.title,
        artist = state.artist,
    )
    DeviceDetailOverlay(
        visible = sheets.device,
        onDismiss = { sheets.device = false },
        playingRateHz = state.quality?.sampleRateHz ?: 0,
        serverPlayer = state.serverPlayer,
        serverOutputDeviceName = state.serverOutputDeviceName,
        serverOutputFormat = state.serverOutputFormat,
    )
}

// ── Small shared pieces ───────────────────────────────────────────────────

@Composable
internal fun TransportIcon(
    icon: ImageVector,
    cd: String,
    size: Dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        // Fixed footprint so Bloom's larger radius (only present when active) can't
        // change this icon's measured size and reflow its SpaceBetween siblings.
        Modifier.size(size + 12.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) Bloom(accent, size * 1.8f, 0.dp, 0.dp, 0.5f)
        Icon(icon, cd, tint = if (active) accent else inkOn(0.9f), modifier = Modifier.size(size))
    }
}

@Composable
internal fun TimeText(text: String) {
    Text(text, color = TextMuted, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}

internal fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(WarnAmber.a(0.14f)).border(1.dp, WarnAmber.a(0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = WarnAmber, modifier = Modifier.size(15.dp))
        Text("Reconnecting…", color = Color(0xFFF2C574), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Shown in place of nothing when the player is quiet. It sits inside the normal
 * layout so the screen never changes shape — the art above it is the last thing that
 * played, dimmed, and everything else stays where the user left it.
 */
@Composable
internal fun IdleNotice(playerName: String, blank: Boolean, onBrowse: () -> Unit) {
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
