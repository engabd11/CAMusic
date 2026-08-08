package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Predictive beat grid: tempo lock, phase accuracy, and graceful unlock.
 *
 * Ported from syncoV2 `tests/test_tempo.py`, and deliberately end-to-end: a
 * synthetic click track goes through the real [AudioAnalyzer] rather than
 * hand-fed flux values. That makes these tests cover the FFT, the filterbank and
 * the onset detector as well as the tracker — if the spectrum were wrong again,
 * the tempo would not lock and this would say so.
 */
class TempoTrackerTest {

    private val sr = ANALYSIS_SAMPLE_RATE

    /** A 60 Hz thump with a fast decay on every beat — a kick drum, roughly. */
    private fun clickTrack(bpm: Float, seconds: Float): FloatArray {
        val sig = FloatArray((sr * seconds).toInt())
        val period = 60f / bpm
        val envLen = (0.1f * sr).toInt()
        var bt = 0.25f
        while (bt < seconds) {
            val i = (bt * sr).toInt()
            for (k in 0 until min(envLen, sig.size - i)) {
                val env = exp(-k.toFloat() / (0.03f * sr))
                sig[i + k] += (sin(2.0 * PI * 60.0 * k / sr) * env).toFloat()
            }
            bt += period
        }
        var peak = 0f
        for (v in sig) peak = maxOf(peak, abs(v))
        if (peak > 0f) for (i in sig.indices) sig[i] = sig[i] / peak * 0.9f
        return sig
    }

    private class Step(val frame: AnalysisFrame, val grid: BeatGrid)

    private fun run(sig: FloatArray): List<Step> {
        val a = AudioAnalyzer()
        val tt = TempoTracker(a.framePeriod)
        val out = ArrayList<Step>()
        val hop = FloatArray(ANALYSIS_HOP)
        for (k in 0 until sig.size / ANALYSIS_HOP) {
            System.arraycopy(sig, k * ANALYSIS_HOP, hop, 0, ANALYSIS_HOP)
            val f = a.push(hop)
            out.add(Step(f, tt.update(f.tAudio, f.flux, f.beat, f.beatStrength)))
        }
        return out
    }

    @Test
    fun `tempo locks across the common range`() {
        for (bpm in listOf(90f, 120f, 140f, 174f)) {
            val steps = run(clickTrack(bpm, 12f))
            val final = steps.last().grid
            assertTrue(final.locked, "$bpm BPM never locked (confidence ${final.confidence})")
            assertTrue(
                abs(final.bpm - bpm) / bpm < 0.06f,  // lag quantisation
                "$bpm BPM tracked as ${final.bpm}",
            )
        }
    }

    @Test
    fun `phase aligns to real beats`() {
        // Once locked, the beat clock should be near a boundary at the moments
        // the audio actually has a beat. This is the property the whole show
        // rests on: a wave fired on the predicted beat has to peak on the kick.
        val steps = run(clickTrack(120f, 14f))
        val period = steps.last().grid.periodS
        val errs = ArrayList<Float>()
        for (s in steps.drop(steps.size / 2)) {
            if (s.frame.beat && s.grid.locked) errs.add(min(s.grid.phase, 1f - s.grid.phase))
        }
        assertTrue(errs.isNotEmpty(), "expected detected beats after lock")
        val mean = errs.sum() / errs.size
        assertTrue(mean < 0.2f, "mean phase error $mean of a beat")
        assertTrue(period in 0.45f..0.55f, "120 BPM should be ~0.5 s per beat, got $period")
    }

    @Test
    fun `the next beat is always in the future and within one period`() {
        val steps = run(clickTrack(128f, 10f))
        for (s in steps.takeLast(50)) {
            if (!s.grid.locked) continue
            assertTrue(s.grid.timeToNextBeat > 0f, "next beat is in the past")
            assertTrue(
                s.grid.timeToNextBeat <= s.grid.periodS + 1e-3f,
                "next beat is more than a period away",
            )
            assertTrue(
                abs(s.grid.nextBeatT - (s.frame.tAudio + s.grid.timeToNextBeat)) < 1e-3f,
                "nextBeatT disagrees with timeToNextBeat",
            )
        }
    }

