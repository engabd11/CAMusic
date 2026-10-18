package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.hue.Rgb
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerceptualTest {

    @Test
    fun `the curve keeps black and white and bends the middle`() {
        assertEquals(0f, Perceptual.toLinear(0f), 1e-6f)
        assertEquals(1f, Perceptual.toLinear(1f), 1e-4f)
        // L* 50 is about 18 percent linear light, the classic mid grey.
        assertEquals(0.184f, Perceptual.toLinear(0.5f), 0.005f)
    }

    @Test
    fun `it is monotonic and round-trips`() {
        var prev = -1f
        for (i in 0..100) {
            val p = i / 100f
            val y = Perceptual.toLinear(p)
            assertTrue(y >= prev, "not monotonic at $p")
            prev = y
            assertEquals(p, Perceptual.fromLinear(y), 1e-3f)
        }
    }

    @Test
    fun `a dim step is bigger in perceived units than the same linear step`() {
        // Why the scenes work in perceived units: 0.02 linear near black is a huge jump.
        val low = Perceptual.fromLinear(0.04f) - Perceptual.fromLinear(0.02f)
        val high = Perceptual.fromLinear(0.94f) - Perceptual.fromLinear(0.92f)
        assertTrue(low > 5 * high, "low $low high $high")
    }

    @Test
    fun `encoding keeps the hue`() {
        val c = Perceptual.encode(Rgb(0.6f, 0.3f, 0.0f))
        assertEquals(2f, c.first / c.second, 1e-3f)
        assertEquals(0f, c.third, 1e-6f)
        assertEquals(Perceptual.toLinear(0.6f), c.first, 1e-4f)
        assertEquals(Rgb(0f, 0f, 0f), Perceptual.encode(Rgb(0f, 0f, 0f)))
    }
}

class ComfortLimiterTest {

    private fun frame(level: Float, vararg ids: Int): Map<Int, Rgb> =
        ids.associateWith { Rgb(level, level * 0.5f, 0f) }

    private val dt = 1f / 60f

    @Test
    fun `a lamp rises no faster than the profile allows`() {
        val l = ComfortLimiter(ComfortProfile(risePerS = 0.6f, fallPerS = 0.4f, roomSwingPerS = 10f))
        var prev = 0f
        // First frame starts from dark, so even this is limited.
        repeat(120) {
            val out = l.process(frame(0.8f, 1), dt, burst = false).getValue(1)
            val v = Perceptual.levelOf(out)
            assertTrue(v - prev <= 0.6f * dt + 1e-5f, "rose ${v - prev} in a frame")
            prev = v
        }
        assertEquals(0.8f, prev, 1e-4f)
    }

    @Test
    fun `a lamp falls no faster than the profile allows`() {
        val l = ComfortLimiter(ComfortProfile(risePerS = 5f, fallPerS = 0.4f, roomSwingPerS = 10f))
        repeat(30) { l.process(frame(0.8f, 1), dt, false) }
        var prev = 0.8f
        repeat(60) {
            val v = Perceptual.levelOf(l.process(frame(0f, 1), dt, false).getValue(1))
            assertTrue(prev - v <= 0.4f * dt + 1e-5f, "fell ${prev - v} in a frame")
            prev = v
        }
    }

    @Test
    fun `the room mean is capped even when every lamp is within its own limit`() {
        val l = ComfortLimiter(ComfortProfile(risePerS = 0.6f, fallPerS = 0.6f, roomSwingPerS = 0.1f))
        val ids = intArrayOf(1, 2, 3, 4, 5, 6)
        var prevMean = 0f
        repeat(120) {
            val out = l.process(frame(0.8f, *ids), dt, false)
            val mean = out.values.map { Perceptual.levelOf(it) }.average().toFloat()
            assertTrue(mean - prevMean <= 0.1f * dt + 1e-5f, "mean moved ${mean - prevMean}")
            prevMean = mean
        }
    }

    @Test
    fun `a named event may exceed the calm limits within the burst limits`() {
        val p = ComfortProfile(risePerS = 0.5f, fallPerS = 0.5f, roomSwingPerS = 0.3f,
            burstRisePerS = 30f, burstFallPerS = 10f, burstRoomSwingPerS = 50f)
        val l = ComfortLimiter(p)
        repeat(60) { l.process(frame(0.1f, 1), dt, false) }
        val v = Perceptual.levelOf(l.process(frame(0.9f, 1), dt, burst = true).getValue(1))
        assertTrue(v - 0.1f > 0.5f * dt * 3, "burst was not allowed through ($v)")
        assertTrue(v - 0.1f <= 30f * dt + 1e-4f, "burst exceeded its own limit ($v)")
    }

