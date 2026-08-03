package com.engabd.sendpin.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.audio.AudioOutputs
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val accent = LocalAccent.current
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val currentFormat by viewModel.currentFormat.collectAsState()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var haSaved by remember { mutableStateOf(false) }
    var haTokenVisible by remember { mutableStateOf(false) }
    /** A saved token is sealed: shown as dots, not editable until explicitly replaced. */
    var haTokenLocked by remember { mutableStateOf(false) }
    var playerName by remember { mutableStateOf("") }
    var sendspinCodec by remember { mutableStateOf("auto") }
    val configStatus by viewModel.configStatus.collectAsState()
    var navFormat by remember { mutableStateOf("raw") }
    val backend by settings.backend.collectAsState(initial = "ma")
    // The same activity-scoped instance the Library tab uses, so edits here land on
    // the live client rather than on a copy that has to be restarted to be read.
    val libraryVm: LibraryViewModel = viewModel()

    LaunchedEffect(Unit) {
        // Only start discovery if it's not already running — avoids re-scanning
        // every time the user switches to the Settings tab.
        if (!isDiscovering && discoveredServers.isEmpty()) {
            viewModel.startDiscovery()
        }
        haUrl = settings.haUrl.first()
        haToken = settings.haToken.first()
        haTokenLocked = haToken.isNotBlank()
        playerName = settings.playerName.first()
        sendspinCodec = settings.sendspinCodec.first()
        navFormat = settings.navStreamFormat.first()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
                    letterSpacing = (-0.5).sp,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // ── Connection ──────────────────────────────────────────────
                item {
                    var manualUrl by remember { mutableStateOf("") }
                    SectionHeader(Icons.Default.Cloud, "Connection", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isDiscovering) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Scanning…", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                            if (!isDiscovering && discoveredServers.isEmpty()) {
                                Text("No servers found on the network", color = TextFaint, fontSize = 13.sp)
                            }
                            discoveredServers.forEach { server ->
                                ServerRow(
                                    name = server.name,
                                    host = "${server.host}:${server.port}",
                                    connected = serverUrl == server.webSocketUrl && connected,
                                    accent = accent,
                                ) { viewModel.connectToServer(server.webSocketUrl) }
                            }
                            OledField(manualUrl, { manualUrl = it }, "WebSocket URL", "ws://192.168.0.100:8095/ws", accent)
                            OledButton(
                                if (connected) "Reconnect" else "Connect",
                                enabled = manualUrl.isNotBlank(),
                                accent = accent,
                            ) { viewModel.connectToServer(manualUrl) }
                            if (connected) {
                                // Disconnecting drops the player out of Music
                                // Assistant — that's a destructive action and it
                                // should look like one rather than a spare grey chip.
                                OledButton("Disconnect", accent = accent, danger = true) { viewModel.disconnect() }
                            }
                        }
                    }
                }

                // ── Player ──────────────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Smartphone, "Player", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // The name is editable at any time: it is Music Assistant's
                            // per-player config that decides the displayed name, not the
                            // hello, so a rename is a config write and needs no reconnect.
                            OledField(
                                playerName,
                                { playerName = it },
                                "Player name",
                                "e.g. Abdullah's phone",
                                accent,
                            )
                            Text(
                                "Shown in Music Assistant.",
                                color = TextFaint, fontSize = 11.sp,
                            )
                            // A rename can only be refused by the server —
                            // `config/players/save` is an admin command — and a refusal
                            // used to be swallowed, so the name simply stayed wrong with
                            // no explanation.
                            configStatus.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = WarnAmber, style = MaterialTheme.typography.bodySmall)
                            }

                            // Stream codec. Sits here rather than under Audio because it
                            // is announced in the same hello as the name.
                            Text(
                                "Stream format",
                                color = TextSecondary, fontFamily = AppFont,
                                style = MaterialTheme.typography.labelLarge
                            )
                            SegmentedToggle(
                                options = CodecLabels,
                                selectedIndex = CodecValues.indexOf(sendspinCodec).coerceAtLeast(0),
                                modifier = Modifier.fillMaxWidth(),
                            ) { i ->
                                sendspinCodec = CodecValues[i]
                                scope.launch { settings.setSendspinCodec(CodecValues[i]) }
                            }
                            Text(
                                when (sendspinCodec) {
                                    "flac" -> "Lossless, about half the bandwidth of PCM. Music Assistant " +
                                        "must send FLAC — it is the only format offered."
                                    "pcm" -> "Lossless and uncompressed. Highest bandwidth, no decode cost."
                                    "opus" -> "Compressed. Lowest bandwidth, 48 kHz only."
                                    else -> "Offer all three and let Music Assistant choose — FLAC first, " +
                                        "then PCM, then Opus."
                                },
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (connected) {
                                    OledButton("Save name", modifier = Modifier.weight(1f), accent = accent) {
                                        viewModel.renamePlayer(playerName.trim())
                                    }
                                    OledButton("Disable", modifier = Modifier.weight(1f), accent = accent) { viewModel.disablePlayer() }
                                } else {
                                    OledButton("Enable player", modifier = Modifier.weight(1f), accent = accent) {
                                        viewModel.enablePlayer(playerName.trim(), sendspinCodec)
                                    }
                                }
                            }
                            if (connected) {
                                // On "Auto" the hello advertised every codec, so the
                                // server is free to switch to any of them on request —
                                // `client/request_format`, answered with a fresh
                                // stream/start. Connect on a single codec and the
                                // advertised list has one entry, so there is nothing
                                // to switch to without reconnecting.
                                val live = viewModel.canSwitchFormatLive && sendspinCodec != "auto"
                                if (live) {
                                    OledButton(
                                        "Switch to ${CodecLabels[CodecValues.indexOf(sendspinCodec)]} now",
                                        modifier = Modifier.fillMaxWidth(), accent = accent,
                                    ) { viewModel.requestFormat(sendspinCodec) }
                                }
                                Text(
                                    if (live)
                                        "This connection offered every format, so the change can be " +
                                            "applied to the running stream. It sticks for good on the " +
                                            "next reconnect."
                                    else
                                        "A format change needs the player disabled and enabled again — " +
                                            "it is only announced when the connection opens.",
                                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            // Last resort for a name Music Assistant won't let go of.
                            // MA keys a player on its client id and keeps the name it
                            // was first registered under; the protocol has no rename
                            // message, so when the config edit is refused, arriving as
                            // a new player is the only thing left.
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
                            Text(
                                "Still showing the old name? Music Assistant keeps the name a player " +
                                    "was first registered under. Registering again arrives as a new " +
                                    "player under the name above — the old entry stays in Music " +
                                    "Assistant, greyed out, for you to delete.",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                            OledButton("Register again as a new player", accent = accent) {
                                viewModel.reregister(playerName.trim())
                            }
                        }
                    }
                }

                // ── Library ─────────────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.LibraryMusic, "Library", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Where the Library tab browses from",
                                color = TextPrimary, fontFamily = AppFont,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            SegmentedToggle(
                                options = listOf("Music Assistant", "Navidrome"),
                                selectedIndex = if (backend == "subsonic") 1 else 0,
                            ) { scope.launch { settings.setBackend(if (it == 1) "subsonic" else "ma") } }
                            Text(
                                if (backend == "subsonic")
                                    "Navidrome plays on this phone only. Speaker grouping and Light Sync " +
                                        "both run through Music Assistant, so those tabs are off while " +
                                        "Navidrome is the library."
                                else
                                    "Music Assistant browses the whole library, plays to any speaker, and " +
                                        "keeps grouping and Light Sync available.",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // ── Navidrome server ────────────────────────────────────────
                // The server details used to be reachable only from the Library
                // tab's connect form, which is hidden the moment a connection
                // works — so a moved server or a changed password could not be
                // fixed from inside the app at all.
                item {
                    // Quality lives in this card rather than a section of its own:
                    // it is a property of the Navidrome connection, not of the app.
                    NavidromeCard(
                        vm = libraryVm,
                        accent = accent,
                        format = navFormat,
                        onFormat = { navFormat = it; scope.launch { settings.setNavStreamFormat(it) } },
                    )
                }

                // ── Downloads ───────────────────────────────────────────────
                item { DownloadsCard(libraryVm, accent) }

                // ── Now Playing ─────────────────────────────────────────────
                item {
                    var npLayout by remember { mutableStateOf("tab") }
                    LaunchedEffect(Unit) { npLayout = settings.nowPlayingLayout.first() }
                    SectionHeader(Icons.Default.PlayArrow, "Now Playing", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Layout style",
                                color = TextPrimary, fontFamily = AppFont,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "Overlay: the cover slides over the app; swipe down to minimize into a bar. " +
                                    "Tab: the classic full-screen player as a bottom tab.",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToggleChip("Tab (classic)", npLayout == "tab") {
                                    npLayout = "tab"
                                    scope.launch { settings.setNowPlayingLayout("tab") }
                                }
                                ToggleChip("Overlay", npLayout == "overlay") {
                                    npLayout = "overlay"
                                    scope.launch { settings.setNowPlayingLayout("overlay") }
                                }
                            }
                        }
                    }
                }

                // ── Audio ───────────────────────────────────────────────────
                item {
                    var preferHiRes by remember { mutableStateOf(true) }
                    var preferFlac by remember { mutableStateOf(true) }
                    var preferOriginal by remember { mutableStateOf(false) }
                    var bitPerfect by remember { mutableStateOf(false) }
                    var replayGain by remember { mutableStateOf("album") }
                    LaunchedEffect(Unit) {
                        preferHiRes = settings.preferHiRes.first()
                        preferFlac = settings.preferFlac.first()
                        preferOriginal = settings.preferOriginal.first()
                        bitPerfect = settings.bitPerfect24Bit.first()
                        replayGain = settings.replayGainMode.first()
                    }
                    SectionHeader(Icons.Default.GraphicEq, "Audio", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToggleRow(
                                "Offer hi-res rates",
                                "Also accept 88.2/96 kHz so hi-res masters aren't downsampled to 48 kHz",
                                preferHiRes, accent,
                            ) { preferHiRes = it; scope.launch { settings.setPreferHiRes(it) } }
                            ToggleRow(
                                "Prefer FLAC over PCM",
                                "Lossless either way — FLAC uses about half the bandwidth",
                                preferFlac, accent,
                            ) { preferFlac = it; scope.launch { settings.setPreferFlac(it) } }
                            ToggleRow(
                                "Play at original quality",
                                "Stream straight from Navidrome when Music Assistant would have " +
                                    "to resample. Plays on this phone only — no queue, no grouping.",
                                preferOriginal, accent,
                            ) { preferOriginal = it; scope.launch { settings.setPreferOriginal(it) } }
                            ToggleRow(
                                "Bit-perfect (24-bit)",
                                "Ask for 24-bit instead of 16 and render whatever depth the decoder " +
                                    "reports. Costs bandwidth, and a phone whose mixer runs at 16-bit " +
                                    "gains nothing — leave it off unless you're on a USB DAC.",
                                bitPerfect, accent,
                            ) { bitPerfect = it; scope.launch { settings.setBitPerfect24Bit(it) } }
                            Text(
                                "Music Assistant may only send a format this phone advertises. " +
                                    "44.1 and 48 kHz are always offered, so CD-rate files stream " +
                                    "untouched. Output is ${if (bitPerfect) "up to 24-bit" else "16-bit"}. " +
                                    "Reconnect to apply.",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
                            Text(
                                "ReplayGain",
                                color = TextSecondary, fontFamily = AppFont,
                                style = MaterialTheme.typography.labelLarge
                            )
                            SegmentedToggle(
                                options = ReplayGainLabels,
                                selectedIndex = ReplayGainValues.indexOf(replayGain).coerceAtLeast(0),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                replayGain = ReplayGainValues[it]
                                scope.launch { settings.setReplayGainMode(ReplayGainValues[it]) }
                            }
                            Text(
                                when (replayGain) {
                                    ReplayGain.ALBUM -> "Levels whole albums against each other and keeps " +
                                        "the dynamics within one. What you want for records."
                                    ReplayGain.TRACK -> "Levels every track to the same loudness. Better " +
                                        "for shuffled singles; flattens an album's quiet passages."
                                    else -> "Play files at their mastered level. A 1985 master and a " +
                                        "2015 one can land 10 dB apart."
                                },
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Applies to Navidrome and offline playback. Music Assistant does its " +
                                    "own gain server-side, so this would double it there. Boosts are " +
                                    "capped at +${ReplayGain.MAX_BOOST_DB.toInt()} dB to avoid clipping.",
                                color = TextFaint, style = MaterialTheme.typography.bodySmall,
                            )
                            OutputDevicePicker(accent)
                            // Status readout
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Ink3).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusRow("Connection", if (connected) "Connected" else "Disconnected")
                                StatusRow("Format", currentFormat)
                                StatusRow("Player ID", viewModel.playerId.take(12) + "…")
                                StatusRow("Player name", playerName.ifBlank { viewModel.deviceName })
                                StatusRow("Device", viewModel.deviceName)
                            }
                        }
                    }
                }

                // ── Light Sync (HA) ─────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Lightbulb, "Light Sync", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Drives the Hue Synco light-sync integration. Use a long-lived access token (Profile → Security).",
                                color = TextMuted, fontSize = 13.sp,
                            )
                            OledField(haUrl, { haUrl = it; haSaved = false }, "HA URL", "http://192.168.0.10:8123", accent)
                            if (haTokenLocked) {
                                // A long-lived token is a house key. Once it's in and
                                // working there is no reason to keep it on screen —
                                // the field is sealed and can only be replaced whole.
                                OledField(
                                    "•".repeat(24), {}, "Long-lived access token", "", accent,
                                    enabled = false,
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Lock, "Saved",
                                            tint = TextMuted, modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // The URL stays editable, so keep a way to save it
                                    // without making the user re-paste the token.
                                    OledButton(
                                        if (haSaved) "Saved" else "Save",
                                        enabled = haUrl.isNotBlank() && !haSaved,
                                        accent = accent, modifier = Modifier.weight(1f),
                                    ) {
                                        scope.launch {
                                            settings.setHomeAssistant(haUrl.trim(), haToken.trim()); haSaved = true
                                        }
                                    }
                                    OledButton("Replace token", accent = accent, outline = true, modifier = Modifier.weight(1f)) {
                                        haToken = ""; haTokenVisible = false; haSaved = false; haTokenLocked = false
                                    }
                                }
                            } else {
                                OledField(
                                    haToken, { haToken = it; haSaved = false },
                                    "Long-lived access token", "eyJ…", accent,
                                    visualTransformation = if (haTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        Box(Modifier.size(20.dp).clip(CircleShape).clickable { haTokenVisible = !haTokenVisible }) {
                                            Icon(
                                                if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                if (haTokenVisible) "Hide" else "Show",
                                                tint = TextMuted, modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    },
                                )
                                OledButton(
                                    if (haSaved) "Saved" else "Save",
                                    enabled = haUrl.isNotBlank() && haToken.isNotBlank(),
                                    accent = accent,
                                ) {
                                    scope.launch {
                                        settings.setHomeAssistant(haUrl.trim(), haToken.trim())
                                        haSaved = true
                                        haTokenVisible = false
                                        haTokenLocked = true
                                    }
                                }
                            }
                        }
                    }
                }

                // ── About ────────────────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Info, "About", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Sendspin — a Music Assistant player & controller for Android.\n\n" +
                                "Plays FLAC / Opus 16-bit as an MA player, with Navidrome-direct " +
                                "browse + offline downloads and Hue light-sync controls.",
                                color = TextMuted, fontSize = 13.sp,
                            )
                            Row(
                                Modifier.clip(RoundedCornerShape(100)).background(Glass)
                                    .border(1.dp, Hairline, RoundedCornerShape(100))
                                    .clickable {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/sendspin-nowdroid")))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Code, null, tint = accent, modifier = Modifier.size(16.dp))
                                Text("GitHub Repository", color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            // Read from the build rather than retyped here — the
                            // hard-coded string had been stale since 0.1.3.
                            Text(
                                "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                                color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Navidrome / OpenSubsonic server, editable at any time. Saving reconnects
 * immediately so the result — connected, or the server's own complaint — is
 * visible right here instead of on a different tab.
 */
@Composable
private fun NavidromeCard(
    vm: LibraryViewModel,
    accent: Color,
    format: String,
    onFormat: (String) -> Unit,
) {
    val url by vm.navUrl.collectAsState()
    val user by vm.navUser.collectAsState()
    val pass by vm.navPass.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val ready by vm.ready.collectAsState()
    val offline by vm.offline.collectAsState()
    val connError by vm.connError.collectAsState()
    val onSubsonic by vm.backend.collectAsState()
    var passVisible by remember { mutableStateOf(false) }

    SectionHeader(Icons.Default.Storage, "Navidrome server", accent)
    Spacer(Modifier.height(12.dp))
    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Browsing, playback on this phone, and offline downloads. Works with " +
                    "Music Assistant and Home Assistant both down.",
                color = TextMuted, fontSize = 13.sp,
            )
            OledField(url, vm::setNavUrl, "Server URL", "http://192.168.0.10:4533", accent)
            OledField(user, vm::setNavUser, "Username", "", accent)
            OledField(
                pass, vm::setNavPass, "Password", "", accent,
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Box(Modifier.size(20.dp).clip(CircleShape).clickable { passVisible = !passVisible }) {
                        Icon(
                            if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (passVisible) "Hide" else "Show",
                            tint = TextMuted, modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            OledButton(
                if (connecting) "Connecting…" else "Save & connect",
                enabled = url.isNotBlank() && !connecting,
                accent = accent,
            ) { vm.saveNavidrome() }

            // Only the active backend can report a live state; saying "Connected"
            // while the library is browsing MA would be a lie.
            val status = when {
                onSubsonic != LibraryViewModel.Backend.SUBSONIC -> "Saved — switch the library to Navidrome to use it"
                connecting -> "Connecting…"
                offline -> "Offline — playing downloads"
                ready -> "Connected"
                connError != null -> connError!!
                url.isBlank() -> "Not set up"
                else -> "Not connected"
            }
            Text(
                status,
                color = if (connError != null && onSubsonic == LibraryViewModel.Backend.SUBSONIC && !ready) ErrorRed else TextFaint,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
            Text(
                "Stream quality",
                color = TextSecondary, fontFamily = AppFont,
                style = MaterialTheme.typography.labelLarge
            )
            SegmentedToggle(
                options = NavFormatLabels,
                selectedIndex = NavFormatValues.indexOf(format).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) { onFormat(NavFormatValues[it]) }
            Text(
                when (format) {
                    "raw" -> "The stored file, untouched. Best quality; needs the bandwidth " +
                        "the file was encoded at."
                    "flac" -> "Transcoded to FLAC. Still lossless, and a predictable size " +
                        "even when the library holds huge hi-res masters."
                    else -> "Transcoded, lossy. Worth it on a slow or metered connection."
                },
                color = TextFaint, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Applies to the next track. Downloads always take the original file.",
                color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
            )
        }
    }
}

/**
 * Pin playback to one output device. Android normally routes for you, and for a
 * USB DAC it usually gets it right — but "usually" is not good enough for the
 * one person who went and bought a DAC, and the system offers no way to say so.
 *
 * The list is read once per composition rather than observed: plugging a DAC in
 * while this screen is open is rare enough that reopening Settings is a fair
 * price for not holding an [android.media.AudioDeviceCallback] here.
 */
@Composable
private fun OutputDevicePicker(accent: Color) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val outputs = remember { AudioOutputs.list(am) }
    var selected by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { selected = settings.preferredAudioDeviceId.first() }

    // Nothing to choose between on a phone with only its own speaker.
    if (outputs.size < 2) return

    Spacer(Modifier.height(2.dp))
    Text(
        "Output device",
        color = TextSecondary, fontFamily = AppFont,
        style = MaterialTheme.typography.labelLarge
    )
    val labels = listOf("Automatic") + outputs.map { it.label }
    val ids = listOf("") + outputs.map { it.id }
    SegmentedToggle(
        options = labels,
        selectedIndex = ids.indexOf(selected).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
    ) { i ->
        selected = ids[i]
        scope.launch { settings.setPreferredAudioDeviceId(ids[i]) }
    }
    Text(
        if (selected.isBlank())
            "Android picks the route — normally the last thing you plugged in."
        else
            "Playback is pinned to this output. If it's unplugged, Android routes " +
                "normally again until it's back.",
        color = TextFaint, style = MaterialTheme.typography.bodySmall,
    )
}

/** What's on the phone for offline listening, and the one way to get it back. */
@Composable
private fun DownloadsCard(vm: LibraryViewModel, accent: Color) {
    val downloads by vm.downloads.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    // Measuring means stat-ing every file, so it happens off the main thread and
    // only when the list itself changes.
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloads) { bytes = withContext(Dispatchers.IO) { vm.downloadBytes() } }

    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    var wifiOnly by remember { mutableStateOf(false) }
    var capMb by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        wifiOnly = settings.downloadWifiOnly.first()
        capMb = settings.downloadStorageCapMb.first()
    }

    SectionHeader(Icons.Default.Download, "Downloads", accent)
    Spacer(Modifier.height(12.dp))
    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Original files, not transcodes — a downloaded track plays at the " +
                    "quality it was stored at, with or without a network.",
                color = TextMuted, fontSize = 13.sp,
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink3).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusRow("Tracks", downloads.size.toString())
                StatusRow("On disk", formatBytes(bytes))
                if (capMb > 0) StatusRow("Limit", "${capMb} MB")
            }
            ToggleRow(
                "Wi-Fi only",
                "Skip downloads on mobile data rather than spending the allowance on them",
                wifiOnly, accent,
            ) { wifiOnly = it; scope.launch { settings.setDownloadWifiOnly(it) } }
            Text(
                "Storage limit",
                color = TextPrimary, fontFamily = AppFont,
                style = MaterialTheme.typography.titleLarge,
            )
            // Fixed steps rather than a free text field: the number only has to be
            // roughly right, and a slider or keyboard here would be more precision
            // than the setting deserves.
            SegmentedToggle(
                options = listOf("Off", "1 GB", "5 GB", "20 GB"),
                selectedIndex = when (capMb) {
                    1_000 -> 1
                    5_000 -> 2
                    20_000 -> 3
                    else -> 0
                },
            ) { i ->
                val mb = listOf(0, 1_000, 5_000, 20_000)[i]
                capMb = mb
                scope.launch { settings.setDownloadStorageCapMb(mb) }
            }
            Text(
                if (capMb > 0)
                    "The oldest downloads are deleted once the total goes past the limit."
                else
                    "No limit — downloads are only removed when you delete them.",
                color = TextFaint, style = MaterialTheme.typography.bodySmall,
            )
            if (downloads.isNotEmpty()) {
                OledButton(
                    if (confirming) "Tap again to delete all" else "Delete all downloads",
                    accent = accent, danger = true,
                ) {
                    if (confirming) { vm.deleteAllDownloads(); confirming = false } else confirming = true
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

// ─── Reusable OLED design components for settings ──────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(accent.a(0.12f))
                .border(1.dp, accent.a(0.3f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp)) }
        Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
private fun ServerRow(name: String, host: String, connected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (connected) accent.a(0.08f) else Glass)
            .border(1.dp, if (connected) accent.a(0.3f) else Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text(host, color = TextMuted, fontSize = 11.sp)
        }
        if (connected) Icon(Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OledField(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String, accent: Color,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
            disabledBorderColor = Hairline, disabledTextColor = TextMuted, disabledLabelColor = TextMuted,
        ),
        trailingIcon = trailingIcon,
    )
}

@Composable
private fun OledButton(
    text: String, accent: Color, enabled: Boolean = true, outline: Boolean = false,
    danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val fill = when {
        !enabled -> Glass
        danger -> ErrorRed.a(0.14f)
        outline -> Glass
        else -> accent
    }
    val border = when {
        danger && enabled -> ErrorRed.a(0.55f)
        outline -> Hairline
        else -> Color.Transparent
    }
    val label = when {
        !enabled -> TextMuted
        danger -> ErrorRed
        outline -> TextMuted
        else -> Ink
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = label, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink3)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp)).clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = TextFaint, fontSize = 11.sp)
        }
        Box(
            Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(100))
                .background(if (checked) accent else Glass)
                .border(1.dp, if (checked) accent.a(0.5f) else Hairline, RoundedCornerShape(100))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Ink else TextMuted)) }
    }
}

/**
 * What this phone tells Music Assistant it can decode. "Auto" offers all three;
 * naming one narrows the advertised list to it alone, which is what actually forces
 * the server's hand — see `FormatNegotiator.supportedFormats`.
 */
private val CodecValues = listOf("auto", "flac", "pcm", "opus")
private val CodecLabels = listOf("Auto", "FLAC", "PCM", "Opus")

/**
 * What Navidrome should send for a direct stream, as `format` (and `maxBitRate` where
 * the codec is lossy) on `/rest/stream`. "raw" hands back the stored file untouched.
 */
private val NavFormatValues = listOf("raw", "flac", "mp3-320", "mp3-192", "opus-128")
private val NavFormatLabels = listOf("Original", "FLAC", "MP3 320", "MP3 192", "Opus 128")

private val ReplayGainValues = listOf(ReplayGain.OFF, ReplayGain.TRACK, ReplayGain.ALBUM)
private val ReplayGainLabels = listOf("Off", "Track", "Album")

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}