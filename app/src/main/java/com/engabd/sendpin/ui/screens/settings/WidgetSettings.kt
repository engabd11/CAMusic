package com.engabd.sendpin.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.LocalAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The home-screen widget's one look option. Off by default. */
@Composable
internal fun WidgetCard(settings: AppSettings, scope: CoroutineScope) {
    val cover by settings.widgetCover.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "Home-screen widget",
        lead = "What's playing, with previous, play and next.",
        info = "To add it, long-press an empty spot on the home screen, choose Widgets and " +
            "find CAMusic.\n\nTap the widget to open the app. Made one row high, it puts " +
            "the song beside play and next.",
    ) {
        ToggleRow(
            title = "Show the cover",
            subtitle = "The album art beside the song, on a widget three cells wide or more",
            checked = cover,
            accent = LocalAccent.current,
        ) { on -> scope.launch { settings.setWidgetCover(on) } }
    }
}
