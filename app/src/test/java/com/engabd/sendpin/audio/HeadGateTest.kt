package com.engabd.sendpin.audio

import com.engabd.sendpin.audio.SendspinAudioEngine.HeadGate
import com.engabd.sendpin.audio.SendspinAudioEngine.HeadGate.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What to do with the frame at the head of a stream.
 *
 * This decision is the whole of the fix for tracks that began a couple of seconds
 * in. The engine used to schedule the head frame against `ClockSync.isSynced()`,
 * which is true after a **single** round-trip — long before the Kalman filter has
 * converged. Scheduling on an offset still seconds wide meant the head was either
 * held in silence while the server ran on, or dropped as "late".
 */
class HeadGateTest {

    @Test
    fun `a converged clock is scheduled against`() {
        assertEquals(Decision.SCHEDULE, HeadGate.decide(clockReady = true, stalledMs = 0))
    }

    @Test
    fun `an unconverged clock holds the frame rather than playing it unscheduled`() {
        assertEquals(Decision.HOLD, HeadGate.decide(clockReady = false, stalledMs = 0))
        assertEquals(Decision.HOLD, HeadGate.decide(clockReady = false, stalledMs = 500))
    }

    @Test
    fun `holding is bounded — a clock that never converges still plays`() {
        // Without this branch a stalled `client/time` loop would mean indefinite
        // silence, which is worse than a stream that is merely out of step.
        assertEquals(
            Decision.PLAY_NOW,
            HeadGate.decide(clockReady = false, stalledMs = HeadGate.MAX_STALL_MS),
        )
        assertEquals(
            Decision.PLAY_NOW,
            HeadGate.decide(clockReady = false, stalledMs = HeadGate.MAX_STALL_MS + 1),
        )
    }

    @Test
    fun `a clock that converges just before the deadline is still scheduled against`() {
        // Readiness wins over the deadline: having waited for the offset, use it.
        assertEquals(
            Decision.SCHEDULE,
            HeadGate.decide(clockReady = true, stalledMs = HeadGate.MAX_STALL_MS + 5_000),
        )
    }
}
