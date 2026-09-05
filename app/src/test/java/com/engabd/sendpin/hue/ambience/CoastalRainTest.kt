package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.FLASH_DELTA
import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.scripts.CoastalRainScript
import kotlin.math.abs
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

    /** Everything [s] schedules over its first [horizonS] seconds, in lookahead steps. */
    private fun scheduled(s: CoastalRainScript, horizonS: Double): List<AmbienceEvent> {
        val out = ArrayList<AmbienceEvent>()
        var t = 0.0
        while (t < horizonS) {
            s.schedule(t, t + 0.25) { out.add(it) }
            t += 0.25
        }
        return out
    }

    private fun firstSweep(): AmbienceEvent =
        scheduled(bound(), 120.0).first { it.kind == AmbienceEvent.SWEEP }

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
    fun `a sweep travels across the room, one way, without doubling back`() {
        // The sweep's own contribution, isolated: two scripts with the same seed, one
        // given the event and one not, so the breathing base subtracts out and what is
        // left is the headlights alone. (The same trick FireplaceCastTest uses.)
        val e = firstSweep()
        val lit = bound()
        val dark = bound()
        val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
        val none = arrayOfNulls<AmbienceEvent>(1)

        val brightest = ArrayList<Int>()
        val peaks = ArrayList<Float>()
        var t = e.startS
        val travelEndS = e.startS + e.env.attackS + e.env.holdS + 0.5
        while (t <= travelEndS) {
            val a = HashMap<Int, Rgb>()
            val b = HashMap<Int, Rgb>()
            lit.renderLights(t, one, 1, a)
            dark.renderLights(t, none, 0, b)
            var bestId = -1
            var best = 0f
            for ((id, c) in a) {
                val v = b.getValue(id)
                val glint = max(c.first - v.first, max(c.second - v.second, c.third - v.third))
                if (glint > best) { best = glint; bestId = id }
            }
            brightest.add(bestId)
            peaks.add(best)
            t += 1.0 / 60.0
        }

        // Only the samples where the head is actually over the room: at both ends of
        // the travel it is out of range of every lamp and the argmax means nothing.
        val ceiling = peaks.max()
        val walk = brightest.filterIndexed { i, id -> id >= 0 && peaks[i] > 0.2f * ceiling }
        assertTrue(walk.size >= 10, "the sweep was barely visible: ${walk.size} lit frames")
        assertTrue(walk.distinct().size >= 4, "the head sat still: visited ${walk.distinct()}")

        // One direction, start to finish. The wrap this replaced showed up here as the
        // head reaching one wall and reappearing at the other, which is neither.
        val rising = walk.zipWithNext().all { (a, b) -> b >= a }
        val falling = walk.zipWithNext().all { (a, b) -> b <= a }
        assertTrue(rising || falling, "the head doubled back: $walk")
    }

    /**
     * The bug this pins: the head position used to be `(azimuth + travel) % 1f`, and
     * the modulo moved the bright spot to the opposite wall in a single frame.
     * `positions` is min-max normalised, so a lamp sits at x=0 and another at x=1 to
     * catch it, and the step measured 0.14-0.23 against a FLASH_DELTA of 0.10.
     *
     * Neither guard downstream would have caught it — FieldSafety keys off *whole
     * field* brightness, and one lamp of six moving that far hardly shifts the mean,
     * while EffectRateLimiter only gates repeated reversals inside its interval and
     * passes an isolated step straight through. So it has to be right here.
     */
    @Test
    fun `a sweep never jumps across the room`() {
        val s = bound(1f)   // full intensity: the brightest sweeps this effect emits
        var worst = 0f
        var worstAt = ""
        for (e in scheduled(s, 600.0).filter { it.kind == AmbienceEvent.SWEEP }) {
            val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
            var prev: Map<Int, Rgb>? = null
            var t = e.startS
            while (t < e.startS + e.env.lifetimeS) {
                val f = HashMap<Int, Rgb>()
                s.renderLights(t, one, 1, f)
                prev?.let { p ->
                    for ((id, c) in f) {
                        val a = p.getValue(id)
                        val d = max(
                            abs(c.first - a.first),
                            max(abs(c.second - a.second), abs(c.third - a.third)),
                        )
                        if (d > worst) { worst = d; worstAt = "lamp $id at age ${t - e.startS}" }
                    }
                }
                prev = f
                t += 1.0 / 60.0
            }
        }
        assertTrue(worst < FLASH_DELTA, "a sweep moved $worst in one frame ($worstAt)")
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
