package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.RoomTopology
import com.engabd.sendpin.hue.ambience.scripts.ThunderstormScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SR = 48_000

/** Run a script's whole show and hand back everything it scheduled. */
private fun collect(script: AmbienceScript, toS: Double, stepS: Double = 0.25): List<AmbienceEvent> {
    val out = ArrayList<AmbienceEvent>()
    var t = 0.0
    while (t < toS) {
        script.schedule(t, t + stepS) { out.add(it) }
        t += stepS
    }
    return out
}

private fun scratch(events: List<AmbienceEvent>, tS: Double): Pair<Array<AmbienceEvent?>, Int> {
    val alive = events.filter { it.aliveAt(tS) }
    val arr = arrayOfNulls<AmbienceEvent>(maxOf(alive.size, 1))
    alive.forEachIndexed { i, e -> arr[i] = e }
    return arr to alive.size
}

/**
 * Every script, held to the properties the whole design depends on.
 *
 * The scheduler tests matter most. `AmbienceSession` calls `schedule` once per audio
 * block with contiguous windows, so a script that drops or duplicates an event at a
 * block boundary would produce a show that changes depending on the buffer size — which
 * is the kind of bug that only appears on one device and cannot be reproduced.
 */
class AmbienceSchedulerTest {

    private fun bound(script: AmbienceScript): AmbienceScript = script.also {
        it.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity = 0.7f))
    }

    private fun everyScript(): List<AmbienceScript> =
        AmbienceEffect.entries.map { bound(scriptFor(it)) }

    @Test
    fun `the same seed produces the same show`() {
        for (effect in AmbienceEffect.entries) {
            val a = collect(bound(scriptFor(effect)), 40.0)
            val b = collect(bound(scriptFor(effect)), 40.0)
            assertEquals(a.size, b.size, "${effect.wire}: different event counts")
            a.zip(b).forEach { (x, y) ->
                assertEquals(x.startS, y.startS, 1e-9, "${effect.wire}: start moved")
                assertEquals(x.gain, y.gain, 1e-6f, "${effect.wire}: gain moved")
            }
        }
    }

    @Test
    fun `block size does not change the show`() {
        // The heart of it: schedule(0,1)+schedule(1,2) must equal schedule(0,2). If a
        // script drops an event on a boundary, this is where it shows up.
        for (effect in AmbienceEffect.entries) {
            val fine = collect(bound(scriptFor(effect)), 30.0, stepS = 0.021)   // ~1024 frames
            val coarse = collect(bound(scriptFor(effect)), 30.0, stepS = 1.0)
            assertEquals(coarse.size, fine.size, "${effect.wire}: event count depends on block size")
            coarse.zip(fine).forEach { (c, f) ->
                assertEquals(c.startS, f.startS, 1e-6, "${effect.wire}: event moved")
            }
        }
    }

    @Test
    fun `intensity drives the event rate`() {
        // Aurora is deliberately eventless, so it is the one exception.
        for (effect in AmbienceEffect.entries - AmbienceEffect.AURORA) {
            val calm = scriptFor(effect).also {
                it.bind(RoomModel(linearRoom()), AmbienceParams(intensity = 0.05f))
            }
            val wild = scriptFor(effect).also {
                it.bind(RoomModel(linearRoom()), AmbienceParams(intensity = 1f))
            }
            val calmN = collect(calm, 120.0).size
            val wildN = collect(wild, 120.0).size
            assertTrue(wildN > calmN, "${effect.wire}: $wildN not more than $calmN")
        }
    }

    @Test
    fun `an aurora has no events at all`() {
        assertTrue(collect(bound(scriptFor(AmbienceEffect.AURORA)), 120.0).isEmpty())
    }

    @Test
    fun `every event has a finite lifetime and a sane gain`() {
        for (s in everyScript()) {
            for (e in collect(s, 60.0)) {
                assertTrue(e.env.lifetimeS.isFinite() && e.env.lifetimeS > 0f)
                assertTrue(e.gain in 0f..1.001f, "${s.effect.wire}: gain ${e.gain}")
                assertTrue(e.startS.isFinite())
            }
        }
    }
}

/**
 * The test the whole design exists to make possible.
 *
 * A thunder strike is one event. The lights read it at `startS`; the audio reads the
 * same object at `startS + timbre / 0.343`. Nothing synchronises them — so what this
 * checks is that the gap between the flash and the crack really is the propagation
 * delay implied by the event's own distance, and not something maintained by hand that
 * could drift.
 */
