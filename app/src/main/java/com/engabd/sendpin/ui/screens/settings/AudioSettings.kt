package com.engabd.sendpin.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
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
import com.engabd.sendpin.data.rememberIsIgnoringBatteryOptimizations
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.InfoChip
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.WarnAmber
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
        LoudnessCard(settings, accent, scope)
        ContinuousPlayCard(settings, accent, scope)
        // Here rather than in a section of its own: it is about how playback is
        // *controlled* on this device, which is what this section already is, and a
        // top-level entry for one card is the shape the "CAMusic player" section
        // was just removed for being.
        DrivingCard(settings, accent, scope)
        // The Music Assistant connection readout is no longer here. It described one
        // phone's registration with one server, and it now sits on that server's own
        // page beside the settings it reports the effect of — see
        // PlayerSettings.MaPlayerStatusCard. This section is the device-wide half:
        // where sound goes, how loud, and what happens between tracks, none of which
        // depends on which server is connected.
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
                    "Pinned to this output. Unplug it and Android routes normally again.",
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
            title = "Bit-perfect (24-bit)",
            subtitle = "Ask for 24-bit instead of 16",
            checked = bitPerfect,
            accent = accent,
            info = "Renders whatever depth the decoder reports rather than flattening to 16. " +
                "It costs bandwidth, and a phone whose mixer runs at 16-bit gains nothing from " +
                "it — leave it off unless you are on a USB DAC.\n\n" +
                "On a library this phone plays, it also turns on float output, so a 24-bit file " +
                "isn't requantised to 16 on its way to the sink. That is fixed when the player " +
                "is built, so it applies next time the app starts. It is off by default because " +
                "float output is experimental and has been heard to distort 44.1 kHz material " +
                "on phones whose mixer runs at 48 — if that happens, turn it back off.",
        ) { bitPerfect = it; scope.launch { settings.setBitPerfect24Bit(it) } }
    }
}

// ── Streaming quality ─────────────────────────────────────────────────────

/**
 * What this phone advertises to Music Assistant.
 *
 * `internal` because the Music Assistant server's own page renders it too — these are
 * per-server preferences (`ServerConfig.OPT_PREFER_*`), so they belong with that
 * server, and the card's own title has always said so.
 */
@Composable
internal fun StreamingCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
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
        lead = "What this phone is willing to be sent.",
        info = "Music Assistant may only send a format this phone has advertised, so these " +
            "decide what it is allowed to choose. 44.1 and 48 kHz are always offered " +
            "whatever you set here, so CD-rate files stream untouched.",
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
            title = "Play at original quality",
            subtitle = "Bypass Music Assistant rather than let it resample",
            checked = preferOriginal,
            accent = accent,
            info = "When Music Assistant would have to resample a file, the app streams it " +
                "straight from Navidrome instead, exactly as it is stored.\n\n" +
                "The trade is that it plays on this phone only: going direct means going " +
                "around the server, so there is no queue on it and no speaker grouping while " +
                "a track is playing this way.",
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
            "Applies to the library this phone plays, and to downloads.",
            title = "ReplayGain",
            info = "Not to anything Music Assistant streams: it does its own gain server-side, " +
                "so applying this as well would double it.\n\n" +
                "Boosts are capped at +${ReplayGain.MAX_BOOST_DB.toInt()} dB, which is where " +
                "raising a quiet master far enough starts to clip.",
        )
    }
}

// ── Between tracks ────────────────────────────────────────────────────────

@Composable
private fun ContinuousPlayCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val fade by settings.navFadeSeconds.collectAsStateWithLifecycle(initialValue = 0)
    val beatMatched by settings.beatMatchedCrossfade.collectAsStateWithLifecycle(initialValue = false)

    SettingsCard(
        title = "Between tracks",
        lead = "What happens in the gap between one track and the next.",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FieldLabel("Smooth transitions")
            InfoChip(
                "Smooth transitions",
                "Fades one track out and the next in, on the player this phone runs.\n\n" +
                    "Off is gapless, which is what an album wants — so this is suppressed " +
                    "automatically while the queue is a single record, however it is set " +
                    "here.\n\n" +
                    "It is not a crossfade: the two tracks do not overlap, because one player " +
                    "has one output.",
                Modifier.heightIn(0.dp),
            )
        }
        SliderRow(
            value = fade / 12f,
            format = { if (fade == 0) "Off" else "${fade}s" },
            onChange = { f -> scope.launch { settings.setNavFadeSeconds(Math.round(f * 12f)) } },
        )
        if (fade > 0) {
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Beat-matched fade",
                subtitle = "Time the fade to end on a beat, when the track has been scanned",
                checked = beatMatched,
                accent = accent,
            ) { on -> scope.launch { settings.setBeatMatchedCrossfade(on) } }
            Note(
                "Needs the track already scanned this session.",
                title = "Beat-matched fade",
                info = "The same analysis Light Sync uses. Where it has not run for the track " +
                    "coming up, the fade falls back to the fixed length above without saying " +
                    "so — a fade that waited for a scan would be a gap.",
            )
        }
        Note(
            "What happens when the queue runs out is on Now Playing.",
            title = "End of the queue",
            info = "It is a player setting rather than an app one, so it lives with the player " +
                "— the options chip under the artwork on Now Playing, next to the queue it " +
                "governs.",
        )
    }
}
