package com.engabd.sendpin.game

import kotlin.math.roundToLong

/**
 * The clock arithmetic the rhythm game and its calibration share, kept pure so the
 * signs and units can be tested off-device.
 *
 * Three clocks meet in this game, and most "it feels off" bugs are two of them being
 * mixed:
 *
 * - **Real time** is `SystemClock.elapsedRealtime()`: what the analysis thread and the
 *   light render loop stamp things with.
 * - **Game time** is real time less every pause ([PausableClock]): what notes, taps and
 *   the board are written in.
 * - **Uptime** is `SystemClock.uptimeMillis()` / `System.nanoTime()`: what the input
 *   and display systems stamp events with.
 */
object GameTiming {

    /** Hue's own latency from a streamed frame to a visible change, as `FrameDelayQueue.LIGHT_PIPELINE_MS`. */
    const val LIGHT_PIPELINE_MS = 100L

    /** The most the stored timing offset may be, either way. */
    const val OFFSET_LIMIT_MS = 300

    /** Longest an input event is assumed to have waited before the handler saw it. */
    private const val MAX_INPUT_AGE_MS = 250L

    /**
     * How long until the audio of the frame being charted is heard.
     *
     * [leadMs] answers for the *newest* sample the backend has handed over; the frame
     * describes audio [frameLagMs] older than that, which is heard that much sooner.
     * This is the same correction the light engine applies to its own delay
     * (`DirectLightSync.effectiveLeadMs`), so the chart and the room agree about when
     * a beat is audible. On Music Assistant the lag is seconds after a stream start,
     * and charting with the raw lead put every note that late.
     */
    fun effectiveLeadMs(leadMs: Long, frameLagMs: Float): Long =
        (leadMs - frameLagMs).coerceAtLeast(0f).roundToLong()

    /**
     * The game-clock time a touch actually happened at.
     *
     * Pointer events carry the uptime they were stamped with by the input system, not
     * the time the handler ran. Both bases stop in deep sleep and `elapsedRealtime`
     * does not, so the stamp cannot be subtracted from a game time directly: the age
     * of the event (uptime now less uptime stamped) is taken instead and applied to the
     * game clock, which is correct whatever the two bases' offset is.
     */
    fun tapGameMs(gameNowMs: Long, uptimeNowMs: Long, eventUptimeMs: Long): Long {
        val age = (uptimeNowMs - eventUptimeMs).coerceIn(0L, MAX_INPUT_AGE_MS)
        return gameNowMs - age
    }

    /**
     * The game-clock time of a display frame, from its vsync stamp.
     *
     * [frameNanos] is the choreographer's `System.nanoTime()` based frame time; the
     * callback runs a little after it, so the frame belongs that much earlier than a
     * fresh clock read would say.
     */
    fun frameGameMs(gameNowMs: Long, nanoNow: Long, frameNanos: Long): Long {
        val ageMs = ((nanoNow - frameNanos) / 1_000_000L).coerceIn(0L, 50L)
        return gameNowMs - ageMs
    }

    /** One vsync: what a frame drawn now still waits before it reaches the glass. */
    fun displayLatencyMs(refreshHz: Float): Long =
        if (refreshHz < 24f) 17L else (1000f / refreshHz).roundToLong()

    /**
     * How long to hold a hit's flash so the light and the beat arrive together.
     *
     * [earlyByMs] is how far before the note's audible moment the tap was judged. The
     * lamp takes [pipelineMs] to show a command, so a tap early by more than that
     * waits the difference; anything else goes out at once, because it can no longer
     * be earlier.
     */
    fun flashDelayMs(earlyByMs: Long, pipelineMs: Long = LIGHT_PIPELINE_MS): Long =
        (earlyByMs - pipelineMs).coerceAtLeast(0L)

    /**
     * The game-clock time the next beat of an analysis snapshot is heard at.
     *
     * [frameGameMs] is when the frame was analysed, [timeToNextBeatS] the grid's
     * answer for that frame, [leadMs] and [frameLagMs] as in [effectiveLeadMs]. The
     * lead is *added*: a beat the analyser sees now is heard a lead from now. (The
     * calibration used to subtract it.)
     */
    fun audibleBeatMs(frameGameMs: Long, timeToNextBeatS: Float, leadMs: Long, frameLagMs: Float): Long =
        frameGameMs + (timeToNextBeatS * 1000f).roundToLong() + effectiveLeadMs(leadMs, frameLagMs)

    /**
     * Signed distance, in ms, from [tapMs] to the nearest beat of a grid that has a
     * beat at [beatMs] and period [periodMs]. Positive means the tap came after the
     * beat.
     */
    fun beatDeltaMs(tapMs: Long, beatMs: Long, periodMs: Float): Float {
        if (periodMs <= 0f) return 0f
        val rem = (tapMs - beatMs).toFloat().mod(periodMs)
        return if (rem <= periodMs / 2f) rem else rem - periodMs
    }

    /** Median of [values]; even counts average the middle pair. 0 when empty. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    /**
     * A tap's time on the judged clock.
     *
     * The stored offset is how late the player's taps run (positive means late), so
     * the judged clock comes back by it: a player who taps 40 ms late is judged as if
     * they had tapped on the beat. It used to be added, which judged them 80 ms late.
     */
    fun judgedMs(tapGameMs: Long, offsetMs: Int): Long = tapGameMs - offsetMs

    /**
     * The offset to store after a run.
     *
     * [deltasMs] are signed tap errors as judged (tap less note; positive is late) with
     * [currentOffsetMs] already applied, so whatever median is left over is the part
     * the current offset has not yet absorbed.
     */
    fun suggestedOffsetMs(currentOffsetMs: Int, deltasMs: List<Long>): Int =
        (currentOffsetMs + median(deltasMs)).toInt().coerceIn(-OFFSET_LIMIT_MS, OFFSET_LIMIT_MS)

    /**
     * Counts of signed tap errors in [bins] buckets of [binMs] centred on zero, early
     * on the left. Errors beyond the outer buckets pile into them.
     */
    fun histogram(deltasMs: List<Long>, binMs: Long = 50L, bins: Int = 7): IntArray {
        val out = IntArray(bins)
        val half = bins / 2
        for (d in deltasMs) {
            val idx = (Math.floorDiv(d + binMs / 2, binMs).toInt() + half).coerceIn(0, bins - 1)
            out[idx]++
        }
        return out
    }
}
