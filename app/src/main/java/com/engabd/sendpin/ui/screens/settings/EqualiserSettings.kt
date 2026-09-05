package com.engabd.sendpin.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.engabd.sendpin.ui.screens.LocalEqBody

/**
 * The ten-band equaliser, in Settings.
 *
 * It was already written and already reachable — from a panel behind the Now Playing
 * cover, which is the right place to reach for it mid-track. What it was not was
 * *findable*: the Audio Engine & DSP row has promised "equalizer" in its description
 * since that section was written, and there was no equaliser anywhere in Settings to
 * find. Somebody looking for one had no reason to go hunting in a player panel.
 *
 * The same body, so there is one equaliser rather than two that can disagree — see
 * [LocalEqBody], which writes straight to settings on every change. The Now Playing
 * panel stays exactly where it is.
 *
 * Local playback only, and the card says so rather than appearing to do nothing: a
 * Music Assistant stream is shaped by MA's own server-side DSP, which is configured
 * per player on the server and is a different thing with a different UI.
 */
@Composable
internal fun EqualiserCard(accent: Color) {
    SettingsCard(
        title = "Equaliser",
        lead = "Ten bands and a preamp, for the music this phone plays itself.",
        info = "A graphic equaliser: ten fixed bands from bass to air, each cut or boosted by " +
            "up to twelve decibels, with a preamp to take back the headroom a boost " +
            "costs.\n\nIt runs in this phone's own audio pipeline, so it shapes Navidrome, " +
            "Jellyfin, local files and downloads. A Music Assistant stream is shaped by Music " +
            "Assistant's own DSP instead, on the server, per player — that lives on the " +
            "server's page under Media Providers.\n\nPure and Direct to DAC output modes " +
            "deliberately build the player with no processors at all, so the curve is kept but " +
            "does nothing while one of those is selected.\n\nTip: cut rather than boost. " +
            "Pulling everything else down leaves more headroom than pushing one band up, and " +
            "no boost can add detail that was never recorded.",
    ) {
        LocalEqBody(accent)
    }
}
