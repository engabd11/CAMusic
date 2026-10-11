package com.engabd.sendpin.service

/**
 * What a requested `playWhenReady` should do, given whether the player is playing.
 *
 * media3 hands a session player the *state it wants*, not a toggle. A facade that
 * answered every request with `playPause()` turned a stale pause — the lock screen
 * re-sending one after the music had already stopped, a Bluetooth head unit
 * repeating the last command on reconnect — into a resume. Deciding from the
 * request, and doing nothing when it is already true, keeps a pause a pause.
 */
enum class PlayIntent {
    PLAY, PAUSE, NOTHING;

    companion object {
        fun of(playWhenReady: Boolean, isPlaying: Boolean): PlayIntent = when {
            // Explicit, and harmless when already paused: PlaybackOwner.pause() is a
            // set, not a toggle.
            !playWhenReady -> PAUSE
            isPlaying -> NOTHING
            else -> PLAY
        }
    }
}
