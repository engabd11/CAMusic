package com.engabd.sendpin.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.ScanLibrarySource
import com.engabd.sendpin.audio.ScanFailureNote
import com.engabd.sendpin.audio.ScanProgress
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.hue.DiscoveredBridge
import com.engabd.sendpin.hue.LinkButtonNotPressed
import com.engabd.sendpin.ui.design.MeterBar
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.Pill
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Hearing
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.SegmentedToggle
import com.engabd.sendpin.capture.PlaybackCapture
import androidx.compose.material3.MaterialTheme

/**
 * The plumbing behind the light show.
 *
 * The Lights **tab** is the show itself — which room, how hard, what colour. This is
 * what carries the signal there, and it is a different question with a different
 * audience: you set it up once and never look at it again.
 *
 * Track analysis moved here from the Lights tab for the same reason. "Read tracks
 * ahead", "Wi-Fi only", "Analyse library" and "Delete analyses" are not part of a
 * light show; they are a background job with a storage cost, sitting in the middle of
 * the intensity and colour controls where nobody looking for them would think to
 * look and everybody adjusting the show had to scroll past them.
 */
internal const val BRIDGE_ROUTE = "__bridge__"

/** Home Assistant's address and token, when that is the transport. */
internal const val HA_ROUTE = "__ha__"

/**
 * Reading tracks ahead of the show.
 *
 * Its own page, not a second card under the bridge. Pairing a bridge is a one-time
 * network setup; this is a background batch job with a disk cost, a progress meter, a
 * failures list and a destructive delete. They were together because both had been
 * moved off the Lights tab at the same time, which is not a reason.
 */
internal const val ANALYSIS_ROUTE = "__analysis__"

/**
 * What this phone's Light Sync listens to.
 *
 * On the Lights tab until now, where it was the eighth of thirteen sections. It is a
 * plumbing question - a runtime permission, a system consent dialog and a foreground
 * service - and this page's whole remit is how the signal reaches the lights, so it
 * belongs here beside the bridge and the track analysis rather than among the controls
 * for what the show looks like.
 *
 * The one part that stayed behind is the tap: Android 14+ makes the projection token
 * single-use, so starting it recurs every app session, and an action that frequent
 * cannot live two screens deep. The Light Sync hero offers it whenever a capture feed
 * has no token.
 */
internal const val LISTEN_ROUTE = "__listen__"

