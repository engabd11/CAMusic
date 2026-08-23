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
import androidx.compose.ui.platform.LocalContext
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.rememberIsIgnoringBatteryOptimizations
import com.engabd.sendpin.ma.MaConfigEntry
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.InfoChip
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.WarnAmber
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
// The top-level "CAMusic player" section is gone, and so is the signpost that stood
// in it. Once every setting had moved onto the Music Assistant server's own page, all
// the section could do was tell a first-time user that something they had never seen
// was somewhere else — and the app has never been published, so there is nobody to
// redirect.

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
    var duck by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        playerName = settings.playerName.first()
        codec = settings.sendspinCodec.first()
        keepAlive = settings.keepAliveForAnnouncements.first()
        duck = settings.duckAnnouncements.first()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasMaServer) {
            SettingsCard(
                title = "No Music Assistant server",
                lead = "Add one under Libraries and this page starts meaning something.",
                info = "Everything here configures the player CAMusic registers with Music " +
                    "Assistant: its name, the format it advertises, how it behaves between " +
                    "tracks. With no server to register with, none of it has anywhere to " +
                    "go.\n\nTip: add one under Libraries and come back. This phone then appears " +
                    "in Music Assistant's own speaker list, and you can play to it from any " +
                    "other client.",
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
                "Still showing the old name?",
                title = "Renaming a registered player",
                info = "Music Assistant keeps the name a player was first registered under, and " +
                    "the protocol has no rename message. Editing the config is the only route, " +
                    "and that is an admin command the server can refuse.\n\nRegistering again " +
                    "is the last resort. The phone arrives as a new player under the name " +
                    "above, and the old entry stays in Music Assistant, greyed out, for you to " +
                    "delete.\n\nTip: try Save name first and watch for a warning above the " +
                    "buttons. If it was refused you are logged in as a non-admin, and fixing " +
                    "that is easier than re-registering.",
            )
            OledButton("Register again as a new player", accent = accent, outline = true) {
                viewModel.reregister(playerName.trim())
            }
        }

        SettingsCard(
            title = "Stream format",
            lead = "What this phone tells Music Assistant it can decode.",
            info = "The server may only send a format the client has listed, so naming one here is " +
                "what actually forces its hand. There is no way to ask for a format the phone " +
                "has not advertised.\n\nAuto advertises all three and lets Music Assistant " +
                "pick, which is also what makes switching format on a live connection " +
                "possible.\n\nTip: leave it on Auto unless you are testing something. FLAC and " +
                "PCM are both lossless, and FLAC uses roughly half the bandwidth.",
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
                        "send FLAC, it is the only format offered."
                    "pcm" -> "Lossless and uncompressed. Highest bandwidth, no decode cost."
                    "opus" -> "Compressed. Lowest bandwidth, 48 kHz only."
                    else -> "Offer all three and let Music Assistant choose, FLAC first, then " +
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
                    if (live) "Can be applied to the running stream."
                    else "Needs the player disabled and enabled again.",
                    title = "Switching format",
                    info = if (live)
                        "This connection opened on Auto, so it advertised every format and " +
                            "the server is free to switch between them on request. The change " +
                            "sticks for good on the next reconnect.\n\nTip: switch while " +
                            "something is playing if you want to hear the difference. The " +
                            "stream restarts at the new format within a second or two."
                    else
                        "The advertised list is only sent when the connection opens, and " +
                            "this one opened on a single format, so there is nothing to switch " +
                            "to without reconnecting.\n\nTip: set the format to Auto and " +
                            "re-enable the player once. After that you can change format on a " +
                            "live stream without disconnecting again.",
                )
            }
        }

        MaPlaybackConfigCard(viewModel, accent)
        // What this player is actually doing right now, beside what it is configured
        // to do. It used to sit under Playback & audio, a section away from every
        // setting it reports the effect of — so checking whether a codec change had
        // taken meant leaving the page that changed it. Every row of it is about one
        // phone's registration with one Music Assistant server, which is this page.
        MaPlayerStatusCard(viewModel, settings)
        MaExperimentalCard(settings, accent, scope)

        SettingsCard(
            title = "Announcements",
            lead = "Doorbells, timers, anything Home Assistant wants to say out loud.",
            info = "For any of it to reach this phone, the connection has to be held open while " +
                "the app is not in front of you. That is what the first switch below buys, and " +
                "what it costs.\n\nPlayback is unaffected either way. Music started here keeps " +
                "playing in the background, and the connection comes back when you reopen the " +
                "app.\n\nTip: if announcements never arrive, check that CAMusic is set to " +
                "Unrestricted battery in Android's own settings. Battery saver closes the " +
                "connection while the screen is off, and no switch here can override that.",
        ) {
            ToggleRow(
                title = "Stay reachable in the background",
                subtitle = "Costs battery. Turn it off if you never announce here.",
                checked = keepAlive,
                accent = accent,
                info = "Holds the Music Assistant connection open while the app is in the " +
                    "background. Turning it off takes the connection, the wake lock and the " +
                    "ongoing notification with it.\n\nTip: leave it off if nothing ever " +
                    "announces to this phone. It is the largest single battery saving on this " +
                    "page.",
            ) { keepAlive = it; scope.launch { settings.setKeepAliveForAnnouncements(it) } }
            ToggleRow(
                title = "Duck other apps to be heard",
                subtitle = "Turn another app down rather than take the output from it",
                checked = duck,
                accent = accent,
                info = "An announcement is audible over a video this way, instead of stopping " +
                    "it.\n\nWith ducking off the announcement takes the output instead, which " +
                    "is louder and more definite but interrupts whatever was playing.\n\nTip: " +
                    "turn it off if announcements come out at a noticeably different volume " +
                    "from your music, since ducking leaves the other app's own level in the " +
                    "mix.",
            ) { duck = it; scope.launch { settings.setDuckAnnouncements(it) } }
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
        lead = "Music Assistant's settings, reachable from here.",
        info = "The server applies these to the stream before it reaches this phone, so they are " +
            "its settings rather than the app's. That is also why there is no client-side " +
            "gapless to turn on.\n\nEvery row is built from what the server declared, so a " +
            "Music Assistant build that renames one of these, or grows a fourth mode, needs no " +
            "update here.\n\nTip: these are per player. Changing them here changes them for " +
            "this phone only, not for the speakers in the next room.",
    ) {
        entries.forEachIndexed { i, entry ->
            if (i > 0) CardDivider()
            MaConfigRow(entry, accent) { viewModel.setPlaybackConfig(entry, it) }
        }
        error?.let { StatusLine(it, Health.BAD, accent) }
        Note(
            "Changing these needs an admin login.",
            title = "Why a change can be refused",
            info = "Saving a player's config is an admin command in Music Assistant, so a " +
                "non-admin login is refused every time.\n\nThe refusal is shown above rather " +
                "than swallowed, which is how you can tell it apart from a setting that simply " +
                "did not take.\n\nTip: if you see one, sign in to Music Assistant with an admin " +
                "account under Libraries. Nothing on this card will save until you do.",
        )
    }
}