    @Test
    fun `noise does not lock`() {
        // The point of the confidence gate. Locking onto noise would drive
        // scheduled pulses off a grid that means nothing.
        val rnd = java.util.Random(7)
        val sig = FloatArray((sr * 12f).toInt()) { (rnd.nextGaussian() * 0.2).toFloat() }
        val steps = run(sig)
        assertFalse(steps.last().grid.locked, "locked onto white noise")
    }

    @Test
    fun `silence never locks and reports no tempo`() {
        val steps = run(FloatArray((sr * 6f).toInt()))
        val final = steps.last().grid
        assertFalse(final.locked)
        assertTrue(final.confidence <= 0.01f, "confidence ${final.confidence} on silence")
    }

    @Test
    fun `a tempo change is tracked`() {
        // Songs speed up and slow down; the period is smoothed toward each new
        // measurement rather than latched at whatever the intro was.
        val a = clickTrack(100f, 10f)
        val b = clickTrack(140f, 12f)
        val steps = run(a + b)
        val final = steps.last().grid
        assertTrue(final.locked, "lost lock across the tempo change")
        assertTrue(abs(final.bpm - 140f) / 140f < 0.10f, "still reporting ${final.bpm} after the change")
    }

    @Test
    fun `schedule strength ramps with confidence and stays bounded`() {
        val steps = run(clickTrack(120f, 12f))
        for (s in steps) {
            assertTrue(
                s.grid.scheduleStrength in 0f..1f,
                "scheduleStrength ${s.grid.scheduleStrength} out of range",
            )
        }
        // A solid lock on a clean click track should be driving pulses at full.
        assertTrue(
            steps.last().grid.scheduleStrength > 0.9f,
            "a confident lock should pulse at full, got ${steps.last().grid.scheduleStrength}",
        )
    }

    @Test
    fun `beat in bar cycles and phase stays in range`() {
        val steps = run(clickTrack(120f, 12f))
        val seen = HashSet<Int>()
        for (s in steps) {
            assertTrue(s.grid.phase in 0f..1f, "phase ${s.grid.phase} out of range")
            assertTrue(s.grid.barPhase in 0f..1f, "barPhase ${s.grid.barPhase} out of range")
            assertTrue(s.grid.beatInBar in 0..3, "beatInBar ${s.grid.beatInBar} out of range")
            if (s.grid.locked) seen.add(s.grid.beatInBar)
        }
        assertTrue(seen.size == 4, "expected all four beats of the bar, saw $seen")
    }

    @Test
    fun `predicted beats arrive at about the tempo`() {
        val steps = run(clickTrack(120f, 12f))
        val locked = steps.filter { it.grid.locked }
        val ticks = locked.count { it.grid.predictedBeat }
        val span = locked.last().frame.tAudio - locked.first().frame.tAudio
        val expected = span / 0.5f  // 120 BPM
        assertTrue(
            abs(ticks - expected) / expected < 0.1f,
            "clock ticked $ticks times over ${span}s, expected ~$expected",
        )
    }

    @Test
    fun `reset clears the lock`() {
        val a = AudioAnalyzer()
        val tt = TempoTracker(a.framePeriod)
        val sig = clickTrack(120f, 12f)
        val hop = FloatArray(ANALYSIS_HOP)
        var last = BeatGrid()
        for (k in 0 until sig.size / ANALYSIS_HOP) {
            System.arraycopy(sig, k * ANALYSIS_HOP, hop, 0, ANALYSIS_HOP)
            val f = a.push(hop)
            last = tt.update(f.tAudio, f.flux, f.beat, f.beatStrength)
        }
        assertTrue(last.locked, "precondition: should be locked before reset")

        tt.reset()
        val after = tt.update(0f, 0f, false, 0f)
        assertFalse(after.locked, "still locked after reset")
        assertTrue(after.bpm == 0f, "bpm survived reset")
    }
}