@Composable
internal fun LightSyncSection(
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    /** Show detailed rows only when the user has turned on advanced settings. */
    advanced: Boolean,
    detail: String?,
    onDetail: (String?) -> Unit,
    /** Home Assistant's credentials, hoisted so the section stays stateless. */
    haUrl: String,
    onHaUrl: (String) -> Unit,
    haToken: String,
    onHaToken: (String) -> Unit,
    /** Opens the Ambience screen, which is a screen rather than a settings page. */
    onOpenAmbience: () -> Unit,
    /** Opens a server's own page under Media Providers, for the cross-link below. */
    onOpenServer: (String) -> Unit,
) {
    when (detail) {
        BRIDGE_ROUTE -> { DirectBridgeSetup(settings, scope, accent); return }
        ANALYSIS_ROUTE -> { TrackAnalysisCard(settings, accent, advanced); return }
        LISTEN_ROUTE -> { PhoneAudioPage(settings, scope, accent); return }
        HA_ROUTE -> {
            HomeAssistantCard(settings, accent, scope, haUrl, onHaUrl, haToken, onHaToken)
            return
        }
    }

    val mode by settings.lightSyncMode.collectAsStateWithLifecycle(initialValue = AppSettings.MODE_HA)
    val auto by settings.lightSyncModeAuto.collectAsStateWithLifecycle(initialValue = true)
    val direct = mode == AppSettings.MODE_DIRECT
    val bridgeIp by settings.hueBridgeIp.collectAsStateWithLifecycle(initialValue = "")
    val servers by settings.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val maServerId = servers.firstOrNull { it.kind == ServerKind.MUSIC_ASSISTANT }?.id

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "How the lights hear the music",
            lead = "Two ways to drive them. Which is right follows where the audio comes out.",
            info = "This page is only about the route between the music and the bulbs.\n\nThe show " +
                "itself, meaning which room, how hard it reacts, the effect and the colours, " +
                "lives on the Lights tab.\n\nTip: get the route working here first. Nothing on " +
                "the Lights tab will do anything until this page reports a connection.",
        ) {
            SegmentedToggleRow(
                labels = listOf("Home Assistant", "Hue Bridge"),
                selectedIndex = if (direct) 1 else 0,
            ) {
                // Choosing by hand pins the transport, so the library stops moving it.
                scope.launch {
                    settings.setLightSyncMode(
                        if (it == 1) AppSettings.MODE_DIRECT else AppSettings.MODE_HA,
                        manual = true,
                    )
                }
            }
            Note(
                if (direct) "Straight to the bridge over the LAN. This phone is the speaker."
                else "Through Home Assistant. Follows any Music Assistant player.",
                title = "The two routes",
                info = if (direct)
                    "The app talks to the Hue Bridge directly over the LAN, with no Home " +
                        "Assistant in the path at all. It follows this phone's own playback, so " +
                        "it works with Navidrome and it works offline.\n\nTiming is measured " +
                        "rather than guessed, so there is no offset to dial in.\n\nThe trade is " +
                        "that the phone is the only speaker it can follow.\n\nTip: this is the " +
                        "lower-latency route by a clear margin. Prefer it whenever the music is " +
                        "coming out of this phone."
                else
                    "Drives the Hue Synco integration in Home Assistant over its WebSocket " +
                        "API.\n\nThat can follow any Music Assistant player rather than just " +
                        "this one, so it works with multi-room grouping and the lights follow " +
                        "the group.\n\nTip: there is more delay on this route, since every " +
                        "frame goes through Home Assistant. The speaker offset below is how you " +
                        "take it out.",
            )
            if (auto) {
                Note(
                    "Chosen automatically from your library.",
                    title = "Following the library",
                    info = "A library this phone plays uses the bridge directly, because the audio " +
                        "is here. Music Assistant, which can play anywhere, goes through Home " +
                        "Assistant, because the audio may not be.\n\nPicking one above pins it " +
                        "and stops the library moving it.\n\nTip: leave this on unless you have " +
                        "a reason not to. It is right in almost every case, and it follows you " +
                        "when you switch library.",
                )
            } else {
                Text(
                    "Follow the library again",
                    color = accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { scope.launch { settings.setLightSyncModeAuto(true) } },
                )
            }
        }

        if (direct) {
            NavRow(
                Icons.Default.Router,
                "Hue Bridge",
                if (bridgeIp.isBlank()) "Not paired yet — press the button on the bridge"
                else "Paired at $bridgeIp",
                accent,
            ) { onDetail(BRIDGE_ROUTE) }
        } else {
            NavRow(
                Icons.Default.Router,
                "Home Assistant",
                if (haUrl.isBlank()) "Not set up yet" else haUrl,
                accent,
            ) { onDetail(HA_ROUTE) }
        }

        NavRow(
            Icons.Default.GraphicEq,
            "Track analysis",
            "Read tracks ahead of the show, so a song is exact from its first bar.",
            accent,
        ) { onDetail(ANALYSIS_ROUTE) }

        NavRow(
            Icons.Default.Hearing,
            "What this phone listens to",
            "Whether the lights follow this app, or whatever else is playing.",
            accent,
        ) { onDetail(LISTEN_ROUTE) }

        NavRow(
            Icons.Default.Waves,
            "Ambience & room shows",
            "Rain, fire, a storm — a lit room with its own sound, and no music needed.",
            accent,
            onClick = onOpenAmbience,
        )

        // The phone's own identity as a Music Assistant player — its name, the codec
        // it advertises, and the latency trim that lines it up against the rest of a
        // group — lives with the server it belongs to rather than here. This row is
        // the signpost, because "the lights are late on the multi-room group" is a
        // question people arrive at this page with.
        maServerId?.let { id ->
            NavRow(
                Icons.Default.Speaker,
                "Multi-room timing",
                "Latency trim and this phone's player settings, on the Music Assistant server.",
                accent,
            ) { onOpenServer(id) }
        }
    }
}

