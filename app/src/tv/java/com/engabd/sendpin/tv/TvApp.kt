package com.engabd.sendpin.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.tv.screens.TvAmbientBackground
import com.engabd.sendpin.tv.screens.TvLibraryScreen
import com.engabd.sendpin.tv.screens.TvLightSyncScreen
import com.engabd.sendpin.tv.screens.TvNowPlayingScreen
import com.engabd.sendpin.tv.screens.TvOnboardingScreen
import com.engabd.sendpin.tv.screens.TvQueueScreen
import com.engabd.sendpin.tv.screens.TvSettingsScreen
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

private enum class TvTab(val label: String, val icon: ImageVector) {
    NOW_PLAYING("Now Playing", Icons.Default.PlayArrow),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    QUEUE("Queue", Icons.AutoMirrored.Filled.QueueMusic),
    LIGHT_SYNC("Light Sync", Icons.Default.Lightbulb),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * The TV app's root composable. No shared-element transitions, no window-size-
 * class math, no bottom nav — those are phone concerns (see ui/App.kt). A fixed
 * left rail plus a content pane is the standard 10-foot layout, and it means
 * D-pad left/right always has exactly one meaning: rail vs. content.
 *
 * Every screen behind the rail is new View-layer code driving an existing
 * ViewModel/singleton unchanged — see each screen file for which one.
 */
@Composable
fun TvApp() {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context.applicationContext) }
    val onboarded by settings.onboardingCompleted.collectAsStateWithLifecycle(initialValue = settings.hasCompletedOnboarding)

    if (!onboarded) {
        TvOnboardingScreen(onDone = { /* recomposes once the flow writes onboardingCompleted */ })
        return
    }

    var tab by rememberSaveable { mutableStateOf(TvTab.NOW_PLAYING) }

    // Back returns to the app's home tab before it leaves the app, which is what a
    // TV remote's Back key is expected to do — dropping the user onto the launcher
    // from four levels in is the phone's "up" behaviour, not a TV's.
    //
    // Registered before the content below on purpose: the OnBackPressedDispatcher
    // gives the most recently added *enabled* callback the event, so a screen with
    // its own handler (TvLibraryScreen inside a browse stack, the Settings
    // sub-screens) still wins, and this only fires once none of them wants it.
    BackHandler(enabled = tab != TvTab.NOW_PLAYING) { tab = TvTab.NOW_PLAYING }

    Row(Modifier.fillMaxSize().background(Ink)) {
        TvRail(selected = tab, onSelect = { tab = it })
        Box(Modifier.fillMaxHeight().width(1.dp).background(Glass))
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                // The wash goes in first so it sits behind the screen rather than
                // over it. Only under this tab: it is the album's colour, and the
                // album is what this tab is about.
                TvTab.NOW_PLAYING -> {
                    TvAmbientBackground()
                    TvNowPlayingScreen()
                }
                TvTab.LIBRARY -> TvLibraryScreen()
                TvTab.QUEUE -> TvQueueScreen()
                TvTab.LIGHT_SYNC -> TvLightSyncScreen()
                TvTab.SETTINGS -> TvSettingsScreen()
            }
        }
    }
}

@Composable
private fun TvRail(selected: TvTab, onSelect: (TvTab) -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxHeight().width(96.dp).background(Ink2).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvTab.entries.forEach { entry ->
            val on = entry == selected
            TvTile(
                onClick = { onSelect(entry) },
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(entry.icon, entry.label, tint = if (on) accent else TextMuted, modifier = Modifier.size(22.dp))
                    Text(
                        entry.label,
                        color = if (on) TextPrimary else TextMuted,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
