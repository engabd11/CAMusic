package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.ChannelPosition
import com.engabd.sendpin.hue.EntertainmentChannel
import com.engabd.sendpin.hue.Rgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How bright each scene is at full intensity, and how it uses every lamp in the area.
 *
 * Runs every [AmbienceEffect] through the same path [AmbienceSession.renderLights] takes
 * (script, then [AmbienceFrameShaper]) at intensity and brightness 100 percent, and reads
 * what the bridge would be sent. The scenes were once crushed to a third of the lamp by a
 * second perceptual curve; these numbers are what keeps that from coming back, and what
 * keeps a scene reading as one place seen from different angles rather than six identical
 * bulbs or six unrelated ones.
 */
class AmbienceSpreadTest {

    private val ring = ringRoom(6)
    private val line = linearRoom(5)
    /** Three lamps on a flat floor plan, the common real room: two by the TV, one behind. */
    private val small = listOf(
        EntertainmentChannel(0, ChannelPosition(-0.6f, 0.8f, 0f)),
        EntertainmentChannel(1, ChannelPosition(0.6f, 0.8f, 0f)),
        EntertainmentChannel(2, ChannelPosition(0f, -0.9f, 0f)),
    )

    /** Night scenes with events: dark between them by design, bright when they land. */
    private val night = setOf(
        AmbienceEffect.FIREWORKS, AmbienceEffect.FIREWORKS_2, AmbienceEffect.THUNDERSTORM,
        AmbienceEffect.THUNDERSTORM_2, AmbienceEffect.LIGHT_TRAIN,
    )

    /** Calm scenes: a soft room all the time, never a bright one. */
    private val calm = setOf(
        AmbienceEffect.AURORA, AmbienceEffect.UNDERWATER, AmbienceEffect.CANDLELIGHT,
        AmbienceEffect.COASTAL_RAIN, AmbienceEffect.FOREST_DAWN, AmbienceEffect.TIN_ROOF_RAIN,
    )

    private class Run(
        val room: RoomModel,
        /** Output level per lamp, ten times a second, after the fade-in. */
        val levels: List<FloatArray>,
        /** Output colour per lamp at the same instants. */
        val colours: List<Array<Rgb>>,
        /** Whether a named event was under way at that instant. */
        val burst: List<Boolean>,
        /** The brightest any lamp got, over every frame rather than the samples. */
        val peak: Float,
    )

    private fun run(effect: AmbienceEffect, channels: List<EntertainmentChannel>, seconds: Int = 180): Run {
        val fps = 60
        val dt = 1f / fps
        val room = RoomModel(channels)
        val script = scriptFor(effect)
        script.bind(room, AmbienceParams(intensity = 1f))
        val shaper = AmbienceFrameShaper(script)
        val timeline = AmbienceTimeline()
        val scratch = arrayOfNulls<AmbienceEvent>(160)
        val frame = HashMap<Int, Rgb>()
        val ids = room.ids
        val levels = ArrayList<FloatArray>()
        val colours = ArrayList<Array<Rgb>>()
        val burst = ArrayList<Boolean>()
        var generatedTo = 0.0
        var peak = 0f
        for (f in 0 until seconds * fps) {
            val t = f * dt.toDouble()
            val horizon = t + maxOf(0.5, script.lookaheadS.toDouble())
            if (horizon > generatedTo) {
                script.schedule(generatedTo, horizon) { timeline.append(it) }
                generatedTo = horizon
            }
            val live = timeline.windowAt(t, scratch)
            frame.clear()
            script.renderLights(t, scratch, live, frame)
            val out = shaper.shape(frame, dt, 1f, 1f, intensity = 1f)
            for (c in out.values) peak = max(peak, Perceptual.levelOf(c))
            if (f >= 15 * fps && f % 6 == 0) {
                val cs = Array(ids.size) { out[ids[it]] ?: Rgb(0f, 0f, 0f) }
                colours.add(cs)
                levels.add(FloatArray(ids.size) { Perceptual.levelOf(cs[it]) })
                burst.add(shaper.burst)
            }
        }
        return Run(room, levels, colours, burst, peak)
    }

