package com.engabd.sendpin.game

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.RoomModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the room shows in the rhythm game, and which lamps answer which lane.
 *
 * The promises under test: the room is a still, dim glow until the player acts; a hit
 * flashes its own lane, graded by how well it was struck; a miss is only ever a quiet
 * ember; and every lane lights something whatever the room looks like.
 */
class GameLightFieldTest {

    private fun ch(id: Int, x: Float, y: Float, z: Float = 0f) =
        EntertainmentChannel(channelId = id, position = ChannelPosition(x, y, z))

    private val idle = Rgb(1f, 0.5f, 0f)

    /** Four lamps in a row, ids ascending along the room's main axis, so lane k lights lamp k. */
    private fun fourInARow() = RoomModel((0 until 4).map { ch(it, -1f + 2f * it / 3f, 0f) })

    private fun field(room: RoomModel = fourInARow()) = GameLightField().also { it.bindRoom(room) }

    private fun Rgb.peak() = max(first, max(second, third))

    private fun GameLightField.at(t: Long, ceiling: Float = 1f) = render(t, ceiling, idle)

    // ── Idle ─────────────────────────────────────────────────────────────────

    @Test
    fun `idle is a dim glow in the album colour`() {
        val frame = field().at(1_000L)
        assertEquals(4, frame.size)
        for ((_, c) in frame) {
            assertEquals(GameLightField.IDLE_LEVEL, c.peak(), 0.001f)
            // Album colour, orange here: red strongest, blue absent.
            assertTrue(c.first > c.second && c.second > c.third)
        }
    }

    @Test
    fun `idle does not follow time or the beat`() {
        val f = field()
        val first = f.at(0L)
        for (t in listOf(37L, 250L, 500L, 777L, 5_000L)) assertEquals(first, f.at(t), "idle moved at $t")
    }

    @Test
    fun `idle stays under a low brightness ceiling`() {
        val frame = field().at(0L, ceiling = 0.03f)
        for ((_, c) in frame) assertTrue(c.peak() <= 0.0301f, "idle ${c.peak()} above a 3% ceiling")
    }

    @Test
    fun `a track with no colour rests in a neutral glow`() {
        val frame = field().render(0L, 1f, null)
        for ((_, c) in frame) assertEquals(GameLightField.IDLE_LEVEL, c.peak(), 0.001f)
    }

    // ── Hits ─────────────────────────────────────────────────────────────────

    @Test
    fun `a perfect hit lights its lane at full instantly`() {
        val f = field()
        f.postHit(lane = 0, judgement = Judgement.PERFECT, combo = 1, startMs = 1_000L)
        val frame = f.at(1_000L)
        assertEquals(GameLightField.PEAK_PERFECT, frame.getValue(0).peak(), 0.01f)
        // The kick lane wears red: the red channel leads.
        assertTrue(frame.getValue(0).first > frame.getValue(0).second)
    }

    @Test
    fun `grades set the peak`() {
        val peaks = listOf(Judgement.PERFECT, Judgement.GREAT, Judgement.GOOD).map { j ->
            val f = field()
            f.postHit(1, j, 1, 0L)
            f.at(0L).getValue(1).peak()
        }
        assertEquals(1.0f, peaks[0], 0.01f)
        assertEquals(0.7f, peaks[1], 0.01f)
        assertEquals(0.45f, peaks[2], 0.01f)
    }

    @Test
    fun `the brightness ceiling caps the peak`() {
        val f = field()
        f.postHit(2, Judgement.PERFECT, 1, 0L)
        assertEquals(0.4f, f.at(0L, ceiling = 0.4f).getValue(2).peak(), 0.01f)
    }

    @Test
    fun `a hit decays back to the glow in about a quarter of a second`() {
        val f = field()
        f.postHit(0, Judgement.PERFECT, 1, 0L)
        val mid = f.at(125L).getValue(0).peak()
        assertTrue(mid in 0.15f..0.5f, "mid decay $mid")
        assertEquals(GameLightField.IDLE_LEVEL, f.at(400L).getValue(0).peak(), 0.005f)
    }

    @Test
    fun `a long combo lengthens the glow`() {
        val short = field().also { it.postHit(0, Judgement.PERFECT, 1, 0L) }.at(240L).getValue(0).peak()
        val long = field().also { it.postHit(0, Judgement.PERFECT, 49, 0L) }.at(240L).getValue(0).peak()
        assertTrue(long > short + 0.05f, "combo did not lengthen the glow: $short vs $long")
    }

    @Test
    fun `a long combo deepens the colour`() {
        // Same lane, same instant: the high combo's flash is purer (less white).
        val low = field().also { it.postHit(0, Judgement.PERFECT, 1, 0L) }.at(0L).getValue(0)
        val high = field().also { it.postHit(0, Judgement.PERFECT, 49, 0L) }.at(0L).getValue(0)
        assertTrue(high.second < low.second, "combo did not saturate the colour")
        assertTrue(high.third < low.third)
    }

