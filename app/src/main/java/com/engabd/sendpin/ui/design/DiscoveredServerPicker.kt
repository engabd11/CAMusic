package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.MediaServerDiscovery
import com.engabd.sendpin.library.DiscoveredServer
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Discovered-server rows for the "Server address" field on both add-a-server
 * screens (`OnboardingWizard.kt`'s `ConfigStep`, `LibrariesSettings.kt`'s
 * `ServerDetail`). Shown above the manual field, never instead of it — a
 * network that doesn't answer, or a kind with no discovery source at all,
 * always falls through to typing the address in. See
 * `docs/plan/server-mdns-discovery.md`.
 *
 * Music Assistant and Jellyfin/Emby only, both LAN scans triggered the
 * moment this composable enters. Plex's discovery rides its plex.tv sign-in
 * token instead of a scan — it's wired in separately, at that sign-in row
 * (`LibrariesSettings.kt`'s `PlexSignInRow`), reusing [DiscoveredServerRow]
 * for the same look rather than this dispatcher.
 */
@Composable
fun DiscoveredServerPicker(
    kind: ServerKind,
    accent: Color,
    onPicked: (DiscoveredServer) -> Unit,
) {
    when (kind) {
        ServerKind.MUSIC_ASSISTANT -> MaDiscoverySection(accent, onPicked)
        ServerKind.JELLYFIN, ServerKind.EMBY -> MediaServerDiscoverySection(kind, accent, onPicked)
        else -> Unit
    }
}

@Composable
private fun MaDiscoverySection(accent: Color, onPicked: (DiscoveredServer) -> Unit) {
    val context = LocalContext.current
    // A fresh instance per screen visit rather than sharing Playback's own
    // MaDiscovery/PlayerViewModel: this scan is answering a different
    // question ("which server do I even add") from that one's ("which
    // server does this phone register itself on"), and NsdManager supports
    // more than one concurrent listener for the same service type without
    // issue — see docs/plan/server-mdns-discovery.md.
    val discovery = remember { MaDiscovery(context) }
    val servers by discovery.discoveredServers.collectAsStateWithLifecycle()
    val discovering by discovery.isDiscovering.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        discovery.startDiscovery()
        onDispose { discovery.stopDiscovery() }
    }

    DiscoverySection(
        scanning = discovering && servers.isEmpty(),
        found = servers.map { DiscoveredServer(ServerKind.MUSIC_ASSISTANT, it.displayName, it.baseUrl) },
        glyph = "MA",
        accent = accent,
        onPicked = onPicked,
    )
}

@Composable
private fun MediaServerDiscoverySection(kind: ServerKind, accent: Color, onPicked: (DiscoveredServer) -> Unit) {
    val context = LocalContext.current
    var scanning by remember(kind) { mutableStateOf(true) }
    var found by remember(kind) { mutableStateOf<List<MediaServerDiscovery.Found>>(emptyList()) }

    // A one-shot scan, not an ongoing discovery lifecycle — see
    // MediaServerDiscovery's own doc for why. Re-scans if the user backs out
    // to the kind picker and chooses Jellyfin vs. Emby again (kind as key).
    LaunchedEffect(kind) {
        scanning = true
        found = MediaServerDiscovery(context).scan()
        scanning = false
    }

    DiscoverySection(
        scanning = scanning,
        found = found.map { DiscoveredServer(kind, it.name, it.url) },
        glyph = if (kind == ServerKind.JELLYFIN) "JF" else "EM",
        accent = accent,
        onPicked = onPicked,
    )
}

@Composable
private fun DiscoverySection(
    scanning: Boolean,
    found: List<DiscoveredServer>,
    glyph: String,
    accent: Color,
    onPicked: (DiscoveredServer) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (scanning && found.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                Text("Scanning your network…", color = TextMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
        } else if (found.isEmpty()) {
            Text("Nothing found on the network. Enter the address below.", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        } else {
            found.forEach { server ->
                DiscoveredServerRow(server.name, server.url, glyph, accent) { onPicked(server) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * One discovered-server row: a two-letter glyph, name and address, and a
 * tap target that hands the pick back rather than connecting immediately —
 * credentials (where the kind needs any) are still the caller's own field
 * below this. Public so `LibrariesSettings.kt`'s Plex sign-in row can reuse
 * the same look for plex.tv's resource list, which arrives a different way
 * than the LAN scans [DiscoveredServerPicker] runs.
 */
@Composable
fun DiscoveredServerRow(name: String, subtitle: String, glyph: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.a(0.14f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Use", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