    private fun median(xs: List<Float>): Float = xs.sorted()[xs.size / 2]
    private fun lamp(r: Run, i: Int) = r.levels.map { it[i] }

    private fun pearson(a: List<Float>, b: List<Float>): Float {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (k in a.indices) {
            num += (a[k] - ma) * (b[k] - mb); da += (a[k] - ma) * (a[k] - ma); db += (b[k] - mb) * (b[k] - mb)
        }
        return if (da < 1e-12 || db < 1e-12) 1f else (num / sqrt(da * db)).toFloat()
    }

    private fun stats(e: AmbienceEffect, r: Run): String {
        val n = r.room.count
        val medians = (0 until n).map { median(lamp(r, it)) }
        val mean = r.levels.map { it.average() }.average()
        val spread = r.levels.map { (it.max() - it.min()) }.average()
        return "%-15s peakMedian=%.2f roomMean=%.2f minLampMedian=%.2f spread=%.3f peak=%.2f".format(
            e.wire, medians.max(), mean, medians.min(), spread, r.peak,
        )
    }

    @Test
    fun `every scene is bright at full intensity`() {
        val fails = ArrayList<String>()
        for (e in AmbienceEffect.entries) {
            val r = run(e, ring)
            val medians = (0 until r.room.count).map { median(lamp(r, it)) }
            val mean = r.levels.map { it.average() }.average()
            println(stats(e, r))
            // A bright scene is bright all the time and a calm one is a soft room. A night
            // scene rests dimmer, but its events - a strike, a burst, the train going by -
            // must still hit hard.
            val dim = when (e) {
                in night -> mean < 0.18 || r.peak < 0.7f
                in calm -> mean < 0.30 || medians.max() < 0.45f
                else -> mean < 0.45 || medians.max() < 0.7f
            }
            if (dim) fails += stats(e, r)
        }
        assertTrue(fails.isEmpty(), "too dim at 100%:\n" + fails.joinToString("\n"))
    }

    @Test
    fun `every lamp is used, in every room shape`() {
        val fails = ArrayList<String>()
        for (room in listOf(ring, line, small)) for (e in AmbienceEffect.entries) {
            val r = run(e, room, seconds = 120)
            val floor = when (e) { in night -> 0.12f; in calm -> 0.20f; else -> 0.25f }
            val ranges = (0 until r.room.count).map { lamp(r, it).let { xs -> xs.max() - xs.min() } }
            for (i in 0 until r.room.count) {
                val med = median(lamp(r, i))
                if (med < floor) fails += "${e.wire} on ${room.size} lamps: lamp $i median %.3f".format(med)
                // Dim and stuck while the rest of the room is alive reads as a dead bulb.
                // A bright steady lamp is a fixture (tin roof's reading lamp), and a
                // scene that is still everywhere (golden hour) is allowed to be.
                // Against the typical lamp, not the busiest: one lamp a bird sang over is
                // not the measure of the rest.
                val typical = median(ranges)
                if (ranges[i] < 0.25f * typical && typical > 0.05f && med < median(r.levels.map { it.average().toFloat() })) {
                    fails += "${e.wire} on ${room.size} lamps: lamp $i barely moves (%.3f of %.3f)".format(ranges[i], typical)
                }
            }
        }
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }

    @Test
    fun `lamps differ from each other, not a flat wash`() {
        val fails = ArrayList<String>()
        for (e in AmbienceEffect.entries) {
            val r = run(e, ring)
            val spread = r.levels.map { it.max() - it.min() }.average()
            // Low on purpose: an overcast storm sky between strikes is nearly even.
            if (spread < 0.035) fails += "${e.wire}: mean spread across lamps %.3f".format(spread)
        }
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }

    @Test
    fun `neighbouring lamps move together more than far ones`() {
        // One source seen from different angles: the two closest lamps on a line should
        // track each other more closely than its two ends do.
        val fails = ArrayList<String>()
        for (e in AmbienceEffect.entries) {
            val r = run(e, line)
            val near = pearson(lamp(r, 0), lamp(r, 1))
            val far = pearson(lamp(r, 0), lamp(r, 4))
            println("%-15s near=%.2f far=%.2f".format(e.wire, near, far))
            if (near + 0.02f < far) fails += "${e.wire}: near r=%.2f far r=%.2f".format(near, far)
        }
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }

