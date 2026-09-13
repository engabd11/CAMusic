package com.engabd.sendpin.audio

import com.engabd.sendpin.hue.PICK_BPM_HI
import com.engabd.sendpin.hue.PICK_BPM_LO
import com.engabd.sendpin.hue.intensitySignal
import com.engabd.sendpin.hue.loudnessForIntensity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where two tracks get joined.
 *
 * The complaint this answers is "it feels like a cut", and every one of the ways a
 * fixed crossfade produces that is a wrong number rather than a wrong mechanism — so
 * they are all stateable here, with no player and no Android.
 */
class SmartCrossfadeTest {

    /**
     * A scan of a [durationS] track at [bpm] whose music runs from [startsS] to
     * [endsS] and is digitally silent outside that.
     *
     * The intensity curve is built the way the analyser builds it — the light
     * show's blend of loudness, tempo and beat rate, smoothed and decimated — and
     * not as a bare loudness. That matters: the blend holds a real scan's curve
     * near 0.2 through silence, which is the trap the planner has to see past.
     */
    private fun scan(
        durationS: Float = 240f,
        bpm: Float = 120f,
        beatsPerBar: Int = 4,
        startsS: Float = 0f,
        endsS: Float = durationS,
        confidence: Float = 0.9f,
        downbeat: Int = 0,
        complete: Boolean = true,
    ): TrackScan {
        val beat = 60f / bpm
        val beats = FloatArray((durationS / beat).toInt()) { it * beat }
        val rate = CURVE_RATE_HZ
        val frames = (durationS / FRAME_PERIOD).toInt()
        val tempo = ((bpm - PICK_BPM_LO) / (PICK_BPM_HI - PICK_BPM_LO)).coerceIn(0f, 1f)
        val perc = beatRateCurve(beats, frames)
        val raw = FloatArray(frames) { i ->
            val t = i * FRAME_PERIOD
            val energy = if (t >= startsS && t < endsS) 1f else 0f
            intensitySignal(energy, energy, tempo, perc[i])
        }
        val curve = decimate(centredMovingAverage(raw, 70), FRAME_PERIOD, rate)
        return TrackScan(
            durationS = durationS,
            bpm = bpm,
            confidence = confidence,
            beats = beats,
            accents = FloatArray(beats.size) { 0.5f },
            downbeat = downbeat,
            sections = listOf(ScanSection(0f, durationS, 0.5f)),
            intensity = IntensityProfile(
                sigLo = 0.2f, sigHi = 0.8f, dynamics = 0.5f, tilt = 0f,
                tempo = tempo, character = 0.5f,
                curve = curve, curveRateHz = rate,
            ),
            beatsPerBar = beatsPerBar,
            analysedS = if (complete) durationS else durationS / 2f,
        )
    }

    /**
     * The analyser smooths its curve over 1.4 s, so an edge in the music lands in
     * the curve up to ~0.7 s later (at the tail) or earlier (at the head). Both are
     * the conservative direction — a little less trimmed, never more.
     */
    private val smoothing = 0.75f

    // ── Where the music actually is ────────────────────────────────────────

    @Test
    fun `a fade-out is not part of the song`() {
        // 240 s of file, 232 s of music. Crossfading over the last 6 s of a track
        // like this mixes the new song into silence — which is the reported "cut".
        val s = scan(durationS = 240f, endsS = 232f)
        assertEquals(232f, SmartCrossfade.musicEndsAtS(s, 240f), smoothing)
    }

    @Test
    fun `the light show's tempo lift is not music`() {
        // The bug this whole fixture exists to state. A real scan's curve never
        // reaches zero: the intensity blend adds a steady lift for tempo and beat
        // rate, so a 128 BPM track reads well above any sane floor in dead
        // silence. Read the curve raw and a fade-out is never found; read the
        // loudness underneath it and it is.
        val s = scan(durationS = 240f, bpm = 128f, endsS = 232f)
        val raw = s.intensity!!.curve
        assertTrue(raw.last() > SmartCrossfade.SILENCE_FLOOR, "raw curve in silence was ${raw.last()}")
        assertEquals(232f, SmartCrossfade.musicEndsAtS(s, 240f), smoothing)
        // And the loudness itself really is zero there — not merely lower.
        val loud = loudnessCurve(s)!!
        assertEquals(0f, loud.last(), 0.01f)
        assertEquals(1f, loud[loud.size / 2], 0.02f)
    }

