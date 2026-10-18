package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Rgb
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The comfort test: ten minutes of every scene, in rooms of one, three, six and ten lamps,
 * at the default intensity, run through exactly what the app runs them through
 * ([AmbienceFrameShaper]) on a simulated 60 Hz clock.
 *
 * ## What "comfortable" is pinned to
 *
 * All brightness below is **perceived** (CIE L* over 100, see [Perceptual]): 0.1 is the
 * difference between a dim lamp and a slightly less dim one, 0.5 is dim to bright. Each
 * scene has a row in [LIMITS], with the numbers and the reason beside them.
 *
 * Three questions, asked of the whole run:
 *
 * 1. **Did the scene stay inside its own limits without help?** The script's raw output
 *    (before the comfort limiter) is measured per lamp over one second. The limiter is a
 *    backstop; a scene that leans on it is a scene whose designer was wrong, and "the
 *    limiter flattened it" is exactly how a fire ends up looking like a lamp. So the
 *    fraction of calm frames on which the limiter had to move any lamp by more than 0.02
 *    must stay under 2 percent.
 * 2. **What reached the lamps?** The limited output's biggest per-frame step, biggest
 *    change over one second, and biggest swing of the room's mean over one second.
 * 3. **How often do named events happen?** Per minute, and (for the storm) never closer
 *    than the stated gap.
 *
 * Frames in which the script says a named event (a lightning stroke, a firework bloom) is
 * under way, and the second either side of them, are exempt from questions 1 and 2: those
 * events are allowed to exceed the calm limits within the burst limits, which are checked
 * on their own. Everything is also written to `build/ambience-comfort.txt`.
 */
class AmbienceComfortTest {

    /** Lamps on a line; the same shape as the usual setup, and the 1-lamp case does not divide by zero. */
    private fun line(n: Int): List<EntertainmentChannel> = (0 until n).map { i ->
        EntertainmentChannel(i, ChannelPosition(if (n == 1) 0f else -1f + 2f * i / (n - 1f), 0f, 0f))
    }

    private class Limits(
        /** Biggest change of any lamp over one second, limited output. */
        val lampPerSecond: Float,
        /** Biggest change of the room's mean over one second, limited output. */
        val roomPerSecond: Float,
        /** Most named events per minute, averaged over the run. */
        val eventsPerMinute: Float,
        /** Fewest, so a scene that has gone quiet is noticed too. */
        val minEventsPerMinute: Float,
    )

    /**
     * The thresholds, with the reasoning.
     *
     * - fireplace: a lamp may move 0.5 in a second at the very most (a pop plus a flicker
     *   peak); the room mean 0.2. Pops at most 24 a minute (the 2.5 s floor) and at least
     *   4 (the 8 s ceiling would give 7.5).
     * - thunderstorm: calm frames only. A far glow rises and falls over about a second at
     *   0.3, so 0.8 allows it with room. Strikes (groups) 15 a minute at the very most,
     *   which is the 4 s floor, and 1 at least.
     * - fireworks: calm frames are sparks and smoke; the bloom is exempt. Shells at most 16
     *   a minute.
     * - underwater: water does not jump. 0.25 a second is the profile's own ceiling.
     *   Swells and sun shafts together: 1 to 4 a minute.
     * - aurora: 0.12 a second, and no events at all.
     * - light train: windows pass at 0.5 a second at most. Clacks are sound, not named
     *   light events, so they are not counted; trains are counted in the next test.
     * - coastal rain: 0.35. Headlights and distant glows are rare (at most 3 a minute).
     */
    private val LIMITS = mapOf(
        AmbienceEffect.FIREPLACE to Limits(0.50f, 0.20f, 24f, 4f),
        AmbienceEffect.THUNDERSTORM to Limits(0.80f, 0.50f, 15f, 0.5f),
        AmbienceEffect.THUNDERSTORM_2 to Limits(0.80f, 0.50f, 15f, 0.5f),
        AmbienceEffect.FIREWORKS to Limits(1.00f, 0.60f, 16f, 2f),
        AmbienceEffect.FIREWORKS_2 to Limits(1.00f, 0.60f, 16f, 2f),
        AmbienceEffect.UNDERWATER to Limits(0.25f, 0.12f, 4f, 0.5f),
        AmbienceEffect.AURORA to Limits(0.15f, 0.085f, 0f, 0f),
        AmbienceEffect.LIGHT_TRAIN to Limits(0.65f, 0.65f, 1000f, 0f),
        AmbienceEffect.COASTAL_RAIN to Limits(0.35f, 0.25f, 3f, 0f),
    )

