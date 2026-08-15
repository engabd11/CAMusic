package com.engabd.sendpin.ui.screens.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.audio.AudioOutputs
import com.engabd.sendpin.audio.DeviceCapabilities
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Everything between the file and the speaker.
 *
 * This was one card of eleven controls with four paragraphs of explanation stacked
 * under it — sample rates next to ReplayGain next to a connection readout, and no
 * indication which of them applied to which backend. Split into the four questions a
 * listener actually asks, each one saying who it is for.
 */
@Composable
internal fun AudioSection(
    viewModel: PlayerViewModel,
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutputCard(settings, accent, scope)
        StreamingCard(settings, accent, scope)
        LoudnessCard(settings, accent, scope)
        ContinuousPlayCard(settings, scope)
        DiagnosticsCard(viewModel, settings)
        ExperimentalCard(settings, accent, scope)
    }
}

// ── Output ────────────────────────────────────────────────────────────────

/**
 * Where the sound goes, and what that thing can take.
 *
 * The capability readout is the same one the "This phone" card on Now Playing shows,
 * from the same [DeviceCapabilities] — because two places describing the same output
 * differently is worse than only one of them existing.
 */
@Composable
private fun OutputCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    val am = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val outputs = remember { AudioOutputs.list(am) }

    var pinned by remember { mutableStateOf("") }
    var bitPerfect by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        pinned = settings.preferredAudioDeviceId.first()
        bitPerfect = settings.bitPerfect24Bit.first()
    }

    val route = remember(pinned) { DeviceCapabilities.activeRoute(am, pinned) }
    val mixerRate = remember { DeviceCapabilities.mixerRateHz() }

    SettingsCard(
        title = "Output",
        lead = "Where this phone sends audio it decodes itself, and what that output can " +
            "actually accept.",
    ) {
        // Nothing to choose between on a phone with only its own speaker.
        if (outputs.size >= 2) {
            FieldLabel("Output device")
            val labels = listOf("Automatic") + outputs.map { it.label }
            val ids = listOf("") + outputs.map { it.id }
            SegmentedToggleRow(labels, ids.indexOf(pinned).coerceAtLeast(0)) { i ->
                pinned = ids[i]
                scope.launch { settings.setPreferredAudioDeviceId(ids[i]) }
            }
            Note(
                if (pinned.isBlank())
                    "Android picks the route — normally the last thing you plugged in."
                else
                    "Playback is pinned to this output. If it's unplugged, Android routes " +
                        "normally again until it's back.",
            )
        }

        StatusPanel {
            StatusRow("Playing through", route?.label ?: "Unknown")
            route?.sampleRateLabel?.let { StatusRow("Accepts", it) }
            route?.bitDepthLabel?.let { StatusRow("Depth", it) }
            if (mixerRate > 0) StatusRow("Mixer output", "${StreamQuality.khz(mixerRate)} kHz")
        }
        route?.bluetoothCodecNote?.let { Note(it) }

        CardDivider()
        ToggleRow(
            "Bit-perfect (24-bit)",
            "Ask for 24-bit instead of 16 and render whatever depth the decoder reports. " +
                "Costs bandwidth, and a phone whose mixer runs at 16-bit gains nothing — " +
                "leave it off unless you're on a USB DAC.",
            bitPerfect, accent,
        ) { bitPerfect = it; scope.launch { settings.setBitPerfect24Bit(it) } }
        Note(
            "On a library this phone plays, this also turns on float output, so a 24-bit file " +
                "isn't requantised to 16 on its way to the sink. That is fixed when the player " +
                "is built, so it applies next time the app starts. It is off by default because " +
                "float output is experimental and has been heard to distort 44.1 kHz material on " +
                "phones whose mixer runs at 48 — if that happens, turn it back off.",
        )
    }
}

// ── Streaming quality ─────────────────────────────────────────────────────

@Composable
private fun StreamingCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    var preferHiRes by remember { mutableStateOf(true) }
    var preferFlac by remember { mutableStateOf(true) }
    var preferOriginal by remember { mutableStateOf(false) }
    var bitPerfect by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        preferHiRes = settings.preferHiRes.first()
        preferFlac = settings.preferFlac.first()
        preferOriginal = settings.preferOriginal.first()
        bitPerfect = settings.bitPerfect24Bit.first()
    }

    SettingsCard(
        title = "What to ask Music Assistant for",
        lead = "Music Assistant may only send a format this phone has advertised, so these " +
            "decide what it is allowed to choose. 44.1 and 48 kHz are always offered, so " +
            "CD-rate files stream untouched.",
    ) {
        ToggleRow(
            "Offer hi-res rates",
            "Also accept 88.2 and 96 kHz, so hi-res masters aren't downsampled to 48",
            preferHiRes, accent,
        ) { preferHiRes = it; scope.launch { settings.setPreferHiRes(it) } }
        ToggleRow(
            "Prefer FLAC over PCM",
            "Lossless either way — FLAC uses about half the bandwidth",
            preferFlac, accent,
        ) { preferFlac = it; scope.launch { settings.setPreferFlac(it) } }
        ToggleRow(
            "Play at original quality",
            "When Music Assistant would have to resample, stream straight from Navidrome " +
                "instead. Plays on this phone only — no queue, no grouping.",
            preferOriginal, accent,
        ) { preferOriginal = it; scope.launch { settings.setPreferOriginal(it) } }
        Note(
            "Output is ${if (bitPerfect) "up to 24-bit" else "16-bit"} — see Output above. " +
                "Reconnect to apply a change here.",
        )
    }
}

