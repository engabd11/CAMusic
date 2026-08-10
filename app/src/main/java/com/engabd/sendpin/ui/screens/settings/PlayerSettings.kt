package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
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
