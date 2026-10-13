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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.library.DiscoveredServer
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Discovered-server rows for the "Server address" field on both add-a-server
 * screens (`OnboardingWizard.kt`'s `ConfigStep`, `LibrariesSettings.kt`'s
 * `ServerDetail`). Shown above the manual field, never instead of it: a
 * network that doesn't answer, or a kind with no discovery source at all,
 * always falls through to typing the address in. See
 * `docs/plan/server-mdns-discovery.md`.
 *
 * The scanning itself is [rememberServerDiscovery]'s, shared with the TV setup
 * screen. Plex is scanned on the LAN here as well; its plex.tv sign-in also
 * lists the account's servers, remote ones included (`PlexSignInRow`), with
 * the same [DiscoveredServerRow] look.
 */
@Composable
fun DiscoveredServerPicker(
    kind: ServerKind,
    accent: Color,
    onPicked: (DiscoveredServer) -> Unit,
) {
    val state = rememberServerDiscovery(kind)
    if (!state.supported) return
    val glyph = when (kind) {
        ServerKind.MUSIC_ASSISTANT -> "MA"
        ServerKind.JELLYFIN -> "JF"
        ServerKind.EMBY -> "EM"
        ServerKind.PLEX -> "PL"
        else -> "MP"
    }
    Column(Modifier.fillMaxWidth()) {
        when {
            state.scanning -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    Text("Scanning your network…", color = TextMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            state.found.isEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Nothing found on the network. Enter the address below.",
                        color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    ScanAgain(accent, state.rescan)
                }
                Spacer(Modifier.height(10.dp))
            }
            else -> {
                state.found.forEach { server ->
                    DiscoveredServerRow(server.name, server.url, glyph, accent) { onPicked(server) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ScanAgain(accent, state.rescan) }
                Spacer(Modifier.height(6.dp))
            }
        }
        state.elsewhere.forEach { other ->
            Text(
                "Also found ${other.name}, a ${other.kind.label} server. To add it, go back and choose ${other.kind.label}.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScanAgain(accent: Color, onClick: () -> Unit) {
    Text(
        "Scan again",
        color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Scan the network again", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
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