    @Test
    fun `the loudness blend inverts exactly`() {
        // Forward then back, across the range, both with and without the grid term.
        for (l in listOf(0f, 0.02f, 0.05f, 0.1f, 0.3f, 0.6f, 0.9f)) {
            for (tempo in listOf(0f, 0.5f, 1f)) {
                for (perc in listOf(0f, 0.4f, 0.8f)) {
                    val back = loudnessForIntensity(intensitySignal(l, l, tempo, perc), tempo, perc)
                    assertEquals(l, back, 0.001f, "l=$l tempo=$tempo perc=$perc")
                }
            }
        }
    }

    @Test
    fun `a track that ends cleanly ends where the file does`() {
        val s = scan(durationS = 240f)
        assertEquals(240f, SmartCrossfade.musicEndsAtS(s, 240f), 0.15f)
        // A slow one too: the tempo lift is smallest here, and the beat grid's
        // share of the floor is what a naive "subtract the tempo" would miss.
        assertEquals(240f, SmartCrossfade.musicEndsAtS(scan(durationS = 240f, bpm = 80f), 240f), 0.15f)
    }

    @Test
    fun `trimming is capped, so a quiet ending is never lopped off`() {
        // A scan claiming the last minute is silence is wrong about the song, not
        // right about the file. Past the cap it is ignored.
        val s = scan(durationS = 240f, endsS = 180f)
        assertEquals(
            240f - SmartCrossfade.maxTailTrimS(240f),
            SmartCrossfade.musicEndsAtS(s, 240f),
            0.15f,
        )
    }

    @Test
    fun `the tail cap scales with the track, so a real fade-out still counts`() {
        // Twelve seconds of fade-out on a four-minute song is ordinary, and a flat
        // cap short enough to protect a thirty-second interlude would have refused
        // to see it.
        assertEquals(228f, SmartCrossfade.musicEndsAtS(scan(durationS = 240f, endsS = 228f), 240f), smoothing)
        // The same twelve seconds off a forty-second interlude is the scan being
        // wrong about a third of the piece, and is refused.
        assertTrue(SmartCrossfade.maxTailTrimS(40f) < 12f)
    }

    @Test
    fun `a half-analysed track claims nothing about its own end`() {
        // The scan stopped at the analysis cap, so its curve running out says where
        // the *scan* stopped, not where the music did.
        val s = scan(durationS = 600f, endsS = 300f, complete = false)
        assertEquals(600f, SmartCrossfade.musicEndsAtS(s, 600f), 0.01f)
    }

    @Test
    fun `dead air at the front is trimmed, an intro is not`() {
        assertEquals(3f, SmartCrossfade.musicStartsAtS(scan(startsS = 3f)), smoothing)
        assertEquals(0f, SmartCrossfade.musicStartsAtS(scan()), 0.15f)
        // Ten seconds of "quiet" at the front is a song, not padding — capped.
        assertEquals(
            SmartCrossfade.MAX_HEAD_TRIM_S,
            SmartCrossfade.musicStartsAtS(scan(startsS = 10f)),
            0.15f,
        )
    }

    // ── How long, and where ────────────────────────────────────────────────

