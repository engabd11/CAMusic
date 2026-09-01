package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How the player *behaves*, as distinct from how it looks — gestures, the local
 * auto-queue's ranking, and what the app remembers about a play. Split out of
 * Appearance, which used to carry these alongside the theme and accent picker
 * under the doc comment "how it looks, and how it behaves" — a mix that had
 * nothing to do with new-user legibility and everything to do with there being
 * nowhere else for a behavioral setting to go.
 */
@Composable
internal fun BehaviorSection(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val showVisualizer by settings.showVisualizer.collectAsStateWithLifecycle(initialValue = false)
    val swipeToSkip by settings.swipeToSkip.collectAsStateWithLifecycle(initialValue = false)
    val sensorGestures by settings.sensorGestures.collectAsStateWithLifecycle(initialValue = false)
    val djMode by settings.djMode.collectAsStateWithLifecycle(initialValue = false)
    val listeningDna by settings.listeningDna.collectAsStateWithLifecycle(initialValue = false)
    val offset by settings.lyricsOffsetMs.collectAsStateWithLifecycle(initialValue = 0)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "On the player",
            lead = "Tap the cover on Now Playing any time to flip it to this. Off by default.",
        ) {
            ToggleRow(
                title = "Visualizer by default",
                subtitle = "Start Now Playing already showing it, instead of the cover",
                checked = showVisualizer,
                accent = accent,
                info = "The live visualizer reads the same analysis Light Sync already runs, " +
                    "drawn full-size in the cover's own place. It's always one tap away — " +
                    "this only decides what Now Playing opens on.",
            ) { scope.launch { settings.setShowVisualizer(it) } }
        }

        SettingsCard(
            title = "Hands off the screen",
            lead = "Ways to change track without looking at the phone. Both off by default.",
        ) {
            ToggleRow(
                title = "Swipe to skip",
                subtitle = "On Now Playing: swipe right for next, left for previous",
                checked = swipeToSkip,
                accent = accent,
            ) { scope.launch { settings.setSwipeToSkip(it) } }
            ToggleRow(
                title = "Shake, flip and tap",
                subtitle = "Shake to skip, lie it face-down to pause, double-tap the body to play/pause",
                checked = sensorGestures,
                accent = accent,
                info = "Shake: a sharp jerk skips to the next track. Flip face-down (with the " +
                    "proximity sensor covered, the way it would be lying screen-down on a " +
                    "table): pauses. Double-tap the phone's body: play/pause.\n\nAll three read " +
                    "the accelerometer and proximity sensor, and only while this is on — " +
                    "nothing is sent anywhere.",
            ) { on ->
                scope.launch { settings.setSensorGestures(on) }
                if (on) SendpinApp.instance.playbackGestureMonitor.start()
                else SendpinApp.instance.playbackGestureMonitor.stop()
            }
        }

        SettingsCard(
            title = "What the track scans are for",
            lead = "Two uses for the key and tempo the offline scan already works out. Both need " +
                "tracks that have been scanned, and both are off by default.",
        ) {
            ToggleRow(
                title = "Harmonic DJ mode",
                subtitle = "Weighs compatible key and tempo into the local auto-queue's ranking",
                checked = djMode,
                accent = accent,
                info = "\"Keep the music going\" already ranks the next few tracks by genre and " +
                    "artist on every library this phone plays itself. DJ mode adds a bonus " +
                    "on top of that ranking — never instead of it — for tracks whose key sits " +
                    "well against what's playing on the Camelot wheel (the same or relative key, " +
                    "or one step around it) and whose tempo is close, or exactly half or double " +
                    "time. It has nothing to weigh while the queue is carrying on through an " +
                    "album in order, which is a running order rather than a ranking." +
                    "\n\nApplies whether that library is reached online or from downloads " +
                    "on this phone. Never Music Assistant, which tops up its own queue " +
                    "server-side with no key or tempo data to weigh. An unscanned track simply " +
                    "keeps its plain genre/artist ranking.",
            ) { scope.launch { settings.setDjMode(it) } }
            ToggleRow(
                title = "Listening DNA",
                subtitle = "Log each play's key and tempo, for the Stats screen",
                checked = listeningDna,
                accent = accent,
                info = "Adds a dominant-keys and a BPM sweet-spot breakdown to Stats, from a " +
                    "snapshot of each played track's key and tempo kept alongside the usual " +
                    "listening history.\n\nNothing extra is stored until this is turned on, and " +
                    "turning it off again stops new snapshots without deleting the ones already " +
                    "logged.",
            ) { scope.launch { settings.setListeningDna(it) } }
        }

        SettingsCard(
            title = "Lyrics timing",
            lead = "Nudge synced lyrics against the vocal.",
            info = "Providers stamp the same track differently, so there is no right answer here, " +
                "only what looks in time to you. Adjustments snap to 50 ms, which is finer than " +
                "anyone can pick out against a sung line.\n\nThe offset applies to every track " +
                "rather than being remembered per song.\n\nTip: set it against a slow, clear " +
                "vocal rather than a fast one. If lyrics run late on some songs and early on " +
                "others, that is the provider disagreeing with itself and no single offset will " +
                "fix both.",
        ) {
            SliderRow(
                value = (offset + AppSettings.MAX_LYRICS_OFFSET_MS) /
                    (2f * AppSettings.MAX_LYRICS_OFFSET_MS),
                format = { if (offset == 0) "0 ms" else "%+d ms".format(offset) },
                onChange = { f ->
                    val ms = (f * 2f * AppSettings.MAX_LYRICS_OFFSET_MS -
                        AppSettings.MAX_LYRICS_OFFSET_MS).toInt()
                    scope.launch { settings.setLyricsOffsetMs((ms / 50) * 50) }
                },
            )
            Note("Later ← → earlier")
        }
    }
}
