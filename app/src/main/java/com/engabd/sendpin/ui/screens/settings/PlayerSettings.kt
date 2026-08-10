package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.MaConfigEntry
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * This phone, as something Music Assistant can play *to*.
 *
 * Named after that rather than "This player", which was true and told you nothing —
 * every screen in Settings is about this device. What is configured here is the
 * player CAMusic registers with Music Assistant: its name, the codec it advertises,
 * and whether it stays reachable when the app is closed.
 *
 * All of it is meaningless without a Music Assistant server, and the page says so up
 * front rather than presenting controls that write to nothing.
 */
@Composable
internal fun PlayerSection(
    viewModel: PlayerViewModel,
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    hasMaServer: Boolean,
) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val configStatus by viewModel.configStatus.collectAsStateWithLifecycle()

    var playerName by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("auto") }
    var keepAlive by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        playerName = settings.playerName.first()
        codec = settings.sendspinCodec.first()
        keepAlive = settings.keepAliveForAnnouncements.first()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasMaServer) {
            SettingsCard(
                title = "No Music Assistant server",
                lead = "This page configures the player CAMusic registers with Music Assistant. " +
                    "Add one under Libraries and these settings start meaning something.",
            ) {}
        }

        SettingsCard(
            title = "Name",
            lead = "What this phone is called in Music Assistant's own player list, and on any " +
                "other device controlling it.",
        ) {
            OledField(playerName, { playerName = it }, "Player name", "e.g. Abdullah's phone", accent)
            // A rename can only be refused by the server — `config/players/save` is an
            // admin command — and a refusal used to be swallowed, so the name simply
            // stayed wrong with no explanation.
            configStatus.takeIf { it.isNotBlank() }?.let { StatusLine(it, Health.WARN, accent) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) {
                    OledButton("Save name", modifier = Modifier.weight(1f), accent = accent) {
                        viewModel.renamePlayer(playerName.trim())
                    }
                    OledButton("Disable player", modifier = Modifier.weight(1f), accent = accent, outline = true) {
                        viewModel.disablePlayer()
                    }
                } else {
                    OledButton("Enable player", modifier = Modifier.weight(1f), accent = accent) {
                        viewModel.enablePlayer(playerName.trim(), codec)
                    }
                }
            }

            CardDivider()
            // Last resort for a name Music Assistant won't let go of. MA keys a player
            // on its client id and keeps the name it was first registered under; the
            // protocol has no rename message, so when the config edit is refused,
            // arriving as a new player is the only thing left.
            Note(
                "Still showing the old name? Music Assistant keeps the name a player was first " +
                    "registered under. Registering again arrives as a new player under the name " +
                    "above — the old entry stays in Music Assistant, greyed out, for you to delete.",
            )
            OledButton("Register again as a new player", accent = accent, outline = true) {
                viewModel.reregister(playerName.trim())
            }
        }

        SettingsCard(
            title = "Stream format",
            lead = "What this phone tells Music Assistant it can decode. The server may only " +
                "send a format the client listed, so naming one is what actually forces its hand.",
        ) {
            SegmentedToggleRow(
                labels = CodecLabels,
                selectedIndex = CodecValues.indexOf(codec).coerceAtLeast(0),
            ) { i ->
                codec = CodecValues[i]
                scope.launch { settings.setSendspinCodec(CodecValues[i]) }
            }
            Note(
                when (codec) {
                    "flac" -> "Lossless, about half the bandwidth of PCM. Music Assistant must " +
                        "send FLAC — it is the only format offered."
                    "pcm" -> "Lossless and uncompressed. Highest bandwidth, no decode cost."
                    "opus" -> "Compressed. Lowest bandwidth, 48 kHz only."
                    else -> "Offer all three and let Music Assistant choose — FLAC first, then " +
                        "PCM, then Opus."
                },
            )

            if (connected) {
                // On "Auto" the hello advertised every codec, so the server is free to
                // switch to any of them on request — `client/request_format`, answered
                // with a fresh stream/start. Connect on a single codec and the
                // advertised list has one entry, so there is nothing to switch to
                // without reconnecting.
                val live = viewModel.canSwitchFormatLive && codec != "auto"
                if (live) {
                    OledButton(
                        "Switch to ${CodecLabels[CodecValues.indexOf(codec)]} now",
                        modifier = Modifier.fillMaxWidth(), accent = accent,
                    ) { viewModel.requestFormat(codec) }
                }
                Note(
                    if (live)
                        "This connection offered every format, so the change can be applied to " +
                            "the running stream. It sticks for good on the next reconnect."
                    else
                        "A format change needs the player disabled and enabled again — it is " +
                            "only announced when the connection opens.",
                )
            }
        }

        MaPlaybackConfigCard(viewModel, accent)

        SettingsCard(
            title = "Announcements",
            lead = "Home Assistant can speak to this phone — doorbells, timers, anything that " +
                "announces. That needs the connection held open when the app isn't in front of you.",
        ) {
            ToggleRow(
                "Stay reachable in the background",
                "Turn it off to save battery if you never send announcements here: the " +
                    "connection, the wake lock and the ongoing notification all go with it.",
                keepAlive, accent,
            ) { keepAlive = it; scope.launch { settings.setKeepAliveForAnnouncements(it) } }
            Note(
                "Playback is unaffected either way — music started here keeps playing in the " +
                    "background, and the connection comes back when you reopen the app.",
            )
        }
    }
}

