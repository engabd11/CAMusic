package com.engabd.sendpin.game

/**
 * A monotonic clock that can be stopped and started again, and forgets the time it
 * spent stopped.
 *
 * The rhythm game runs on one clock: notes are charted at `now + lead`, the board is
 * advanced against it, and a tap is judged by the distance to a note's time on it —
 * see [NoteGenerator]. That clock was `SystemClock.elapsedRealtime()` read directly,
 * which is right about everything except pausing. The frame loop kept advancing while
 * the music stood still, so `reap` retired every note still in flight as a miss: the
 * combo went to zero and the accuracy with it, for pressing pause.
 *
 * Freezing the clock rather than gating the loop is what keeps the board *correct*
 * across a pause instead of merely still. Every pending note's time was written
 * against this clock, so if it stops and resumes where it stopped, the notes resume
 * exactly where they were relative to the line. The alternative — advancing real time
 * and skipping the reap — would resume with the whole board already past the line.
 *
 * Not `SystemClock` itself, so the arithmetic can be tested off-device: this app's
 * test suite is plain JVM.
 *
 * Not thread-safe, and does not need to be: the game reads and writes it from the
 * frame loop and the tap handler, both on the main thread.
 *
 * @param source the underlying monotonic time in milliseconds — `elapsedRealtime` in
 *   the app, a counter in tests. Must never go backwards.
 */
class PausableClock(private val source: () -> Long) {

    /** How much time has been spent paused, in total, and so never handed out. */
    private var pausedTotalMs = 0L

    /** When the current pause began, on [source]'s clock, or null while running. */
    private var pausedAtMs: Long? = null

    /** True while [now] is frozen. */
    val paused: Boolean get() = pausedAtMs != null

    /** The game's time now: real time, less every millisecond spent paused. */
    fun now(): Long = (pausedAtMs ?: source()) - pausedTotalMs

    /**
     * Stop the clock. Idempotent — the screen pushes playback state in on every
     * change, and a second pause must not restart the count or the accumulated
     * total would grow by the time already frozen.
     */
    fun pause() {
        if (pausedAtMs == null) pausedAtMs = source()
    }

    /** Start it again, from exactly where it stopped. Idempotent. */
    fun resume() {
        val at = pausedAtMs ?: return
        pausedTotalMs += source() - at
        pausedAtMs = null
    }

    /** Pause or resume, whichever [playing] asks for. */
    fun setPlaying(playing: Boolean) {
        if (playing) resume() else pause()
    }

    /** Back to zero elapsed and running, for a new run. */
    fun reset() {
        pausedTotalMs = 0L
        pausedAtMs = null
    }
}
