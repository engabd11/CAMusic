package com.engabd.sendpin.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.ThemeChoice
import kotlinx.coroutines.launch

/**
 * Where Settings currently is. Small enough to be an inline sealed type rather
 * than a nav graph: the phone's Settings is a NavHost because it has a dozen
 * destinations, and this has three.
 */
private sealed interface TvSettingsRoute {
    data object Root : TvSettingsRoute
    data object PickLibraryKind : TvSettingsRoute
    data class ConnectLibrary(val kind: ServerKind) : TvSettingsRoute
    data object HueBridge : TvSettingsRoute
}

/**
 * MVP subset of the phone's `SettingsScreen.kt`: Libraries (switch or add a
 * server, reusing [LibraryViewModel] and [TvServerSetupScreen] exactly), Light
 * Sync (pair or unpair a Hue bridge, via [TvHueBridgeScreen]) and Appearance
 * (theme choice, reusing [AppSettings] exactly). Downloads/DSP/Stats/Driving
 * stay off this screen — each is a screen's worth of work whose feature is not
 * ported to TV yet, so there would be nothing behind the entry.
 *
 * The Libraries and Light Sync entries are what [TvLibraryScreen] and
 * [TvLightSyncScreen] send the user here for in their empty states; both of
 * those tabs are inert until something on this screen has been used.
 */
@Composable
fun TvSettingsScreen(libraryViewModel: LibraryViewModel = viewModel()) {
    var route by remember { mutableStateOf<TvSettingsRoute>(TvSettingsRoute.Root) }

    when (val here = route) {
        is TvSettingsRoute.Root -> TvSettingsRoot(
            libraryViewModel = libraryViewModel,
            onAddLibrary = { route = TvSettingsRoute.PickLibraryKind },
            onHueBridge = { route = TvSettingsRoute.HueBridge },
        )

        is TvSettingsRoute.PickLibraryKind -> TvPickLibraryKind(
            onPick = { route = TvSettingsRoute.ConnectLibrary(it) },
            onBack = { route = TvSettingsRoute.Root },
        )

        is TvSettingsRoute.ConnectLibrary -> TvServerSetupScreen(
            kind = here.kind,
            libraryViewModel = libraryViewModel,
            onConnected = { route = TvSettingsRoute.Root },
            onBack = { route = TvSettingsRoute.PickLibraryKind },
        )

        is TvSettingsRoute.HueBridge -> TvHueBridgeScreen(
            onBack = { route = TvSettingsRoute.Root },
        )
    }
}

@Composable
private fun TvPickLibraryKind(onPick: (ServerKind) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState())) {
        Text("Add a library", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("What should CAMusic play from?", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        TvLibraryKindTiles(onPick = onPick)
        Spacer(Modifier.height(20.dp))
        com.engabd.sendpin.tv.design.TvButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun TvSettingsRoot(
    libraryViewModel: LibraryViewModel,
    onAddLibrary: () -> Unit,
    onHueBridge: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current

    val servers by libraryViewModel.allServers.collectAsStateWithLifecycle()
    val activeConfig by libraryViewModel.activeServerConfig.collectAsStateWithLifecycle()
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = "oled")
    // Only the address is read here, and only to say paired vs. not — the app key it
    // is paired *with* has no business being on a screen shown across a living room.
    val hueIp by settings.hueBridgeIp.collectAsStateWithLifecycle(initialValue = "")

    // Scrollable rather than a plain Column: this is now four sections tall, and a
    // 10-foot layout has fewer rows on screen than a phone does, not more. Compose
    // scrolls a newly focused child into view inside a scrollable parent, so the
    // D-pad walks the whole screen without any explicit scroll handling.
    Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        SectionLabel("Libraries")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (servers.isEmpty()) {
                Text("No libraries configured yet.", color = TextMuted)
            } else {
                servers.forEach { server ->
                    TvTile(
                        onClick = { libraryViewModel.selectServer(server) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(server.kind.label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (activeConfig?.id == server.id) {
                                Text("Active", color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            TvTile(onClick = onAddLibrary, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Add a library", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Music Assistant, Navidrome, Jellyfin or this device", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Light Sync")
        Spacer(Modifier.height(10.dp))
        TvTile(onClick = onHueBridge, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hue Bridge", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hueIp.isBlank()) "Not paired — pair one to use the Light Sync tab"
                        else "Paired with $hueIp",
                        color = TextMuted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Appearance")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeChoice.entries.forEach { choice ->
                TvTile(
                    onClick = { scope.launch { settings.setTheme(choice.key) } },
                    shape = RoundedCornerShape(100),
                ) {
                    Text(
                        choice.label,
                        color = if (choice.key == themeKey) accent else TextMuted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("About")
        Spacer(Modifier.height(10.dp))
        Text("CAMusic TV — v${BuildConfig.VERSION_NAME}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}