    @Test
    fun `events land on different lamps`() {
        val travelling = listOf(
            AmbienceEffect.THUNDERSTORM, AmbienceEffect.THUNDERSTORM_2,
            AmbienceEffect.FIREWORKS, AmbienceEffect.FIREWORKS_2,
            AmbienceEffect.LIGHT_TRAIN, AmbienceEffect.COASTAL_RAIN,
        )
        val fails = ArrayList<String>()
        for (e in travelling) {
            val r = run(e, ring, seconds = 300)
            val n = r.room.count
            val medians = (0 until n).map { median(lamp(r, it)) }
            // An event moment: one lamp clearly above its own resting level.
            val leaders = HashSet<Int>()
            for (s in r.levels) {
                val i = s.indices.maxBy { s[it] - medians[it] }
                if (s[i] - medians[i] > 0.08f) leaders += i
            }
            println("%-15s event leaders=%s".format(e.wire, leaders.sorted()))
            if (leaders.size < (n + 1) / 2) fails += "${e.wire}: events only ever lead on lamps ${leaders.sorted()}"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }

    @Test
    fun `the light train reaches the lamps in order along the line`() {
        // Several carriages are lit at once, so the brightest lamp jumps about; what has to
        // be in order is when each lamp first sees the train on a pass.
        val r = run(AmbienceEffect.LIGHT_TRAIN, line, seconds = 300)
        val n = r.room.count
        val medians = (0 until n).map { median(lamp(r, it)) }
        val first = IntArray(n) { -1 }
        var quiet = 0
        var passes = 0
        var ordered = 0
        fun close() {
            // The first pass starts during the fade-in, so it says nothing about order.
            if (first.all { it > 10 }) {
                passes++
                // Two samples of slack: neighbours a fifth of a second apart are a tie.
                val fwd = (0 until n - 1).all { first[it] <= first[it + 1] + 2 }
                val back = (0 until n - 1).all { first[it] >= first[it + 1] - 2 }
                if (fwd || back) ordered++ else println("out of order: ${first.toList()}")
            }
            first.fill(-1)
        }
        for ((k, s) in r.levels.withIndex()) {
            var any = false
            for (i in 0 until n) if (s[i] - medians[i] > 0.15f) { any = true; if (first[i] < 0) first[i] = k }
            quiet = if (any) 0 else quiet + 1
            if (quiet == 50) close()          // five seconds of night track ends a pass
        }
        assertTrue(passes >= 3, "only $passes complete passes in five minutes")
        assertTrue(ordered == passes, "$ordered of $passes passes ran along the line in order")
    }

    @Test
    fun `colours stay in each scene's palette`() {
        val warm = setOf(AmbienceEffect.FIREPLACE, AmbienceEffect.CANDLELIGHT, AmbienceEffect.GOLDEN_HOUR)
        val cool = setOf(AmbienceEffect.UNDERWATER, AmbienceEffect.AURORA, AmbienceEffect.COASTAL_RAIN)
        val fails = ArrayList<String>()
        for (e in warm + cool) {
            val r = run(e, ring)
            var off = 0; var lit = 0
            for ((k, cs) in r.colours.withIndex()) {
                if (r.burst[k]) continue
                for (c in cs) {
                    val m = Perceptual.levelOf(c)
                    if (m < 0.05f) continue
                    lit++
                    val sat = (m - min(c.first, min(c.second, c.third))) / m
                    val ok = if (e in warm) c.first >= c.second && c.second >= c.third && sat > 0.25f
                    else c.first < max(c.second, c.third) || sat < 0.1f
                    if (!ok) off++
                }
            }
            // A few percent may be off: a car's headlights on the rain, say.
            if (off > lit / 20) fails += "${e.wire}: $off of $lit lamp samples off palette"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }
}
