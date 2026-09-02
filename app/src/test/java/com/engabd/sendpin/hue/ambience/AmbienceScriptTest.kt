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
        // Only the strikes that carry thunder. A near strike re-strikes its channel two
        // or three times milliseconds apart; those extra flickers are inside the first
        // one's crack and deliberately carry no roll of their own.
        val events = collect(script, 60.0)
            .filter { it.kind == AmbienceEvent.STRIKE && it.soundS > 0f }
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

/**
 * The scripts driven the way `AmbienceSession` drives them, through a real
 * [AmbienceTimeline].
 *
 * Everything else in this file hands `renderAudio` an event directly, which is a fine
 * way to test a script and a **terrible** way to find out whether the app makes any
 * sound. It cannot: it bypasses the one component that decides which events the audio
 * block is allowed to see. That gap hid the effect's worst bug for its whole life —
 * the coherence test above passed, proving thunder followed its flash by exactly the
 * right delay, while in the running app the timeline had retired the strike on its
 * *light* envelope seconds before the thunder was due, and two storms in three were
 * silent from end to end.
 *
 * So these tests own the seam. They schedule into a real timeline and pull each block's
 * live set out of it exactly as the generator loop does.
 */
class AmbienceTimelinePathTest {

    /** One block of the generator loop, verbatim: schedule, then window, then render. */
    private fun blockPeaks(
        effect: AmbienceEffect,
        seconds: Double,
        intensity: Float = 1f,
        frames: Int = 1024,
    ): FloatArray {
        val script = scriptFor(effect)
        script.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity))
        val timeline = AmbienceTimeline()
        val scratch = arrayOfNulls<AmbienceEvent>(160)
        val block = FloatArray(frames * 2)
        val blocks = (seconds * SR).toInt() / frames
        val peaks = FloatArray(blocks)
        var written = 0L
        var generatedToS = 0.0
        for (b in 0 until blocks) {
            val startS = written.toDouble() / SR
            val endS = startS + frames.toDouble() / SR
            val horizon = endS + script.lookaheadS
            if (horizon > generatedToS) {
                script.schedule(generatedToS, horizon) { timeline.append(it) }
                generatedToS = horizon
            }
            java.util.Arrays.fill(block, 0f)
            val n = timeline.windowOver(startS, endS, scratch)
            script.renderAudio(block, frames, startS, SR, scratch, n)
            var p = 0f
            for (v in block) p = maxOf(p, kotlin.math.abs(v))
            peaks[b] = p
            written += frames
        }
        return peaks
    }

    /** The bed alone — the floor a transient has to clear to be a transient. */
    private fun bedPeak(effect: AmbienceEffect, seconds: Double = 20.0): Float {
        val script = scriptFor(effect)
        script.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity = 1f))
        val block = FloatArray(1024 * 2)
        var peak = 0f
        var t = 0.0
        while (t < seconds) {
            java.util.Arrays.fill(block, 0f)
            script.renderAudio(block, 1024, t, SR, arrayOfNulls(1), 0)
            for (v in block) peak = maxOf(peak, kotlin.math.abs(v))
            t += 1024.0 / SR
        }
        return peak
    }

    @Test
    fun `an event-driven effect is more than its bed once it runs for real`() {
        // Three minutes of storm, and three of fireworks. If the transients are being
        // withheld from the audio block, what is left is the rain — and the peak never
        // rises meaningfully above the bed.
        // The effects that are nothing but transients. When one of these plays its
        // recording the lights react to that instead; this is the fallback path, for a
        // clip that failed to open or an effect with nothing bundled.
        val eventDriven = listOf(
            AmbienceEffect.THUNDERSTORM, AmbienceEffect.THUNDERSTORM_2,
            AmbienceEffect.FIREWORKS, AmbienceEffect.FIREWORKS_2,
        )
        for (effect in eventDriven) {
            val bed = bedPeak(effect)
            val peaks = blockPeaks(effect, 180.0)
            val loud = peaks.count { it > bed * 2.5f }
            assertTrue(
                loud > peaks.size / 20,
                "${effect.wire}: only $loud of ${peaks.size} blocks rose above its own bed — " +
                    "the transients are not reaching the output",
            )
        }
    }

    @Test
    fun `no effect leans on the soft clipper at all`() {
        // Under unity, not merely under softClip's hard cap at 1.5. An earlier version
        // of the synthesised storm ran to 1.36 and left the clipper holding it, which is
        // audible as exactly the grit it was reported as: the loudest moments of a storm
        // are the ones that matter, and rounding their tops off is not a rescue.
        // Nothing here should be asking the clipper for help.
        for (effect in AmbienceEffect.entries) {
            val peak = blockPeaks(effect, 180.0).max()
            assertTrue(peak < 1.0f, "${effect.wire}: peaked at $peak through the timeline")
        }
    }

    @Test
    fun `an event that leads with sound is rendered during its lead-in`() {
        // The second half of the same bug, and the one the event's own span cannot fix.
        // A shell was only scheduled when the block containing its `startS` came up, so
        // however long its declared lead, the blocks that should have carried the
        // whistle had already been rendered and written. The climb was never once
        // rendered in the app's life; this is the test that says so.
        for (effect in AmbienceEffect.entries) {
            val script = scriptFor(effect)
            script.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity = 0.6f))
            val leads = collect(script, 30.0).any { it.leadS > 0f }
            if (!leads) continue

            val timeline = AmbienceTimeline()
            val scratch = arrayOfNulls<AmbienceEvent>(160)
            val frames = 1024
            var written = 0L
            var generatedToS = 0.0
            var duringClimb = 0
            repeat((60.0 * SR).toInt() / frames) {
                val startS = written.toDouble() / SR
                val endS = startS + frames.toDouble() / SR
                val horizon = endS + script.lookaheadS
                if (horizon > generatedToS) {
                    script.schedule(generatedToS, horizon) { timeline.append(it) }
                    generatedToS = horizon
                }
                val n = timeline.windowOver(startS, endS, scratch)
                // Blocks that end before the event even starts: pure lead-in.
                for (i in 0 until n) if (endS <= scratch[i]!!.startS) duringClimb++
                written += frames
            }
            assertTrue(
                duringClimb > 0,
                "${effect.wire}: never rendered a single block of an event's lead-in",
            )
        }
    }

    @Test
    fun `every scheduled event is still in the timeline when its own sound is due`() {
        for (effect in AmbienceEffect.entries) {
            val script = scriptFor(effect)
            script.bind(RoomModel(linearRoom(6)), AmbienceParams(intensity = 1f))
            for (e in collect(script, 120.0)) {
                if (e.soundS > 0f) {
                    assertTrue(
                        e.aliveAt(e.startS + e.soundS * 0.99),
                        "${effect.wire}: retired before the end of its own sound",
                    )
                }
                if (e.leadS > 0f) {
                    assertTrue(
                        e.aliveAt(e.startS - e.leadS * 0.99),
                        "${effect.wire}: not published until after its sound had begun",
                    )
                }
            }
        }
    }
}