class AmbienceCoherenceTest {

    @Test
    fun `thunder is heard after it is seen, by exactly the distance it says`() {
        val room = RoomModel(linearRoom(6))
        val script = ThunderstormScript()
        script.bind(room, AmbienceParams(intensity = 1f))
        val events = collect(script, 60.0).filter { it.kind == AmbienceEvent.STRIKE }
        assertTrue(events.isNotEmpty(), "no strikes scheduled")

        for (e in events.take(20)) {
            val expectedDelayS = e.timbre / 0.343f          // km at 343 m/s
            // Onset against onset. Peak-against-onset would be measuring two different
            // things: the field peaks a little after the flash begins, because the lamps
            // light in distance order across the room.
            val lightOnset = firstLightTime(script, room, e)
            val audioOnset = firstAudioTime(AmbienceEffect.THUNDERSTORM, e)
            val measured = audioOnset - lightOnset
            // One render frame (17 ms) is the resolution of the light probe.
            assertTrue(
                kotlin.math.abs(measured - expectedDelayS) < 0.05f,
                "distance ${e.timbre}km implies ${expectedDelayS}s, measured ${measured}s",
            )
        }
    }

    /**
     * The first moment the room shows this strike at all, relative to its `startS`.
     *
     * Measured against the rain bed rather than against zero, for the same reason the
     * audio probe subtracts: the room is never dark in a storm, so "brighter than
     * nothing" would report the bed. Sampled at the render rate, which bounds the
     * resolution of the whole test.
     */
    private fun firstLightTime(script: AmbienceScript, room: RoomModel, e: AmbienceEvent): Float {
        val withIt = HashMap<Int, Rgb>()
        val without = HashMap<Int, Rgb>()
        val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
        val none = arrayOfNulls<AmbienceEvent>(1)
        var t = e.startS - 0.1
        while (t < e.startS + e.env.lifetimeS) {
            withIt.clear(); without.clear()
            script.renderLights(t, one, 1, withIt)
            script.renderLights(t, none, 0, without)
            val delta = room.ids.sumOf { id ->
                val a = withIt[id] ?: Rgb(0f, 0f, 0f)
                val b = without[id] ?: Rgb(0f, 0f, 0f)
                ((a.first - b.first) + (a.second - b.second) + (a.third - b.third)).toDouble()
            }
            if (delta > 0.01) return (t - e.startS).toFloat()
            t += 1.0 / 60.0
        }
        return 0f
    }

    /**
     * First moment this strike puts energy into the audio, relative to its `startS`.
     *
     * Measured as the **difference** between rendering with the event and without it.
     * The rain bed is always there and is far louder than the tail of a distant strike,
     * so an absolute threshold would just find the rain. Two scripts with the same seed
     * produce byte-identical beds, which is what makes the subtraction clean — and is
     * itself a property worth relying on.
     */
    private fun firstAudioTime(effect: AmbienceEffect, e: AmbienceEvent): Float {
        val frames = 1024
        val withIt = FloatArray(frames * 2)
        val without = FloatArray(frames * 2)
        val room = RoomModel(linearRoom(6))
        val a = scriptFor(effect).also { it.bind(room, AmbienceParams(intensity = 1f)) }
        val b = scriptFor(effect).also { it.bind(room, AmbienceParams(intensity = 1f)) }
        val one = arrayOfNulls<AmbienceEvent>(1).also { it[0] = e }
        val none = arrayOfNulls<AmbienceEvent>(1)

        // Start a little before the strike so a false positive would be visible.
        var blockStart = e.startS - 0.5
        val limit = e.startS + e.timbre / 0.343 + 3.0
        while (blockStart < limit) {
            java.util.Arrays.fill(withIt, 0f)
            java.util.Arrays.fill(without, 0f)
            a.renderAudio(withIt, frames, blockStart, SR, one, 1)
            b.renderAudio(without, frames, blockStart, SR, none, 0)
            for (i in 0 until frames) {
                val d = kotlin.math.abs(withIt[i * 2] - without[i * 2]) +
                    kotlin.math.abs(withIt[i * 2 + 1] - without[i * 2 + 1])
                if (d > 0.01f) return (blockStart + i.toDouble() / SR - e.startS).toFloat()
            }
            blockStart += frames.toDouble() / SR
        }
        return Float.MAX_VALUE
    }
}

