package com.engabd.sendpin.tv

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where the D-pad focus belongs when the rail takes it.
 *
 * A control that opens something inside a tab (an album, a settings page) is
 * removed by what it opens, and Compose hands the focus to the first control on
 * screen, which is the rail's top item. So opening an album left the focus on
 * Now Playing, and the next OK left the library. The rail is only ever *meant*
 * to gain focus by the user pressing Left (or at launch), so any other way in is
 * a focused control disappearing, and the focus goes back into the content.
 */
object TvFocus {
    /**
     * Whether a rail that just took the focus should hand it back to the content.
     * [lastKey] is the last key pressed ([Key.Unknown] before any), and [tabChanged]
     * whether that key also changed the tab (Back to Now Playing, say), in which
     * case the content it came from is gone and the rail is the right place.
     */
    fun returnToContent(lastKey: Key, tabChanged: Boolean): Boolean =
        lastKey != Key.Unknown && lastKey != Key.DirectionLeft && !tabChanged
}

/**
 * A tab asked for from outside the TV app's composition: a voice request
 * ("play … on CAMusic") sends the user to Now Playing. Held until [TvApp] takes it,
 * because a cold start asks before there is any UI.
 */
internal object TvRequests {
    val tab = MutableStateFlow<TvTab?>(null)
}