/**
 * The other half of coherence: what the *room* does while the sound is happening.
 *
 * Getting the delay right is not the same as making one effect out of two media. A
 * flash, six seconds of a room doing nothing, and then thunder is physically correct
 * and still reads as a light show with an unrelated recording under it — which is
 * exactly the complaint these effects drew. So each script owes the ear something to
 * look at: the room swells while the thunder rolls, and an ember climbs while the shell
 * whistles.
 */
class AmbienceAnswerTest {

    private fun totalBrightness(
        script: AmbienceScript, tS: Double, events: List<AmbienceEvent>,
    ): Float {
        val out = HashMap<Int, Rgb>()
        val arr = arrayOfNulls<AmbienceEvent>(maxOf(events.size, 1))
        events.forEachIndexed { i, e -> arr[i] = e }
        script.renderLights(tS, arr, events.size, out)
        return out.values.fold(0f) { a, c -> a + c.first + c.second + c.third }
    }

    @Test
    fun `the room answers the thunder while it rolls, and not before it arrives`() {
        val room = RoomModel(ringRoom(8))
        val script = ThunderstormScript()
        script.bind(room, AmbienceParams(intensity = 1f))
        val e = collect(script, 200.0).first {
            it.kind == AmbienceEvent.STRIKE && it.soundS > 0f && it.timbre > 1.2f
        }
        val propS = e.timbre / 0.343f

        // Mid-roll: the room is lifted by the same envelope the speaker is playing.
        val rolling = e.startS + propS + 1.5
        val lift = totalBrightness(script, rolling, listOf(e)) -
            totalBrightness(script, rolling, emptyList())
        assertTrue(lift > 0.02f, "the room ignores the thunder it is playing ($lift)")

        // Well before the sound could have crossed the distance: nothing but the flash,
        // which is long over. Anticipating the thunder would be worse than ignoring it.
        val quiet = e.startS + propS * 0.4
        val early = totalBrightness(script, quiet, listOf(e)) -
            totalBrightness(script, quiet, emptyList())
        assertTrue(early < lift * 0.35f, "the room glowed before the thunder arrived ($early)")
    }

    @Test
    fun `an ember climbs while the shell whistles`() {
        val room = RoomModel(ringRoom(8))
        val script = scriptFor(AmbienceEffect.FIREWORKS)
        script.bind(room, AmbienceParams(intensity = 0.6f))
        val e = collect(script, 60.0).first { it.kind == AmbienceEvent.BURST }
        assertTrue(e.leadS > 0.5f, "the shell claims no climb")

        val dark = totalBrightness(script, e.startS - 0.5, emptyList())
        val early = totalBrightness(script, e.startS - 0.80, listOf(e)) - dark
        val late = totalBrightness(script, e.startS - 0.10, listOf(e)) - dark
        assertTrue(late > early, "the ember does not climb ($early then $late)")
        assertTrue(late > 0.01f, "the ember is invisible ($late)")
        // ...and it is an ember, not a second firework.
        val burst = totalBrightness(script, e.startS + 0.1, listOf(e)) - dark
        assertTrue(late < burst, "the climb outshone the burst")
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
