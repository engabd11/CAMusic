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

    @Test
    fun `reset clears state`() {
        val f = ClockKalmanFilter()
        repeat(10) { feed(f, it * 300_000L, 500_000L, 4_000, 1_000) }
        f.reset()
        assertFalse(f.isSynced())
        assertTrue(f.sampleCount == 0)
    }
}
