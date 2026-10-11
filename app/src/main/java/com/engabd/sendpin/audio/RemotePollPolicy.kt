package com.engabd.sendpin.audio

/**
 * How often [LocalPlayer] reads a remote player (MPD, foobar2000), kept pure so the
 * schedule is tested rather than remembered.
 *
 * Every read is a TCP connection to a machine that is also decoding audio. Without a
 * push channel ([RemotePlayback.changes]) it has to be frequent enough to notice a
 * track change. With one, the player says when anything changes, and a read is only
 * there to keep the hand-carried position honest.
 */
internal object RemotePollPolicy {

    fun intervalMs(playing: Boolean, foreground: Boolean, pushed: Boolean): Long = when {
        pushed && playing -> 5_000L
        pushed && foreground -> 30_000L
        pushed -> 5 * 60_000L
        playing -> REMOTE_POLL_MS
        foreground -> REMOTE_PAUSED_POLL_MS
        else -> REMOTE_BACKGROUND_PAUSED_POLL_MS
    }

    /**
     * After [failures] unanswered reads in a row, how long until the next. A failed
     * read used to be retried on the very next 250 ms tick, forever — four new
     * connections a second to a server that had gone away.
     */
    fun retryAfterMs(failures: Int): Long =
        (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    /**
     * Unanswered this many times running, the player is no longer shown as playing.
     * Before, the last reading stood for as long as the server stayed away: the
     * notification said "playing" all night over a box that had been switched off.
     */
    const val STALE_AFTER_FAILURES = 3

    /** The loop's tick while nothing is playing: no position to carry, so no hurry. */
    const val PAUSED_TICK_MS = 1_000L

    /**
     * Without a push channel, while playing: once a second, with the position carried
     * forward on the 250 ms ticks in between (see LocalPlayer.startRemoteLoop) - as
     * often as is polite and as rarely as a scrub bar can bear.
     */
    const val REMOTE_POLL_MS = 1000L

    /** Paused, app on screen: someone could press play on the server itself. */
    const val REMOTE_PAUSED_POLL_MS = 2_000L

    /** Paused, app in the background: only the notification is reading. */
    const val REMOTE_BACKGROUND_PAUSED_POLL_MS = 15_000L
}