/** Audio has to be finite, bounded and continuous across block boundaries. */
class AmbienceAudioTest {

    private fun bound(effect: AmbienceEffect, intensity: Float = 1f): AmbienceScript =
        scriptFor(effect).also { it.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity)) }

    @Test
    fun `nothing ever produces NaN or infinity`() {
        for (effect in AmbienceEffect.entries) {
            val s = bound(effect)
            val events = collect(s, 30.0)
            val frames = 1024
            val block = FloatArray(frames * 2)
            var t = 0.0
            while (t < 30.0) {
                java.util.Arrays.fill(block, 0f)
                val (arr, n) = scratch(events, t)
                s.renderAudio(block, frames, t, SR, arr, n)
                for (v in block) {
                    assertTrue(v.isFinite(), "${effect.wire}: non-finite sample at t=$t")
                }
                t += frames.toDouble() / SR
            }
        }
    }

    @Test
    fun `stays inside the rails at full intensity`() {
        // Clipping is the one instantly-audible bug, and an hour-long bed is exactly
        // where a slow accumulation would surface.
        for (effect in AmbienceEffect.entries) {
            val s = bound(effect, intensity = 1f)
            val events = collect(s, 60.0)
            val frames = 1024
            val block = FloatArray(frames * 2)
            var peak = 0f
            var t = 0.0
            while (t < 60.0) {
                java.util.Arrays.fill(block, 0f)
                val (arr, n) = scratch(events, t)
                s.renderAudio(block, frames, t, SR, arr, n)
                for (v in block) peak = maxOf(peak, kotlin.math.abs(v))
                t += frames.toDouble() / SR
            }
            // The session soft-clips on top of this, so a little headroom over 1.0 here
            // is survivable — but an order of magnitude means a runaway.
            assertTrue(peak < 2.0f, "${effect.wire}: peaked at $peak")
            assertTrue(peak > 0.0005f, "${effect.wire}: silent (peak $peak)")
        }
    }

    @Test
    fun `two half blocks equal one whole block`() {
        // The DSP twin of the scheduler's contiguity test, and what catches a filter
        // whose state was reset per call rather than carried.
        for (effect in AmbienceEffect.entries) {
            val whole = bound(effect)
            val split = bound(effect)
            val events = collect(whole, 12.0)

            val big = FloatArray(1024 * 2)
            val a = FloatArray(512 * 2)
            val b = FloatArray(512 * 2)

            var t = 0.0
            var checked = 0
            while (t < 6.0) {
                java.util.Arrays.fill(big, 0f); java.util.Arrays.fill(a, 0f); java.util.Arrays.fill(b, 0f)
                val (arr, n) = scratch(events, t)
                whole.renderAudio(big, 1024, t, SR, arr, n)
                split.renderAudio(a, 512, t, SR, arr, n)
                split.renderAudio(b, 512, t + 512.0 / SR, SR, arr, n)
                for (i in 0 until 512 * 2) {
                    assertTrue(
                        kotlin.math.abs(big[i] - a[i]) < 1e-3f,
                        "${effect.wire}: first half diverged at $i (t=$t)",
                    )
                }
                checked++
                t += 1024.0 / SR
            }
            assertTrue(checked > 10)
        }
    }
}

/** Each script's spatial promise, checked against the room it was given. */
class AmbienceSpatialTest {

    private fun field(script: AmbienceScript, tS: Double, events: List<AmbienceEvent>): Map<Int, Rgb> {
        val out = HashMap<Int, Rgb>()
        val (arr, n) = scratch(events, tS)
        script.renderLights(tS, arr, n, out)
        return out
    }

    private fun brightness(c: Rgb) = c.first + c.second + c.third

    @Test
    fun `thunder lights the nearest lamp first`() {
        val room = RoomModel(linearRoom(6))
        val s = ThunderstormScript()
        s.bind(room, AmbienceParams(intensity = 1f))
        val e = collect(s, 60.0).first { it.kind == AmbienceEvent.STRIKE }

        val near = room.ids.minByOrNull { room.distanceTo(it, e.origin) }!!
        val far = room.ids.maxByOrNull { room.distanceTo(it, e.origin) }!!

        fun peakTime(id: Int): Double {
            var best = -1f; var bestT = 0.0
            var t = e.startS
            while (t < e.startS + e.env.lifetimeS) {
                val v = brightness(field(s, t, listOf(e))[id] ?: Rgb(0f, 0f, 0f))
                if (v > best) { best = v; bestT = t }
                t += 1.0 / 240.0
            }
            return bestT
        }
        assertTrue(peakTime(near) <= peakTime(far), "the far lamp did not lag the near one")
    }

