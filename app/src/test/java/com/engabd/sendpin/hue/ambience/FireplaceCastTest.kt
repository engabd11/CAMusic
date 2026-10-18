package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.scripts.FireplaceScript
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A pop is a coal spitting: it warms the lamp or two beside the fire, slowly, and the
 * room does not notice.
 *
 * These replace the tests of the old "cast" filter, which pinned a different design: a
 * glint that surged every lamp and reached the far wall a few frames late. That surge was
 * the uncomfortable part. What matters now is the opposite property: the far wall is not
 * touched at all.
 *
 * Every test renders two scripts from one seed, with and without the event, so the
 * difference between them is the pop alone (the flicker is a pure function of time and
 * the seed, and identical in both).
 */
class FireplaceCastTest {

    /** Six lamps up a line, rising with the line so the first is unambiguously the hearth. */
    private fun tallRoom(): RoomModel {
        val channels = (0 until 6).map { i ->
            EntertainmentChannel(
                channelId = i,
                position = ChannelPosition(-1f + 2f * i / 5f, 0f, z = i / 5f),
            )
        }
        return RoomModel(channels)
    }

    private fun pop(room: RoomModel, gain: Float = 0.1f, startS: Double = 1.0) = AmbienceEvent(
        kind = AmbienceEvent.POP,
        startS = startS,
        env = Envelope(attackS = 0.12f, holdS = 0.03f, decayTauS = 0.25f, tailCut = 0.05f),
        gain = gain,
        origin = room.positions.getValue(room.ids.first()),  // at the hearth itself
        azimuth = 0f,
        colour = Rgb(1f, 0.62f, 0.27f),
        timbre = 1f,
    )

    private fun bound(room: RoomModel, intensity: Float) =
        FireplaceScript().also { it.bind(room, AmbienceParams(intensity = intensity)) }

    private fun glint(
        a: FireplaceScript, b: FireplaceScript, e: AmbienceEvent, t: Double, id: Int,
    ): Float {
        val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
        val none = arrayOfNulls<AmbienceEvent>(1)
        val withIt = HashMap<Int, Rgb>()
        val without = HashMap<Int, Rgb>()
        a.renderLights(t, one, 1, withIt)
        b.renderLights(t, none, 0, without)
        val w = withIt.getValue(id)
        val v = without.getValue(id)
        return max(w.first - v.first, max(w.second - v.second, w.third - v.third))
    }

    @Test
    fun `a pop warms the lamps beside the fire and leaves the far wall alone`() {
        val room = tallRoom()
        val hearth = room.ids.first()
        val e = pop(room)
        val a = bound(room, 0.5f)
        val b = bound(room, 0.5f)

        var nearPeak = 0f
        var farPeak = 0f
        var t = e.startS
        while (t < e.startS + 2.0) {
            nearPeak = max(nearPeak, glint(a, b, e, t, hearth))
            for (id in room.ids.drop(2)) farPeak = max(farPeak, glint(a, b, e, t, id))
            t += 1.0 / 60.0
        }
        assertTrue(nearPeak in 0.05f..0.12f, "the nearest lamp rose by $nearPeak, not the 0.06 to 0.10 a pop is")
        assertTrue(farPeak < 0.002f, "a pop reached the far lamps ($farPeak) - it should be local")
    }

    @Test
    fun `a pop rises over about a tenth of a second and cools over most of a second`() {
        val room = tallRoom()
        val hearth = room.ids.first()
        val e = pop(room)
        val a = bound(room, 0.5f)
        val b = bound(room, 0.5f)

        val frames = ArrayList<Float>()
        var t = e.startS - 0.1
        while (t < e.startS + 2.5) {
            frames.add(glint(a, b, e, t, hearth))
            t += 1.0 / 60.0
        }
        val peak = frames.max()
        val peakAt = frames.indexOf(peak)
        val startAt = 6          // frame of e.startS: 0.1 s of lead-in
        val firstSeen = frames.indexOfFirst { it > peak * 0.1f }
        val riseS = (peakAt - firstSeen) / 60f
        assertTrue(riseS in 0.06f..0.30f, "the pop rose over ${riseS}s")
        // Half a second after the peak it is still a visible glow, and it is gone by two.
        val half = frames[peakAt + 30]
        assertTrue(half > peak * 0.05f && half < peak * 0.7f, "half a second on it is at ${half / peak} of the peak")
        assertTrue(frames[peakAt + 120] < peak * 0.03f, "an ember still glowing two seconds on")
        // A step in one frame is a pop, not a spark.
        var worstStep = 0f
        for (i in 1 until frames.size) worstStep = max(worstStep, kotlin.math.abs(frames[i] - frames[i - 1]))
        assertTrue(worstStep < 0.025f, "the glow stepped $worstStep in one frame")
        assertTrue(startAt >= 0)
    }

    /**
     * The pop is added light, so the room's answer to two identical pops is exactly twice
     * its answer to one - there is no filter state left in the way. (This is the old
     * "stays linear through the cast filter" test, which existed because the filter made
     * it untrue.) Low intensity keeps the base dim so nothing clips at 1.0.
     */
    @Test
    fun `overlapping pops add`() {
        val room = tallRoom()
        val hearth = room.ids.first()
        val one = arrayOfNulls<AmbienceEvent>(2).also { it[0] = pop(room) }
        val two = arrayOfNulls<AmbienceEvent>(2).also { it[0] = pop(room); it[1] = pop(room) }
        val none = arrayOfNulls<AmbienceEvent>(2)
        val a = bound(room, 0.2f)
        val b = bound(room, 0.2f)
        val c = bound(room, 0.2f)

        var worst = 0f
        var seen = 0f
        var t = 1.0
        while (t < 2.0) {
            val fa = HashMap<Int, Rgb>(); val fb = HashMap<Int, Rgb>(); val fc = HashMap<Int, Rgb>()
            a.renderLights(t, one, 1, fa)
            b.renderLights(t, two, 2, fb)
            c.renderLights(t, none, 0, fc)
            val base = fc.getValue(hearth).first
            val g1 = fa.getValue(hearth).first - base
            val g2 = fb.getValue(hearth).first - base
            worst = max(worst, kotlin.math.abs(g2 - 2f * g1))
            seen = max(seen, g1)
            t += 1.0 / 60.0
        }
        assertTrue(seen > 1e-3f, "no glint reached the hearth lamp at all, nothing was tested")
        assertTrue(worst < 1e-4f, "two pops were not twice one: off by $worst at worst")
    }

    @Test
    fun `an ear-only spit never lights a lamp`() {
        val room = tallRoom()
        val spit = AmbienceEvent(
            kind = AmbienceEvent.SPIT, startS = 1.0,
            env = Envelope(attackS = 0.004f, holdS = 0.006f, decayTauS = 0.09f),
            gain = 1f, origin = room.positions.getValue(room.ids.first()), azimuth = 0f,
            colour = Rgb(1f, 0.6f, 0.3f), timbre = 1f,
        )
        val a = bound(room, 0.5f)
        val b = bound(room, 0.5f)
        var t = 0.9
        while (t < 1.5) {
            for (id in room.ids) {
                assertTrue(kotlin.math.abs(glint(a, b, spit, t, id)) < 1e-6f, "a spit lit lamp $id")
            }
            t += 1.0 / 60.0
        }
    }
}