// ── Home Assistant ────────────────────────────────────────────────────────

@Composable
private fun HomeAssistantCard(
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    haUrl: String,
    onHaUrl: (String) -> Unit,
    haToken: String,
    onHaToken: (String) -> Unit,
) {
    var saved by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    /** A saved token is sealed: shown as dots, not editable until explicitly replaced. */
    var locked by remember { mutableStateOf(haToken.isNotBlank()) }
    LaunchedEffect(haToken.isNotBlank()) { if (haToken.isNotBlank()) locked = true }

    SettingsCard(
        title = "Home Assistant",
        lead = "Drives the Hue Synco light-sync integration.",
        info = "Needs a long-lived access token, which Home Assistant issues under your profile, " +
            "Security, at the bottom of the page.\n\nThe token is stored on this phone and sent " +
            "only to the address above. It is included in an encrypted settings backup.\n\nTip: " +
            "the address is the one you use in a browser, port included, for example " +
            "http://192.168.0.10:8123. If Home Assistant sits behind a reverse proxy, use the " +
            "internal address rather than the public one.",
    ) {
        OledField(haUrl, { onHaUrl(it); saved = false }, "Home Assistant address", "http://192.168.0.10:8123", accent)
        if (locked) {
            OledField(
                "•".repeat(24), {}, "Long-lived access token", "", accent,
                enabled = false,
                trailingIcon = { Icon(Icons.Default.Lock, "Saved", tint = TextMuted, modifier = Modifier.size(18.dp)) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OledButton(
                    if (saved) "Saved" else "Save",
                    enabled = haUrl.isNotBlank() && !saved,
                    accent = accent, modifier = Modifier.weight(1f),
                ) {
                    scope.launch { settings.setHomeAssistant(haUrl.trim(), haToken.trim()); saved = true }
                }
                OledButton("Replace token", accent = accent, outline = true, modifier = Modifier.weight(1f)) {
                    onHaToken(""); visible = false; saved = false; locked = false
                }
            }
        } else {
            SecretField(haToken, { onHaToken(it); saved = false }, "Long-lived access token", accent, visible, { visible = it }, "eyJ…")
            OledButton(
                if (saved) "Saved" else "Save",
                enabled = haUrl.isNotBlank() && haToken.isNotBlank(),
                accent = accent,
            ) {
                scope.launch {
                    settings.setHomeAssistant(haUrl.trim(), haToken.trim())
                    saved = true; visible = false; locked = true
                }
            }
        }
    }
}

// ── Bridge & analysis ─────────────────────────────────────────────────────

/**
 * The bridge discovery, pairing and status UI.
 *
 * 1. No bridge paired: "Discover bridges" → mDNS scan → tap one → press the link
 *    button → paired.
 * 2. Paired: bridge name, address, and a way to unpair. The entertainment area is
 *    picked on the Lights tab, where the show is.
 */
@Composable
private fun DirectBridgeSetup(settings: AppSettings, scope: CoroutineScope, accent: Color) {
    val context = LocalContext.current
    val app = context.applicationContext as SendpinApp
    val bridge = app.directLightSync.bridgeClient

    var bridgeIp by remember { mutableStateOf("") }
    var appKey by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf(listOf<DiscoveredBridge>()) }
    /** Set once a scan has run, so the "nothing found" hint only appears after one. */
    var tried by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bridgeIp = settings.hueBridgeIp.first()
        appKey = settings.hueAppKey.first()
        paired = bridgeIp.isNotBlank() && appKey.isNotBlank()
    }

    if (!paired) {
        SettingsCard(
            title = "Pair a Hue Bridge",
            lead = "The app talks to the bridge directly on your network. You will be asked to " +
                "press the link button on the bridge itself.",
        ) {
            if (!discovering && discovered.isEmpty()) {
                OledButton("Discover bridges", accent = accent) {
                    discovering = true
                    bridge.startDiscovery()
                    scope.launch {
                        // Philips' order: mDNS first, then the cloud endpoint, then a
                        // manual address. mDNS is the primary and usually answers
                        // within a second or two.
                        delay(8_000)
                        bridge.stopDiscovery()
                        if (bridge.discovered.value.isEmpty()) {
                            // Nothing on mDNS — some networks block multicast
                            // entirely. The cloud endpoint knows this network's
                            // bridges if any has ever been online, and is cached
                            // against its 15-minute rate limit.
                            bridge.addCloudDiscovered()
                        }
                        discovered = bridge.discovered.value
                        discovering = false
                        tried = true
                    }
                }
            }

            if (discovering) StatusLine("Scanning the network…", Health.WORKING, accent)

            if (!discovering && discovered.isEmpty() && tried) {
                Note(
                    "No bridge found. You can enter its address instead.",
                    warn = true,
                    title = "No bridge found",
                    info = "Some networks block the discovery protocol this uses, so a bridge that " +
                        "is working perfectly well can still be invisible here. Guest networks, " +
                        "and networks with client isolation switched on, are the usual " +
                        "culprits.\n\nThe bridge's address is on the Hue app's Settings, My Hue " +
                        "System screen, or in your router's list of connected devices.\n\nTip: " +
                        "check the phone is on the same network as the bridge and not on a " +
                        "guest SSID. That is the cause more often than anything else.",
                )
            }

            // Manual entry: the spec's required last resort, since mDNS can be blocked
            // and the cloud returns nothing for a bridge that has never been online.
            if (!discovering) {
                OledField(manualIp, { manualIp = it }, "Bridge address", "192.168.1.20", accent)
                if (manualIp.isNotBlank()) {
                    OledButton("Use this address", accent = accent, outline = true) {
                        bridge.addManual(manualIp)
                        discovered = bridge.discovered.value
                    }
                }
            }

            discovered.forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Glass).border(1.dp, Hairline, RoundedCornerShape(12.dp))
                        .clickable {
                            pairing = true
                            pairError = null
                            scope.launch {
                                try {
                                    val (key, clientKey) = bridge.pair(b.host)
                                    val id = bridge.fetchApplicationId(b.host, key) ?: ""
                                    // The bridge id comes off the mDNS TXT record and
                                    // is what the certificate's Common Name is checked
                                    // against on every later connection.
                                    settings.setHueBridge(b.host, key, clientKey, id, b.bridgeId)
                                    bridgeIp = b.host
                                    appKey = key
                                    paired = true
                                } catch (_: LinkButtonNotPressed) {
                                    pairError = "Press the link button on the bridge, then tap again"
                                } catch (e: Exception) {
                                    pairError = e.message ?: "Pairing failed"
                                }
                                pairing = false
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Router, null, tint = accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                        Text(b.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(b.host, color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp)
                    }
                    if (pairing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                }
            }

            pairError?.let { StatusLine(it, Health.BAD, accent) }
        }
    } else {
        SettingsCard(
            title = "Hue Bridge",
            lead = "Paired. Pick which entertainment area to light up on the Lights tab.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Router, "Bridge", tint = accent, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    StatusLine("Connected", Health.GOOD, accent)
                    Text(
                        bridgeIp, color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OledButton("Unpair", accent = accent, outline = true) {
                scope.launch {
                    settings.setHueBridge("", "", "")
                    settings.setHueConfigId("")
                    bridgeIp = ""; appKey = ""; paired = false
                }
            }
        }
    }
}

