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
import com.engabd.sendpin.audio.ExclusiveOutput
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.audio.SignalPath
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
        DjRadioSettingsCard(settings, scope)
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
    val exclusiveOutput by settings.exclusiveOutput.collectAsStateWithLifecycle(initialValue = false)

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
            route?.sampleRateLabel?.let { StatusRow("Device accepts", it) }
            if (mixerRate > 0) StatusRow("Mixer output", "${StreamQuality.khz(mixerRate)} kHz")
        }
        route?.bluetoothCodecNote?.let { Note(it) }

        // The chain itself, stage by stage. The row above describes the *device*;
        // this describes what is actually flowing through it, which is the question
        // someone reading this card is really asking. See SignalPath.
        val path by SignalPath.state.collectAsStateWithLifecycle()
        if (path.source.known || path.decoded.known) {
            CardDivider()
            FieldLabel("Signal path")
            StatusPanel {
                if (path.source.known) StatusRow("File", path.source.summary())
                if (path.decoded.known) StatusRow("Decoder output", path.decoded.summary())
                if (path.sink.known) StatusRow("To Android", path.sink.summary())
                StatusRow(
                    "High-resolution output",
                    when {
                        !path.highResRequested -> "Off"
                        path.floatEngaged -> "On, carrying the extra bits"
                        else -> "On, but this stream is 16-bit so it makes no difference"
                    },
                )
                if (path.processorsBypassed) {
                    StatusRow("Equaliser / Light Sync", "Bypassed")
                }
            }
            with(SignalPath) { path.explain(route?.isBluetooth == true) }?.let {
                Note(it, warn = path.truncating)
            }
            Note(
                "What each line means.",
                title = "Signal path",
                info = "**File** is what the container declares.\n\n**Decoder output** is " +
                    "what the decoder actually handed over, and it is usually the line " +
                    "that explains a disappointment: a 24-bit FLAC decoded by the phone's " +
                    "own MediaCodec comes out as 16-bit PCM, and nothing downstream can " +
                    "put those bits back.\n\n**To Android** is the last thing this app " +
                    "can see. Past it the platform mixes, resamples if it has to, and on " +
                    "Bluetooth hands the result to the codec.\n\n**High-resolution " +
                    "output** is the switch that decides whether the decoder's extra bits " +
                    "survive: without it Android converts anything above 16-bit down to " +
                    "16 on the way to the sink. It only actually engages on a stream that " +
                    "needs it - a 16-bit file plays exactly the same either way, which is " +
                    "what \"on, but this stream is 16-bit\" means above.\n\n**Equaliser / " +
                    "Light Sync** appears only while they are genuinely out of the chain: " +
                    "on a stream carrying the extra bits, or with Exclusive output on, " +
                    "media3 is not running either.\n\nTip: on Bluetooth the device row above " +
                    "will always say 16-bit, whatever LDAC is carrying — that is Android " +
                    "describing the sink it gives apps, not the codec. These lines are " +
                    "the ones that tell you something.",
            )
        }

        CardDivider()
        FieldLabel("Output sample rate")
        val outRate by settings.outputSampleRateHz.collectAsStateWithLifecycle(initialValue = 0)
        val rateOptions = listOf(0) + AppSettings.OUTPUT_RATES
        SegmentedToggleRow(
            labels = rateOptions.map { if (it == 0) "Follow file" else StreamQuality.khz(it) },
            selectedIndex = rateOptions.indexOf(outRate).coerceAtLeast(0),
        ) { i -> scope.launch { settings.setOutputSampleRateHz(rateOptions[i]) } }
        Note(
            if (outRate == 0)
                "Every file plays at its own rate. Android converts if the output needs it."
            else
                "Everything is resampled to ${StreamQuality.khz(outRate)} kHz before it leaves the app.",
            title = "Output sample rate",
            info = "Follow file is right almost always: resampling is a loss, and doing " +
                "it here on top of whatever Android does is two losses instead of one.\n\n" +
                "Fixing a rate is worth it in one situation - when the output is locked " +
                "to a rate your files are not. The Mixer output line above says what " +
                "Android is running at. Matching it means this app resamples once, with " +
                "a good resampler, instead of the platform doing it on every track.\n\n" +
                "On Bluetooth the rate the codec negotiated is in Developer options " +
                "under Bluetooth audio sample rate, and that is the number worth " +
                "matching.\n\n" +
                "Applies to the next track rather than the one playing.\n\n" +
                "Tip: if you do not know, leave it on Follow file. A wrong choice here " +
                "is audible and a right one usually is not.",
        )

        CardDivider()
        ToggleRow(
            title = "High-resolution output",
            subtitle = if (route?.isBluetooth == true)
                "Keeps the decoder's extra bits as far as the Bluetooth codec"
            else
                "Carry more than 16 bits to the output",
            checked = bitPerfect,
            accent = accent,
            info = "Carries whatever depth the decoder produced instead of flattening it to " +
                "16 bits on the way to the sink.\n\nThis matters on Bluetooth too, which is " +
                "not obvious: LDAC is lossy, so nothing here is ever bit-perfect over the " +
                "air — but the codec is fed by Android, and handing it a signal already " +
                "truncated to 16 bits throws away resolution *before* it encodes. Check " +
                "Developer options, Bluetooth audio bits per sample: if it says 24 or 32, " +
                "the codec is ready for more than 16 and this is what supplies it.\n\nIt " +
                "only helps where the decoder produced more than 16 bits to begin with. " +
                "The Signal path panel above says whether it did.\n\nThe float path is " +
                "fixed when the player is built, so a change applies next time the app " +
                "starts rather than straight away.\n\nThe trade: on a stream where it " +
                "engages, media3 stops running any of this app's audio processors at " +
                "all - the equaliser and the Light Sync audio analysis go quiet, not " +
                "because of a setting but because of how media3's float path works. " +
                "The Signal path panel above says when that is happening.\n\nTip: if you " +
                "hear distortion on 44.1 kHz material on a phone whose mixer runs at 48, " +
                "turn it back off — that combination has been known to misbehave, and " +
                "switching it off fixes it immediately.",
        ) { scope.launch { settings.setBitPerfect24Bit(it) } }

        CardDivider()
        ToggleRow(
            title = "Exclusive output",
            subtitle = "Nothing of this app's between the decoder and the DAC",
            checked = exclusiveOutput,
            accent = accent,
            info = "Removes every stage this app puts between the decoder and the DAC, and " +
                "asks the platform to carry the source's own rate and depth instead of " +
                "this app touching either.\n\nTurns off: " +
                ExclusiveOutput.disables.joinToString(", ") { it.title } + ".\n\n" +
                ExclusiveOutput.disables.joinToString("\n\n") { "**${it.title}.** ${it.reason}" } +
                "\n\n" + ExclusiveOutput.VOLUME_NOTE +
                "\n\n" + ExclusiveOutput.ANDROID_CEILING_NOTE +
                "\n\nPins a USB output the moment you turn this on, if one is attached and " +
                "nothing is pinned already — exclusive output through the phone speaker " +
                "is nothing to ask for. Turning it back off does not unpin it; do that " +
                "above if you want Android routing normally again.\n\nThe renderer is " +
                "fixed when the player is built, so this applies next time the app " +
                "starts, same as High-resolution output above.",
        ) { on ->
            scope.launch {
                settings.setExclusiveOutput(on)
                // Reuses AudioOutputs.list rather than `outputs` above: the picker row
                // is hidden on a phone with only its own speaker, but a USB DAC plugged
                // in after that row last composed should still get pinned.
                if (on && pinned.isBlank()) {
                    AudioOutputs.list(am).firstOrNull { it.isUsb }
                        ?.let { settings.setPreferredAudioDeviceId(it.id) }
                }
            }
        }
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
            "Music Assistant playback on this phone is 16-bit — the native output engine " +
                "renders int16 whatever depth is advertised here. High-resolution output " +
                "under Output above is for the library this phone decodes for itself, not " +
                "this one.",
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


