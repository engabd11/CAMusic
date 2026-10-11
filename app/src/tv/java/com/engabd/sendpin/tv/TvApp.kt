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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
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
import com.engabd.sendpin.tv.screens.TvSearchScreen
import com.engabd.sendpin.tv.screens.TvSettingsScreen
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

internal enum class TvTab(val label: String, val icon: ImageVector) {
    NOW_PLAYING("Now Playing", Icons.Default.PlayArrow),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    SEARCH("Search", Icons.Default.Search),
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

    // Focus: the rail takes it at launch, on the tab that is open; the content keeps
    // track of where it was, so Left then Right comes back to the same tile; and a
    // control that removes itself (opening an album, a settings page) hands the focus
    // back to the content rather than to the top of the rail. See [TvFocus].
    val railSelected = remember { FocusRequester() }

    // A voice request ("play ... on CAMusic") lands on Now Playing. See TvMainActivity.
    // The focus goes with it: left where it was, it sat on the tab the user had been
    // on, and the next OK went straight back there.
    LaunchedEffect(Unit) {
        TvRequests.tab.collect { asked ->
            if (asked != null) {
                tab = asked
                TvRequests.tab.value = null
                withFrameNanos { }
                runCatching { railSelected.requestFocus() }
            }
        }
    }

    val content = remember { FocusRequester() }
    var lastKey by remember { mutableStateOf(Key.Unknown) }
    var tabAtKey by remember { mutableStateOf(tab) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { railSelected.requestFocus() } }

    Row(
        Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                lastKey = event.key
                tabAtKey = tab
            }
            false
        },
    ) {
        TvRail(
            selected = tab,
            onSelect = { tab = it },
            selectedRequester = railSelected,
            onFocusArrived = {
                if (TvFocus.returnToContent(lastKey, tabChanged = tabAtKey != tab)) {
                    scope.launch {
                        // The screen that replaced the control is composed but may not
                        // be laid out yet; a frame later it can take the focus. A screen
                        // that already placed it itself (the library, returning to the
                        // tile it opened) keeps its choice.
                        withFrameNanos { }
                        if (!contentHasFocus) runCatching { content.requestFocus() }
                    }
                }
            },
        )
        Box(Modifier.fillMaxHeight().width(1.dp).background(Glass))
        Box(Modifier.fillMaxSize()) {
            // The wash goes in first so it sits behind the screen rather than over
            // it, and outside the safe-area inset below, so it still reaches the edge.
            // Only under this tab: it is the album's colour, and the album is what
            // this tab is about.
            if (tab == TvTab.NOW_PLAYING) TvAmbientBackground()
            Box(
                Modifier
                    .fillMaxSize()
                    // The rest of the TV safe area (48 dp a side): the screens already
                    // pad themselves by 32 to 40 dp.
                    .padding(end = 16.dp)
                    .focusRequester(content)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusRestorer()
                    .focusGroup(),
            ) {
                when (tab) {
                    TvTab.NOW_PLAYING -> TvNowPlayingScreen()
                    TvTab.LIBRARY -> TvLibraryScreen()
                    TvTab.SEARCH -> TvSearchScreen()
                    TvTab.QUEUE -> TvQueueScreen()
                    TvTab.LIGHT_SYNC -> TvLightSyncScreen()
                    TvTab.SETTINGS -> TvSettingsScreen()
                }
            }
        }
    }
}

/**
 * The tabs, inside the TV safe area: a television may crop up to about 5% of each
 * edge (48 dp across, 27 dp down), so the rail's colour runs to the edge and its
 * items start inside that margin. They used to sit 12 dp from the corner.
 */
@Composable
private fun TvRail(
    selected: TvTab,
    onSelect: (TvTab) -> Unit,
    selectedRequester: FocusRequester,
    onFocusArrived: () -> Unit,
) {
    val accent = LocalAccent.current
    var hadFocus by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxHeight()
            .background(Ink2)
            .padding(start = 36.dp)
            .width(96.dp)
            .padding(vertical = 27.dp)
            .onFocusChanged {
                if (it.hasFocus && !hadFocus) onFocusArrived()
                hadFocus = it.hasFocus
            }
            // Coming back to the rail lands on the tab that is open, not on whichever
            // item happens to be nearest the control the focus left.
            .focusRestorer(selectedRequester)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvTab.entries.forEach { entry ->
            val on = entry == selected
            TvTile(
                onClick = { onSelect(entry) },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .then(if (on) Modifier.focusRequester(selectedRequester) else Modifier),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(
                    Modifier.padding(vertical = 12.dp).fillMaxWidth(),
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
