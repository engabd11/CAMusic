package com.engabd.sendpin.hue

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What shape are the lamps in, and what may be drawn on that shape?
 *
 * Every test here corresponds to a way the room gesture layer could look broken
 * rather than absent, which is the failure mode that matters — an effect that
 * does not fire is invisible, and one that fires in the wrong place is a fault
 * the user will report as flickering.
 *
 * All synthetic rooms and pure geometry: no bridge, no audio, no device.
 */
class RoomTopologyTest {

    private fun ch(id: Int, x: Float, y: Float, z: Float = 0f) =
        EntertainmentChannel(channelId = id, position = ChannelPosition(x, y, z))

    /** n lamps evenly around a circle of radius [r], as a room's corners would be. */
    private fun ringRoom(n: Int, r: Float = 1f, from: Float = 0f, span: Float = 1f) =
        (0 until n).map { i ->
            val t = 2.0 * PI * (from + span * i / n)
            ch(i, (r * cos(t)).toFloat(), (r * sin(t)).toFloat())
        }

    private val line = (0 until 5).map { ch(it, -1f + 0.5f * it, 0f) }

    // ── Classification ────────────────────────────────────────────────────

    @Test
    fun `a row of lamps is linear`() {
        assertEquals(RoomTopology.LINEAR, classifyTopology(line))
    }

    /**
     * The case a per-axis normalisation gets wrong. Five lamps along a wall with a
     * few centimetres of wobble are a line; `normalizePositions` stretches that
     * wobble to the full width of the unit cube and turns them into a blob.
     */
    @Test
    fun `a nearly straight row is still linear`() {
        val wobbly = (0 until 5).map { i -> ch(i, -1f + 0.5f * i, if (i % 2 == 0) 0.03f else -0.03f) }
        assertEquals(
            RoomTopology.LINEAR, classifyTopology(wobbly),
            "a 3 cm wobble across a 2 m row should not stop it being a line",
        )
    }

    @Test
    fun `lamps around the room are a ring`() {
        assertEquals(RoomTopology.RING, classifyTopology(ringRoom(6)))
    }

    /**
     * The gap test earning its place. Four lamps along one wall fit a circle
     * beautifully — any four points near an arc do — and cannot be swept around.
     */
    @Test
    fun `lamps bunched in an arc are not a ring`() {
        val arc = ringRoom(4, span = 0.22f)
        assertTrue(
            classifyTopology(arc) != RoomTopology.RING,
            "four lamps spanning a fifth of a turn were classified as a ring",
        )
    }

    /**
     * The test that would catch a regression to classifying on normalised
     * positions: [normalizePositions] stretches these two to opposite corners of
     * the unit cube, at which point they are indistinguishable from a room.
     */
    @Test
    fun `two lamps on a shelf are a cluster however they normalise`() {
        val shelf = listOf(ch(0, 0.10f, 0f), ch(1, 0.20f, 0f))
        assertEquals(RoomTopology.CLUSTER, classifyTopology(shelf))

        val normalised = normalizePositions(shelf)
        assertEquals(0f, normalised.getValue(0).x, 1e-6f)
        assertEquals(1f, normalised.getValue(1).x, 1e-6f)
    }

    @Test
    fun `three lamps in a huddle are a cluster`() {
        val huddle = listOf(ch(0, 0f, 0f), ch(1, 0.15f, 0.1f), ch(2, 0.1f, -0.12f))
        assertEquals(RoomTopology.CLUSTER, classifyTopology(huddle))
    }

    @Test
    fun `fewer than three lamps can never be anything else`() {
        assertEquals(RoomTopology.CLUSTER, classifyTopology(listOf(ch(0, -1f, -1f), ch(1, 1f, 1f))))
        assertEquals(RoomTopology.CLUSTER, classifyTopology(emptyList()))
    }

    @Test
    fun `scattered lamps are a field`() {
        val scattered = listOf(
            ch(0, -1f, -0.9f), ch(1, 0.9f, -1f), ch(2, -0.2f, 0.1f), ch(3, 1f, 0.8f),
            ch(4, -0.95f, 0.85f), ch(5, 0.1f, -0.35f), ch(6, 0.55f, 0.2f), ch(7, -0.4f, -0.6f),
        )
        val t = classifyTopology(scattered)
        assertTrue(
            t == RoomTopology.FIELD,
            "eight scattered lamps classified as $t rather than a field",
        )
    }

