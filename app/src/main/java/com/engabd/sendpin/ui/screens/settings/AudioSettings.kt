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

    // Collected, not read once with `first()`. Both of these are also settable from
    // the Music Assistant server's own page, so a one-shot read left whichever copy
    // of the card you were not standing on showing the value from before the change.
    // Cheap now that every AppSettings flow is deduped — see AppSettings.pref().
    val pinned by settings.preferredAudioDeviceId.collectAsStateWithLifecycle(initialValue = "")
    val bitPerfect by settings.bitPerfect24Bit.collectAsStateWithLifecycle(initialValue = false)

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
                scope.launch { settings.setPreferredAudioDeviceId(ids[i]) }
            }
            Note(
                if (pinned.isBlank())
                    "Android picks the route, normally the last thing you plugged in."
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
            info = "Renders whatever depth the decoder reports instead of flattening everything to " +
                "16 bits. It costs bandwidth, and a phone whose mixer runs at 16-bit gains " +
                "nothing at all from it.\n\nOn a library this phone plays, it also turns on " +
                "float output, so a 24-bit file is not requantised to 16 on its way to the " +
                "sink. That is fixed when the player is built, so it applies the next time the " +
                "app starts rather than straight away.\n\nIt is off by default because float " +
                "output is still experimental. It has been heard to distort 44.1 kHz material " +
                "on phones whose mixer runs at 48.\n\nTip: check the panel above first. If your " +
                "output says it accepts 16-bit, there is nothing here for you. This is really " +
                "for a USB DAC, and if you turn it on and hear distortion, turning it back off " +
                "fixes it immediately.",
        ) { scope.launch { settings.setBitPerfect24Bit(it) } }
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
    // This card is rendered on two pages at once — Playback & audio, and the Music
    // Assistant server's own page — so reading each value once meant changing
    // bit-perfect on one and finding the other still showing the old answer. The
    // initial values are the same defaults AppSettings applies.
    val preferHiRes by settings.preferHiRes.collectAsStateWithLifecycle(initialValue = true)
    val preferFlac by settings.preferFlac.collectAsStateWithLifecycle(initialValue = true)
    val preferOriginal by settings.preferOriginal.collectAsStateWithLifecycle(initialValue = false)
    val bitPerfect by settings.bitPerfect24Bit.collectAsStateWithLifecycle(initialValue = false)

    SettingsCard(
        title = "What to ask Music Assistant for",
        lead = "What this phone is willing to be sent.",
        info = "Music Assistant may only send a format this phone has advertised, so these decide " +
            "what it is allowed to choose. Nothing here asks for a format, it widens or narrows " +
            "what is on the table.\n\n44.1 and 48 kHz are always offered whatever you set, so " +
            "CD-rate files stream untouched either way.\n\nTip: a change here only takes effect " +
            "on the next connection. Disable and re-enable the player under Libraries if you " +
            "want it now.",
    ) {
        ToggleRow(
            "Offer hi-res rates",
            "Also accept 88.2 and 96 kHz, so hi-res masters aren't downsampled to 48",
            preferHiRes, accent,
        ) { scope.launch { settings.setPreferHiRes(it) } }
        ToggleRow(
            "Prefer FLAC over PCM",
            "Lossless either way, FLAC uses about half the bandwidth",
            preferFlac, accent,
        ) { scope.launch { settings.setPreferFlac(it) } }
        ToggleRow(
            title = "Play at original quality",
            subtitle = "Bypass Music Assistant rather than let it resample",
            checked = preferOriginal,
            accent = accent,
            info = "When Music Assistant would have to resample a file, the app streams it " +
                "straight from Navidrome instead, exactly as it is stored.\n\nThe trade is that " +
                "it plays on this phone only. Going direct means going around the server, so " +
                "there is no shared queue and no speaker grouping while a track is playing this " +
                "way.\n\nTip: turn it on if you have hi-res files and one listening spot. Leave " +
                "it off if you group speakers, because a track that goes direct drops out of " +
                "the group.",
        ) { scope.launch { settings.setPreferOriginal(it) } }
        Note(
            "Output is ${if (bitPerfect) "up to 24-bit" else "16-bit"}, see Output above. " +
                "Reconnect to apply a change here.",
        )
    }
}

// ── Loudness ──────────────────────────────────────────────────────────────

@Composable
private fun LoudnessCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val mode by settings.replayGainMode.collectAsStateWithLifecycle(initialValue = ReplayGain.ALBUM)

    SettingsCard(
        title = "Loudness",
        lead = "A 1985 master and a 2015 one can land 10 dB apart. ReplayGain uses the level " +
            "tags in your files to even that out.",
    ) {
        SegmentedToggleRow(ReplayGainLabels, ReplayGainValues.indexOf(mode).coerceAtLeast(0)) {
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
            info = "This covers the library this phone plays and your downloads. It is not applied " +
                "to anything Music Assistant streams, because the server does its own gain, and " +
                "doing it here as well would double it.\n\nBoosts are capped at " +
                "+${ReplayGain.MAX_BOOST_DB.toInt()} dB, which is roughly where raising a quiet " +
                "master starts to clip.\n\nTip: Album keeps the loud and quiet tracks of one " +
                "record in the relationship the engineer intended, which is what you want for " +
                "albums. Track levels everything flat, which suits a shuffled queue of singles.",
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
                "Fades one track out and the next in, on the player this phone runs.\n\nOff " +
                    "is gapless, which is what an album wants. This is suppressed automatically " +
                    "while the queue is a single record, however it is set here, so you do not " +
                    "have to keep turning it off and on.\n\nIt is not a crossfade: the two " +
                    "tracks do not overlap, because one player has one output.\n\nTip: two to " +
                    "four seconds suits a shuffled queue. Much longer and a short track starts " +
                    "fading before its last chorus has finished.",
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
                info = "Times the fade to end on a beat rather than partway through a bar, using " +
                    "the same analysis Light Sync uses.\n\nWhere that analysis has not run for " +
                    "the track coming up, the fade quietly falls back to the fixed length " +
                    "above. A fade that waited for a scan would be a gap.\n\nTip: run a sweep " +
                    "under Settings, Light Sync, Track analysis and this will apply to far more " +
                    "of your queue.",
            )
        }
        Note(
            "What happens when the queue runs out is on Now Playing.",
            title = "End of the queue",
            info = "It is a player setting rather than an app one, so it lives with the player: " +
                "the options chip under the artwork on Now Playing, next to the queue it " +
                "governs.\n\nThat is also where you choose whether the player carries on with " +
                "similar tracks when the queue empties, or simply stops.",
        )
    }
}
