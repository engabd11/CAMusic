package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.scripts.FireplaceScript
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pop glint is cast light: it reaches the far side of the room off the walls,
 * and bounced light arrives spread over several frames rather than in one step.
 *
 * The near lamp may keep its near-instant glint — light from the fire itself has
 * nothing to bounce off. The far lamp's glint must lag. Both scripts below share
 * one seed, so the with/without difference is the glint alone (the same trick the
 * thunder-coherence test uses against the rain bed).
 */
class FireplaceCastTest {

    @Test
    fun `a pop's glint reaches the far wall later than the near lamp`() {
        val room = RoomModel(linearRoom(6))
        // The hearth is the lowest lamp: give the line real height so the first lamp
        // (z = 0, the floor) is unambiguously it, and the last lamp (z = 1) sits far
        // from it on the room's longest axis.
        val withHeight = (0 until 6).map { i ->
            val base = linearRoom(6)[i]
            com.engabd.sendpin.hue.EntertainmentChannel(
                channelId = i,
                position = com.engabd.sendpin.hue.ChannelPosition(
                    base.position.x, base.position.y, z = i / 5f,
                ),
            )
        }
        val tall = RoomModel(withHeight)
        val hearth = tall.ids.first()
        val e = AmbienceEvent(
            kind = AmbienceEvent.POP,
            startS = 1.0,
            env = Envelope(attackS = 0.020f, holdS = 0.006f, decayTauS = 0.09f),
            gain = 1f,
            origin = tall.positions.getValue(hearth),  // at the hearth itself
            azimuth = 0f,
            colour = Rgb(1f, 0.85f, 0.55f),
            timbre = 1f,
        )
        val a = FireplaceScript().also { it.bind(tall, AmbienceParams(intensity = 1f)) }
        val b = FireplaceScript().also { it.bind(tall, AmbienceParams(intensity = 1f)) }
        val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
        val none = arrayOfNulls<AmbienceEvent>(1)

        fun peakFrame(id: Int): Int {
            var best = -1f
            var bestF = 0
            var f = 0
            var t = e.startS
            while (t < e.startS + 0.5) {
                val withIt = HashMap<Int, Rgb>()
                val without = HashMap<Int, Rgb>()
                a.renderLights(t, one, 1, withIt)
                b.renderLights(t, none, 0, without)
                val w = withIt[id] ?: Rgb(0f, 0f, 0f)
                val v = without[id] ?: Rgb(0f, 0f, 0f)
                val glint = max(w.first - v.first, max(w.second - v.second, w.third - v.third))
                if (glint > best) { best = glint; bestF = f }
                f++
                t += 1.0 / 60.0
            }
            return bestF
        }

        val near = peakFrame(hearth)
        val far = peakFrame(tall.ids.last())
        assertTrue(far > near, "far glint peaked at frame $far, near at $near - no cast lag")
    }

    /**
     * The cast filter is per-lamp state, so it must advance exactly once per frame.
     *
     * It used to be stepped inside the loop over live events, which made its time
     * constant depend on how many pops happened to overlap — and they do overlap:
     * at full intensity the rate is 3 a second against a 0.38 s envelope. A one-pole
     * is linear, so the room's answer to two identical pops must be exactly twice its
     * answer to one. Under the old code it was not: the doubled stepping converged
     * the filter faster and the early frames ran hot.
     */
    @Test
    fun `overlapping pops stay linear through the cast filter`() {
        val room = RoomModel(linearRoom(6))
        val far = room.ids.last()
        fun pop() = AmbienceEvent(
            kind = AmbienceEvent.POP,
            startS = 1.0,
            env = Envelope(attackS = 0.020f, holdS = 0.006f, decayTauS = 0.09f),
            gain = 1f,
            origin = room.positions.getValue(room.ids.first()),
            azimuth = 0f,
            colour = Rgb(1f, 0.85f, 0.55f),
            timbre = 1f,
        )
        // Two events, identical in everything the light path reads, so twice the input.
        val one = arrayOfNulls<AmbienceEvent>(2).also { it[0] = pop() }
        val two = arrayOfNulls<AmbienceEvent>(2).also { it[0] = pop(); it[1] = pop() }
        val none = arrayOfNulls<AmbienceEvent>(2)

        // Low intensity keeps the base dim so nothing clips at 1.0 and the doubling
        // stays visible in the arithmetic rather than in the ceiling.
        val a = FireplaceScript().also { it.bind(room, AmbienceParams(intensity = 0.2f)) }
        val b = FireplaceScript().also { it.bind(room, AmbienceParams(intensity = 0.2f)) }
        val c = FireplaceScript().also { it.bind(room, AmbienceParams(intensity = 0.2f)) }

        var worst = 0f
        var seen = 0f
        var t = 1.0
        while (t < 1.5) {
            val fa = HashMap<Int, Rgb>()
            val fb = HashMap<Int, Rgb>()
            val fc = HashMap<Int, Rgb>()
            a.renderLights(t, one, 1, fa)
            b.renderLights(t, two, 2, fb)
            c.renderLights(t, none, 0, fc)
            val base = fc.getValue(far).first
            val g1 = fa.getValue(far).first - base
            val g2 = fb.getValue(far).first - base
            worst = max(worst, kotlin.math.abs(g2 - 2f * g1))
            seen = max(seen, g1)
            t += 1.0 / 60.0
        }
        assertTrue(seen > 1e-4f, "no glint reached the far lamp at all, nothing was tested")
        assertTrue(worst < 1e-4f, "two pops were not twice one: off by $worst at worst")
    }
}
