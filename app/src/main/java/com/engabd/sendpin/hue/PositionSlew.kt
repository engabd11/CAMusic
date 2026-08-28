package com.engabd.sendpin.hue

import kotlin.math.abs

/**
 * Follows a coarse, occasionally-corrected playhead without ever jumping.
 *
 * ## Why a jump is not acceptable here
 *
 * A beat grid is only worth having if its phase is continuous. Applying a 120 ms
 * correction as a step retimes every lamp in the room in a single frame, which reads
 * as a glitch; spread over a few seconds the same correction is invisible. The show
 * would rather be 100 ms wrong and smooth than exactly right and stuttering.
 *
 * ## Why the corrections are coarse in the first place
 *
 * [com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker.setAnchor] says it outright:
 * a repeated `elapsed_time_last_updated` means the server has not recomputed the
 * playhead, and for a remote speaker that is most polls. So the position free-runs
 * between updates that land seconds apart, and each one arrives with whatever drift
 * has accumulated. This absorbs that.
 *
 * Above [snapErrorS] the correction is taken as a seek rather than drift and applied
 * at once — which also gives track-change and skip detection for free, since both
 * land well outside any plausible drift.
 *
 * Pure, and testable without a clock: [advance] is handed its own `dt`.
 */
class PositionSlew(
    /**
     * How much correction may be absorbed per second of playback.
     *
     * 30 ms/s is about a third of a beat's worth over four seconds at 120 BPM —
     * fast enough to catch up between polls, slow enough that no single frame moves
     * the grid perceptibly.
     */
    private val maxRateS: Float = 0.03f,
    /** Past this the difference is a seek, not accumulated drift. */
    private val snapErrorS: Float = 0.75f,
) {

    private var current = 0f
    private var target = 0f
    private var started = false

    /** True when the last [anchor] was taken as a seek rather than as drift. */
    var lastWasSnap: Boolean = false
        private set

    /** The server's latest word on where the playhead is. */
    fun anchor(targetS: Float) {
        if (!started) {
            reset(targetS)
            return
        }
        target = targetS
        lastWasSnap = abs(target - current) > snapErrorS
        if (lastWasSnap) current = target
    }

    /**
     * Advance by [dt] seconds of wall clock, closing part of any gap on the way.
     *
     * The free-run term is what keeps the grid moving between anchors — the position
     * must advance whether or not the server has said anything lately.
     */
    fun advance(dt: Float): Float {
        if (!started) return current
        val step = dt.coerceAtLeast(0f)
        current += step
        target += step
        val error = target - current
        val budget = maxRateS * step
        current += error.coerceIn(-budget, budget)
        return current
    }

    fun reset(atS: Float) {
        current = atS
        target = atS
        started = true
        lastWasSnap = true
    }

    /** Forget everything — the track changed, or the source went away. */
    fun clear() {
        started = false
        current = 0f
        target = 0f
        lastWasSnap = false
    }

    val positionS: Float get() = current
    val running: Boolean get() = started
}
