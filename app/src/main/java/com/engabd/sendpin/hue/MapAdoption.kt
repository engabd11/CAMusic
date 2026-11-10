package com.engabd.sendpin.hue

/**
 * When `DirectLightSync` lets a track's scan drive the show. Pure, so the two
 * decisions that can make a room confidently, precisely wrong are testable.
 */
internal object MapAdoption {

    /**
     * How far into a stretch of listening a scan may arrive and still be adopted.
     *
     * One regime per stretch, decided once. A grid that appears mid-song replaces a
     * PLL that has by then found the beat itself, and the handover between two
     * clocks that agree on tempo but not quite on phase is a visible stumble — the
     * room skips. Better to stay causal for the rest of this play and be exact from
     * the first bar of the next one. syncoV2 draws the line in the same place
     * (`coordinator._MAP_COMMIT_WINDOW_S`) for the same reason.
     */
    const val COMMIT_WINDOW_S = 6f

    /**
     * A drop in track position this large with no flush in between is the tap
     * crossing into the next queue item. A seek always flushes, and the position
     * otherwise only moves forward, a hop at a time.
     */
    const val BOUNDARY_JUMP_S = 1f

    /**
     * How long after crossing an item boundary the scan stays out, waiting for the
     * player to say which track arrived. Long past the ~200 ms the tap runs ahead
     * of it; past this the same track is assumed to have started again (repeat one).
     */
    const val BOUNDARY_HOLD_S = 2f

    /**
     * Whether the scan drives this stretch: true, false, or null for "not decided yet".
     *
     * [stretchStartS] is the track position the stretch began at — 0 for a track
     * played from the top, wherever it was for a seek, a resume or Light Sync turned
     * on mid-song. Where the position is the player's own exact media time
     * ([positionTrusted]), the window counts from there: a scan already in hand at the
     * first frame replaces no PLL at all, so there is nothing to stumble over. Where
     * the position is only a session clock, it counts from 0 as it always has.
     */
    fun decide(
        committed: Boolean?,
        hasScan: Boolean,
        posS: Float,
        stretchStartS: Float,
        positionTrusted: Boolean,
    ): Boolean? {
        if (committed != null) return committed
        val into = if (positionTrusted) posS - stretchStartS else posS
        return when {
            hasScan && into <= COMMIT_WINDOW_S -> true
            into > COMMIT_WINDOW_S -> false
            else -> null
        }
    }

    /** Whether the frame at [posS] has crossed into the next item since [prevS]. */
    fun crossedBoundary(prevS: Float?, posS: Float): Boolean =
        prevS != null && posS < prevS - BOUNDARY_JUMP_S
}