    /**
     * Lamps round a rectangular room are an ellipse, not a circle, and must still
     * read as a ring — that is what "lights in the corners" actually looks like.
     */
    @Test
    fun `lamps round a rectangular room are still a ring`() {
        val corners = listOf(
            ch(0, -1f, -0.5f), ch(1, 1f, -0.5f), ch(2, 1f, 0.5f), ch(3, -1f, 0.5f),
        )
        assertEquals(RoomTopology.RING, classifyTopology(corners))
    }

    // ── Height ────────────────────────────────────────────────────────────

    /**
     * The common case, and the one the vertical gesture has to survive: the Hue
     * app is a floor plan and its own API example reports `z: 0.0` for every
     * channel.
     */
    @Test
    fun `a flat room has no height to rise through`() {
        assertTrue(!hasHeight(line), "a room with every z at 0 reported usable height")
        assertTrue(!hasHeight(emptyList()))
    }

    @Test
    fun `a room with lamps high and low has height`() {
        val stacked = listOf(ch(0, -1f, 0f, -0.8f), ch(1, 0f, 0f, 0f), ch(2, 1f, 0f, 0.9f))
        assertTrue(hasHeight(stacked))
    }

    // ── Axis and ring geometry ────────────────────────────────────────────

    @Test
    fun `the principal axis of a row is the row`() {
        val (dir, explained) = principalAxis(roomShape(line).values.toList())
        assertTrue(explained > 0.99f, "a perfect line explained only $explained of its variance")
        assertTrue(abs(abs(dir.x) - 1f) < 1e-3f, "the axis of an x-aligned row was $dir")
    }

    /**
     * A fixed power-iteration start vector can be exactly orthogonal to the
     * dominant axis, and this is the room that does it.
     */
    @Test
    fun `a diagonal row does not defeat the power iteration`() {
        val diagonal = (0 until 5).map { i -> ch(i, -1f + 0.5f * i, 1f - 0.5f * i) }
        val (dir, explained) = principalAxis(roomShape(diagonal).values.toList())
        assertTrue(explained > 0.99f, "a diagonal line explained only $explained of its variance")
        assertTrue(abs(dir.x) > 0.5f && abs(dir.y) > 0.5f, "the diagonal axis came out as $dir")
    }

    @Test
    fun `a circle fits itself with almost no residual`() {
        val fitted = fitRing(normalizePositions(ringRoom(8)).values.toList())
        assertNotNull(fitted)
        assertTrue(fitted.residual < 0.02f, "a perfect circle fitted with residual ${fitted.residual}")
        assertEquals(0.5f, fitted.centre.x, 0.02f)
        assertEquals(0.5f, fitted.centre.y, 0.02f)
    }

