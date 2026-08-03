package com.engabd.sendpin.protocol

/**
 * Where the playhead is *now*, given what the server last said and when it said it.
 *
 * The Sendspin spec defines this exactly:
 *
 * > `calculated_progress = metadata.progress.track_progress +
 * >  (current_time - metadata.timestamp) * metadata.progress.playback_speed / 1000000`
 *
 * with the result clamped to `[0, track_duration]` when the duration is known.
 *
 * A pure function, separated from [com.engabd.sendpin.service.Playback], because it is
 * the arithmetic the whole seek bar rests on and the class around it is welded to a
 * live WebSocket — there is no other way to test it. The engine's job is only to keep
 * [Anchor] fresh and call this often enough.
 */
object ProgressProjection {

    /** Normal playback speed in the spec's ×1000 units. */
    const val NORMAL_SPEED_MILLI = 1000L

    /**
     * A progress reading plus the instant it was true at, both already expressed on
     * the *local* clock — converting the server's timestamp is the caller's job, since
     * only it holds the clock filter.
     */
    data class Anchor(
        /** Milliseconds into the track at [atLocalUs]. */
        val positionMs: Long,
        /** Local monotonic microseconds the reading described. */
        val atLocalUs: Long,
        /** Playback speed ×1000; 1000 is normal. */
        val speedMilli: Long = NORMAL_SPEED_MILLI,
        /** Track length in milliseconds, or 0 when unknown. */
        val durationMs: Long = 0L,
    )

    /**
     * [anchor] projected to [nowLocalUs].
     *
     * [playing] false holds the position at the anchor rather than running it on:
     * `server/state` is pushed on a pause, so the anchor is already correct, and
     * projecting through a pause would have the bar advance while nothing was audible.
     *
     * A negative age (a timestamp slightly in the future, which a converging clock
     * filter can produce) is floored at zero rather than run backwards — a bar that
     * ticks down is a worse lie than one that pauses for a few milliseconds.
     */
    fun project(anchor: Anchor, nowLocalUs: Long, playing: Boolean): Long {
        val elapsedUs = if (playing) (nowLocalUs - anchor.atLocalUs).coerceAtLeast(0L) else 0L
        val projected = anchor.positionMs + elapsedUs * anchor.speedMilli / 1_000_000L
        return if (anchor.durationMs > 0) {
            projected.coerceIn(0L, anchor.durationMs)
        } else {
            projected.coerceAtLeast(0L)
        }
    }
}