    @Test
    fun `the overlap is whole bars, nearest the setting`() {
        // 120 BPM 4/4: a bar is exactly 2 s, so the 2 s default is one bar.
        assertEquals(2f, SmartCrossfade.windowFor(scan(bpm = 120f), 2), 0.001f)
        // 5 s asked for is two and a half bars. Ties go short, so two bars.
        assertEquals(4f, SmartCrossfade.windowFor(scan(bpm = 120f), 5), 0.001f)
        // 7 s is three and a half bars, and rounds up to four the same way.
        assertEquals(6f, SmartCrossfade.windowFor(scan(bpm = 120f), 7), 0.001f)
        // A waltz has three beats to the bar and so a different answer entirely.
        assertEquals(1.5f, SmartCrossfade.windowFor(scan(bpm = 120f, beatsPerBar = 3), 2), 0.001f)
    }

    @Test
    fun `never less than one bar, however short the setting`() {
        // A bar at 60 BPM is four seconds. Asking for one does not get half of one.
        assertEquals(4f, SmartCrossfade.windowFor(scan(bpm = 60f), 1), 0.001f)
    }

    @Test
    fun `an unusable grid falls back to the plain number of seconds`() {
        val vague = scan(confidence = 0.05f)
        assertNull(SmartCrossfade.barSeconds(vague))
        assertEquals(3f, SmartCrossfade.windowFor(vague, 3), 0.001f)
    }

    @Test
    fun `the join snaps back to a downbeat`() {
        // 120 BPM, 4/4, downbeat on beat 0: bars start every 2 s.
        val s = scan(bpm = 120f)
        assertEquals(100f, SmartCrossfade.snapToDownbeat(s, 100.9f), 0.001f)
        assertEquals(100f, SmartCrossfade.snapToDownbeat(s, 101.9f), 0.001f)
        assertEquals(102f, SmartCrossfade.snapToDownbeat(s, 102.0f), 0.001f)
    }

    @Test
    fun `the downbeat is the one, not just any beat`() {
        // With the downbeat on beat 1, bars start at 0.5 s, 2.5 s, 4.5 s...
        val s = scan(bpm = 120f, downbeat = 1)
        assertEquals(100.5f, SmartCrossfade.snapToDownbeat(s, 102.0f), 0.001f)
    }

    @Test
    fun `no usable grid means no invented downbeat`() {
        val vague = scan(confidence = 0.05f)
        assertEquals(101.37f, SmartCrossfade.snapToDownbeat(vague, 101.37f), 0.001f)
    }

    // ── The plan ───────────────────────────────────────────────────────────

    @Test
    fun `standard overlaps the last N seconds, and nothing else`() {
        val plan = SmartCrossfade.plan(null, null, 240_000, 2, smart = false)
        assertNotNull(plan)
        assertEquals(238_000, plan.handOverAtMs)
        assertEquals(2f, plan.windowS, 0.001f)
        assertEquals(0L, plan.incomingStartMs)
        assertFalse(plan.smart)
    }

    @Test
    fun `smart leaves before the fade-out, on a downbeat`() {
        // Music stops at 232 s; one bar of overlap at 120 BPM is 2 s; 230 s is a
        // downbeat. Standard would have handed over at 238 s — six seconds into
        // material that has already gone.
        val out = scan(durationS = 240f, bpm = 120f, endsS = 232f)
        val plan = SmartCrossfade.plan(out, null, 240_000, 2, smart = true)
        assertNotNull(plan)
        assertTrue(plan.smart)
        assertEquals(230_000, plan.handOverAtMs, "expected the downbeat before the fade")
        assertEquals(2f, plan.windowS, 0.001f)
    }

    @Test
    fun `smart skips the next track's dead air`() {
        val out = scan(durationS = 240f)
        val incoming = scan(durationS = 200f, startsS = 2.5f)
        val plan = SmartCrossfade.plan(out, incoming, 240_000, 2, smart = true)
        assertNotNull(plan)
        // At or a little before the first note — never past it, which would be a
        // song starting late on every play.
        assertTrue(
            plan.incomingStartMs in (2_500 - (smoothing * 1000).toLong())..2_500,
            "expected to enter at the first note, got ${plan.incomingStartMs}ms",
        )
    }

