package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Rgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [AmbienceComfortTest] cannot see: the four newer scenes over their whole length
 * (dawn is 20 minutes and golden hour up to 45, longer than that test's ten), and the
 * particular behaviours each one promises.
 */
class AmbienceNewScenesTest {

    private fun line(n: Int): List<EntertainmentChannel> = (0 until n).map { i ->
        EntertainmentChannel(i, ChannelPosition(if (n == 1) 0f else -1f + 2f * i / (n - 1f), 0f, 0f))
    }

    private class Run(
        val room: RoomModel,
        val events: List<AmbienceEvent>,
        /** Perceived colour of every lamp, once a second. */
        val lamps: List<Map<Int, Rgb>>,
        val maxLampPerSecond: Float,
        val maxRoomPerSecond: Float,
        val engagedFraction: Float,
    ) {
        fun mean(sec: Int) = lamps[sec].values.map { Perceptual.levelOf(it) }.average().toFloat()
    }

    private fun run(
        effect: AmbienceEffect,
        channels: List<EntertainmentChannel>,
        seconds: Int,
        intensity: Float = 0.5f,
    ): Run {
        val fps = 60
        val dt = 1f / fps
        val room = RoomModel(channels)
        val script = scriptFor(effect)
        script.bind(room, AmbienceParams(intensity = intensity))
        val shaper = AmbienceFrameShaper(script)
        val timeline = AmbienceTimeline()
        val scratch = arrayOfNulls<AmbienceEvent>(160)
        val frame = HashMap<Int, Rgb>()
        val events = ArrayList<AmbienceEvent>()
        val ids = room.ids
        val perSecond = ArrayList<Map<Int, Rgb>>()
        val levels = Array(seconds + 1) { FloatArray(ids.size) }
        var generatedTo = 0.0
        var engaged = 0
        var frames = 0
        for (f in 0 until seconds * fps) {
            val t = f * dt.toDouble()
            val horizon = t + 0.5
            if (horizon > generatedTo) {
                script.schedule(generatedTo, horizon) { timeline.append(it); events.add(it) }
                generatedTo = horizon
            }
            val live = timeline.windowAt(t, scratch)
            frame.clear()
            script.renderLights(t, scratch, live, frame)
            val raw = ids.map { Perceptual.levelOf(frame[it] ?: Rgb(0f, 0f, 0f)) }
            shaper.shape(frame, dt, 1f, 1f)
            if (f >= 10 * fps) {
                frames++
                if (ids.indices.any { abs(raw[it] - Perceptual.levelOf(shaper.perceived.getValue(ids[it]))) > 0.02f }) engaged++
            }
            if (f % fps == 0) {
                perSecond.add(HashMap(shaper.perceived))
                for ((i, id) in ids.withIndex()) levels[f / fps][i] = Perceptual.levelOf(shaper.perceived.getValue(id))
            }
        }
        var lamp = 0f
        var roomMove = 0f
        for (s in 11 until perSecond.size) {
            var m0 = 0f
            var m1 = 0f
            for (i in ids.indices) {
                lamp = maxOf(lamp, abs(levels[s][i] - levels[s - 1][i]))
                m0 += levels[s - 1][i]
                m1 += levels[s][i]
            }
            roomMove = maxOf(roomMove, abs(m1 - m0) / ids.size)
        }
        return Run(room, events, perSecond, lamp, roomMove, engaged.toFloat() / frames.coerceAtLeast(1))
    }

    private fun avgColour(r: Run, sec: Int): Triple<Float, Float, Float> {
        val cs = r.lamps[sec].values
        return Triple(cs.map { it.first }.average().toFloat(), cs.map { it.second }.average().toFloat(), cs.map { it.third }.average().toFloat())
    }

    @Test
    fun `candles are the lamps nearest the middle, warm, and the rest is dim reflection`() {
        for ((n, expected) in listOf(1 to 1, 3 to 2, 6 to 3, 10 to 3)) {
            val room = RoomModel(line(n))
            val script = scriptFor(AmbienceEffect.CANDLELIGHT)
            script.bind(room, AmbienceParams(0.5f))
            val frame = HashMap<Int, Rgb>()
            script.renderLights(100.0, arrayOfNulls(1), 0, frame)
            val levels = frame.mapValues { Perceptual.levelOf(it.value) }
            val bright = levels.filter { it.value > 0.25f }.keys
            assertEquals(expected, bright.size, "$n lamps")
            for (id in bright) {
                val c = frame.getValue(id)
                // 1700 to 1850 K: red leads, blue is nearly absent.
                assertTrue(c.first > c.second && c.third < c.second * 0.45f, "candle colour $c")
            }
            if (n > 1) {
                val dim = levels.filter { it.value <= 0.25f }.values
                assertTrue(dim.all { it in 0.04f..0.2f }, "reflected light $dim")
            }
            // Candles are the ones closest to the centre.
            val centre = room.centre()
            val nearest = room.ids.sortedBy { room.distanceTo(it, centre) + 0.25f * room.heightOf(it) }.take(expected).toSet()
            assertEquals(nearest, bright)
        }
    }

    @Test
    fun `a draught comes every one to four minutes and touches one candle`() {
        val r = run(AmbienceEffect.CANDLELIGHT, line(6), 1800)
        val draughts = r.events.filter { it.kind == AmbienceEvent.SWELL }
        assertTrue(draughts.size in 7..30, "${draughts.size} draughts in half an hour")
        val gaps = draughts.zipWithNext { a, b -> b.startS - a.startS }
        assertTrue(gaps.all { it in 59.9..240.1 }, "gaps $gaps")
        assertTrue(r.engagedFraction < 0.02f)
    }

    @Test
    fun `forest dawn runs blue to pink to gold in twenty minutes and then holds`() {
        val r = run(AmbienceEffect.FOREST_DAWN, line(6), 1800)
        val start = avgColour(r, 12)
        val mid = avgColour(r, 560)
        val end = avgColour(r, 1500)
        assertTrue(start.third > start.first, "blue hour should be blue: $start")
        assertTrue(mid.first > mid.second && mid.first > mid.third && mid.third > 0.3f * mid.first, "pink expected: $mid")
        assertTrue(end.first > end.second && end.second > end.third * 1.4f, "gold expected: $end")
        assertTrue(r.mean(1500) > 2.5f * r.mean(12), "dawn did not brighten: ${r.mean(12)} to ${r.mean(1500)}")
        // After the ramp it holds: the room's brightness drifts by only a few points.
        val held = (1300..1790 step 10).map { r.mean(it) }
        assertTrue(held.max() - held.min() < 0.06f, "held range ${held.max() - held.min()}")
        // Shade drifts: lamps are not all equally bright mid-morning.
        val mornings = r.lamps[1500].values.map { Perceptual.levelOf(it) }
        assertTrue(mornings.max() - mornings.min() > 0.01f, "no dappling")
        assertTrue(r.maxLampPerSecond <= 0.12f && r.maxRoomPerSecond <= 0.05f, "lamp ${r.maxLampPerSecond} room ${r.maxRoomPerSecond}")
        assertTrue(r.engagedFraction < 0.02f, "limiter engaged ${r.engagedFraction}")
    }

    @Test
    fun `golden hour takes 30 to 45 minutes by intensity and holds a dim dusk`() {
        val short = run(AmbienceEffect.GOLDEN_HOUR, line(6), 3000, intensity = 0f)
        val long = run(AmbienceEffect.GOLDEN_HOUR, line(6), 3000, intensity = 1f)
        // Warm amber at the start, and brighter than the end.
        val a = avgColour(short, 15)
        assertTrue(a.first > a.second && a.second > a.third, "amber expected: $a")
        // Monotone dimming (sun glow moves, so allow a little slack), no step.
        for (r in listOf(short, long)) {
            val means = (30..2990 step 60).map { r.mean(it) }
            assertTrue(means.zipWithNext().all { (x, y) -> y <= x + 0.015f }, "not dimming: $means")
            assertTrue(r.maxLampPerSecond <= 0.085f && r.maxRoomPerSecond <= 0.045f)
            assertTrue(r.engagedFraction < 0.02f, "limiter engaged ${r.engagedFraction}")
        }
        // The 30 minute show is finished at 31 minutes and holds; the 45 minute one is not.
        assertTrue(abs(short.mean(1900) - short.mean(2990)) < 0.02f, "short show still moving")
        assertTrue(long.mean(1900) > long.mean(2990) + 0.05f, "long show already finished")
        val end = avgColour(short, 2990)
        assertTrue(end.third > end.first, "night should be blue-violet: $end")
        assertTrue(short.mean(2990) in 0.03f..0.09f, "dusk is ${short.mean(2990)}")
    }

    @Test
    fun `the sun crosses the room along the main axis`() {
        val r = run(AmbienceEffect.GOLDEN_HOUR, line(10), 1500, intensity = 0f)
        fun brightestAxis(sec: Int): Float {
            val id = r.lamps[sec].maxByOrNull { Perceptual.levelOf(it.value) }!!.key
            return r.room.axisPos.getValue(id)
        }
        assertTrue(brightestAxis(60) < brightestAxis(700), "sun did not move")
        assertTrue(brightestAxis(700) < brightestAxis(1300) + 1e-4f)
    }

    @Test
    fun `tin roof has a warm lamp, cool window light and lightning only in heavy rain`() {
        val room = RoomModel(line(6))
        val script = scriptFor(AmbienceEffect.TIN_ROOF_RAIN)
        script.bind(room, AmbienceParams(0.5f))
        val frame = HashMap<Int, Rgb>()
        script.renderLights(100.0, arrayOfNulls(1), 0, frame)
        val lamp = room.ids.minByOrNull { room.heightOf(it) }!!
        val c = frame.getValue(lamp)
        assertTrue(c.first > c.second && c.second > c.third, "reading lamp should be warm: $c")
        for ((id, w) in frame) if (id != lamp) assertTrue(w.third > w.first, "window light should be cool: $w")
        assertTrue(frame.filterKeys { it != lamp }.values.all { Perceptual.levelOf(it) < Perceptual.levelOf(c) })

        val calm = run(AmbienceEffect.TIN_ROOF_RAIN, line(6), 3600, intensity = 0.5f)
        assertEquals(0, calm.events.size, "no lightning at the default intensity")
        val heavy = run(AmbienceEffect.TIN_ROOF_RAIN, line(6), 3600, intensity = 1f)
        val strikes = heavy.events
        assertTrue(strikes.isNotEmpty() && strikes.size <= 60, "${strikes.size} flashes in an hour")
        assertTrue(strikes.zipWithNext().all { (a, b) -> b.startS - a.startS >= 59.9 })
        assertTrue(strikes.all { it.soft && it.timbre in 3f..8f })
        assertTrue(heavy.maxLampPerSecond <= 0.25f, "lamp ${heavy.maxLampPerSecond}")
        assertTrue(heavy.engagedFraction < 0.02f, "limiter engaged ${heavy.engagedFraction}")
    }

    @Test
    fun `every new scene makes sound on its own`() {
        for (effect in listOf(
            AmbienceEffect.CANDLELIGHT, AmbienceEffect.FOREST_DAWN,
            AmbienceEffect.TIN_ROOF_RAIN, AmbienceEffect.GOLDEN_HOUR,
        )) {
            val room = RoomModel(line(6))
            val script = scriptFor(effect)
            script.bind(room, AmbienceParams(1f))
            val timeline = AmbienceTimeline()
            val scratch = arrayOfNulls<AmbienceEvent>(160)
            val rate = 48000
            val block = 1024
            var peak = 0f
            var energy = 0.0
            var generated = 0.0
            var t = 0.0
            // Two hours of simulated clock sampled in short stretches around a few moments.
            for (start in listOf(70.0, 900.0, 1500.0, 3000.0)) {
                t = start
                generated = start
                repeat(200) {
                    if (t + 1.0 > generated) {
                        script.schedule(generated, t + 1.0) { timeline.append(it) }
                        generated = t + 1.0
                    }
                    val live = timeline.windowAt(t, scratch)
                    val out = FloatArray(block * 2)
                    script.renderAudio(out, block, t, rate, scratch, live)
                    for (x in out) {
                        assertTrue(x.isFinite(), "${effect.wire} produced $x")
                        peak = maxOf(peak, abs(x))
                        energy += x * x
                    }
                    t += block.toDouble() / rate
                }
            }
            assertTrue(peak > 0.005f && peak < 0.9f, "${effect.wire} peak $peak")
            assertTrue(energy > 0.0)
        }
    }
}
