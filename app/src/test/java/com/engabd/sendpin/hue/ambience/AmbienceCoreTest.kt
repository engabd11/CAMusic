package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lamps laid out along a line — the commonest real arrangement. */
internal fun linearRoom(n: Int = 6): List<EntertainmentChannel> =
    (0 until n).map { i ->
        EntertainmentChannel(
            channelId = i,
            position = ChannelPosition(x = -1f + 2f * i / (n - 1f), y = 0f, z = 0f),
        )
    }

/** Lamps around a circle, floor plan only. */
internal fun ringRoom(n: Int = 8): List<EntertainmentChannel> =
    (0 until n).map { i ->
        val a = 2.0 * Math.PI * i / n
        EntertainmentChannel(
            channelId = i,
            position = ChannelPosition(
                x = Math.cos(a).toFloat(), y = Math.sin(a).toFloat(), z = 0f,
            ),
        )
    }

/** Three lamps huddled together — a table, not a room. */
internal fun clusterRoom(): List<EntertainmentChannel> = listOf(
    EntertainmentChannel(0, ChannelPosition(0.00f, 0.00f, 0f)),
    EntertainmentChannel(1, ChannelPosition(0.04f, 0.02f, 0f)),
    EntertainmentChannel(2, ChannelPosition(0.02f, 0.05f, 0f)),
)

class EnvelopeTest {

    @Test
    fun `reaches full at the end of the attack`() {
        val e = Envelope(attackS = 0.1f, holdS = 0f, decayTauS = 0.2f)
        assertEquals(1f, e.at(0.1f), 1e-3f)
    }

    @Test
    fun `rises monotonically through the attack`() {
        val e = Envelope(0.1f, 0f, 0.2f)
        var last = -1f
        var t = 0f
        while (t <= 0.1f) {
            val v = e.at(t)
            assertTrue(v >= last - 1e-6f, "fell at t=$t: $v after $last")
            last = v
            t += 0.005f
        }
    }

    @Test
    fun `is silent before it starts`() {
        assertEquals(0f, Envelope(0.1f, 0f, 0.2f).at(-0.5f))
    }

    @Test
    fun `is finished by its own lifetime`() {
        val e = Envelope(0.01f, 0.02f, 0.3f)
        assertEquals(0f, e.at(e.lifetimeS))
        assertTrue(e.at(e.lifetimeS - 1e-4f) <= e.tailCut * 1.5f)
    }

    @Test
    fun `has a finite lifetime even for a very short decay`() {
        // The ring can only be fixed-size because this is always finite.
        val e = Envelope(0f, 0f, 0.001f)
        assertTrue(e.lifetimeS.isFinite() && e.lifetimeS > 0f)
    }

    @Test
    fun `holds at full through the hold phase`() {
        val e = Envelope(0.01f, 0.05f, 0.1f)
        assertEquals(1f, e.at(0.03f), 1e-4f)
    }
}

class AmbienceTimelineTest {

    private fun event(startS: Double, life: Float = 0.5f) = AmbienceEvent(
        kind = AmbienceEvent.STRIKE,
        startS = startS,
        env = Envelope(0.01f, 0f, life / 8f),
        gain = 1f,
        origin = Vec3(0.5f, 0.5f, 0.5f),
        azimuth = 0f,
        colour = com.engabd.sendpin.hue.Rgb(1f, 1f, 1f),
    )

    @Test
    fun `returns only events that are alive`() {
        val t = AmbienceTimeline(16)
        t.append(event(0.0))
        t.append(event(10.0))
        val into = arrayOfNulls<AmbienceEvent>(8)
        val n = t.windowAt(10.01, into)
        assertEquals(1, n)
        assertEquals(10.0, into[0]!!.startS, 1e-9)
    }

    @Test
    fun `an event that has not started yet is not alive`() {
        val t = AmbienceTimeline(16)
        t.append(event(5.0))
        assertEquals(0, t.windowAt(4.9, arrayOfNulls(8)))
    }

    @Test
    fun `survives far more events than it can hold`() {
        // The ring overwrites, which is safe precisely because lifetimes are finite.
        val t = AmbienceTimeline(16)
        repeat(10_000) { t.append(event(it * 0.001)) }
        val into = arrayOfNulls<AmbienceEvent>(32)
        val n = t.windowAt(9.999, into)
        assertTrue(n in 0..32)
        for (i in 0 until n) assertTrue(into[i]!!.aliveAt(9.999))
    }

    @Test
    fun `clear empties it`() {
        val t = AmbienceTimeline(16)
        t.append(event(0.0))
        t.clear()
        assertEquals(0, t.windowAt(0.01, arrayOfNulls(8)))
    }

    @Test
    fun `capacity must be a power of two`() {
        var threw = false
        try { AmbienceTimeline(10) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }
}

class RoomModelTest {

    @Test
    fun `axis position runs end to end along a line`() {
        val room = RoomModel(linearRoom(6))
        val values = room.ids.map { room.axisPos[it]!! }
        assertEquals(0f, values.min(), 1e-4f)
        assertEquals(1f, values.max(), 1e-4f)
    }

    @Test
    fun `a line is classified as linear and a huddle as a cluster`() {
        assertEquals(com.engabd.sendpin.hue.RoomTopology.LINEAR, RoomModel(linearRoom()).topology)
        assertEquals(com.engabd.sendpin.hue.RoomTopology.CLUSTER, RoomModel(clusterRoom()).topology)
    }

    @Test
    fun `a flat room still produces a usable spread of heights`() {
        // The whole point of the synthetic height: the Hue app records a floor plan, so
        // z is zero everywhere in most real rooms and every vertical effect would
        // otherwise be a no-op.
        val room = RoomModel(ringRoom(8))
        assertTrue(!room.realHeight, "test fixture should be flat")
        val heights = room.ids.map { room.heightOf(it) }
        assertTrue(heights.all { it in 0f..1f })
        assertTrue(heights.max() - heights.min() > 0.2f, "no usable height spread: $heights")
    }

    @Test
    fun `synthetic height is stable across calls`() {
        val room = RoomModel(linearRoom())
        val first = room.ids.map { room.heightOf(it) }
        val second = room.ids.map { room.heightOf(it) }
        assertEquals(first, second)
    }

    @Test
    fun `coupling is row-stochastic, so it redistributes rather than amplifies`() {
        val room = RoomModel(linearRoom(6))
        for (row in room.coupling) {
            assertEquals(1f, row.sum(), 1e-3f)
        }
    }

    @Test
    fun `neighbours couple more strongly than distant lamps`() {
        val room = RoomModel(linearRoom(6))
        assertTrue(room.coupling[0][1] > room.coupling[0][5])
    }

    @Test
    fun `distance to a point is zero at the lamp itself`() {
        val room = RoomModel(linearRoom())
        val id = room.ids.first()
        assertEquals(0f, room.distanceTo(id, room.positions[id]!!), 1e-5f)
    }

    @Test
    fun `azimuth covers the ring`() {
        val room = RoomModel(ringRoom(8))
        val az = room.ids.map { room.azimuth[it]!! }
        assertTrue(az.all { it in 0f..1f })
        assertTrue(az.max() - az.min() > 0.6f, "azimuths bunched: $az")
    }

    @Test
    fun `centre is inside the room`() {
        val c = RoomModel(ringRoom()).centre()
        assertNotNull(c)
        assertTrue(c.x in 0f..1f && c.y in 0f..1f)
    }
}
