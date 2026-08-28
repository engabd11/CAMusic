package com.engabd.sendpin.audio

import com.engabd.sendpin.audio.SendspinPlaybackSupport.PlayheadGate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holding the playhead until a started stream can actually be heard.
 *
 * `stream/start` precedes audio by over 1.6 s on a skip (decoder plus audio-track
 * warm-up, measured on-device). Music Assistant's `server/state` progress keeps
 * running through that gap and describes *its* stream, not ours — so the bar used to
 * count up from wherever MA's queue clock had reached, and then snap back to the
 * beginning the moment MA switched to reporting this player's real position. These
 * pin the two halves of the fix: the gate opens only when audio is out, and a held
 * projection does not run.
 */
class PlayheadGateTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `nothing to wait for`() {
        // 0 is the resting state - no stream starting, or the one that was has been
        // heard - and must never freeze a bar that should be running.
        assertFalse(PlayheadGate.awaitingAudible(sinceUs = 0L, nowUs = t0))
    }

    @Test
    fun `held across the warm-up gap`() {
        assertTrue(PlayheadGate.awaitingAudible(sinceUs = t0, nowUs = t0))
        // The 1.7 s that was the whole of the visible glitch.
        assertTrue(PlayheadGate.awaitingAudible(sinceUs = t0, nowUs = t0 + 1_700_000L))
    }

    @Test
    fun `the wait is bounded`() {
        // An engine that never reports itself playing must not freeze the bar for the
        // whole track.
        val past = t0 + PlayheadGate.AUDIBLE_WAIT_BUDGET_US
        assertFalse(PlayheadGate.awaitingAudible(sinceUs = t0, nowUs = past))
        assertFalse(PlayheadGate.awaitingAudible(sinceUs = t0, nowUs = past + 1_000_000L))
    }
}

/**
 * Rejecting a `server/state` progress reading that cannot be this track's playhead.
 *
 * Music Assistant was observed sending `track_progress` far past `track_duration`
 * (457044 ms against a 252000 ms track, confirmed against the server's own
 * `player_queues` view of the same item). The tracker clamps an over-long anchor
 * to the duration, so believing one pins the bar at the end of the track until
 * the next honest reading.
 */
class ProgressPlausibilityTest {

    @Test
    fun `a reading past the end of the track is not a playhead`() {
        assertFalse(PlayheadGate.describesTrack(positionMs = 457_044L, durationMs = 252_000L))
    }

    @Test
    fun `an ordinary reading is`() {
        assertTrue(PlayheadGate.describesTrack(positionMs = 120_000L, durationMs = 252_000L))
    }

    @Test
    fun `the very end of a track still counts`() {
        // Rounding and the scheduling lead can put a legitimate reading just past the
        // duration; only a reading well beyond it is nonsense.
        assertTrue(PlayheadGate.describesTrack(positionMs = 252_500L, durationMs = 252_000L))
    }

    @Test
    fun `an unknown duration rules nothing out`() {
        assertTrue(PlayheadGate.describesTrack(positionMs = 999_999L, durationMs = 0L))
    }
}