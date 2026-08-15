package com.engabd.sendpin.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the Sendspin clock filter. Run with:
 *   ./gradlew :app:testDebugUnitTest
 */
class ClockKalmanFilterTest {

    /**
     * Simulate a server clock running [offsetUs] ahead of the local clock, with a
     * fixed [rttUs] and server processing time [procUs], and feed one round-trip.
     * By construction the NTP measurement equals exactly [offsetUs] regardless of
     * rtt/proc, so the filter must converge on it.
     */
    private fun feed(f: ClockKalmanFilter, localSendUs: Long, offsetUs: Long, rttUs: Long, procUs: Long) {
        val t1 = localSendUs
        val serverReceived = (t1 + rttUs / 2) + offsetUs        // server-domain
        val serverTransmitted = serverReceived + procUs          // server-domain
        val t4 = (serverTransmitted - offsetUs) + rttUs / 2       // local-domain
        f.processTimeResponse(t1, serverReceived, serverTransmitted, t4)
    }

    @Test
    fun `converges to a constant offset and becomes ready`() {
        val f = ClockKalmanFilter()
        val offset = 1_000_000L // server 1s ahead
        var localSend = 100_000L
        repeat(30) {
            feed(f, localSend, offsetUs = offset, rttUs = 4_000, procUs = 1_000)
            localSend += 300_000L
        }
        assertTrue(
            f.isReadyForPlaybackStart(),
            "expected ready: samples=${f.sampleCount} err=${f.errorUs()}us",
        )
        // server = local + offset  ->  serverToLocal(server) should recover local.
        val localNow = 5_000_000L
        val recovered = f.serverToLocalUs(localNow + offset)
        assertTrue(abs(recovered - localNow) < 2_000, "serverToLocal off by ${recovered - localNow}us")
    }

    /**
     * The contract the audio engine's head-of-stream gate now depends on.
     *
     * `isSynced()` is true after a *single* round-trip, and scheduling the head of a
     * stream against an offset that raw is what made tracks start a couple of seconds
     * in. `isReadyForPlaybackStart()` is the stricter test the engine gates on now, so
     * it has to stay strict.
     */
    @Test
    fun `readiness needs more than one sample, unlike synced`() {
        val f = ClockKalmanFilter()
        var localSend = 0L
        feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
        assertTrue(f.isSynced(), "one round-trip is enough to be 'synced'")
        assertFalse(f.isReadyForPlaybackStart(), "one round-trip must not be enough to schedule against")

        // Seven in total: still short of the eight the filter asks for.
        repeat(6) {
            localSend += 300_000L
            feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
        }
        assertEquals(7, f.sampleCount)
        assertFalse(f.isReadyForPlaybackStart(), "seven samples is still short")

        localSend += 300_000L
        feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
        assertTrue(
            f.isReadyForPlaybackStart(),
            "eight converged samples should be ready: err=${f.errorUs()}us",
        )
    }

    @Test
    fun `serverToLocal and localToServer are inverses`() {
        val f = ClockKalmanFilter()
        var localSend = 0L
        repeat(20) {
            feed(f, localSend, offsetUs = 250_000L, rttUs = 3_000, procUs = 500)
            localSend += 300_000L
        }
        val t = 9_999_999L
        val roundTrip = f.localToServerUs(f.serverToLocalUs(t))
        assertTrue(abs(roundTrip - t) <= 2, "round-trip off by ${roundTrip - t}us")
    }

    @Test
    fun `not ready before enough samples`() {
        val f = ClockKalmanFilter()
        feed(f, 0L, offsetUs = 100_000L, rttUs = 4_000, procUs = 1_000)
        assertFalse(f.isReadyForPlaybackStart())
        assertTrue(f.isSynced())
    }

    /**
     * The bug behind "sometimes it takes five seconds to start".
     *
     * A converged filter is a *confident* filter, and its gain is proportional to
     * that confidence — so an offset that moves under it by seconds is walked off a
     * couple of percent per sample while [errorUs] goes on reporting a few hundred
     * microseconds. The audio engine believes that error bar, schedules the head of
     * the stream against an offset that is seconds wrong, and the song sits there.
     *
     * The step here is the one a phone actually produces: `CLOCK_BOOTTIME` is used
     * now precisely so a suspend cannot cause it (see [MonotonicClock]), but a
     * server whose own clock is stepped by NTP is not ours to prevent.
     */
    @Test
    fun `a clock step is re-seeded rather than crawled to`() {
        val f = ClockKalmanFilter()
        var localSend = 0L
        repeat(40) {
            feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
            localSend += 300_000L
        }
        assertTrue(f.isReadyForPlaybackStart(), "should be converged before the step")

        // The offset jumps by 30 seconds between one sample and the next.
        val stepped = 1_000_000L + 30_000_000L

        // First sample after the step: nothing is re-seeded on one reading, but the
        // filter must stop claiming it can be scheduled against.
        feed(f, localSend, offsetUs = stepped, rttUs = 4_000, procUs = 1_000)
        localSend += 300_000L
        assertTrue(f.isStepSuspected(), "a residual of 30s is not measurement noise")
        assertFalse(f.isReadyForPlaybackStart(), "must not schedule against a suspect offset")

        // Second: confirmed, so the estimate restarts from the measurement.
        feed(f, localSend, offsetUs = stepped, rttUs = 4_000, procUs = 1_000)
        assertFalse(f.isStepSuspected())
        assertTrue(
            f.isReadyForPlaybackStart(),
            "a re-seed on a 4ms link is immediately good enough: err=${f.errorUs()}us",
        )
        val localNow = 90_000_000L
        val recovered = f.serverToLocalUs(localNow + stepped)
        assertTrue(
            abs(recovered - localNow) < 5_000,
            "offset should be back within milliseconds, not seconds: off by ${recovered - localNow}us",
        )
    }