    @Test
    fun `the light train advances along the room and does not chase in a cluster`() {
        // LINEAR: the brightest lamp should move from one end to the other.
        val line = RoomModel(linearRoom(6))
        val train = scriptFor(AmbienceEffect.LIGHT_TRAIN)
        train.bind(line, AmbienceParams(intensity = 0.5f))
        val seen = LinkedHashSet<Int>()
        var t = 0.0
        while (t < 4.0) {
            val f = field(train, t, emptyList())
            f.maxByOrNull { brightness(it.value) }?.let { seen.add(it.key) }
            t += 0.05
        }
        assertTrue(seen.size >= 3, "the beam did not travel: visited $seen")

        // CLUSTER: three lamps on a table have no length to run along, so the whole
        // cluster should pulse rather than the beam stuttering between them.
        val huddle = RoomModel(clusterRoom())
        assertEquals(RoomTopology.CLUSTER, huddle.topology)
        val pulse = scriptFor(AmbienceEffect.LIGHT_TRAIN)
        pulse.bind(huddle, AmbienceParams(intensity = 0.5f))
        var maxSpread = 0f
        var u = 0.0
        while (u < 4.0) {
            val f = field(pulse, u, emptyList())
            val vals = f.values.map { brightness(it) }
            maxSpread = maxOf(maxSpread, (vals.max() - vals.min()))
            u += 0.05
        }
        assertTrue(maxSpread < 0.25f, "the cluster chased a beam instead of pulsing ($maxSpread)")
    }

    @Test
    fun `an aurora barely changes brightness`() {
        // The quiet one. If this starts failing, the effect has grown a moment.
        val s = scriptFor(AmbienceEffect.AURORA)
        s.bind(RoomModel(ringRoom(8)), AmbienceParams(intensity = 1f))
        var lo = Float.MAX_VALUE
        var hi = 0f
        var t = 0.0
        while (t < 60.0) {
            val total = field(s, t, emptyList()).values.sumOf { brightness(it).toDouble() }.toFloat()
            lo = minOf(lo, total); hi = maxOf(hi, total)
            t += 0.1
        }
        assertTrue(hi <= lo * 1.6f, "aurora swung from $lo to $hi")
    }

    @Test
    fun `a fireplace never flickers hard enough to trip the flash limiter`() {
        // A fire that engaged the limiter would compress the whole room and go flat.
        val room = RoomModel(linearRoom(6))
        val s = scriptFor(AmbienceEffect.FIREPLACE)
        s.bind(room, AmbienceParams(intensity = 1f))
        val events = collect(s, 20.0)
        var prev: Map<Int, Rgb>? = null
        var worst = 0f
        var t = 0.0
        while (t < 20.0) {
            val f = field(s, t, events)
            prev?.let { p ->
                for (id in room.ids) {
                    val a = brightness(p[id] ?: Rgb(0f, 0f, 0f)) / 3f
                    val b = brightness(f[id] ?: Rgb(0f, 0f, 0f)) / 3f
                    worst = maxOf(worst, kotlin.math.abs(a - b))
                }
            }
            prev = f
            t += 1.0 / 60.0
        }
        // The pops are real events and are allowed to be sharp; the bed must not be.
        assertTrue(worst < 0.5f, "fireplace swung $worst in one frame")
    }

    @Test
    fun `every script paints every lamp`() {
        // A lamp left out of the map keeps whatever it was showing, which across a mode
        // change is the previous show's colour stuck in the corner of the room.
        val room = RoomModel(ringRoom(8))
        for (effect in AmbienceEffect.entries) {
            val s = scriptFor(effect)
            s.bind(room, AmbienceParams(intensity = 0.6f))
            val events = collect(s, 10.0)
            val f = field(s, 5.0, events)
            assertEquals(room.count, f.size, "${effect.wire} painted ${f.size} of ${room.count}")
            for (c in f.values) {
                assertTrue(c.first in 0f..1.001f && c.second in 0f..1.001f && c.third in 0f..1.001f,
                    "${effect.wire} produced an out-of-range colour: $c")
            }
        }
    }
}