/**
 * Reading songs ahead of playing them.
 *
 * Everything the show can learn from the music as it arrives, it already does. What is
 * left needs the *whole* track — where the beats are before the first one has sounded,
 * how loud this song gets compared to other songs, where its sections are — and the
 * only way to have that is to have read it first. This is where that is turned on,
 * kicked off for a whole library, and undone.
 *
 * Moved here from the Lights tab: it is a background job with a storage cost, not a
 * light-show control, and it was sitting between the intensity pills and the colour
 * picker.
 *
 * What it now says about itself is the part that changed. A background job whose only
 * output was a count and a one-line "most recent problem" could be parked on a metered
 * connection, fail on forty files, or be working from months-old analyses, and look
 * identical in all three cases: a number that was not going up.
 *
 * All of which is diagnosis, and diagnosis is what [advanced] is for — the same line
 * the output card draws around its signal path. Simple keeps the two switches, how far
 * a running sweep has got, what is on disk, and the two buttons that start and undo the
 * whole thing. The per-track verdict, the files that would not read and the older-
 * analyser count are the rows someone asks for only once they are asking *why*.
 */
@Composable
private fun TrackAnalysisCard(settings: AppSettings, accent: Color, advanced: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as SendpinApp
    val scans = app.trackScans
    val scope = rememberCoroutineScope()

    val on by settings.lightSyncPrescan.collectAsStateWithLifecycle(initialValue = true)
    val wifiOnly by settings.lightSyncPrescanWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val progress by scans.progress.collectAsStateWithLifecycle()
    val playing by app.localPlayer.current.collectAsStateWithLifecycle()

    // Polled rather than recomputed per completed scan: counting the directory is a
    // listing, and doing one per track would turn a five-thousand-track sweep into
    // five thousand listings of a directory that is still growing.
    var usage by remember { mutableStateOf(0 to 0L) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showFailures by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            usage = scans.usage()
            delay(if (scans.progress.value.busy) 4_000L else 30_000L)
        }
    }

    // How many stored scans a newer analyser would improve on. Opening each file's
    // header is too much for the poll above, and the answer only changes when a sweep
    // ends or the app is updated — so it is asked on arrival and after each sweep.
    var outdated by remember { mutableStateOf(0) }
    LaunchedEffect(progress.sweeping) {
        if (!progress.sweeping) outdated = runCatching { scans.outdatedCount() }.getOrDefault(0)
    }

    // What the show knows about the song playing right now — the one track the reader
    // can check the claim against by looking at their own lights.
    var playingScan by remember { mutableStateOf<TrackScan?>(null) }
    LaunchedEffect(playing?.id, progress.current) {
        playingScan = playing?.let { runCatching { scans.cached(it) }.getOrNull() }
    }

    SettingsCard(
        title = "Track analysis",
        lead = "A song that has been read is exact from its first bar.",
        info = "Reading a track ahead means the beat grid is known rather than found, drops are " +
            "counted down to instead of noticed a moment late, and Auto can size the room to " +
            "how hard the song actually goes.\n\nSongs that have not been read still work. They " +
            "are simply learnt as they play, so the first chorus is where the lights catch " +
            "up.\n\nTip: run one sweep, on Wi-Fi and on charge. It is the single biggest " +
            "improvement to the show, and the results are cached, so it is a one-off.",
    ) {
        ToggleRow(
            "Read tracks ahead",
            when {
                progress.current != null -> "Analysing ${progress.current}"
                progress.waitingForNetwork -> "Waiting for Wi-Fi"
                progress.pending > 0 -> "${progress.pending} queued"
                on -> "New tracks are analysed in the background"
                else -> "The show works it out as it goes"
            },
            on, accent,
        ) { checked -> scope.launch { settings.setLightSyncPrescan(checked) } }

        if (!on) return@SettingsCard

        ToggleRow(
            "Analyse on Wi-Fi only",
            "Streamed tracks are downloaded to be read. Downloaded ones are always analysed, " +
                "network or not.",
            wifiOnly, accent,
        ) { checked -> scope.launch { settings.setLightSyncPrescanWifiOnly(checked) } }

        // ── This song ────────────────────────────────────────────────────
        if (advanced) {
            playing?.let { track -> PlayingTrackScan(track.title, playingScan, accent) }
        }

        // ── The sweep, while it runs ─────────────────────────────────────
        if (progress.sweeping || progress.waitingForNetwork) {
            SweepProgress(progress, accent)
        }

        val (count, bytes) = usage
        StatusPanel {
            StatusRow("Analysed", if (count == 0) "None yet" else count.toString())
            StatusRow("On disk", formatBytes(bytes))
            if (advanced && outdated > 0) StatusRow("From an older version", outdated.toString())
        }

        // ── What failed ──────────────────────────────────────────────────
        if (advanced && progress.failures.isNotEmpty()) {
            FailureList(
                failures = progress.failures,
                expanded = showFailures,
                onToggle = { showFailures = !showFailures },
                accent = accent,
                onRetry = scans::retryFailed,
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress.sweeping) {
                Pill("Stop sweep", true) { scans.cancelSweep() }
            } else {
                Pill("Analyse library", false) {
                    scans.sweep { ScanLibrarySource.tracks(context.applicationContext, app.downloads) }
                }
            }
            // Only offered when there is something to gain: a refresh with nothing out
            // of date is a full library decode that ends where it started.
            if (advanced && outdated > 0 && !progress.sweeping) {
                Pill("Refresh $outdated older", false) {
                    scans.sweep(refreshOutdated = true) {
                        ScanLibrarySource.tracks(context.applicationContext, app.downloads)
                    }
                }
            }
            if (advanced) {
                playing?.let { track -> Pill("Re-analyse this track", false) { scans.rescan(track) } }
            }
            // Two taps, because this can throw away hours of decoding — and on a
            // streamed library, a gigabyte of transfer — that nothing can get back
            // except by doing it all again.
            Pill(if (confirmDelete) "Tap again to delete" else "Delete analyses", confirmDelete) {
                if (confirmDelete) {
                    confirmDelete = false
                    scope.launch { scans.deleteAll(); usage = 0 to 0L; outdated = 0 }
                } else confirmDelete = true
            }
        }

        Note(
            "Covers downloads and the library this phone plays.",
            title = "What the sweep covers",
            info = "Downloads and the library this phone plays. Those are the only tracks the " +
                "direct path ever sees, so they are the only ones worth reading ahead.\n\nIt " +
                "skips anything already analysed, so stopping it and starting it again picks up " +
                "where it left off rather than beginning over.\n\nTip: it is safe to leave " +
                "running and safe to interrupt. Nothing is lost by stopping it partway.",
        )
    }
}