    @Test
    fun `hue is preserved and a lamp easing to black keeps its colour`() {
        val l = ComfortLimiter(ComfortProfile(risePerS = 5f, fallPerS = 0.3f, roomSwingPerS = 5f))
        repeat(30) { l.process(frame(0.6f, 1), dt, false) }
        val eased = l.process(mapOf(1 to Rgb(0f, 0f, 0f)), dt, false).getValue(1)
        assertTrue(eased.first > 0f)
        assertEquals(2f, eased.first / eased.second, 1e-3f)
    }
}

class AmbienceBedTimelineTest {

    private fun collect(tl: AmbienceBedTimeline, from: Double, to: Double): List<Double> {
        val out = ArrayList<Double>()
        tl.forEachOnset(from, to) { _, at -> out.add(at) }
        return out
    }

    private val tl = AmbienceBedTimeline.synthetic(
        durationS = 30.0,
        onsets = listOf(SyntheticOnset(2.0), SyntheticOnset(10.0), SyntheticOnset(29.0)),
    )

    @Test
    fun `a window sees the onsets inside it, ahead of the playhead`() {
        assertEquals(listOf(10.0), collect(tl, 6.0, 12.0))
        assertEquals(listOf(2.0, 10.0), collect(tl, 0.0, 12.0))
        assertTrue(collect(tl, 3.0, 9.0).isEmpty())
    }

    @Test
    fun `lookahead wraps across the loop seam in unwrapped time`() {
        // Playhead at 27 s looking 4 s ahead reaches the next pass's 2.0 s onset.
        assertEquals(listOf(29.0, 32.0), collect(tl, 27.0, 31.0 + 2.5))
        // And a later pass keeps counting.
        assertEquals(listOf(59.0, 62.0), collect(tl, 58.0, 63.0))
    }

    @Test
    fun `levels are continuous across the seam`() {
        val a = BedSample(); val b = BedSample()
        tl.levelsAt(29.95, a)
        tl.levelsAt(30.05, b)
        assertEquals(a.level, b.level, 1e-4f)
    }

    @Test
    fun `it survives being written and read, and rejects rubbish`() {
        val bytes = ByteArrayOutputStream().also { tl.write(DataOutputStream(it)) }.toByteArray()
        val back = AmbienceBedTimeline.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertNotNull(back)
        assertEquals(listOf(2.0, 10.0), collect(back, 0.0, 12.0))
        assertEquals(30.0, back.durationS, 1e-9)
        assertNull(AmbienceBedTimeline.read(DataInputStream(ByteArrayInputStream(ByteArray(64)))))
    }

    @Test
    fun `the builder turns frames into onsets`() {
        val b = AmbienceBedTimeline.Builder()
        val quiet = AnalysisFrame(energy = 0.05f)
        repeat(100) { b.consume(quiet, (it + 1) * 0.02) }
        assertEquals(0, b.build(2.0).onsetCount)
    }
}

/**
 * The session replaying a plan: the point of the whole timeline.
 */
class AmbiencePlanSessionTest {

    /** A recording whose heard position is whatever the test says, looping at [loopS]. */
    private class FakeAnalysis : AmbienceAudioAnalysis {
        @Volatile var media = 0.0
        override fun heardMediaS() = media
        override fun onFrame(consumer: ((AnalysisFrame, Double) -> Unit)?) {}
    }

    /** Records every onset it is handed, and when (by the clock of its last render). */
    private class Spy : AmbienceScript {
        override val effect = AmbienceEffect.THUNDERSTORM
        override val eventsComeFromAudio = true
        var now = 0.0
        val got = ArrayList<Triple<Double, Float, Double>>()   // atS, aheadS, time received
        var resets = 0
        override fun bind(room: RoomModel, params: AmbienceParams) {}
        override fun retune(params: AmbienceParams) {}
        override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {}
        override fun onBedReset() { resets++ }
        override fun react(sample: BedSample, onset: BedOnset, emit: (AmbienceEvent) -> Unit) {
            got.add(Triple(onset.atS, onset.aheadS, now))
        }
        override fun renderLights(tS: Double, live: Array<AmbienceEvent?>, n: Int, out: MutableMap<Int, Rgb>) {
            now = tS
            out[0] = Rgb(0.2f, 0.2f, 0.2f)
        }
        override fun renderAudio(out: FloatArray, frames: Int, startS: Double, sampleRate: Int, live: Array<AmbienceEvent?>, n: Int) {}
    }

