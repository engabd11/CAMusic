package com.engabd.sendpin.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scrubber reports the position it showed.
 *
 * It used to hand the view model the bar's *fraction*, which then multiplied by a
 * duration it read again from a flow that had been free to move in between. Two
 * multiplications, two chances to use a different length: the magnifier promised one
 * timestamp and the server was sent another. The fraction is only meaningful
 * alongside the duration it was taken against, so the release carries the
 * milliseconds it already computed from that duration and nothing downstream gets a
 * second opinion.
 */
class ScrubberTest {

    @Test
    fun `release reports the milliseconds the label showed`() {
        var sought: Long? = null
        val scrubber = Scrubber { sought = it }

        // A 5:32 track, dropped at 1:49.
        val duration = 332_000L
        val fraction = 109_000f / duration
        scrubber.onDrag(fraction, duration)
        assertEquals(109_000L, scrubber.positionMs)

        scrubber.onRelease(fraction, duration)
        assertEquals(109_000L, sought)
        // And the bar holds there until the server catches up.
        assertEquals(109_000L, scrubber.positionMs)
    }

    @Test
    fun `a duration that changes after the drag cannot move the target`() {
        var sought: Long? = null
        val scrubber = Scrubber { sought = it }

        scrubber.onRelease(0.5f, 200_000L)
        assertEquals(100_000L, sought)

        // The next track is a different length. What was already released is a
        // position, not a proportion, so it is unaffected.
        assertEquals(100_000L, scrubber.positionMs)
    }

    @Test
    fun `the live position drives the bar once the hold is lifted`() {
        val scrubber = Scrubber { }
        scrubber.livePos = 42_000L
        assertEquals(42_000L, scrubber.positionMs)

        scrubber.onRelease(0.5f, 200_000L)
        assertEquals(100_000L, scrubber.positionMs)

        scrubber.seekTarget = -1L
        assertEquals(42_000L, scrubber.positionMs)
    }
}