/**
 * What the analysis knows about the song playing right now.
 *
 * The card had a "Re-analyse this track" button and no way to tell whether the track had
 * been analysed, or whether the analysis was any good — so the button was a guess, and
 * the honest answer to "why did the lights not hit that drop" was not on the screen at
 * all. The grid confidence is the thing worth showing: below
 * [TrackScan.MIN_GRID_CONFIDENCE] the show falls back to finding the beat live, which is
 * exactly what a listener would describe as "it did not seem to know the song".
 */
@Composable
private fun PlayingTrackScan(title: String, scan: TrackScan?, accent: Color) {
    val (text, health) = when {
        scan == null -> "Not analysed yet, the show is working this one out as it plays" to Health.IDLE
        !scan.gridUsable -> "Analysed, but the beat is unclear, so the show tracks it live" to Health.WARN
        !scan.complete ->
            "Analysed for the first ${(scan.analysedS / 60f).toInt()} min; the rest is worked out live" to Health.WARN
        else -> "Analysed at ${scan.bpm.toInt()} BPM, beat grid known from the first bar" to Health.GOOD
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel("Playing now")
        Text(
            title, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        StatusLine(text, health, accent)
    }
}

/**
 * How far through the sweep is, and why it is not moving when it is not.
 *
 * A count with no total cannot say whether a sweep is nearly done or has barely started,
 * and "parked, waiting for Wi-Fi" was previously indistinguishable from "finished".
 */
@Composable
private fun SweepProgress(progress: ScanProgress, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        progress.sweepFraction?.let { MeterBar(it, color = accent) }
        val done = progress.sweepDone
        val total = progress.sweepTotal
        if (total > 0) {
            Text(
                "$done of $total" + (progress.current?.let { " · $it" } ?: ""),
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (progress.parked > 0) {
            StatusLine(
                "${progress.parked} waiting for Wi-Fi, they resume when you are back on one",
                Health.WARN, accent,
            )
        }
    }
}

/**
 * The tracks that produced no usable analysis, and one button for the ones worth
 * another go.
 *
 * Collapsed by default: the count is what most people want, and the list is what the
 * one person with a folder of broken files needs.
 */
@Composable
private fun FailureList(
    failures: List<ScanFailureNote>,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color,
    onRetry: () -> Unit,
) {
    val retryable = failures.count { it.retryable }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${failures.size} could not be analysed" + if (expanded) "" else ", tap to see them",
            color = WarnAmber, fontFamily = AppFont, fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onToggle),
        )
        // The label above says "tap to see them", so the panel should be seen to
        // arrive. As a bare `if` it did not: the list of failures — which can be long —
        // appeared between two frames and shoved everything below it down the screen
        // with no indication that the tap was what did it.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.contentSize()) + fadeIn(Motion.effects()),
            exit = shrinkVertically(Motion.contentSize()) + fadeOut(Motion.effects()),
        ) {
            StatusPanel {
                failures.forEach { failure ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            failure.title, color = TextSecondary, fontFamily = AppFont, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(failure.why, color = TextFaint, fontFamily = AppFont, fontSize = 11.sp)
                    }
                }
            }
        }
        if (retryable > 0) {
            // Only the ones that might come out differently. A silent file will be
            // silent again, and on a streamed library that retry costs a download.
            Pill("Try $retryable again", false, onClick = onRetry)
        }
    }
}