    private fun session(spy: Spy, a: FakeAnalysis, tl: AmbienceBedTimeline): AmbienceSession {
        val s = AmbienceSession(
            script = spy, room = RoomModel(linearRoom(3)), sink = null, focus = null,
            params = AmbienceParams(), analysis = a, bedPlan = tl,
        )
        assertTrue(s.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)))
        return s
    }

    @Test
    fun `onsets reach the script seconds before they are heard`() {
        val tl = AmbienceBedTimeline.synthetic(60.0, listOf(SyntheticOnset(20.0)))
        val a = FakeAnalysis(); val spy = Spy(); val s = session(spy, a, tl)
        var t = 0.0
        while (t < 25.0) { a.media = t; s.renderLights(1f / 60f); t += 1.0 / 60 }
        assertEquals(1, spy.got.size)
        val (at, ahead, receivedAt) = spy.got[0]
        assertEquals(20.0, at, 1e-6)
        assertTrue(ahead in 3.0f..4.1f, "delivered only ${ahead}s ahead")
        assertTrue(at - receivedAt in 3.0..4.1, "delivered ${at - receivedAt}s ahead of the playhead")
    }

    @Test
    fun `a loop keeps the clock moving forward and delivers the next pass early`() {
        val tl = AmbienceBedTimeline.synthetic(30.0, listOf(SyntheticOnset(2.0), SyntheticOnset(29.0)))
        val a = FakeAnalysis(); val spy = Spy(); val s = session(spy, a, tl)
        var t = 0.0
        val times = ArrayList<Double>()
        while (t < 70.0) {
            a.media = t % 30.0
            s.renderLights(1f / 60f)
            times.add(spy.now)
            t += 1.0 / 60
        }
        for (i in 1 until times.size) assertTrue(times[i] >= times[i - 1], "the show clock went backwards")
        // 2, 29, 32, 59, 62 within 70 s plus lookahead (66 reaches 62 only; 69+ not yet).
        val ats = spy.got.map { it.first }
        assertEquals(listOf(2.0, 29.0, 32.0, 59.0, 62.0), ats.take(5))
        // The pass-two onset at 32 was delivered while pass one was still playing.
        val early = spy.got[2]
        assertTrue(early.third < 30.0, "the next pass's onset arrived only after the seam (${early.third})")
        assertEquals(0, spy.resets - 1, "a loop must not reset the script (only the first start does)")
    }

    @Test
    fun `a seek drops what was scheduled and starts again`() {
        val tl = AmbienceBedTimeline.synthetic(120.0, listOf(SyntheticOnset(20.0), SyntheticOnset(90.0)))
        val a = FakeAnalysis(); val spy = Spy(); val s = session(spy, a, tl)
        var t = 0.0
        while (t < 10.0) { a.media = t; s.renderLights(1f / 60f); t += 1.0 / 60 }
        val before = spy.resets
        t = 80.0
        while (t < 95.0) { a.media = t; s.renderLights(1f / 60f); t += 1.0 / 60 }
        assertTrue(spy.resets > before, "a seek did not reset the script")
        assertTrue(spy.got.any { abs(it.first - 20.0) < 0.5 }, "the onset after the seek never arrived (${spy.got})")
        val clock = spy.now
        assertTrue(clock > 10.0 && clock < 30.0, "the show clock jumped with the seek ($clock)")
    }

    @Test
    fun `the session paints perceived brightness as linear light and fades`() {
        val tl = AmbienceBedTimeline.synthetic(60.0, emptyList())
        val a = FakeAnalysis(); val spy = Spy(); val s = session(spy, a, tl)
        var out: Map<Int, Rgb>? = null
        // The script paints 0.2 perceived; the limiter fades up from dark, so wait.
        var t = 0.0
        while (t < 5.0) { a.media = t; out = s.renderLights(1f / 60f); t += 1.0 / 60 }
        assertEquals(Perceptual.toLinear(0.2f), out!!.getValue(0).first, 1e-3f)
        s.setFade(0.5f)
        while (t < 15.0) { a.media = t; out = s.renderLights(1f / 60f); t += 1.0 / 60 }
        assertEquals(Perceptual.toLinear(0.1f), out!!.getValue(0).first, 1e-3f)
        assertTrue(abs(Perceptual.toLinear(0.2f) - Perceptual.toLinear(0.1f)) > 0.01f)
    }
}
