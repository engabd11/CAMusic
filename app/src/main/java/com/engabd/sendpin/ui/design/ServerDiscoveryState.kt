package com.engabd.sendpin.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.MediaServerDiscovery
import com.engabd.sendpin.discovery.NsdBrowser
import com.engabd.sendpin.discovery.PlexGdmDiscovery
import com.engabd.sendpin.library.DiscoveredServer
import com.engabd.sendpin.library.ServerKind
import kotlinx.coroutines.delay

/**
 * What a LAN scan for one [ServerKind] has found so far, for the add-a-server
 * screens on the phone (onboarding and Settings) and the TV setup screen alike.
 * Rendering is each screen's own; the scanning is all here, so the three cannot
 * drift apart on which kinds are discovered or how.
 */
@Stable
class ServerDiscoveryState internal constructor(
    /** False for a kind with no discovery source (Navidrome, Subsonic, foobar2000…). */
    val supported: Boolean,
    /** Still looking, with nothing to show yet. */
    val scanning: Boolean,
    /** Servers of the kind being set up. */
    val found: List<DiscoveredServer>,
    /**
     * Servers of a sibling kind that answered the same scan: a Jellyfin server
     * while Emby is being set up, or the reverse. Shown as a hint to go back and
     * pick that kind, never as a row that fills the address, since connecting to
     * it as this kind would fail.
     */
    val elsewhere: List<DiscoveredServer>,
    /** Start over: a fresh scan, or a restarted mDNS browse. */
    val rescan: () -> Unit,
)

/** How the scan for each kind is done. Null: nothing to scan with. */
private enum class Method { MA_MDNS, MPD_MDNS, MEDIA_BEACON, PLEX_GDM }

private fun methodFor(kind: ServerKind): Method? = when (kind) {
    ServerKind.MUSIC_ASSISTANT -> Method.MA_MDNS
    ServerKind.MPD -> Method.MPD_MDNS
    ServerKind.JELLYFIN, ServerKind.EMBY -> Method.MEDIA_BEACON
    ServerKind.PLEX -> Method.PLEX_GDM
    else -> null
}

/** Whether [kind] has a LAN discovery source at all. */
fun ServerKind.isDiscoverable(): Boolean = methodFor(this) != null

/**
 * How long an mDNS browse runs before an empty result is reported as "nothing
 * found". The browse keeps listening after that, so a slow responder still
 * turns up; this only stops the spinner from turning for ever.
 */
private const val BROWSE_SETTLE_MS = 4000L

/** Scans for servers of [kind] while the caller is in composition. */
@Composable
fun rememberServerDiscovery(kind: ServerKind): ServerDiscoveryState {
    var generation by remember(kind) { mutableIntStateOf(0) }
    val rescan: () -> Unit = { generation++ }
    return when (methodFor(kind)) {
        Method.MA_MDNS -> maDiscovery(generation, rescan)
        Method.MPD_MDNS -> mpdDiscovery(generation, rescan)
        Method.MEDIA_BEACON -> beaconDiscovery(kind, generation, rescan)
        Method.PLEX_GDM -> plexDiscovery(generation, rescan)
        null -> ServerDiscoveryState(false, false, emptyList(), emptyList(), rescan)
    }
}

@Composable
private fun browseSettled(generation: Int): Boolean {
    var settled by remember(generation) { mutableStateOf(false) }
    LaunchedEffect(generation) {
        delay(BROWSE_SETTLE_MS)
        settled = true
    }
    return settled
}

@Composable
private fun maDiscovery(generation: Int, rescan: () -> Unit): ServerDiscoveryState {
    val context = LocalContext.current
    // A fresh instance per screen visit rather than Playback's app-wide one: that
    // one answers "which server does this phone register on", this one "which
    // server do I add". NsdManager runs concurrent browses of one type happily.
    val discovery = remember { MaDiscovery(context) }
    val servers by discovery.discoveredServers.collectAsStateWithLifecycle()
    val browsing by discovery.isDiscovering.collectAsStateWithLifecycle()
    DisposableEffect(generation) {
        discovery.startDiscovery()
        onDispose { discovery.stopDiscovery() }
    }
    val settled = browseSettled(generation)
    return ServerDiscoveryState(
        supported = true,
        scanning = browsing && !settled && servers.isEmpty(),
        found = servers.map { DiscoveredServer(ServerKind.MUSIC_ASSISTANT, it.displayName, it.baseUrl) },
        elsewhere = emptyList(),
        rescan = rescan,
    )
}

@Composable
private fun mpdDiscovery(generation: Int, rescan: () -> Unit): ServerDiscoveryState {
    val context = LocalContext.current
    val browser = remember { NsdBrowser(context, "_mpd._tcp.") }
    val services by browser.services.collectAsStateWithLifecycle()
    val browsing by browser.browsing.collectAsStateWithLifecycle()
    DisposableEffect(generation) {
        browser.start()
        onDispose { browser.stop() }
    }
    val settled = browseSettled(generation)
    return ServerDiscoveryState(
        supported = true,
        scanning = browsing && !settled && services.isEmpty(),
        found = services.map {
            DiscoveredServer(ServerKind.MPD, "${it.name} (${it.host}:${it.port})", "http://${it.host}:${it.port}")
        },
        elsewhere = emptyList(),
        rescan = rescan,
    )
}

@Composable
private fun beaconDiscovery(kind: ServerKind, generation: Int, rescan: () -> Unit): ServerDiscoveryState {
    val context = LocalContext.current
    var scanning by remember(kind, generation) { mutableStateOf(true) }
    var replies by remember(kind) { mutableStateOf<List<MediaServerDiscovery.Found>>(emptyList()) }
    LaunchedEffect(kind, generation) {
        scanning = true
        replies = MediaServerDiscovery(context).scan()
        scanning = false
    }
    fun kindOf(fork: MediaServerDiscovery.Fork) =
        if (fork == MediaServerDiscovery.Fork.JELLYFIN) ServerKind.JELLYFIN else ServerKind.EMBY
    val all = replies.map { DiscoveredServer(kindOf(it.fork), it.name, it.url) }
    return ServerDiscoveryState(
        supported = true,
        scanning = scanning && all.isEmpty(),
        found = all.filter { it.kind == kind },
        elsewhere = all.filter { it.kind != kind },
        rescan = rescan,
    )
}

@Composable
private fun plexDiscovery(generation: Int, rescan: () -> Unit): ServerDiscoveryState {
    val context = LocalContext.current
    var scanning by remember(generation) { mutableStateOf(true) }
    var replies by remember { mutableStateOf<List<PlexGdmDiscovery.Found>>(emptyList()) }
    LaunchedEffect(generation) {
        scanning = true
        replies = PlexGdmDiscovery(context).scan()
        scanning = false
    }
    return ServerDiscoveryState(
        supported = true,
        scanning = scanning && replies.isEmpty(),
        found = replies.map { DiscoveredServer(ServerKind.PLEX, it.name, it.url) },
        elsewhere = emptyList(),
        rescan = rescan,
    )
}
