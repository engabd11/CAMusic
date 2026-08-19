package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

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
fun SeekRow(scrubber: Scrubber, durationMs: Long) {
    val progress =
        if (durationMs > 0) (scrubber.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    HSlider(
        progress,
        onChange = { scrubber.onDrag(it, durationMs) },
        onCommit = { scrubber.onRelease(it, durationMs) },
        label = { fmtTime((it * durationMs).toLong()) },
    )
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(Icons.Default.Shuffle, "Shuffle", 20.dp, state.shuffle) { viewModel.toggleShuffle() }\n        TransportIcon(Icons.Default.SkipPrevious, "Previous", 26.dp) { viewModel.previous() }\n        Column(\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.spacedBy(4.dp),\n        ) {\n            TappableQualityChip(playing = state.quality, onClick = onShowQuality)\n            PlayButton(state.isPlaying) { viewModel.playPause() }\n        }\n        TransportIcon(Icons.Default.SkipNext, "Next", 26.dp) { viewModel.next() }
        TransportIcon(
            if (state.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
            "Repeat", 20.dp, state.repeatMode != "off",
        ) { viewModel.cycleRepeat() }
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
    Text(
        if (state.blank) "Nothing playing" else state.title,
        color = if (state.idle) TextSecondary else TextPrimary,
        fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp, letterSpacing = (-0.5).sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    )
    if (state.artist.isNotBlank()) {
        Spacer(Modifier.height(5.dp))
        Text(
            state.artist, color = inkOn(0.62f), fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
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
        Text(
            state.album, color = TextFaint, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

// ── Everything drawn over the player ──────────────────────────────────────

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

    /** A sheet — not an overlay card — is up, so swipes belong to it. */
    val sheetOpen: Boolean get() = panel != null || options || speakers || actions

    fun closeSheets() { panel = null; options = false; speakers = false; actions = false }
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
    sheets.panel?.let { NowPlayingSheet(onClose = { sheets.panel = null }, viewModel = viewModel, panel = it) }
    if (sheets.options) PlayerOptionsSheet(onClose = { sheets.options = false }, viewModel = viewModel)
    if (sheets.speakers) SpeakerPickerSheet(onClose = { sheets.speakers = false })

    QualityDetailOverlay(
        visible = sheets.quality,
        playing = state.quality,
        source = state.sourceQuality,
        onDismiss = { sheets.quality = false },
        provider = state.streamProvider,
        localSession = state.isLocalSession,
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
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp),
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
