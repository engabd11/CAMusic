package com.engabd.sendpin.hue

import kotlin.math.abs

/**
 * Holds rendered frames back so the light changes when the beat is *heard*.
 *
 * The analysis tap sits before the AudioTrack, so a sample is analysed some
 * hundreds of milliseconds before anyone hears it. Hue costs about
 * [LIGHT_PIPELINE_MS] of its own between a datagram and a visible change. Left
 * alone the lights therefore run *early*, not late — the usual intuition is
 * backwards here.
 *
 * The correction is one subtraction:
 *
 * ```
 * delay = lead − LIGHT_PIPELINE_MS
 * ```
 *
 * Render at the analysis frame's own moment, hold the result for [delayMs], and
 * the bulb changes exactly as the sample reaches the ear. Because `lead` is
 * measured rather than estimated (see
 * [com.engabd.sendpin.audio.AudioLeadProbe]), there is nothing here for a user
 * to calibrate — which is why the direct path has no timing-offset control where
 * syncoV2 needs one.
 *
 * Not thread-safe; owned by the render loop.
 */
class FrameDelayQueue<T> {

    private class Entry<T>(val at: Long, val value: T)

    private val queue = ArrayDeque<Entry<T>>()

    /** The delay currently being applied, in milliseconds. */
    var delayMs: Float = 0f
        private set

    /**
     * Move [delayMs] toward the delay implied by [leadMs].
     *
     * Slewed rather than assigned. The lead shifts whenever the sink resizes its
     * buffer or a track changes, and stepping the delay would drop or duplicate
     * a burst of frames — visible as a stutter in the middle of a song. At
     * [SLEW_MS_PER_S] the room drifts into alignment over a second or so without
     * anything to see, matching syncoV2's `_DELAY_SLEW_MS_PER_S`.
     *
     * A null [leadMs] means the sink has no position yet (before playback, or
     * mid-seek); the current delay is held rather than collapsed to zero.
     */
    fun updateDelay(leadMs: Float?, dt: Float) {
        // Below the pipeline latency there is nothing to give back: the audio is
        // already closer to the ear than the light can be to the bulb, so the
        // best available answer is to send immediately and be slightly late.
        val target = leadMs?.let { (it - LIGHT_PIPELINE_MS).coerceAtLeast(0f) } ?: return
        val step = SLEW_MS_PER_S * dt
        delayMs = if (abs(target - delayMs) <= step) target
        else if (target > delayMs) delayMs + step
        else delayMs - step
    }

    /** Seed the delay without slewing. For session start, where there is no history. */
    fun resetDelay(leadMs: Float?) {
        delayMs = leadMs?.let { (it - LIGHT_PIPELINE_MS).coerceAtLeast(0f) } ?: 0f
        queue.clear()
    }

    fun clear() = queue.clear()

    /** Add a freshly rendered frame, stamped with the time it was rendered. */
    fun offer(value: T, nowNanos: Long) {
        queue.addLast(Entry(nowNanos, value))
        // The queue is time-bounded in normal operation, but a delay that
        // collapses to zero while frames keep arriving would otherwise let it
        // grow unchecked. 60 Hz for two seconds is well past any real delay.
        while (queue.size > MAX_ENTRIES) queue.removeFirst()
    }

    /**
     * The newest frame that is now due, or null if none is. Frames older than
     * the due one are dropped rather than sent: the room only ever needs the
     * latest state, and sending a backlog would replay stale motion.
     */
    fun poll(nowNanos: Long): T? {
        val cutoff = nowNanos - (delayMs * 1_000_000f).toLong()
        var due: Entry<T>? = null
        while (queue.isNotEmpty() && queue.first().at <= cutoff) {
            due = queue.removeFirst()
        }
        return due?.value
    }

    companion object {
        /**
         * Hue's own latency between a streamed frame and a visible change: a
         * Zigbee hop plus the bulb's ramp. syncoV2 uses the same 100 ms
         * (`LIGHT_PIPELINE_MS`), and the Hue System Performance note puts a
         * single brightness message at ~55 ms and brightness+colour at ~95 ms.
         */
        const val LIGHT_PIPELINE_MS = 100f

        /** How fast the applied delay may move, in milliseconds per second. */
        const val SLEW_MS_PER_S = 120f

        private const val MAX_ENTRIES = 120
    }
}