// ── DJ Radio ──────────────────────────────────────────────────────────────

/**
 * The two numbers behind the button on the library's front page.
 *
 * Beside "Between tracks" rather than inside it, because they are not the same
 * setting under a different name. That card's fade is *sequential* and off by
 * default because an album must not be faded; this one puts two tracks in the air
 * at once and is on by default, because that overlap is the whole of what DJ Radio
 * was asked for. Sharing one number would have meant either fading albums or
 * shipping a DJ mode with a gap in it.
 */
@Composable
private fun DjRadioSettingsCard(settings: AppSettings, scope: CoroutineScope) {
    val crossfade by settings.djRadioCrossfadeSeconds
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT_DJ_CROSSFADE_S)
    val similarity by settings.djRadioSimilarity
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT_DJ_SIMILARITY)
    val smartFade by settings.djRadioSmartFade.collectAsStateWithLifecycle(initialValue = true)

    SettingsCard(
        title = "DJ Radio",
        lead = "The button on the library's front page: one tap, and it keeps choosing.",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FieldLabel("How it joins")
            InfoChip(
                "Smart or Standard",
                "Standard overlaps the last few seconds of the file, by the clock. That is " +
                    "a fact about the file rather than about the music, and it is why a " +
                    "crossfade can be several seconds long and still sound like a cut: if the " +
                    "outgoing track has already faded out, or ends on a couple of seconds of " +
                    "silence, the \"mix\" is the new song coming up under nothing at " +
                    "all.\n\nSmart reads the same offline scan Light Sync uses and plans " +
                    "the join instead. It finds where the music actually stops rather than " +
                    "where the file does, starts the overlap on a downbeat so the two grids " +
                    "meet in time, sizes it in whole bars, and drops the needle past any dead " +
                    "air at the front of the next track.\n\nNeeds the track scanned. Where it " +
                    "has not been, Smart quietly does exactly what Standard does, so there is " +
                    "nothing lost by leaving it on.\n\nTip: run a sweep under Settings, Light " +
                    "Sync, Track analysis and far more of your library gets the good join.",
                Modifier.heightIn(0.dp),
            )
        }
        SegmentedToggleRow(FadeModeLabels, if (smartFade) 0 else 1) { i ->
            scope.launch { settings.setDjRadioSmartFade(i == 0) }
        }
        Note(
            if (smartFade) "Bars and downbeats, from the scan. Falls back to the time below."
            else "Purely by the clock: the last seconds of one track over the first of the next.",
        )

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FieldLabel(if (smartFade) "About this long" else "Crossfade")
            InfoChip(
                "DJ Radio crossfade",
                "How long the two tracks overlap. Unlike Smooth transitions above, this is a " +
                    "real crossfade: the outgoing track keeps playing on a second deck while " +
                    "the next one comes up under it, so the room is never quiet between " +
                    "songs.\n\nOn Smart this is a target rather than the answer — the overlap " +
                    "is rounded to the whole number of bars nearest it, because a bar and a " +
                    "half of overlap is the stumble that one or two bars is not. Two seconds " +
                    "is exactly one bar at 120 BPM in 4/4.\n\nAt 0 the tracks simply run into " +
                    "each other gapless, which is still no silence — just no " +
                    "overlap.\n\nOnly ever applied while DJ Radio is running. Ordinary " +
                    "playback and albums keep the settings above untouched.\n\nTip: past four " +
                    "or five seconds a transition stops reading as one song arriving and " +
                    "starts reading as two playing at once.",
                Modifier.heightIn(0.dp),
            )
        }
        SliderRow(
            value = crossfade / AppSettings.MAX_DJ_CROSSFADE_S.toFloat(),
            format = { if (crossfade == 0) "Gapless" else "${crossfade}s" },
            onChange = { f ->
                scope.launch {
                    settings.setDjRadioCrossfadeSeconds(
                        Math.round(f * AppSettings.MAX_DJ_CROSSFADE_S),
                    )
                }
            },
        )

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FieldLabel("How similar")
            InfoChip(
                "How similar",
                "How close the next track has to be to the last one.\n\nCloseness is measured " +
                    "on the genre tags your library carries and on the offline track analysis " +
                    "— how hard the song goes, how bright it is, and what tempo it really runs " +
                    "at, folded at half and double time the way a DJ hears it. A track that " +
                    "has not been analysed is still eligible; it is just ranked on its tags " +
                    "alone, and sits behind the ones that have been.\n\nNothing ever stalls " +
                    "here: if nothing clears the bar, the bar comes down until something " +
                    "does. Loose is a wider net, not a worse one.\n\nTip: run a sweep under " +
                    "Settings, Light Sync, Track analysis. Every scanned track is one more " +
                    "the set can actually listen to before choosing it.",
                Modifier.heightIn(0.dp),
            )
        }
        SliderRow(
            value = similarity,
            format = {
                when {
                    similarity < 0.25f -> "Wander"
                    similarity < 0.5f -> "Loose"
                    similarity < 0.75f -> "Close"
                    else -> "Tight"
                }
            },
            onChange = { f -> scope.launch { settings.setDjRadioSimilarity(f) } },
        )
        Note(
            "Harmonic DJ mode adds key matching on top.",
            title = "Key matching",
            info = "With Harmonic DJ mode on — under Behaviour — DJ Radio also weighs whether " +
                "the two tracks sit well together on the Camelot wheel, so a transition is in " +
                "key as well as in tempo and in mood.\n\nIt needs both tracks scanned to say " +
                "anything, and contributes nothing where either has not been. Off by default, " +
                "and DJ Radio works without it.",
        )
    }
}

/** Smart first, because it is the default and the one that answers the complaint. */
private val FadeModeLabels = listOf("Smart", "Standard")