/**
 * Player-internals switches not ready for general use.
 *
 * Only one is left. The ExoPlayer engine used to be the other, off by default,
 * on the reasoning that an unvalidated path should not silently become everyone's
 * player — but hardware settled that: the engine it replaced makes no sound on the
 * owner's device. It is now the only MA engine and has no switch. See
 * [com.engabd.sendpin.service.Playback.startSendspin].
 *
 * [AppSettings.useOboeOutput] routes decoded audio through a native Oboe engine
 * instead of the platform AudioTrack, and takes effect on the next MA connection
 * rather than retroactively on whatever is playing.
 *
 * Lives here rather than in the device-wide Audio settings: it is entirely about
 * how *this phone talks to Music Assistant specifically* — the Navidrome/local
 * path never touches it — so it belongs with the rest of this phone's Music
 * Assistant player settings, the same reasoning that already moved
 * gapless/crossfade ([MaPlaybackConfigCard]) here.
 */
@Composable
private fun MaExperimentalCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    var useOboe by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        useOboe = settings.useOboeOutput.first()
    }

    SettingsCard(
        title = "Experimental",
        lead = "Not ready for everyday use. Turn this on only if you're specifically testing it.",
    ) {
        ToggleRow(
            title = "Native Oboe output",
            subtitle = "Currently produces no sound at all",
            checked = useOboe,
            accent = accent,
            info = "Routes decoded audio through a native engine instead of the platform " +
                "AudioTrack, so playback cannot be interrupted by garbage collection.\n\nIt " +
                "does not work yet. The audio stream opens and starts, but nothing is ever " +
                "consumed from it, so the result is silence rather than a glitch. Three " +
                "separate bugs have been fixed on the way to this one and none of them was " +
                "it.\n\nTip: leave it off unless you are helping track it down. If you turned " +
                "it on and have no sound at all, this is why, and turning it back off fixes it " +
                "immediately.",
        ) { useOboe = it; scope.launch { settings.setUseOboeOutput(it) } }
    }
}