    /**
     * The other half of the same contract: one wild sample must not be allowed to
     * throw away a converged estimate. A slow reply carries a wide error bar of its
     * own, and the step threshold scales with it, so this one is not even suspected.
     */
    @Test
    fun `a single slow reply is not a step`() {
        val f = ClockKalmanFilter()
        var localSend = 0L
        repeat(40) {
            feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
            localSend += 300_000L
        }

        // One reply that took a second to come back, arriving 300ms out.
        feed(f, localSend, offsetUs = 1_300_000L, rttUs = 1_000_000, procUs = 1_000)
        // Past the reply itself, so the next T4 is still after this one's.
        localSend += 1_300_000L
        assertFalse(f.isStepSuspected(), "300ms inside a 500ms error bar is noise, not a step")

        // …and the estimate it produced is still the old one, near enough.
        repeat(3) {
            feed(f, localSend, offsetUs = 1_000_000L, rttUs = 4_000, procUs = 1_000)
            localSend += 300_000L
        }
        val localNow = 20_000_000L
        val recovered = f.serverToLocalUs(localNow + 1_000_000L)
        assertTrue(abs(recovered - localNow) < 20_000, "off by ${recovered - localNow}us")
    }

    /**
     * Jitter well inside the round-trip's own error bar must never trip the step
     * detector — a false re-seed would throw away a good estimate every few seconds
     * and stand the player down from "ready" while it did.
     */
    @Test
    fun `ordinary jitter never looks like a step`() {
        val f = ClockKalmanFilter()
        var localSend = 0L
        val wobble = longArrayOf(0, 1_500, -2_000, 900, -1_100, 2_000, -700, 300)
        repeat(60) { i ->
            feed(
                f, localSend,
                offsetUs = 1_000_000L + wobble[i % wobble.size],
                rttUs = 20_000, procUs = 1_000,
            )
            localSend += 300_000L
            assertFalse(f.isStepSuspected(), "sample $i: ±2ms on a 20ms link is not a step")
        }
    }

    @Test
    fun `reset clears state`() {
        val f = ClockKalmanFilter()
        repeat(10) { feed(f, it * 300_000L, 500_000L, 4_000, 1_000) }
        f.reset()
        assertFalse(f.isSynced())
        assertTrue(f.sampleCount == 0)
    }

    /**
     * The bug this exists to fix: a caller gating on RTT during cold start (like
     * [com.engabd.sendpin.protocol.SendspinClient]'s `server/time` handler) never
     * feeds a sample into the filter while a server keeps replying slowly, so
     * [ClockKalmanFilter.sampleCount] never moves — and a caller keyed on it for
     * cadence stays at the fast 300ms rate forever. The backoff ramps purely off
     * [ClockKalmanFilter.markStartupRejected] calls, independent of [sampleCount].
     */
    @Test
    fun `startup backoff ramps with consecutive rejections and clears on a real sample`() {
        val f = ClockKalmanFilter()
        assertEquals(0L, f.startupBackoffMs(), "no rejections yet")

        repeat(2) { f.markStartupRejected() }
        assertEquals(0L, f.startupBackoffMs(), "still within the ordinary fast cadence")

        repeat(3) { f.markStartupRejected() } // 5 total
        assertEquals(600L, f.startupBackoffMs())

        repeat(6) { f.markStartupRejected() } // 11 total
        assertEquals(1_200L, f.startupBackoffMs())

        f.markStartupRejected() // 12 total
        assertEquals(3_000L, f.startupBackoffMs())

        // A sample that actually lands clears the streak, whatever it was.
        feed(f, 0L, offsetUs = 100_000L, rttUs = 4_000, procUs = 1_000)
        assertEquals(0L, f.startupBackoffMs(), "an accepted sample resets the streak")
    }
}
