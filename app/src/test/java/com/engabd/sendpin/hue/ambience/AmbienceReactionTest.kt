package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.audio.AudioAnalyzer
import com.engabd.sendpin.hue.Rgb
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ANALYSIS_SR = 22_050
private const val ANALYSIS_HOP = 441

/**
 * The reactive path, end to end, from PCM.
 *
 * ## Why this is worth the machinery
 *
 * The question these effects were failing is "does the room do something when the
 * recording does something", and nothing short of real audio can answer it. A test that
 * hands a script a hand-built [BedOnset] proves the script's arithmetic and nothing
 * about the thing that was actually broken; the whole difficulty is upstream of that, in
 * whether a thunderclap buried in rain can be told apart from the rain, by a detector
 * reading bands that an AGC has already rescaled by a factor of eight.
 *
 * So these tests synthesise a storm as stereo PCM, push it through the **real**
 * [AudioAnalyzer] a hop at a time exactly as `AudioAnalysisTap` does, and run the result
 * through the real [AmbienceBedAnalyser] and the real script. Everything between the
 * samples and the lamps is under test. It is all pure JVM — the analyzer has no Android
 * dependency at all — so it runs in CI in a couple of seconds.
 *
 * The fixture's levels matter as much as its shapes and are the first thing to check if
 * these ever start failing mysteriously. Rain sits about 20 dB under the thunder,
 * because that is what a real storm recording looks like; an earlier version of this
 * file had them a factor of 1.4 apart and every threshold tuned against it was wrong.
 */

/** One clap: when, how near (1 = overhead, 0 = miles off), and where in the field. */
private class Clap(val atS: Double, val near: Float, val pan: Float)

private class Noise(private var s: Long = 12345) {
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        return ((s ushr 11).toFloat() / (1L shl 53).toFloat()) * 2f - 1f
    }
}

/**
 * A synthetic storm at the analysis rate: steady rain, plus claps whose spectrum and
 * level depend on distance the way real ones do.
 *
 * Air absorbs treble far faster than bass, so a near strike arrives broadband and loud
 * and a far one arrives as a quiet, low-passed swell. That relationship is the one the
 * effect reads a distance back out of, so the fixture has to contain it honestly rather
 * than just making distant claps quieter.
 */
private fun synthStorm(seconds: Double, claps: List<Clap>): Pair<FloatArray, FloatArray> {
    val n = (seconds * ANALYSIS_SR).toInt()
    val l = FloatArray(n)
    val r = FloatArray(n)
    val rn = Noise(9001)
    var pl = 0f
    var pr = 0f
    for (i in 0 until n) {
        pl = pl * 0.94f + rn.next() * 0.06f
        pr = pr * 0.94f + rn.next() * 0.06f
        l[i] = (pl * 0.35f + rn.next() * 0.030f) * RAIN_LEVEL
        r[i] = (pr * 0.35f + rn.next() * 0.030f) * RAIN_LEVEL
    }
    for (c in claps) {
        val start = (c.atS * ANALYSIS_SR).toInt()
        val attack = (0.004f + 0.35f * (1f - c.near)) * ANALYSIS_SR
        val tau = (1.6f + 4.5f * (1f - c.near)) * ANALYSIS_SR
        val lp = 0.06f + 0.80f * c.near          // 1 = wide open, small = only low end
        val peak = 0.22f + 0.70f * c.near
        var z = 0f
        val gl = sqrt(1f - (c.pan + 1f) / 2f)
        val gr = sqrt((c.pan + 1f) / 2f)
        var i = 0
        while (i < 9 * ANALYSIS_SR && start + i < n) {
            val a = if (i < attack) i / attack else 1f
            val env = a * exp(-(i - attack).coerceAtLeast(0f) / tau)
            z += (rn.next() - z) * lp
            // Normalised for the low-pass, or filtering hard would cost level twice.
            val s = z * env * peak / sqrt(lp)
            l[start + i] += s * gl
            r[start + i] += s * gr
            i++
        }
    }
    return l to r
}

/** Rain about 20 dB under the thunder, as a real recording has it. */
private const val RAIN_LEVEL = 0.055f

/** What a reacting show produced: its events, and what its bed was doing throughout. */
private class Reaction(val events: List<AmbienceEvent>, val bed: AmbienceBedTrack)

/**
 * Drive a script the way `AmbienceSession` drives it off an analysed recording.
 *
 * The real analyzer, the real onset detector, the real script, one hop at a time.
 */
