package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.SessionButtons
import com.engabd.sendpin.ui.design.ToggleChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Extra buttons in the shade, the lock screen and on a watch. Off by default. */
@Composable
internal fun MediaButtonsCard(settings: AppSettings, scope: CoroutineScope) {
    val choice by settings.mediaButtons.collectAsStateWithLifecycle(initialValue = SessionButtons.OFF)
    SettingsCard(
        title = "Media controls",
        lead = "Two extra buttons beside play, in the shade and on the lock screen.",
        info = "Android 13 and later draw the media controls in the shade, on the lock screen " +
            "and on a watch from this app's player, with room for two buttons next to " +
            "previous, play and next.\n\nThe heart favourites the song on its own library, " +
            "and only appears for a library that keeps favourites. Shuffle and repeat are the " +
            "same switches as on Now Playing.\n\nFor music playing on this phone. A Music " +
            "Assistant speaker keeps Music Assistant's own controls.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Off", choice == SessionButtons.OFF) {
                    scope.launch { settings.setMediaButtons(SessionButtons.OFF) }
                }
                ToggleChip("Heart and shuffle", choice == SessionButtons.FAV_SHUFFLE) {
                    scope.launch { settings.setMediaButtons(SessionButtons.FAV_SHUFFLE) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Heart and repeat", choice == SessionButtons.FAV_REPEAT) {
                    scope.launch { settings.setMediaButtons(SessionButtons.FAV_REPEAT) }
                }
                ToggleChip("Shuffle and repeat", choice == SessionButtons.SHUFFLE_REPEAT) {
                    scope.launch { settings.setMediaButtons(SessionButtons.SHUFFLE_REPEAT) }
                }
            }
        }
        Note(
            if (choice == SessionButtons.OFF) "Just previous, play and next, as before."
            else "Shown for music playing on this phone, on Android 13 and later.",
        )
    }
}
