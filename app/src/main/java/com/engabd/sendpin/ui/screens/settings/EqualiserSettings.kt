package com.engabd.sendpin.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.SignalPath
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.screens.DspBody
import com.engabd.sendpin.ui.screens.LocalEqBody
import com.engabd.sendpin.ui.viewmodel.DspViewModel

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
 * ## Why there is a status line
 *
 * This curve only exists in the sink of the player *this phone* runs, so what it is
 * doing depends entirely on who is making the sound — and this page can be opened at
 * any time, including while something is playing that it cannot touch. Printing "ten
 * bands" over a control that is doing nothing is the failure this app keeps repeating,
 * and it is worse here than most: an equaliser that appears to work and changes
 * nothing reads as a broken equaliser rather than an inapplicable one.
 */
@Composable
internal fun EqualiserCard(accent: Color) {
    val context = LocalContext.current
    val app = context.applicationContext as SendpinApp

    // Who is actually making the sound. The same question Now Playing asks to decide
    // which DSP panel to show — see NowPlayingPanels — read here from the
    // process-scoped players rather than from a view model, because a settings page has
    // no session of its own and should not start one.
    val maNow by app.maNowPlaying.now.collectAsStateWithLifecycle()
    val localActive by app.localPlayer.active.collectAsStateWithLifecycle()
    val mpdRemote by app.localPlayer.remoteActive.collectAsStateWithLifecycle()
    val signal by SignalPath.state.collectAsStateWithLifecycle()

    val status = equaliserStatus(
        maPlayerName = maNow?.playerName,
        maIsSelf = maNow?.isSelf,
        localActive = localActive,
        mpdRemote = mpdRemote,
        processorsBypassed = signal.processorsBypassed,
    )

    SettingsCard(
        title = "Equaliser",
        lead = "Ten bands and a preamp, for the music this phone plays itself.",
        info = "A graphic equaliser: ten fixed bands from bass to air, each cut or boosted by " +
            "up to twelve decibels, with a preamp to take back the headroom a boost " +
            "costs.\n\nIt runs in this phone's own audio pipeline, so it shapes Navidrome, " +
            "Jellyfin, Plex, local files and downloads. Music Assistant is shaped by Music " +
            "Assistant's own DSP instead — on the server, per player — which is the second " +
            "card on this page whenever a Music Assistant server is set up. MPD decodes on " +
            "the server too, and exposes nothing this app can drive.\n\nPure and Direct to " +
            "DAC output modes deliberately build the player with no processors at all, and " +
            "High-resolution does the same whenever float output actually engages, so the " +
            "curve is kept but does nothing while one of those is in effect.\n\nTip: cut " +
            "rather than boost. Pulling everything else down leaves more headroom than " +
            "pushing one band up, and no boost can add detail that was never recorded.",
    ) {
        Note(status.line, warn = status.warn)
        LocalEqBody(accent)
    }
}

/**
 * The Music Assistant server-side DSP, on the same page as the local equaliser.
 *
 * Two engines on two machines, and neither can describe the other — the same split Now
 * Playing makes with one chip. They share a page rather than a card because "equaliser"
 * is one thing to go looking for, and which of the two answers depends on what happens
 * to be playing at the time, which is not something to make somebody guess at.
 *
 * Only rendered when there is a Music Assistant server to configure; [DspBody] handles
 * the rest of the states — not connected, loading, a save the server refused — itself.
 */
@Composable
internal fun MaDspCard(accent: Color) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val maUrl by settings.maBaseUrl.collectAsStateWithLifecycle(initialValue = "")
    if (maUrl.isBlank()) return

    val viewModel: DspViewModel = viewModel()
    SettingsCard(
        title = "Music Assistant DSP",
        lead = "Parametric bands, tone and gain, applied on the server to one player.",
        info = "Music Assistant shapes the audio itself, before it reaches whatever is " +
            "playing it — so this is per *player* and lives on the server, and it keeps " +
            "working when this phone is not involved at all.\n\nIt is a parametric " +
            "equaliser rather than the ten fixed bands above: each band has its own " +
            "frequency, width and gain. That is the right shape for a room, and the wrong " +
            "shape for a pair of headphones on a train, which is why both exist.\n\nChanges " +
            "here are not live — they are sent when you save.",
    ) {
        DspBody(viewModel, accent, Modifier)
    }
}

/** What the local equaliser is doing right now, and whether that is worth a warning. */
internal data class EqualiserStatus(val line: String, val warn: Boolean)

/**
 * Which engine owns the sound, in one sentence.
 *
 * Pure, so it can be tested without a player, and ordered by precedence rather than by
 * likelihood. Music Assistant comes first because on that path the audio never enters
 * the media3 sink at all — it is decoded by the native Sendspin engine — so a bypassed
 * processor chain is not even the reason the curve is idle, and saying so would send
 * somebody to the Output page to fix something that is not the problem.
 */
internal fun equaliserStatus(
    maPlayerName: String?,
    maIsSelf: Boolean?,
    localActive: Boolean,
    mpdRemote: Boolean,
    processorsBypassed: Boolean,
): EqualiserStatus = when {
    maIsSelf == true -> EqualiserStatus(
        "Not running. Music Assistant is streaming to this phone through its own engine, " +
            "which this curve is not part of — use the Music Assistant DSP below.",
        warn = true,
    )

    maIsSelf == false -> EqualiserStatus(
        "Not running. Music Assistant is playing on ${maPlayerName ?: "another speaker"}, " +
            "and shapes it with its own DSP on the server — that is the card below.",
        warn = true,
    )

    mpdRemote -> EqualiserStatus(
        "Not running. MPD is decoding this on the server, so there is no signal on this " +
            "phone to shape.",
        warn = true,
    )

    processorsBypassed -> EqualiserStatus(
        "Not running. The output mode in use builds the player with no processors at all — " +
            "see Output & signal path. The curve is kept, and applies again as soon as you " +
            "step back to Standard.",
        warn = true,
    )

    localActive -> EqualiserStatus("Shaping what's playing.", warn = false)

    // Nothing is playing. Not a warning: the curve is stored and will be used.
    else -> EqualiserStatus(
        "Ready. This shapes anything this phone decodes — your library, and downloads.",
        warn = false,
    )
}