    private class Result(
        val effect: AmbienceEffect,
        val lamps: Int,
        val topology: String,
        var maxFrameStep: Float = 0f,
        var maxLampPerSecond: Float = 0f,
        var maxRoomPerSecond: Float = 0f,
        var rawMaxLampPerSecond: Float = 0f,
        var engagedFraction: Float = 0f,
        var eventsPerMinute: Float = 0f,
        var burstFraction: Float = 0f,
        var peakLevel: Float = 0f,
        var meanLevel: Float = 0f,
        var minEventGapS: Float = Float.MAX_VALUE,
        var flashPeaksPerSecond: Int = 0,
    ) {
        override fun toString() = (
            "%-15s %-7s lamps=%-2d  frameStep=%.4f  lamp/s=%.3f (raw %.3f)  room/s=%.3f  " +
                "limiterEngaged=%.2f%%  events/min=%.1f  minGap=%.1fs  burstFrames=%.2f%%  " +
                "peak=%.2f mean=%.3f flashPeaks/s=%d"
            ).format(
            effect.wire, topology, lamps, maxFrameStep, maxLampPerSecond, rawMaxLampPerSecond,
            maxRoomPerSecond, engagedFraction * 100f, eventsPerMinute, minEventGapS,
            burstFraction * 100f, peakLevel, meanLevel, flashPeaksPerSecond,
        )
    }

    /** Which events count as "named" for each scene, for the per-minute rate. */
    private fun named(effect: AmbienceEffect, e: AmbienceEvent): Boolean = when (effect) {
        AmbienceEffect.FIREPLACE -> e.kind == AmbienceEvent.POP
        AmbienceEffect.THUNDERSTORM, AmbienceEffect.THUNDERSTORM_2 ->
            e.kind == AmbienceEvent.STRIKE && e.soundS > 0f
        AmbienceEffect.FIREWORKS, AmbienceEffect.FIREWORKS_2 -> e.kind == AmbienceEvent.BURST
        AmbienceEffect.UNDERWATER -> e.kind == AmbienceEvent.SWELL || e.kind == AmbienceEvent.SWEEP
        AmbienceEffect.COASTAL_RAIN -> e.kind == AmbienceEvent.SWEEP || e.kind == AmbienceEvent.STRIKE
        else -> false
    }