    @Test
    fun `smart with no scan is standard`() {
        val plan = SmartCrossfade.plan(null, null, 240_000, 2, smart = true)
        assertNotNull(plan)
        assertFalse(plan.smart, "nothing to be smart with")
        assertEquals(238_000, plan.handOverAtMs)
    }

    @Test
    fun `a short track is not crossfaded at all`() {
        // Three windows is the floor, the same one the sequential fade has always had.
        assertNull(SmartCrossfade.plan(null, null, 5_000, 2, smart = false))
        assertNull(SmartCrossfade.plan(scan(durationS = 5f), null, 5_000, 2, smart = true))
    }

    @Test
    fun `off is off, and an unknown duration never plans one`() {
        assertNull(SmartCrossfade.plan(null, null, 240_000, 0, smart = true))
        assertNull(SmartCrossfade.plan(null, null, 0, 2, smart = true))
    }

    // ── Firing it ──────────────────────────────────────────────────────────

    @Test
    fun `the hand-over fires at the planned point and not before`() {
        val plan = MixPlan(handOverAtMs = 230_000, windowS = 2f)
        assertFalse(SmartCrossfade.shouldHandOver(229_999, plan))
        assertTrue(SmartCrossfade.shouldHandOver(230_000, plan))
        assertTrue(SmartCrossfade.shouldHandOver(231_500, plan))
        // Past the window there is no longer room to run the mix.
        assertFalse(SmartCrossfade.shouldHandOver(232_000, plan))
    }

    @Test
    fun `a streamed track gets a longer head start than a local one`() {
        val plan = MixPlan(handOverAtMs = 230_000, windowS = 2f)
        val local = SmartCrossfade.prerollFor(local = true)
        val stream = SmartCrossfade.prerollFor(local = false)
        assertTrue(stream > local, "a server across the house needs longer to spin up")
        assertFalse(SmartCrossfade.shouldArm(230_000 - stream - 1, plan, stream))
        assertTrue(SmartCrossfade.shouldArm(230_000 - stream, plan, stream))
        assertFalse(SmartCrossfade.shouldArm(230_000 - stream, plan, local))
    }

    @Test
    fun `a deck that never becomes ready is given up on before the track ends`() {
        val plan = MixPlan(handOverAtMs = 230_000, windowS = 8f)
        val deadline = SmartCrossfade.handOverDeadlineMs(plan)
        assertTrue(deadline > plan.handOverAtMs, "a slow deck gets some grace")
        assertTrue(
            deadline < plan.handOverAtMs + plan.windowMs,
            "but not so much that the mix would run off the end of the track",
        )
    }

    // ── The curves ─────────────────────────────────────────────────────────

    @Test
    fun `the incoming ramp is measured from where the needle actually dropped`() {
        // Entered at 2.5 s into the track: at 2.5 s it is silent, not already up.
        assertEquals(0f, SmartCrossfade.fadeInAt(2_500, 2_500, 2f), 0.0001f)
        assertEquals(1f, SmartCrossfade.fadeInAt(4_500, 2_500, 2f), 0.0001f)
        assertTrue(SmartCrossfade.fadeInAt(3_500, 2_500, 2f) > 0.6f)
    }

    @Test
    fun `the two sides sum to constant power, which is the whole point`() {
        for (i in 0..24) {
            val x = i / 24f
            val incoming = SmartCrossfade.fadeInAt((x * 2_000).toLong(), 0, 2f)
            val outgoing = SmartCrossfade.fadeOutAt(x)
            val power = incoming * incoming + outgoing * outgoing
            assertTrue(
                abs(power - 1f) < 0.01f,
                "power dipped to $power a fraction $x through the mix",
            )
        }
    }

    @Test
    fun `the incoming ramp never goes backwards`() {
        var last = -1f
        for (ms in 0..2_000 step 40) {
            val v = SmartCrossfade.fadeInAt(ms.toLong(), 0, 2f)
            assertTrue(v >= last, "the fade-in went backwards at ${ms}ms")
            last = v
        }
    }
}