    @Test
    fun `a hit lights its own lane more than its neighbours`() {
        val f = field()
        f.postHit(1, Judgement.PERFECT, 1, 0L)
        val frame = f.at(0L)
        val own = frame.getValue(1).peak()
        val next = frame.getValue(2).peak()
        val far = frame.getValue(3).peak()
        assertTrue(own > next && next > GameLightField.IDLE_LEVEL, "no spill to the neighbour: $own $next")
        assertEquals(GameLightField.SPILL, next / own, 0.06f)
        assertTrue(far < next, "the far lamp out-shone the neighbour")
    }

    @Test
    fun `an early tap's flash waits for its start time`() {
        val f = field()
        // Tapped at 1 000 for a note heard at 1 250: held 150 ms.
        val start = 1_000L + GameTiming.flashDelayMs(250L)
        f.postHit(0, Judgement.PERFECT, 1, start)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(1_000L).getValue(0).peak(), 0.001f)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(start - 1).getValue(0).peak(), 0.001f)
        assertEquals(1f, f.at(start).getValue(0).peak(), 0.01f)
    }

    @Test
    fun `a missed grade never lights anything`() {
        val f = field()
        f.postHit(0, Judgement.MISS, 0, 0L)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(0L).getValue(0).peak(), 0.001f)
    }

    // ── Misses ───────────────────────────────────────────────────────────────

    @Test
    fun `a miss is a dim red ember on its lane and never a flash`() {
        val f = field()
        f.postMiss(2, 500L)
        var most = 0f
        for (t in 500L..800L step 10L) {
            val c = f.at(t).getValue(2)
            most = max(most, c.peak())
            assertTrue(c.first >= c.second && c.first >= c.third, "ember not red at $t")
        }
        assertEquals(GameLightField.EMBER_LEVEL, most, 0.01f)
        // Eased in, not struck: the first frame is no brighter than the glow.
        assertTrue(f.at(500L).getValue(2).peak() <= GameLightField.IDLE_LEVEL + 0.001f)
    }

    @Test
    fun `an ember touches only its lane and ends after 300 ms`() {
        val f = field()
        f.postMiss(2, 0L)
        val mid = f.at(60L)
        assertTrue(mid.getValue(2).peak() > GameLightField.IDLE_LEVEL)
        for (id in listOf(0, 1, 3)) assertEquals(GameLightField.IDLE_LEVEL, mid.getValue(id).peak(), 0.001f)
        assertEquals(GameLightField.IDLE_LEVEL, f.at(300L).getValue(2).peak(), 0.001f)
    }

    // ── Combo ────────────────────────────────────────────────────────────────

    @Test
    fun `combo milestones bloom the whole room`() {
        for (combo in GameLightField.BLOOM_COMBOS) {
            val f = field()
            f.postHit(0, Judgement.PERFECT, combo, 0L)
            val far = f.at(100L).getValue(3).peak()
            assertTrue(far > 0.2f, "no bloom at combo $combo: far lamp $far")
        }
    }

    @Test
    fun `between milestones there is no bloom`() {
        for (combo in listOf(9, 11, 24, 26, 49)) {
            val f = field()
            f.postHit(0, Judgement.PERFECT, combo, 0L)
            val far = f.at(100L).getValue(3).peak()
            assertTrue(far < 0.12f, "bloom at combo $combo: far lamp $far")
        }
    }

    @Test
    fun `the bloom is soft and gone within a second`() {
        val f = field()
        f.postHit(0, Judgement.PERFECT, 10, 0L)
        var most = 0f
        for (t in 0L..800L step 20L) most = max(most, f.at(t).getValue(3).peak())
        assertTrue(most <= 0.31f, "bloom too strong: $most")
        assertEquals(GameLightField.IDLE_LEVEL, f.at(900L).getValue(3).peak(), 0.001f)
    }

    @Test
    fun `a combo break fades the colour over 400 ms`() {
        fun hueRatio(breakIt: Boolean, t: Long): Float {
            val f = field()
            f.postHit(0, Judgement.PERFECT, 49, 0L)
            if (breakIt) f.postComboBreak(0L)
            val c = f.at(t).getValue(0)
            return c.second / c.first
        }
        // Held at full combo the colour stays pure; once broken it washes toward white.
        val held = hueRatio(false, 200L)
        val faded = hueRatio(true, 200L)
        val early = hueRatio(true, 100L)
        assertTrue(faded > held + 0.05f, "colour did not fade: $held vs $faded")
        assertTrue(faded > early && early > held, "colour fade is not progressive: $held $early $faded")
    }

    // ── Threading ────────────────────────────────────────────────────────────

    @Test
    fun `commands posted from another thread reach the next frame`() {
        val f = field()
        val t = Thread { f.postHit(3, Judgement.GREAT, 1, 0L) }
        t.start()
        t.join()
        assertEquals(0.7f, f.at(0L).getValue(3).peak(), 0.01f)
    }

    // ── Lane to lamp mapping ─────────────────────────────────────────────────

    private fun lanesOf(channels: List<EntertainmentChannel>) = GameLightField.laneMap(RoomModel(channels))

    private fun assertEveryLaneLights(lanes: List<List<Int>>, lamps: Int) {
        assertEquals(GameLightField.LANE_COUNT, lanes.size)
        lanes.forEachIndexed { i, l -> assertTrue(l.isNotEmpty(), "lane $i lights nothing: $lanes") }
        if (lamps >= GameLightField.LANE_COUNT) {
            assertEquals(lamps, lanes.flatten().size, "a lamp is in two zones or none: $lanes")
            assertEquals(lamps, lanes.flatten().toSet().size)
        }
    }

    @Test
    fun `one lamp serves every lane`() {
        val lanes = lanesOf(listOf(ch(7, 0f, 0f)))
        assertEveryLaneLights(lanes, 1)
        assertTrue(lanes.all { it == listOf(7) })
    }

    @Test
    fun `two lamps split the lanes in pairs`() {
        // Ids against the grain of the room: the lower id sits at the far end.
        val lanes = lanesOf(listOf(ch(5, 1f, 0f), ch(2, -1f, 0f)))
        assertEveryLaneLights(lanes, 2)
        val (a, b, c, d) = lanes
        assertEquals(a, b)
        assertEquals(c, d)
        assertTrue(a != c, "both ends of the room answer the same lamp")
    }

    @Test
    fun `three lamps put kick and melody at opposite ends`() {
        val lanes = lanesOf(listOf(ch(0, -1f, 0f), ch(1, 0f, 0f), ch(2, 1f, 0f)))
        assertEveryLaneLights(lanes, 3)
        assertEquals(listOf(listOf(0), listOf(1), listOf(1), listOf(2)), lanes)
    }

    @Test
    fun `a ring of six is cut into azimuth sectors`() {
        val channels = (0 until 6).map { i ->
            val t = 2.0 * PI * i / 6
            ch(i, cos(t).toFloat(), sin(t).toFloat())
        }
        val room = RoomModel(channels)
        assertEquals(com.engabd.sendpin.hue.RoomTopology.RING, room.topology)
        val lanes = GameLightField.laneMap(room)
        assertEveryLaneLights(lanes, 6)
        // Sectors are contiguous round the ring: every lane's azimuths sit entirely
        // before the next lane's.
        for (k in 0 until 3) {
            val hi = lanes[k].maxOf { room.azimuth.getValue(it) }
            val lo = lanes[k + 1].minOf { room.azimuth.getValue(it) }
            assertTrue(hi <= lo, "lane $k overlaps lane ${k + 1} in azimuth")
        }
    }

    @Test
    fun `ten lamps along a wall are cut into bands by position`() {
        // Ids shuffled against position, so ordering by id would give a different answer.
        val xs = listOf(5, 2, 8, 0, 9, 3, 6, 1, 7, 4)
        val channels = xs.mapIndexed { id, x -> ch(id, -1f + x * 0.2f, 0f) }
        val room = RoomModel(channels)
        assertEquals(com.engabd.sendpin.hue.RoomTopology.LINEAR, room.topology)
        val lanes = GameLightField.laneMap(room)
        assertEveryLaneLights(lanes, 10)
        assertEquals(listOf(2, 3, 2, 3), lanes.map { it.size })
        for (k in 0 until 3) {
            val hi = lanes[k].maxOf { room.axisPos.getValue(it) }
            val lo = lanes[k + 1].minOf { room.axisPos.getValue(it) }
            assertTrue(hi <= lo, "lane $k overlaps lane ${k + 1} along the axis")
        }
    }

    @Test
    fun `ten scattered lamps still give every lane a zone`() {
        val spots = listOf(
            0f to 0f, 1f to 0.2f, 0.3f to 1f, 0.9f to 0.9f, 0.5f to 0.4f,
            0.1f to 0.7f, 0.7f to 0.1f, 0.4f to 0.8f, 0.8f to 0.5f, 0.2f to 0.3f,
        )
        val channels = spots.mapIndexed { i, (x, y) -> ch(i, x, y, if (i % 3 == 0) 0.4f else 0f) }
        assertEveryLaneLights(lanesOf(channels), 10)
    }

    @Test
    fun `an empty room paints nothing`() {
        val f = GameLightField().also { it.bindRoom(RoomModel(emptyList())) }
        f.postHit(0, Judgement.PERFECT, 1, 0L)
        assertTrue(f.at(0L).isEmpty())
    }

    @Test
    fun `lane colours are the ones the board draws`() {
        // The room and the glass wear the same four colours.
        assertEquals(4, LANE_RGB.size)
        assertEquals(4, LANE_RGB.toSet().size)
        for (rgb in LANE_RGB) assertTrue(abs(rgb) <= 0xFFFFFF)
    }
}
