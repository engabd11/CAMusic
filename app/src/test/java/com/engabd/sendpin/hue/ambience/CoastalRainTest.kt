package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.scripts.CoastalRainScript
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The low steady one. The Guide Book's "scene lighting for lamps further away"
 * pushed to its conclusion: a cool near-neutral base that never spikes, the only
 * events slow warm sweeps and rare distant strikes. These tests pin the three
 * properties the effect exists for: calm, occasional, and cold-leaning.
 */
class CoastalRainTest {

    private fun bound(intensity: Float = 0.7f): CoastalRainScript =
        CoastalRainScript().also { it.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity)) }

    @Test
    fun `the base field is cool, dim, and paints every lamp`() {
        val s = bound()
        val out = HashMap<Int, Rgb>()
        s.renderLights(5.0, arrayOfNulls(1), 0, out)
        assertEquals(6, out.size, "painted ${out.size} of 6 lamps")
        for ((_, c) in out) {
            val level = max(c.first, max(c.second, c.third))
            assertTrue(level <= 0.35f, "base too bright: $c")
            assertTrue(level >= 0.05f, "base too dark to read as rain: $c")
            // Cool lean: green at least red, blue at least green.
            assertTrue(c.second >= c.first, "warm cast in the base: $c")
        }
    }

    @Test
    fun `the base never swings hard between frames`() {
        val s = bound(1f)
        var prev: Map<Int, Rgb>? = null
        var worst = 0f
        var t = 0.0
        while (t < 60.0) {
            val f = HashMap<Int, Rgb>()
            s.renderLights(t, arrayOfNulls(1), 0, f)
            prev?.let { p ->
                for ((id, c) in f) {
                    val a = p[id] ?: Rgb(0f, 0f, 0f)
                    worst = maxOf(
                        worst,
                        max(c.first - a.first, max(a.first - c.first, max(c.second - a.second, max(a.second - c.second, max(c.third - a.third, a.third - c.third))))),
                    )
                }
            }
            prev = f
            t += 1.0 / 60.0
        }
        // Well under FieldSafety's 0.10 flash threshold: rain is restful, never a flicker.
        assertTrue(worst < 0.05f, "base swung $worst in one frame")
    }

    @Test
    fun `headlight sweeps are slow and occasional`() {
        val s = bound()
        val events = ArrayList<AmbienceEvent>()
        var t = 0.0
        while (t < 120.0) {
            s.schedule(t, t + 0.25) { events.add(it) }
            t += 0.25
        }
        val sweeps = events.filter { it.kind == AmbienceEvent.SWEEP }
        assertTrue(sweeps.size in 2..6, "expected a couple of sweeps in 2 min, got ${sweeps.size}")
        for (e in sweeps) {
            assertTrue(e.env.attackS >= 1.0f, "sweep attack ${e.env.attackS} is sudden")
            assertTrue(e.leadS == 0f && e.soundS == 0f, "headlights are silent")
            // Warm against the cool base - that contrast is the whole look.
            assertTrue(e.colour.first > e.colour.third, "sweep is not warm: ${e.colour}")
        }
    }

    @Test
    fun `a sweep travels across the room`() {
        val s = bound()
        val events = ArrayList<AmbienceEvent>()
        var t = 0.0
        while (t < 120.0) {
            s.schedule(t, t + 0.25) { events.add(it) }
            t += 0.25
        }
        val e = events.first { it.kind == AmbienceEvent.SWEEP }
        val room = RoomModel(linearRoom(6))
        fun brightestAt(tS: Double): Int {
            val f = HashMap<Int, Rgb>()
            s.renderLights(tS, arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }, 1, f)
            return f.maxByOrNull { max(it.value.first, max(it.value.second, it.value.third)) }!!.key
        }
        val early = brightestAt(e.startS + 0.2)
        val late = brightestAt(e.startS + e.env.attackS + e.env.holdS * 0.8)
        assertTrue(early != late, "the brightest lamp did not move: stuck on $early")
    }

    @Test
    fun `distant strikes are rare and dim`() {
        val s = bound()
        val events = ArrayList<AmbienceEvent>()
        var t = 0.0
        while (t < 600.0) {
            s.schedule(t, t + 0.25) { events.add(it) }
            t += 0.25
        }
        val strikes = events.filter { it.kind == AmbienceEvent.STRIKE }
        assertTrue(strikes.size <= 3, "too many strikes for distant weather: ${strikes.size}")
        for (e in strikes) {
            assertTrue(e.gain <= 0.35f, "strike too bright for the far-off premise: ${e.gain}")
            assertTrue(e.timbre >= 2f, "strike too near: ${e.timbre} km")
            // Coherence contract: the roll arrives after the propagation delay.
            assertTrue(e.soundS > e.timbre / 0.343f, "strike claims no thunder after its flash")
        }
    }

    @Test
    fun `the same seed produces the same show`() {
        val a = ArrayList<AmbienceEvent>()
        val b = ArrayList<AmbienceEvent>()
        for (sink in listOf(a, b)) {
            val s = bound()
            var t = 0.0
            while (t < 40.0) {
                s.schedule(t, t + 0.25) { sink.add(it) }
                t += 0.25
            }
        }
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.startS, y.startS, 1e-9)
            assertEquals(x.gain, y.gain, 1e-6f)
        }
    }
}
