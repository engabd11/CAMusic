package com.engabd.sendpin.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scrobble position [JellyfinSource] reports for a completed play.
 *
 * `startedAtMs` is a wall-clock epoch, so deriving a position from it drifts by
 * however long the track was paused — a 3-minute pause on a 3-minute song reports
 * "finished twice over". [JellyfinSource.resolvePosition] exists so the player's
 * real, pause-aware position always wins when the caller has one.
 */
class JellyfinSourceTest {

    @Test
    fun `a real position always wins over the wall-clock fallback`() {
        assertEquals(
            42_000L,
            JellyfinSource.resolvePosition(positionMs = 42_000L, startedAtMs = 0L, nowMs = 999_999L),
        )
    }

    @Test
    fun `no position falls back to elapsed wall-clock time`() {
        assertEquals(
            10_000L,
            JellyfinSource.resolvePosition(positionMs = null, startedAtMs = 5_000L, nowMs = 15_000L),
        )
    }

    /** The bug this fixes: a pause inflates elapsed wall-clock time past the real position. */
    @Test
    fun `a pause would have inflated the wall-clock fallback, which is why positionMs is preferred`() {
        // Track started at t=0, played for 30s, then paused for 3 minutes before the
        // completion report fires at t=210s. The real position is still 30s.
        val wallClockFallback = JellyfinSource.resolvePosition(positionMs = null, startedAtMs = 0L, nowMs = 210_000L)
        assertEquals(210_000L, wallClockFallback, "documents the drift the fallback is prone to")

        val realPosition = JellyfinSource.resolvePosition(positionMs = 30_000L, startedAtMs = 0L, nowMs = 210_000L)
        assertEquals(30_000L, realPosition, "the real position must not drift across the pause")
    }

    @Test
    fun `neither position nor startedAtMs reports zero`() {
        assertEquals(0L, JellyfinSource.resolvePosition(positionMs = null, startedAtMs = null, nowMs = 5_000L))
    }

    @Test
    fun `elapsed time is never negative even with a clock that moved backwards`() {
        assertEquals(
            0L,
            JellyfinSource.resolvePosition(positionMs = null, startedAtMs = 10_000L, nowMs = 5_000L),
        )
    }
}
