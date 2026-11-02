package com.engabd.sendpin.game

import com.engabd.sendpin.hue.Rgb
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The room in the rhythm game is the music's own show behind a gate the player opens.
 *
 * The promises under test: with no hits the room is the idle glow whatever the show
 * is doing; an open gate passes the show itself, graded by the hit; a hold, a drop
 * and a build charge keep it open; a miss shuts it.
 */
class GameLightFieldTest {

    private val idle = Rgb(1f, 0.5f, 0f)

    /** A busy show: two lamps at full, mid-flash. */
    private val show = mapOf(1 to Rgb(0.2f, 0.4f, 1f), 2 to Rgb(1f, 1f, 1f))

    private fun Rgb.peak() = max(first, max(second, third))

    private fun GameLightField.at(t: Long, ceiling: Float = 1f) = composite(show, t, ceiling, idle)

    @Test
    fun `with no hits the room is the idle glow, whatever the show does`() {
        val f = GameLightField()
        for (t in listOf(0L, 250L, 5_000L)) {
            for ((_, c) in f.at(t)) {
                assertEquals(GameLightField.IDLE_LEVEL, c.peak(), 0.001f)
                assertTrue(c.first > c.second && c.second > c.third, "the glow should be the album colour")
            }
        }
    }

    @Test
    fun `an open gate passes the show itself`() {
        val f = GameLightField()
        f.postOpen(fromMs = 1_000L, untilMs = 1_500L, level = 1f)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(999L)[2]!!.peak(), 0.001f, "the gate opened early")
        val open = f.at(1_200L)
        assertEquals(show[1], open[1])
        assertEquals(show[2], open[2])
    }

    @Test
    fun `a good hit lets in about half the show`() {
        val f = GameLightField()
        f.postOpen(1_000L, 1_500L, level = 0.5f)
        assertEquals(0.5f, f.at(1_200L)[2]!!.peak(), 0.01f)
    }

    @Test
    fun `the gate lets go after its window, and a close shuts it at once`() {
        val f = GameLightField()
        f.postOpen(1_000L, 1_500L, 1f)
        f.at(1_100L)
        assertTrue(f.gateAt(1_500L + GameLightField.RELEASE_MS.toLong() + 1) <= 0f, "the gate never let go")

        f.postOpen(2_000L, 3_000L, 1f)
        f.at(2_100L)
        f.postClose(2_200L)
        f.at(2_200L)
        assertTrue(f.gateAt(2_200L + GameLightField.RELEASE_MS.toLong() + 1) <= 0f, "a miss left the gate open")
    }

    @Test
    fun `a hold or a drop keeps the gate open to its end`() {
        val f = GameLightField()
        f.postOpen(1_000L, 4_000L, 1f)
        for (t in 1_100L..3_900L step 400L) assertEquals(1f, f.at(t)[2]!!.peak(), 0.001f, "the gate sagged at $t")
    }

    @Test
    fun `a build charge lets the show in on its own`() {
        val f = GameLightField()
        f.postCharge(1f)
        assertEquals(GameLightField.CHARGE_GATE, f.at(500L)[2]!!.peak(), 0.001f)
        f.postCharge(0f)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(600L)[2]!!.peak(), 0.001f)
    }

    @Test
    fun `the idle glow stays under a low brightness ceiling`() {
        val c = GameLightField().at(0L, ceiling = 0.02f)[1]!!
        assertTrue(c.peak() <= 0.02f + 1e-4f)
    }
}