/**
 * What this player is actually doing right now.
 *
 * Every row describes one phone's registration with one Music Assistant server — the
 * connection state, the format that registration ended up negotiating, the client id
 * it registered under and the name it registered with. It used to sit under Playback
 * & audio, a section away from every setting whose effect it reports, so checking
 * whether a codec change had taken meant leaving the page that made it.
 *
 * The battery warning rides along because the thing it threatens is this connection:
 * `SendspinConnectionService` losing Doze survival is what makes announcements and
 * background playback drop, and that is a fact about this player rather than about
 * the phone's audio output.
 */
@Composable
private fun MaPlayerStatusCard(viewModel: PlayerViewModel, settings: AppSettings) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val currentFormat by viewModel.currentFormat.collectAsStateWithLifecycle()
    var playerName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { playerName = settings.playerName.first() }

    // Live-checked, not just "onboarding was completed once" — OEM battery managers
    // (Samsung, Xiaomi, Oppo) can revoke the exemption later without telling the
    // user, and SendspinConnectionService silently losing Doze survival is exactly
    // the kind of thing that should surface here rather than stay invisible.
    val batteryGranted = rememberIsIgnoringBatteryOptimizations()
    val context = LocalContext.current

    SettingsCard(
        title = "Player status",
        lead = "What this phone's connection to Music Assistant is actually doing right now. " +
            "Worth quoting in a bug report.",
    ) {
        StatusPanel {
            StatusRow("Connection", if (connected) "Connected" else "Disconnected")
            StatusRow("Format", currentFormat)
            StatusRow("Player ID", viewModel.playerId.take(12) + "…")
            StatusRow("Player name", playerName.ifBlank { viewModel.deviceName })
            StatusRow("Device", viewModel.deviceName)
        }
        if (!batteryGranted) {
            CardDivider()
            StatusLine(
                "Battery optimisation is restricting this app, so announcements and background " +
                    "playback may drop while the screen is off.",
                health = Health.WARN,
                accent = WarnAmber,
            )
            OledButton(
                "Open battery settings",
                accent = WarnAmber,
                outline = true,
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

/** One server-declared setting, rendered by whatever type the server said it is. */
@Composable
private fun MaConfigRow(entry: MaConfigEntry, accent: Color, onChange: (JsonElement) -> Unit) {
    when {
        entry.type.equals("boolean", ignoreCase = true) ->
            // The description here is the server's own text, of unknown length and
            // sometimes absent — so it goes straight behind the chip rather than being
            // summarised into a subtitle this app is in no position to write.
            ToggleRow(
                title = entry.label,
                checked = entry.boolValue == true,
                accent = accent,
                info = entry.description?.takeIf { it.isNotBlank() },
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
            entry.description?.takeIf { it.isNotBlank() }?.let {
                Note("What this does", title = entry.label, info = it)
            }
        }

        // A number with a stated range — crossfade duration is the one that matters.
        entry.rangeMin != null && entry.rangeMax != null -> {
            val min = entry.rangeMin!!
            val max = entry.rangeMax!!
            val current = entry.numberValue ?: min
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FieldLabel(entry.label)
                entry.description?.takeIf { it.isNotBlank() }?.let {
                    InfoChip(entry.label, it, Modifier.heightIn(0.dp))
                }
            }
            SliderRow(
                value = if (max > min) ((current - min) / (max - min)).toFloat() else 0f,
                format = {
                    if (entry.type.equals("float", ignoreCase = true)) "%.1f".format(current)
                    else "${current.toInt()}"
                },
                // Rounded only where the server said the setting is an integer.
                // Rounding a float entry — a gain in dB, a duration in seconds —
                // would make half its range unreachable.
                onChange = { f ->
                    val raw = min + f * (max - min)
                    onChange(
                        if (entry.type.equals("float", ignoreCase = true)) {
                            JsonPrimitive(Math.round(raw * 10) / 10.0)
                        } else JsonPrimitive(Math.round(raw))
                    )
                },
            )
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