/**
 * Music Assistant's own playback settings for this player, driven from here.
 *
 * Gapless and crossfade are **MA's**, not this app's: the server decides them per
 * player and applies them to the stream before it reaches the phone, which is why
 * there is no client-side gapless to implement. What there *was* was a trip to Music
 * Assistant's own web UI to change them. This is a remote control for settings that
 * already exist, and nothing more.
 *
 * Every row is built from what the server declared — its label, its help text, its
 * permitted options — so a build that renames crossfade, or grows a fourth mode,
 * needs no release here. A server that offers none of them shows no card.
 */
@Composable
private fun MaPlaybackConfigCard(viewModel: PlayerViewModel, accent: Color) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val entries by viewModel.playbackConfig.collectAsStateWithLifecycle()
    val error by viewModel.configError.collectAsStateWithLifecycle()

    // Re-read on every (re)connection: these live on the server and another client —
    // or MA's own UI — can have changed them since this screen was last opened.
    LaunchedEffect(connected) { viewModel.loadPlaybackConfig() }

    if (!connected || entries.isEmpty()) return

    SettingsCard(
        title = "Gapless and crossfade",
        lead = "Music Assistant applies these to the stream before it reaches this phone, so " +
            "they are its settings rather than the app's. Changed here so you don't have to " +
            "open Music Assistant to reach them.",
    ) {
        entries.forEachIndexed { i, entry ->
            if (i > 0) CardDivider()
            MaConfigRow(entry, accent) { viewModel.setPlaybackConfig(entry, it) }
        }
        error?.let { StatusLine(it, Health.BAD, accent) }
        Note(
            "Saving a player's config is an admin command in Music Assistant. A non-admin " +
                "login is refused every time, and the refusal is shown above rather than " +
                "swallowed.",
        )
    }
}

/** One server-declared setting, rendered by whatever type the server said it is. */
@Composable
private fun MaConfigRow(entry: MaConfigEntry, accent: Color, onChange: (JsonElement) -> Unit) {
    when {
        entry.type.equals("boolean", ignoreCase = true) ->
            ToggleRow(
                entry.label,
                entry.description.orEmpty(),
                entry.boolValue == true,
                accent,
            ) { onChange(JsonPrimitive(it)) }

        entry.options.isNotEmpty() -> {
            FieldLabel(entry.label)
            val values = entry.options.map { it.first }
            // Not coerced to 0. A value the server holds that isn't in the options it
            // declared — or one it hasn't set at all — is a real state, and painting
            // the first option as selected would report a setting the server does not
            // have. -1 simply lights none of them.
            val selected = values.indexOf(entry.stringValue)
            SegmentedToggleRow(
                labels = entry.options.map { it.second },
                selectedIndex = selected,
            ) { onChange(JsonPrimitive(values[it])) }
            if (selected < 0) Note("Music Assistant hasn't set this yet.")
            entry.description?.let { Note(it) }
        }

        // A number with a stated range — crossfade duration is the one that matters.
        entry.rangeMin != null && entry.rangeMax != null -> {
            val min = entry.rangeMin!!
            val max = entry.rangeMax!!
            val current = entry.numberValue ?: min
            FieldLabel(entry.label)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HSlider(
                    value = if (max > min) ((current - min) / (max - min)).toFloat() else 0f,
                    onChange = {},
                    // Rounded only where the server said the setting is an integer.
                    // Rounding a float entry — a gain in dB, a duration in seconds —
                    // would make half its range unreachable.
                    onCommit = { f ->
                        val raw = min + f * (max - min)
                        onChange(
                            if (entry.type.equals("float", ignoreCase = true)) {
                                JsonPrimitive(Math.round(raw * 10) / 10.0)
                            } else JsonPrimitive(Math.round(raw))
                        )
                    },
                    accented = true,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (entry.type.equals("float", ignoreCase = true)) "%.1f".format(current)
                    else "${current.toInt()}",
                    color = TextSecondary, fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            entry.description?.let { Note(it) }
        }

        // A type this app has no control for. Saying what it is set to is more use
        // than leaving it out, and it keeps the card honest about what MA holds.
        else -> {
            FieldLabel(entry.label)
            Note(entry.stringValue ?: entry.description ?: "Set in Music Assistant")
        }
    }
}

/** `SegmentedToggle` at full width, which is how every settings page wants it. */
@Composable
internal fun SegmentedToggleRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    com.engabd.sendpin.ui.design.SegmentedToggle(
        options = labels,
        selectedIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        onSelect = onSelect,
    )
}