/**
 * The listening page: the card, and the capture state it needs.
 *
 * The collectors live here rather than in [PhoneAudioCard] so the card stays a plain
 * function of what it is told - it is the piece that moved, and moving it should not
 * have changed it.
 */
@Composable
private fun PhoneAudioPage(settings: AppSettings, scope: CoroutineScope, accent: Color) {
    val context = LocalContext.current
    val selected by settings.phoneAudioFeed.collectAsStateWithLifecycle(initialValue = "auto")
    val state by PlaybackCapture.state.collectAsStateWithLifecycle()

    PhoneAudioCard(
        selected = selected,
        state = state,
        accent = accent,
        onSelect = { choice ->
            scope.launch { settings.setPhoneAudioFeed(choice) }
            // The choice *is* the switch. This is the whole of the fix: picking a mode
            // that needs capture now starts capture, where before it only changed which
            // feed the picker would have preferred had anything ever started one.
            if (choice == "internal") {
                PlaybackCapture.stop(context)
            } else if (!PlaybackCapture.hasToken()) {
                PlaybackCapture.requestStart(context)
            }
        },
        onStartCapture = { PlaybackCapture.requestStart(context) },
    )
}

/**
 * What this phone's Light Sync is listening to, and the one control that decides it.
 *
 * ## Why this is one card and not two
 *
 * It used to be two, and they did not add up. A "Use this phone's audio" selector
 * offered Auto and Always projection, and a separate "React to other apps" switch
 * owned `MediaProjection`. But the selector only expressed a *preference* — the feed
 * picker consults it and then asks whether capture happens to be running — and
 * nothing in the selector ever started capture. So choosing Auto or Always
 * projection and expecting the room to follow Spotify did exactly nothing, forever,
 * with no error: the picker fell through to the local tap, which was silent, and the
 * lights ran the idle show. The two controls could also disagree by construction,
 * since either could be set to something the other contradicted.
 *
 * Now the choice is the switch. Picking a mode that needs capture asks for it, and
 * picking the one that does not stops it, so there is nothing left for a second
 * toggle to say.
 *
 * ## Why it is not a plain switch
 *
 * Capture needs a runtime microphone grant (which is what `AudioPlaybackCapture` is
 * gated on, even though no microphone is opened), a system consent dialog, and a
 * foreground service — and on Android 14+ that consent dialog reappears every single
 * time capture starts, because the projection token is single-use. So the card can
 * want capture and not have it, and it has to be able to say so and offer the way
 * back, rather than showing a switch that is on while nothing is listening.
 *
 * The blocked message is the part that earns the feature its keep. An app that opts
 * out of capture is delivered as bit-exact silence with no error of any kind, so
 * without saying so plainly the only observation available to the user is "the
 * lights do nothing", which is indistinguishable from a bug here.
 */