    @Test
    fun `a ring cannot be fitted through fewer than three lamps`() {
        assertNull(fitRing(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 1f, 0f))))
    }

    @Test
    fun `collinear lamps do not fit a circle`() {
        val fitted = fitRing(normalizePositions(line).values.toList())
        // Either the solve is singular or the residual is hopeless; both are
        // correct answers and neither may be mistaken for a ring.
        assertTrue(
            fitted == null || fitted.residual > 0.25f,
            "a straight line fitted a circle with residual ${fitted?.residual}",
        )
    }

    @Test
    fun `azimuth runs counter-clockwise from room-right`() {
        val ring = Ring(Vec3(0.5f, 0.5f, 0f), 0.5f, 0f)
        assertEquals(0f, azimuthOf(Vec3(1f, 0.5f, 0f), ring), 1e-4f)      // right
        assertEquals(0.25f, azimuthOf(Vec3(0.5f, 1f, 0f), ring), 1e-4f)   // front
        assertEquals(0.5f, azimuthOf(Vec3(0f, 0.5f, 0f), ring), 1e-4f)    // left
        assertEquals(0.75f, azimuthOf(Vec3(0.5f, 0f, 0f), ring), 1e-4f)   // behind
    }

    /**
     * The sign error this whole feature is most likely to ship with: a sound
     * crossing in front of the listener rendered as one going round behind them.
     */
    @Test
    fun `a left to right sweep goes round the front`() {
        val from = azimuthForPan(-1f)
        val to = azimuthForPan(1f)
        assertEquals(0.5f, from, 1e-4f)
        assertEquals(0f, to, 1e-4f)
        val midway = from + (to - from) * 0.5f
        assertEquals(0.25f, midway, 1e-4f, "the halfway point of a sweep was not the front of the room")
    }

    @Test
    fun `an even ring has no large gap and a bunched one does`() {
        val even = (0 until 6).map { it / 6f }
        assertEquals(1f / 6f, largestAzimuthGap(even), 1e-4f)
        val bunched = listOf(0f, 0.02f, 0.05f, 0.08f)
        assertTrue(largestAzimuthGap(bunched) > 0.9f, "four bunched lamps reported a small gap")
    }

    // ── Gesture primitives ────────────────────────────────────────────────

    @Test
    fun `a front reaches every lamp in order and each one only once`() {
        val f = TravellingFront(from = 0f, to = 1f, durationS = 2f, strength = 1f, width = 0.2f)
        val lamps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val peakAt = FloatArray(lamps.size)
        val peak = FloatArray(lamps.size)
        var t = 0f
        while (t <= 2f) {
            lamps.forEachIndexed { i, c ->
                val a = f.amplitudeAt(c)
                if (a > peak[i]) { peak[i] = a; peakAt[i] = t }
            }
            f.advance(0.02f)
            t += 0.02f
        }
        for (i in 1 until lamps.size) {
            assertTrue(
                peakAt[i] > peakAt[i - 1],
                "lamp $i peaked at ${peakAt[i]}s, not after lamp ${i - 1} at ${peakAt[i - 1]}s",
            )
        }
        for (i in lamps.indices) assertTrue(peak[i] > 0.1f, "lamp $i never lit (peak ${peak[i]})")
    }

    @Test
    fun `a front fades in and out rather than appearing`() {
        val f = TravellingFront(0f, 1f, durationS = 1f, strength = 1f, width = 10f)
        assertEquals(0f, f.envelope, 1e-4f, "a gesture was already at full brightness on its first frame")
        f.advance(0.5f)
        assertEquals(1f, f.envelope, 1e-4f)
        f.advance(0.5f)
        assertEquals(0f, f.envelope, 1e-4f, "a gesture ended abruptly instead of fading")
    }

    /** A lamp just *behind* the start of a sweep is close to it, not a turn away. */
    @Test
    fun `a circular front measures the short way round`() {
        val f = TravellingFront(0f, 0.5f, durationS = 1f, strength = 1f, width = 0.15f, circular = true)
        f.advance(0.001f)
        val ahead = f.amplitudeAt(0.05f)
        val behind = f.amplitudeAt(0.95f)
        assertTrue(behind > 0.3f * ahead, "a lamp 0.05 turns behind the front read as far away ($behind vs $ahead)")
    }

    @Test
    fun `a moving source travels between its endpoints`() {
        val s = MovingSource(
            from = Vec3(0f, 0.5f, 0.5f), to = Vec3(1f, 0.5f, 0.5f),
            durationS = 1f, strength = 1f, radius = 0.3f,
        )
        val left = Vec3(0f, 0.5f, 0.5f)
        val right = Vec3(1f, 0.5f, 0.5f)
        s.advance(0.05f)
        assertTrue(s.amplitudeAt(left) > s.amplitudeAt(right), "the source did not start on the left")
        s.advance(0.9f)
        assertTrue(s.amplitudeAt(right) > s.amplitudeAt(left), "the source did not end on the right")
        s.advance(0.1f)
        assertTrue(s.done)
    }

    @Test
    fun `a gesture with no duration is immediately finished rather than dividing by zero`() {
        val f = TravellingFront(0f, 1f, durationS = 0f, strength = 1f, width = 0.2f)
        assertTrue(f.done)
        assertTrue(f.amplitudeAt(0.5f).isFinite())
    }
}