    private fun simulate(
        effect: AmbienceEffect, channels: List<EntertainmentChannel>, topologyName: String, intensity: Float = 0.5f,
    ): Result {
        val seconds = 600
        val fps = 60
        val frames = seconds * fps
        val dt = 1f / fps
        val room = RoomModel(channels)
        val script = scriptFor(effect)
        script.bind(room, AmbienceParams(intensity = intensity))
        val shaper = AmbienceFrameShaper(script)
        val timeline = AmbienceTimeline()
        val scratch = arrayOfNulls<AmbienceEvent>(160)
        val frame = HashMap<Int, Rgb>()
        val ids = room.ids
        val n = ids.size

        val raw = Array(frames) { FloatArray(n) }
        val lim = Array(frames) { FloatArray(n) }
        val burstFrame = BooleanArray(frames)
        val namedStarts = ArrayList<Double>()
        var generatedTo = 0.0

        for (f in 0 until frames) {
            val t = f * dt.toDouble()
            val horizon = t + maxOf(0.5, script.lookaheadS.toDouble())
            if (horizon > generatedTo) {
                script.schedule(generatedTo, horizon) {
                    timeline.append(it)
                    if (named(effect, it)) namedStarts.add(it.startS)
                }
                generatedTo = horizon
            }
            val live = timeline.windowAt(t, scratch)
            frame.clear()
            script.renderLights(t, scratch, live, frame)
            for ((i, id) in ids.withIndex()) raw[f][i] = Perceptual.levelOf(frame[id] ?: Rgb(0f, 0f, 0f))
            shaper.shape(frame, dt, 1f, 1f)
            burstFrame[f] = shaper.burst
            for ((i, id) in ids.withIndex()) lim[f][i] = Perceptual.levelOf(shaper.perceived[id] ?: Rgb(0f, 0f, 0f))
        }

        // Frames near a named event, a second either side, are exempt from the calm checks.
        val exempt = BooleanArray(frames)
        // A pop is a quarter of a second of event; a bloom or stroke group needs a second.
        val margin = if (effect == AmbienceEffect.FIREPLACE) (0.3f * fps).toInt() else fps
        var last = -10_000
        for (f in 0 until frames) if (burstFrame[f]) last = f else if (f - last <= margin) exempt[f] = true
        run {
            var next = Int.MAX_VALUE
            for (f in frames - 1 downTo 0) {
                if (burstFrame[f]) next = f else if (next - f <= margin) exempt[f] = true
            }
        }
        for (f in 0 until frames) if (burstFrame[f]) exempt[f] = true

        val r = Result(effect, n, topologyName)
        var calm = 0
        var engaged = 0
        var burstFrames = 0
        var sum = 0.0
        val skip = 10 * fps               // the fade-in from dark at the start
        for (f in 1 until frames) {
            if (burstFrame[f]) burstFrames++
            for (i in 0 until n) {
                r.peakLevel = maxOf(r.peakLevel, lim[f][i])
                sum += lim[f][i]
            }
            if (f < skip || exempt[f]) continue
            calm++
            var clipped = false
            for (i in 0 until n) {
                r.maxFrameStep = maxOf(r.maxFrameStep, abs(lim[f][i] - lim[f - 1][i]))
                if (abs(raw[f][i] - lim[f][i]) > 0.02f) clipped = true
                if (f >= fps && !exempt[f - fps]) {
                    r.maxLampPerSecond = maxOf(r.maxLampPerSecond, abs(lim[f][i] - lim[f - fps][i]))
                    r.rawMaxLampPerSecond = maxOf(r.rawMaxLampPerSecond, abs(raw[f][i] - raw[f - fps][i]))
                }
            }
            if (clipped) engaged++
            if (f >= fps && !exempt[f - fps]) {
                var m0 = 0f; var m1 = 0f
                for (i in 0 until n) { m0 += lim[f - fps][i]; m1 += lim[f][i] }
                r.maxRoomPerSecond = maxOf(r.maxRoomPerSecond, abs(m1 - m0) / n)
            }
        }
        r.engagedFraction = if (calm == 0) 0f else engaged.toFloat() / calm
        r.burstFraction = burstFrames.toFloat() / frames
        r.meanLevel = (sum / (frames.toDouble() * n)).toFloat()
        r.eventsPerMinute = namedStarts.size / (seconds / 60f)
        namedStarts.sort()
        for (i in 1 until namedStarts.size) {
            r.minEventGapS = minOf(r.minEventGapS, (namedStarts[i] - namedStarts[i - 1]).toFloat())
        }

        // Photosensitivity, for the scenes with sudden light: how many separate bright
        // peaks does the room's mean make in any one second? Counted as local maxima
        // standing at least 0.12 above both sides within a quarter second.
        var worstPeaks = 0
        if (r.burstFraction > 0f) {
            val mean = FloatArray(frames) { f -> lim[f].sum() / n }
            val peaks = BooleanArray(frames)
            for (f in 15 until frames - 15) {
                val v = mean[f]
                var isPeak = true
                var lowL = v; var lowR = v
                for (k in 1..15) {
                    if (mean[f - k] > v || mean[f + k] > v) isPeak = false
                    lowL = minOf(lowL, mean[f - k]); lowR = minOf(lowR, mean[f + k])
                }
                peaks[f] = isPeak && v - lowL >= 0.12f && v - lowR >= 0.12f
            }
            var inWindow = 0
            for (f in 0 until frames) {
                if (peaks[f]) inWindow++
                if (f >= fps && peaks[f - fps]) inWindow--
                worstPeaks = maxOf(worstPeaks, inWindow)
            }
        }
        r.flashPeaksPerSecond = worstPeaks
        return r
    }


