package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.engabd.sendpin.protocol.ClockSync
import com.engabd.sendpin.protocol.MonotonicClock
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.util.concurrent.TimeUnit

/**
 * Grouped (multi-device) playback: every frame is scheduled against the shared
 * server clock at `serverToLocal(serverTs) + 200ms headroom` - the playout phase
 * every official Sendspin client targets (sendspin-js `SCHEDULE_HEADROOM_SEC`).
 * See Massdroid's `SendspinSyncEngine`, which this mirrors: the 200ms is the
 * agreed instant every client in the group plays at, not slack to trim.
 *
 * Unlike a raw-`AudioTrack` engine, which schedules only the *head* of a stream and
 * then lets `AudioTrack` pace the rest itself, every frame is scheduled here -
 * there is no downstream `AudioTrack` in this pipeline, so [read] blocking is
 * the only pacing ExoPlayer's loading thread sees.
 */
@OptIn(UnstableApi::class)
class SendspinSyncDataSource(
    format: StreamStartPlayerInfo,
    private val clock: ClockSync,
) : SendspinDataSource(format) {

    companion object {
        /** sendspin-js `SCHEDULE_HEADROOM_SEC` - the shared group playout phase. */
        const val SCHEDULE_HEADROOM_US = 200_000L

        /** How long the head of a stream may hold for the clock to converge before giving up - matches [SendspinPlaybackSupport.HeadGate]. */
        const val MAX_STALL_MS = 3_000L
        const val HOLD_POLL_MS = 25L

        /** Below this a frame is already behind and only pushes everything after it later - drop it. */
        const val LATE_TOLERANCE_US = 120_000L

        /** A schedule further ahead than this isn't believed - the filter hasn't converged; play now rather than stall. */
        const val MAX_LEAD_US = 15_000_000L

        private const val QUEUE_POLL_MS = 200L
    }

    /** Trim from `client/state.player.static_delay_ms` - see [com.engabd.sendpin.protocol.SendspinClient.setStaticDelayMs]. */
    @Volatile var staticDelayMs: Int = 0

    private enum class HeadGateDecision { HOLD, SCHEDULE, PLAY_NOW }

    /**
     * Decided once, from the first frame, matching [SendspinPlaybackSupport.HeadGate]'s
     * reasoning: scheduling only part of a stream - say, once the clock happens to
     * converge mid-stream - is exactly the stall a listener hears as a skip.
     * Either the whole stream is scheduled, or none of it is.
     */
    private var decided = false
    private var scheduleFrames = false
    private var stalledSinceMs = 0L

    override fun awaitNextFrame(): ByteArray? {
        while (true) {
            if (!decided) {
                when (headGateDecide()) {
                    HeadGateDecision.HOLD -> {
                        Thread.sleep(HOLD_POLL_MS)
                        continue
                    }
                    HeadGateDecision.SCHEDULE -> {
                        decided = true; scheduleFrames = true
                    }
                    HeadGateDecision.PLAY_NOW -> {
                        decided = true; scheduleFrames = false
                    }
                }
            }

            val frame = queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (isEndOfStreamSignalled() && queue.isEmpty()) return null
                continue
            }
            if (!scheduleFrames) {
                // The head gate gave up on the clock entirely for this stream -
                // there is no trustworthy schedule to anchor against, so this is
                // a best-effort "now" (matches SendspinPlaybackSupport.HeadGate.PLAY_NOW).
                armPresentationAnchor(MonotonicClock.nowUs())
                return frame.payload
            }
            val targetLocalUs = targetLocalUsFor(frame.serverTsUs)
            if (!waitUntilDue(targetLocalUs)) continue // hopelessly late - try the next one
            // Armed from whichever frame is actually released first - later calls
            // are no-ops (see SendspinDataSource.armPresentationAnchor).
            armPresentationAnchor(targetLocalUs)
            return frame.payload
        }
    }

    private fun headGateDecide(): HeadGateDecision {
        if (clock.isReadyForPlaybackStart()) return HeadGateDecision.SCHEDULE
        if (stalledSinceMs == 0L) stalledSinceMs = android.os.SystemClock.elapsedRealtime()
        val stalledMs = android.os.SystemClock.elapsedRealtime() - stalledSinceMs
        return if (stalledMs >= MAX_STALL_MS) HeadGateDecision.PLAY_NOW else HeadGateDecision.HOLD
    }

    /**
     * Where [serverTsUs] lands on the local clock, headroom and static delay
     * applied - computed once per frame (not re-read live during [waitUntilDue]'s
     * wait, matching the original single-read of `staticDelayMs` this replaced).
     * `<= 0` (nothing to schedule against) resolves to "due now".
     */
    private fun targetLocalUsFor(serverTsUs: Long): Long {
        if (serverTsUs <= 0L) return clock.nowUs()
        return clock.serverTimeToLocal(serverTsUs) + SCHEDULE_HEADROOM_US - staticDelayMs * 1_000L
    }

    /**
     * Block until [targetLocalUs] is due. False if it's already too late to be worth
     * playing, or if the stream was ended out from under this wait.
     *
     * That second case is what a pause or a seek looks like from in here: both
     * arrive as `stream/end`/`stream/clear` on [SendspinExoEngine]'s ingest
     * coroutine, which calls [SendspinDataSource.discardQueued] and
     * [SendspinDataSource.signalEndOfStream] on a *different* thread than this one.
     * `discardQueued` only reaches frames still sitting in the queue - it does
     * nothing for the one already popped out and being waited on right here, and
     * without this check the wait ran to completion regardless: a frame scheduled
     * [MAX_LEAD_US] out held pause silent for that long, no matter how loudly the
     * queue behind it had already been told to stop. Checked once per sleep slice
     * (~20ms), so an end signal is noticed almost immediately instead of at the
     * frame's original, now-irrelevant schedule.
     */
    private fun waitUntilDue(targetLocalUs: Long): Boolean {
        var waitUs = targetLocalUs - clock.nowUs()
        if (waitUs < -LATE_TOLERANCE_US) return false
        if (waitUs > MAX_LEAD_US) return true // implausible: don't stall on it
        while (waitUs > 0) {
            if (isEndOfStreamSignalled()) return false
            val slice = waitUs.coerceAtMost(20_000L)
            try {
                Thread.sleep(slice / 1_000L, ((slice % 1_000L) * 1_000L).toInt())
            } catch (_: InterruptedException) {
                return false
            }
            waitUs = targetLocalUs - clock.nowUs()
        }
        return true
    }
}
