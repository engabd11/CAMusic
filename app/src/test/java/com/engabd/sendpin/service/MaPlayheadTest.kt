package com.engabd.sendpin.service

import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaQueue
import com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The playhead's rules, off-device: what a server reading does, when a hold is placed,
 * what lifts it. The clock is hand-cranked and the coroutine scheduler is virtual, so
 * "now" only moves when a test moves it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaPlayheadTest {

    private var now = 1_000_000L
    private val self = MaPlayhead.Target(key = "up1", queueId = "up1", isSelf = true)
    private val remote = MaPlayhead.Target(key = "sonos", queueId = "sonos", isSelf = false)

    private fun TestScope.playhead() =
        MaPlayhead(scope = this, nowMs = { now }, tracker = PlayerPositionTracker { now })

    private fun player(id: String, playing: Boolean = true) =
        MaPlayer(playerId = id, name = id, available = true, powered = true, state = if (playing) "playing" else "paused")

    private fun queue(id: String, elapsedMs: Long, stampMs: Long = now, item: String = "item-1", playing: Boolean = true) =
        MaQueue(
            queueId = id,
            currentItemDurationMs = 300_000,
            currentQueueItemId = item,
            state = if (playing) "playing" else "paused",
            elapsedMs = elapsedMs,
            elapsedTimeLastUpdated = stampMs / 1000.0,
        )

    @Test
    fun `a poll anchors and the bar ticks from it`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        now += 2_000
        assertEquals(12_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `a self seek holds the target through the server's echo and releases on the stream edge`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        ph.onStreamChunk(seq = 7)

        ph.holdForSeek(self, targetMs = 120_000, durationMs = 300_000) { /* sent */ }
        assertTrue(ph.isHeld("up1"))
        // The server still echoes the old position for a beat. Held.
        now += 400
        ph.onQueueUpdated(self, queue("up1", 10_400), player("up1"))
        assertEquals(120_000L, ph.effectiveMs("up1"))
        // The new stream's first chunk arrives: released, ticking from the target.
        now += 800
        ph.onStreamChunk(seq = 8)
        assertFalse(ph.isHeld("up1"))
        now += 1_000
        assertEquals(121_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `a stale poll answer landing after the release is dropped`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        ph.holdForSeek(self, targetMs = 120_000, durationMs = 300_000) { }
        // The seek landed: fresh stamp with the target, then the edge.
        now += 500
        ph.onQueueUpdated(self, queue("up1", 120_000), player("up1"))
        ph.onStreamChunk(seq = 1)
        // An answer from before the seek, older stamp.
        ph.onPoll(self, player("up1"), queue("up1", 10_600, stampMs = now - 1_200))
        now += 1_000
        assertEquals(121_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `a remote seek places no hold and follows the server`() = runTest {
        val ph = playhead()
        ph.onPoll(remote, player("sonos"), queue("sonos", 10_000))
        ph.holdForSeek(remote, targetMs = 120_000, durationMs = 300_000) { }
        assertFalse(ph.isHeld("sonos"))
        now += 300
        ph.onQueueTime(remote, "sonos", 120_000)
        now += 1_000
        assertEquals(121_000L, ph.effectiveMs("sonos"))
    }

    @Test
    fun `a self skip holds zero until the new stream, then ticks`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 150_000))
        ph.holdForTrackChange(self) { }
        now += 500
        // The outgoing track's position keeps arriving.
        ph.onPoll(self, player("up1"), queue("up1", 150_500))
        assertEquals(0L, ph.effectiveMs("up1"))
        ph.onStreamChunk(seq = 1)
        now += 1_000
        assertEquals(1_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `a command that throws releases the hold at once`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        assertFailsWith<IllegalStateException> {
            ph.holdForSeek(self, targetMs = 400_000, durationMs = 300_000) { error("Can not seek outside of duration range") }
        }
        assertFalse(ph.isHeld("up1"))
    }

    @Test
    fun `the watchdog lets go of a hold nothing confirms`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        ph.holdForSeek(self, targetMs = 120_000, durationMs = 300_000) { }
        advanceTimeBy(MaPlayhead.WATCHDOG_MS - 1)
        assertTrue(ph.isHeld("up1"))
        advanceTimeBy(2)
        assertFalse(ph.isHeld("up1"))
    }

    @Test
    fun `a short track pinned at zero by the server keeps climbing`() = runTest {
        val ph = playhead()
        ph.onQueueUpdated(self, queue("up1", 0, item = "short"), player("up1"))
        repeat(5) {
            now += 1_000
            ph.onPoll(self, player("up1"), queue("up1", 0, item = "short"))
        }
        assertEquals(5_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `the next item at zero is news`() = runTest {
        val ph = playhead()
        ph.onQueueUpdated(self, queue("up1", 0, item = "short-a"), player("up1"))
        now += 19_000
        ph.onQueueUpdated(self, queue("up1", 0, item = "short-b"), player("up1"))
        assertEquals(0L, ph.effectiveMs("up1"))
    }

    @Test
    fun `a player update pauses and resumes the projection`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        now += 2_000
        ph.onPlayerUpdated(self, player("up1", playing = false))
        now += 5_000
        assertEquals(12_000L, ph.effectiveMs("up1"))
        ph.onPlayerUpdated(self, player("up1", playing = true))
        now += 1_000
        assertEquals(13_000L, ph.effectiveMs("up1"))
    }

    @Test
    fun `readings for another queue are ignored`() = runTest {
        val ph = playhead()
        ph.onPoll(self, player("up1"), queue("up1", 10_000))
        ph.onQueueUpdated(self, queue("kitchen", 200_000), null)
        ph.onQueueTime(self, "kitchen", 250_000)
        assertEquals(10_000L, ph.effectiveMs("up1"))
    }
}