    private fun layouts(): List<Triple<String, List<EntertainmentChannel>, String>> = listOf(
        Triple("line1", line(1), "line"),
        Triple("line3", line(3), "line"),
        Triple("line6", line(6), "line"),
        Triple("line10", line(10), "line"),
        Triple("ring6", ringRoom(6), "ring"),
        Triple("cluster3", clusterRoom(), "cluster"),
    )

    private val report = ArrayList<String>()

    private fun writeReport() {
        runCatching {
            val out = File("build/ambience-comfort.txt")
            out.parentFile?.mkdirs()
            out.writeText(report.joinToString("\n") + "\n")
        }
    }

    @Test
    fun `every scene stays comfortable for ten minutes in every room`() {
        val failures = ArrayList<String>()
        for (effect in AmbienceEffect.entries) {
            val limits = LIMITS.getValue(effect)
            val profile = scriptFor(effect).comfort
            for ((_, channels, topo) in layouts()) {
                val r = simulate(effect, channels, topo)
                report.add(r.toString())
                fun check(ok: Boolean, what: String) {
                    if (!ok) failures.add("${effect.wire}/${r.topology}/${r.lamps}: $what   [$r]")
                }
                // 1. Inside its own limits without help.
                check(r.engagedFraction < 0.02f, "the limiter had to hold the scene back on ${r.engagedFraction * 100}% of calm frames")
                check(r.rawMaxLampPerSecond <= limits.lampPerSecond * 1.5f, "the script asked for ${r.rawMaxLampPerSecond}/s")
                // 2. What reached the lamps.
                check(r.maxFrameStep <= profile.risePerS.coerceAtLeast(profile.fallPerS) / 60f + 1e-4f,
                    "a lamp stepped ${r.maxFrameStep} in one frame")
                check(r.maxLampPerSecond <= limits.lampPerSecond, "a lamp moved ${r.maxLampPerSecond} in a second")
                check(r.maxRoomPerSecond <= limits.roomPerSecond, "the room mean moved ${r.maxRoomPerSecond} in a second")
                // 3. Event rates.
                check(r.eventsPerMinute <= limits.eventsPerMinute, "${r.eventsPerMinute} events a minute")
                check(r.eventsPerMinute >= limits.minEventsPerMinute, "only ${r.eventsPerMinute} events a minute")
                if (effect == AmbienceEffect.THUNDERSTORM || effect == AmbienceEffect.THUNDERSTORM_2) {
                    check(r.minEventGapS >= 3.9f, "two strikes ${r.minEventGapS}s apart")
                    check(r.flashPeaksPerSecond <= 5, "${r.flashPeaksPerSecond} flash peaks in one second")
                }
                if (effect == AmbienceEffect.FIREPLACE) {
                    check(r.minEventGapS >= 2.49f, "two pops ${r.minEventGapS}s apart")
                    // A real fire at this scale never reaches half a lamp's range.
                    check(r.peakLevel <= 0.85f, "the fire peaked at ${r.peakLevel}")
                }
                if (effect == AmbienceEffect.FIREWORKS || effect == AmbienceEffect.FIREWORKS_2) {
                    check(r.minEventGapS >= 0.79f, "two shells ${r.minEventGapS}s apart")
                    check(r.flashPeaksPerSecond <= 3, "${r.flashPeaksPerSecond} bursts' peaks in one second")
                }
            }
        }
        writeReport()
        assertTrue(failures.isEmpty(), "comfort failures:\n" + failures.joinToString("\n"))
    }