@Composable
internal fun PhoneAudioCard(
    selected: String,
    state: PlaybackCapture.State,
    accent: Color,
    onSelect: (String) -> Unit,
    onStartCapture: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wantsCapture = selected != "internal"
    var granted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    // Remembers which choice asked for the grant, so answering the dialog completes
    // that choice rather than starting capture for a mode the user has since left.
    var pendingChoice by remember { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        val choice = pendingChoice
        pendingChoice = null
        if (ok && choice != null) onSelect(choice)
    }

    fun choose(choice: String) {
        if (choice != "internal" && !granted) {
            pendingChoice = choice
            permission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        onSelect(choice)
    }

    GlassCard(radius = 18.dp, fill = if (wantsCapture) accent.a(0.10f) else Glass) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    "What this phone listens to",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text(
                    phoneFeedBlurb(selected, state),
                    color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                )
            }

            val options = listOf("Auto", "CAMusic only", "Other apps")
            val values = listOf("auto", "internal", "projection")
            SegmentedToggle(
                options = options,
                selectedIndex = values.indexOf(selected).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) { choose(values[it]) }

            Text(
                when (selected) {
                    "internal" ->
                        "Only CAMusic's own playback drives the lights. Nothing else on the " +
                            "phone is listened to, and no permission is needed."
                    "projection" ->
                        "Always listens through screen capture, even while CAMusic is playing. " +
                            "Pick this if you want another app's audio to win outright."
                    else ->
                        "Uses CAMusic's own audio while it is playing - which is exact and free - " +
                            "and listens to whatever else is playing when it is not."
                },
                color = TextFaint, style = MaterialTheme.typography.bodySmall,
            )

            if (state == PlaybackCapture.State.BLOCKED) {
                Text(
                    "That app does not allow its audio to be captured. YouTube, YouTube Music " +
                        "and most DRM-protected apps opt out, and nothing here can change that. " +
                        "Spotify, podcast apps and local players usually work.",
                    color = WarnAmber, style = MaterialTheme.typography.bodySmall,
                )
            }

            // Wanting capture and not having it is a normal, recurring state rather
            // than an error: the grant is single-use, so this is where every app
            // restart lands. Offer the way back instead of a switch that lies.
            if (wantsCapture && !PlaybackCapture.hasToken()) {
                Text(
                    "Android asks permission each time listening starts, and the grant cannot " +
                        "be remembered - so this needs one tap per app session.",
                    color = TextFaint, style = MaterialTheme.typography.bodySmall,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, accent.a(0.55f), RoundedCornerShape(12.dp))
                        .clickable {
                            if (granted) {
                                onStartCapture()
                            } else {
                                pendingChoice = selected
                                permission.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (state == PlaybackCapture.State.OFF) "Start listening"
                        else "Start again",
                        color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun phoneFeedBlurb(
    selected: String,
    state: PlaybackCapture.State,
): String = when {
    selected == "internal" -> "CAMusic's own playback."
    state == PlaybackCapture.State.RUNNING -> "Listening."
    state == PlaybackCapture.State.QUIET -> "Listening, nothing playing."
    state == PlaybackCapture.State.BLOCKED -> "That app will not allow it."
    state == PlaybackCapture.State.STARTING -> "Starting..."
    state == PlaybackCapture.State.STOPPED_BY_SYSTEM -> "Listening was stopped."
    state == PlaybackCapture.State.DENIED -> "Permission was not granted."
    selected == "projection" -> "Other apps on this phone, once started."
    else -> "CAMusic when it is playing, other apps when it is not."
}