// ── Loudness ──────────────────────────────────────────────────────────────

@Composable
private fun LoudnessCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    var mode by remember { mutableStateOf(ReplayGain.ALBUM) }
    LaunchedEffect(Unit) { mode = settings.replayGainMode.first() }

    SettingsCard(
        title = "Loudness",
        lead = "A 1985 master and a 2015 one can land 10 dB apart. ReplayGain uses the level " +
            "tags in your files to even that out.",
    ) {
        SegmentedToggleRow(ReplayGainLabels, ReplayGainValues.indexOf(mode).coerceAtLeast(0)) {
            mode = ReplayGainValues[it]
            scope.launch { settings.setReplayGainMode(ReplayGainValues[it]) }
        }
        Note(
            when (mode) {
                ReplayGain.ALBUM -> "Levels whole albums against each other and keeps the " +
                    "dynamics within one. What you want for records."
                ReplayGain.TRACK -> "Levels every track to the same loudness. Better for " +
                    "shuffled singles; flattens an album's quiet passages."
                else -> "Play files at their mastered level."
            },
        )
        Note(
            "Applies to the library this phone plays, and to downloads. Music Assistant does " +
                "its own gain server-side, so this would double it there. Boosts are capped at " +
                "+${ReplayGain.MAX_BOOST_DB.toInt()} dB to avoid clipping.",
        )
    }
}

// ── Between tracks ────────────────────────────────────────────────────────

@Composable
private fun ContinuousPlayCard(settings: AppSettings, scope: CoroutineScope) {
    val fade by settings.navFadeSeconds.collectAsStateWithLifecycle(initialValue = 0)

    SettingsCard(
        title = "Between tracks",
        lead = "What happens in the gap between one track and the next.",
    ) {
        FieldLabel("Smooth transitions")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HSlider(
                value = fade / 12f,
                onChange = {},
                onCommit = { f -> scope.launch { settings.setNavFadeSeconds(Math.round(f * 12f)) } },
                accented = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (fade == 0) "Off" else "${fade}s",
                color = TextSecondary, fontFamily = MonoFont,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Note(
            "Fades one track out and the next in, on the player this phone runs. Off is " +
                "gapless, which is what an album wants — so this is suppressed automatically " +
                "while the queue is a single record, however it is set. It is not a crossfade: " +
                "the two tracks don't overlap, because one player has one output.",
        )
        Note(
            "What happens when the queue runs out altogether is now a player setting rather " +
                "than an app one — the options chip under the artwork on Now Playing, where " +
                "it sits next to the queue it governs.",
        )
    }
}

// ── Diagnostics ───────────────────────────────────────────────────────────

@Composable
private fun DiagnosticsCard(viewModel: PlayerViewModel, settings: AppSettings) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val currentFormat by viewModel.currentFormat.collectAsStateWithLifecycle()
    var playerName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { playerName = settings.playerName.first() }

    SettingsCard(
        title = "Diagnostics",
        lead = "What the Music Assistant connection is actually doing right now. Worth quoting " +
            "in a bug report.",
    ) {
        StatusPanel {
            StatusRow("Connection", if (connected) "Connected" else "Disconnected")
            StatusRow("Format", currentFormat)
            StatusRow("Player ID", viewModel.playerId.take(12) + "…")
            StatusRow("Player name", playerName.ifBlank { viewModel.deviceName })
            StatusRow("Device", viewModel.deviceName)
        }
    }
}

// ── Experimental ─────────────────────────────────────────────────────────

/**
 * Player-internals switches not ready for general use. See
 * `docs/exoplayer-upgrade-plan.md`: [AppSettings.useExoPlayerForSendspin] routes
 * MA playback through ExoPlayer instead of the default hand-built
 * MediaCodec+AudioTrack engine; [AppSettings.useOboeOutput] (only meaningful
 * with that one already on) further routes decoded audio through a native Oboe
 * engine instead of the platform AudioTrack. Both take effect on the next MA
 * connection, not retroactively on whatever's already playing.
 */
@Composable
private fun ExperimentalCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    var useExoPlayer by remember { mutableStateOf(false) }
    var useOboe by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        useExoPlayer = settings.useExoPlayerForSendspin.first()
        useOboe = settings.useOboeOutput.first()
    }

    SettingsCard(
        title = "Experimental",
        lead = "Not ready for everyday use. Turn these on only if you're specifically testing them.",
    ) {
        ToggleRow(
            "ExoPlayer for MA playback",
            "Replaces the MA (Sendspin) audio engine with ExoPlayer. Unvalidated: expect " +
                "playback glitches, and disconnect/reconnect if it misbehaves. Takes effect on " +
                "the next MA connection.",
            useExoPlayer, accent,
        ) { useExoPlayer = it; scope.launch { settings.setUseExoPlayerForSendspin(it) } }
        CardDivider()
        ToggleRow(
            "Native Oboe output for MA",
            "Only applies with ExoPlayer for MA playback on. Routes decoded audio through a " +
                "native engine instead of the platform AudioTrack. Far less tested than the " +
                "ExoPlayer switch above — only 16-bit PCM is supported, and it does not yet " +
                "combine with Bit-perfect (24-bit) above. Expect rough edges.",
            useOboe, accent,
        ) { useOboe = it; scope.launch { settings.setUseOboeOutput(it) } }
    }
}
