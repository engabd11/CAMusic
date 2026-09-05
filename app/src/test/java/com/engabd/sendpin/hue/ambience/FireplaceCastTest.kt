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
}
