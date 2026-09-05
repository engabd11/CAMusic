package com.engabd.sendpin.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clock the rhythm game's board is advanced against.
 *
 * The arithmetic is three lines and the bug it fixes was a whole run's score, so it
 * is worth pinning: a paused game must resume where it stopped, not where real time
 * got to while nobody was playing.
 */
class PausableClockTest {

    private var t = 1_000L
    private val clock = PausableClock { t }

    /**
     * The clock is an offset over its source, not a stopwatch — it never claims to
     * start at zero, because the game only ever compares it against note times taken
     * from itself. So every assertion here is about *elapsed* time.
     */
    private val start = clock.now()

    @Test
    fun `runs with the source while nothing has paused it`() {
        assertEquals(0L, clock.now() - start)
        t += 250
        assertEquals(250L, clock.now() - start)
        assertFalse(clock.paused)
    }

    @Test
    fun `stands still while paused`() {
        t += 500
        clock.pause()
        assertTrue(clock.paused)
        val frozen = clock.now()
        t += 10_000
        assertEquals(frozen, clock.now(), "the board must not advance while the song is stopped")
    }

    @Test
    fun `resumes exactly where it stopped`() {
        t += 500                       // 500 elapsed
        clock.pause()
        t += 10_000                    // ten seconds of pause
        clock.resume()
        assertFalse(clock.paused)
        assertEquals(500L, clock.now() - start, "the pause must cost the run nothing")
        t += 100
        assertEquals(600L, clock.now() - start)
    }

    @Test
    fun `several pauses each cost nothing`() {
        t += 100
        repeat(3) {
            clock.pause()
            t += 5_000
            clock.resume()
            t += 100
        }
        assertEquals(400L, clock.now() - start)
    }

    /**
     * The screen pushes playback state in on every emission, not only on a change, so
     * a second pause must not restart the count — it would add the time already
     * frozen a second time and the board would jump backwards on resume.
     */
    @Test
    fun `pausing twice does not double-count the pause`() {
        t += 100
        clock.pause()
        t += 1_000
        clock.pause()
        t += 1_000
        clock.resume()
        assertEquals(100L, clock.now() - start)
    }

    @Test
    fun `resuming when it was never paused changes nothing`() {
        t += 100
        clock.resume()
        assertEquals(100L, clock.now() - start)
    }

    @Test
    fun `setPlaying is the two of them`() {
        t += 100
        clock.setPlaying(false)
        assertTrue(clock.paused)
        t += 900
        clock.setPlaying(true)
        assertEquals(100L, clock.now() - start)
    }

    @Test
    fun `reset drops the time spent paused, and starts it running`() {
        t += 100
        clock.pause()
        t += 900
        clock.reset()
        assertFalse(clock.paused)
        assertEquals(t, clock.now(), "a new run owes nothing to the last one's pauses")
    }
}