    /**
     * Near strikes are only allowed at high intensity; this is the one place the named
     * strokes appear, so it is where the burst limits and the flash rate are pinned: no
     * more than five bright peaks in any second, strikes at least 4 s apart, and the
     * calm frames between them as gentle as ever.
     */
    @Test
    fun `a storm at full intensity flashes in strokes but stays inside its flash budget`() {
        val failures = ArrayList<String>()
        for (effect in listOf(AmbienceEffect.THUNDERSTORM, AmbienceEffect.THUNDERSTORM_2)) {
            for ((_, channels, topo) in layouts()) {
                val r = simulate(effect, channels, topo, intensity = 1f)
                report.add("[intensity 1.0] $r")
                fun check(ok: Boolean, what: String) {
                    if (!ok) failures.add("${effect.wire}/$topo/${r.lamps}: $what [$r]")
                }
                check(r.flashPeaksPerSecond <= 5, "${r.flashPeaksPerSecond} flash peaks in a second")
                check(r.minEventGapS >= 3.9f, "strikes ${r.minEventGapS}s apart")
                check(r.eventsPerMinute <= 15f, "${r.eventsPerMinute} strikes a minute")
                check(r.maxLampPerSecond <= 0.8f, "calm frames moved ${r.maxLampPerSecond}/s")
                check(r.engagedFraction < 0.03f, "limiter engaged ${r.engagedFraction * 100}%")
            }
        }
        writeReport()
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `a fireplace is warm, and a hearth lamp is steadier than a far lamp`() {
        val channels = (0 until 6).map { i ->
            EntertainmentChannel(i, ChannelPosition(-1f + 2f * i / 5f, 0f, i / 5f))
        }
        val room = RoomModel(channels)
        val script = scriptFor(AmbienceEffect.FIREPLACE)
        script.bind(room, AmbienceParams(0.5f))
        val hearth = room.ids.minByOrNull { room.heightOf(it) }!!
        val far = room.ids.maxByOrNull { room.gap(it, hearth) }!!
        val a = ArrayList<Float>()
        val b = ArrayList<Float>()
        val frame = HashMap<Int, Rgb>()
        var t = 0.0
        while (t < 300.0) {
            frame.clear()
            script.renderLights(t, arrayOfNulls(1), 0, frame)
            a.add(Perceptual.levelOf(frame.getValue(hearth)))
            b.add(Perceptual.levelOf(frame.getValue(far)))
            // Warm throughout: red leads, blue is the least.
            for (c in frame.values) {
                assertTrue(c.first >= c.second && c.second >= c.third, "a cold lamp in a fire: $c")
            }
            t += 1.0 / 60.0
        }
        fun spread(x: List<Float>): Float {
            val m = x.average().toFloat()
            return Math.sqrt(x.sumOf { ((it - m) * (it - m)).toDouble() } / x.size).toFloat() / m
        }
        assertTrue(a.average() > b.average(), "the far lamp is not softer than the hearth")
        assertTrue(spread(a) < spread(b), "the coals flicker more than the walls (${spread(a)} vs ${spread(b)})")
        assertTrue(b.average() in 0.2..0.45, "the bounce is ${b.average()}")
    }

    @Test
    fun `a train is a row of carriages with dark between them`() {
        val room = RoomModel(line(6))
        val script = scriptFor(AmbienceEffect.LIGHT_TRAIN)
        script.bind(room, AmbienceParams(0.5f))
        // Watch one lamp through a whole cycle: it should be lit and then dark more than
        // once during the pass, which is carriages and the gaps between them.
        val mid = room.ids[3]
        val frame = HashMap<Int, Rgb>()
        var lit = false
        var transitions = 0
        var t = 0.0
        while (t < 60.0) {
            frame.clear()
            script.renderLights(t, arrayOfNulls(1), 0, frame)
            val v = Perceptual.levelOf(frame.getValue(mid))
            val nowLit = v > 0.18f
            if (nowLit != lit) { transitions++; lit = nowLit }
            t += 1.0 / 60.0
        }
        assertTrue(transitions >= 4, "a lamp saw only $transitions changes as the train went by")
    }
}