private fun react(script: AmbienceScript, l: FloatArray, r: FloatArray): Reaction {
    val analyzer = AudioAnalyzer()
    val detector = AmbienceBedAnalyser()
    val bed = AmbienceBedTrack(4096)
    val events = ArrayList<AmbienceEvent>()
    val hopL = FloatArray(ANALYSIS_HOP)
    val hopR = FloatArray(ANALYSIS_HOP)
    var i = 0
    while (i + ANALYSIS_HOP <= l.size) {
        System.arraycopy(l, i, hopL, 0, ANALYSIS_HOP)
        System.arraycopy(r, i, hopR, 0, ANALYSIS_HOP)
        val frame = analyzer.pushStereo(hopL, hopR)
        val atS = (i + ANALYSIS_HOP).toDouble() / ANALYSIS_SR
        val (sample, onset) = detector.consume(frame, atS)
        bed.append(sample)
        script.react(sample, onset) { events.add(it) }
        i += ANALYSIS_HOP
    }
    script.bindBed(bed)
    return Reaction(events, bed)
}

private val STORM = listOf(
    Clap(6.0, near = 0.95f, pan = -0.75f),
    Clap(14.0, near = 0.15f, pan = 0.10f),
    Clap(24.0, near = 0.70f, pan = 0.80f),
    Clap(34.0, near = 0.05f, pan = -0.20f),
    Clap(44.0, near = 1.00f, pan = 0.55f),
)

class AmbienceReactionTest {

    private fun storm(intensity: Float = 0.7f): Pair<AmbienceScript, Reaction> {
        val script = scriptFor(AmbienceEffect.THUNDERSTORM)
        script.bind(RoomModel(ringRoom(8)), AmbienceParams(intensity))
        val (l, r) = synthStorm(56.0, STORM)
        return script to react(script, l, r)
    }

    @Test
    fun `every thunderclap in the recording flashes the room`() {
        val (_, out) = storm()
        for (c in STORM) {
            val hit = out.events.filter { abs(it.startS - c.atS) < 0.35 }
            assertTrue(
                hit.isNotEmpty(),
                "the clap at ${c.atS}s (near ${c.near}) produced no flash; " +
                    "strikes at ${out.events.map { "%.2f".format(it.startS) }}",
            )
        }
    }

    @Test
    fun `the flash lands on the clap rather than behind it`() {
        val (_, out) = storm()
        for (c in STORM) {
            val first = out.events.filter { abs(it.startS - c.atS) < 0.35 }
                .minByOrNull { it.startS } ?: continue
            val offset = abs(first.startS - c.atS)
            // A tenth of a second is roughly the point at which a flash stops reading as
            // belonging to the bang it came from. The detector's own confirmation and
            // filter delays are measured and given back (see BedOnset.atS), so what is
            // left is the analysis hop.
            assertTrue(
                offset < 0.10,
                "the flash for the clap at ${c.atS}s was ${(offset * 1000).toInt()} ms off",
            )
        }
    }

    @Test
    fun `a roll does not re-flash part-way through itself`() {
        val (_, out) = storm()
        // Every strike should be the leading edge of a clap. Lightning does re-strike,
        // and a close one is allowed its double flicker inside a tenth of a second; what
        // it never does is flash again four seconds into its own rumble.
        val strays = out.events.filter { e -> STORM.none { abs(e.startS - it.atS) < 0.35 } }
        assertTrue(
            strays.isEmpty(),
            "re-triggered on a roll at ${strays.map { "%.2f".format(it.startS) }}",
        )
    }

    @Test
    fun `steady rain never flashes the room`() {
        val script = scriptFor(AmbienceEffect.THUNDERSTORM)
        script.bind(RoomModel(ringRoom(8)), AmbienceParams(0.7f))
        val (l, r) = synthStorm(60.0, emptyList())
        val out = react(script, l, r)
        assertTrue(
            out.events.isEmpty(),
            "rain alone produced ${out.events.size} strikes — the detector is hearing noise",
        )
    }

    @Test
    fun `silence produces nothing at all`() {
        val script = scriptFor(AmbienceEffect.THUNDERSTORM)
        script.bind(RoomModel(ringRoom(8)), AmbienceParams(0.7f))
        val n = 20 * ANALYSIS_SR
        val out = react(script, FloatArray(n), FloatArray(n))
        assertTrue(out.events.isEmpty(), "digital silence produced ${out.events.size} events")
    }

    @Test
    fun `the clap's own timbre decides how far away the room draws it`() {
        val (_, out) = storm()
        // The thing the effect used to invent and now reads out of the sound. A strike
        // that arrives with its treble intact was close; one that is all bottom end has
        // had the treble taken out of it by several kilometres of air.
        val measured = STORM.mapNotNull { c ->
            out.events.filter { abs(it.startS - c.atS) < 0.35 }.minByOrNull { it.startS }
                ?.let { c.near to it.timbre }
        }
        assertTrue(measured.size >= 4, "not enough claps detected to compare")
        val byNearness = measured.sortedByDescending { it.first }
        for (i in 0 until byNearness.size - 1) {
            assertTrue(
                byNearness[i].second <= byNearness[i + 1].second + 0.35f,
                "a nearer clap read as further away: $byNearness",
            )
        }
    }

    @Test
    fun `a clap on the left lights the left of the room`() {
        val (_, out) = storm()
        for (c in STORM) {
            val hit = out.events.filter { abs(it.startS - c.atS) < 0.35 }
                .minByOrNull { it.startS } ?: continue
            // azimuthOfPan: hard right is 0, hard left is half a turn.
            val onLeft = hit.azimuth > 0.25f
            assertTrue(
                onLeft == (c.pan < 0f),
                "a clap panned ${c.pan} lit azimuth ${hit.azimuth}",
            )
        }
    }

    @Test
    fun `the room swells with the rumble it is playing`() {
        val (script, out) = storm()
        fun brightness(t: Double): Float {
            val map = HashMap<Int, Rgb>()
            script.renderLights(t, arrayOfNulls(1), 0, map)
            return map.values.fold(0f) { a, c -> a + c.first + c.second + c.third }
        }
        // Rain only, before anything has happened, against the middle of a long roll.
        // Deliberately measured with *no events live at all*, so what is being compared
        // is purely the bed's answer to the audio rather than any flash still fading.
        val quiet = brightness(2.0)
        val rolling = brightness(STORM[1].atS + 1.6)
        assertTrue(
            rolling > quiet * 1.3f,
            "the room ignored the rumble it was playing ($quiet then $rolling)",
        )
        assertTrue(out.bed.ready, "nothing was ever written to the bed track")
    }

    @Test
    fun `a firework display lights the room on its bangs`() {
        val bangs = (0 until 12).map {
            Clap(4.0 + it * 2.3, near = 0.6f + 0.4f * (it % 3) / 2f, pan = -0.8f + 0.16f * it)
        }
        val script = scriptFor(AmbienceEffect.FIREWORKS)
        script.bind(RoomModel(ringRoom(8)), AmbienceParams(0.7f))
        val (l, r) = synthStorm(34.0, bangs)
        val out = react(script, l, r)
        // All but the first: the detector's floors are seeded from the opening frame and
        // need a moment to settle, which is honest and costs one bang at the very start
        // of a show.
        val matched = bangs.count { b -> out.events.any { abs(it.startS - b.atS) < 0.35 } }
        assertTrue(
            matched >= bangs.size - 1,
            "only $matched of ${bangs.size} bangs lit the room",
        )
    }

    @Test
    fun `a reacting script does not also invent its own events`() {
        // The bug the whole reactive path exists to fix, stated as a property: an effect
        // that is its events must not run its scheduler while a recording of those same
        // events is playing, or the room shows two storms.
        for (effect in AmbienceEffect.entries) {
            val script = scriptFor(effect)
            if (!script.eventsComeFromAudio) continue
            script.bind(RoomModel(ringRoom(8)), AmbienceParams(0.7f))
            val scheduled = ArrayList<AmbienceEvent>()
            script.schedule(0.0, 30.0) { scheduled.add(it) }
            assertTrue(
                scheduled.isNotEmpty(),
                "${effect.wire}: has no scheduler to stand down, so the flag is wrong",
            )
        }
    }
}

class AmbienceBedTrackTest {

    private fun sample(t: Double, rumble: Float = 0.5f) = BedSample().apply {
        tS = t
        this.rumble = rumble
    }

    @Test
    fun `reads back the sample nearest the asked-for moment`() {
        val bed = AmbienceBedTrack(16)
        for (i in 0 until 10) bed.append(sample(i * 0.02, rumble = i / 10f))
        val out = BedSample()
        assertTrue(bed.sampleAt(0.101, out))
        assertTrue(abs(out.tS - 0.10) < 1e-9, "got ${out.tS}")
    }

    @Test
    fun `says so rather than guessing when there is nothing near`() {
        val bed = AmbienceBedTrack(16)
        bed.append(sample(1.0))
        val out = BedSample()
        // Across a loop point, or before the first frame, the honest answer is "nothing
        // is playing" — a script that got a stale value instead would freeze the room at
        // whatever the bed was doing when it stopped.
        assertTrue(!bed.sampleAt(5.0, out), "returned a sample four seconds stale")
        assertTrue(!bed.sampleAt(0.0, out), "returned a sample from the future")
    }

    @Test
    fun `is empty before anything is analysed, and after a clear`() {
        val bed = AmbienceBedTrack(16)
        val out = BedSample()
        assertTrue(!bed.ready)
        assertTrue(!bed.sampleAt(0.0, out))
        bed.append(sample(1.0))
        assertTrue(bed.ready)
        bed.clear()
        assertTrue(!bed.ready)
        assertTrue(!bed.sampleAt(1.0, out))
    }
}
