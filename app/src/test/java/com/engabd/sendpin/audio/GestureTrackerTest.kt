package com.engabd.sendpin.audio

import java.util.Random
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the detector can tell a sound that moves from a sound that does not.
 *
 * Half of these tests are about *not* firing, deliberately. The hard requirement
 * behind this feature is that most music has neither a stereo traversal nor a
 * linear swell in it, and a gesture that fires anyway is worse than one that
 * never fires — it makes every track look like the ones it is supposed to
 * distinguish. A detector that catches every sweep and also fires on a mix that
 * leans left has failed.
 *
 * Synthetic frames, driven hop by hop, following `StructureTrackerTest`'s shape.
 * What these cannot say is how often a real traversal actually occurs; that is
 * what the log line in `DirectLightSync` is for.
 */
class GestureTrackerTest {

    private val dt = 0.02f
    private val bins = MELBANK_BINS

    /**
     * One analysis frame. [panAt] gives each bin's stereo position and [melAt]
     * its level, so a test can move one bin and hold the rest still.
     */
    private fun frame(
        i: Int,
        panAt: (Int) -> Float,
        melAt: (Int) -> Float = { 0.5f },
        energy: Float = 0.5f,
        centroid: Float = 0.35f,
        flux: Float = 0.05f,
        beat: Boolean = false,
    ) = AnalysisFrame(
        energy = energy,
        beat = beat,
        bassBeat = beat,
        flux = flux,
        centroid = centroid,
        tAudio = i * dt,
        melbank = FloatArray(bins) { melAt(it) },
        pan = FloatArray(bins) { panAt(it) },
    )

    /** Run [n] frames and return every gesture launched, in order. */
    private fun run(
        n: Int,
        tracker: GestureTracker = GestureTracker(dt),
        structure: StructureState? = null,
        build: (Int) -> AnalysisFrame,
    ): List<GestureState> {
        val out = ArrayList<GestureState>()
        var lastId = 0L
        for (i in 0 until n) {
            val g = tracker.update(build(i), structure)
            if (g.kind != GestureKind.NONE && g.id != lastId) { lastId = g.id; out.add(g) }
        }
        return out
    }

    /**
     * A source occupying bins 6–9 that walks from hard left to hard right over
     * two seconds, against a mix that stays centred.
     */
    private fun sweepFrame(
        i: Int,
        durationS: Float = 2f,
        spanBins: IntRange = 6..9,
        /** Frame index driving the *sound*, when it differs from the clock. */
        phase: Int = i,
    ): AnalysisFrame {
        val t = (phase * dt / durationS).coerceIn(0f, 1f)
        val p = -0.9f + 1.8f * t
        return frame(
            i,
            panAt = { b -> if (b in spanBins) p else 0f },
            melAt = { b -> if (b in spanBins) 0.6f else 0.25f },
        )
    }

    // ── Traversal: it should fire ─────────────────────────────────────────

    @Test
    fun `a source sweeping across the stereo field is a traversal`() {
        val hits = run(200) { sweepFrame(it) }
        assertTrue(hits.isNotEmpty(), "a hard-left to hard-right sweep produced no gesture at all")
        val g = hits.first()
        assertEquals(GestureKind.TRAVERSAL, g.kind)
        assertTrue(g.fromPan < -0.3f, "the sweep was reported as starting at pan ${g.fromPan}")
        assertTrue(g.toPan > 0.3f, "the sweep was reported as ending at pan ${g.toPan}")
        assertTrue(g.strength > 0f && g.strength <= 1f, "strength was ${g.strength}")
    }

    /** Speed comes from the sound: a slow pad and a fast riser must differ. */
    @Test
    fun `a slow sweep is reported as longer than a fast one`() {
        val fast = run(200) { sweepFrame(it, durationS = 0.8f) }
        val slow = run(400) { sweepFrame(it, durationS = 3.5f) }
        assertTrue(fast.isNotEmpty() && slow.isNotEmpty(), "one of the two sweeps did not fire")
        assertTrue(
            slow.first().durationS > fast.first().durationS,
            "a 3.5 s sweep (${slow.first().durationS}s) was not longer than a 0.8 s one (${fast.first().durationS}s)",
        )
    }

    // ── Traversal: it should not fire ─────────────────────────────────────

    /**
     * The common-mode trap, and the one this feature is most likely to fail on in
     * the field. A mix that is simply louder on one side moves every bin's pan
     * together, and that is a mix leaning, not a source travelling.
     */
    @Test
    fun `a whole mix drifting off-centre is not a traversal`() {
        val hits = run(400) { i ->
            val t = (i * dt / 3f).coerceIn(0f, 1f)
            val p = -0.9f + 1.8f * t
            frame(i, panAt = { p })   // every bin, together
        }
        assertTrue(hits.isEmpty(), "a whole-mix lean fired ${hits.size} gestures")
    }

    /** A wobble ends where it started, so its monotonicity is near zero. */
    @Test
    fun `a panning wobble is not a traversal`() {
        val hits = run(600) { i ->
            val p = 0.8f * sin(2.0 * Math.PI * i * dt / 1.2).toFloat()
            frame(i, panAt = { b -> if (b in 6..9) p else 0f }, melAt = { b -> if (b in 6..9) 0.6f else 0.25f })
        }
        assertTrue(hits.isEmpty(), "a 1.2 s pan wobble fired ${hits.size} gestures")
    }

    /** A bin that went silent and came back somewhere else cut; it did not travel. */
    @Test
    fun `a source that cuts to the other side is not a traversal`() {
        val hits = run(400) { i ->
            val t = i * dt
            val left = t < 2f
            val hot = if (t < 1.6f || t > 2.4f) 0.6f else 0.0f
            frame(
                i,
                panAt = { b -> if (b in 6..9) (if (left) -0.9f else 0.9f) else 0f },
                melAt = { b -> if (b in 6..9) hot else 0.25f },
            )
        }
        assertTrue(hits.isEmpty(), "a hard cut across the stereo field fired ${hits.size} gestures")
    }

    /**
     * Mono material. The tap folds a mono source onto both sides on purpose, so
     * `pan` arrives as exact zeros rather than empty — nothing else in the
     * pipeline distinguishes that from a perfectly centred mix.
     */
    @Test
    fun `mono material never moves`() {
        val tracker = GestureTracker(dt)
        val hits = run(400, tracker) { i -> frame(i, panAt = { 0f }) }
        assertTrue(hits.isEmpty(), "mono input fired ${hits.size} gestures")
        assertEquals(0f, tracker.stereo, 1e-4f, "mono input reported stereo spread ${tracker.stereo}")
    }

    /** Silence is the analyzer saying it had nothing to measure, not a still sound. */
    @Test
    fun `silence produces nothing and does not corrupt the history`() {
        val tracker = GestureTracker(dt)
        for (i in 0 until 100) tracker.update(AnalysisFrame(tAudio = i * dt), null)
        val hits = run(200, tracker) { i -> sweepFrame(i) }
        assertTrue(hits.isNotEmpty(), "a sweep after a silent passage no longer fired")
    }

    /** A lone bin drifting is far more likely to be the per-bin AGC than a sound. */
    @Test
    fun `a single bin drifting on its own is not a traversal`() {
        val hits = run(300) { i ->
            val t = (i * dt / 2f).coerceIn(0f, 1f)
            val p = -0.9f + 1.8f * t
            frame(i, panAt = { b -> if (b == 7) p else 0f }, melAt = { b -> if (b == 7) 0.6f else 0.25f })
        }
        assertTrue(hits.isEmpty(), "one bin moving alone fired ${hits.size} gestures")
    }

    // ── Swell ─────────────────────────────────────────────────────────────

    @Test
    fun `a linear rise with no beats is a swell`() {
        val building = StructureState(phase = SongPhase.BUILDING, buildProgress = 0.8f)
        val hits = run(250, structure = building) { i ->
            val t = i * dt
            frame(i, panAt = { 0f }, energy = (0.2f + 0.12f * t).coerceAtMost(1f),
                centroid = (0.2f + 0.06f * t).coerceAtMost(1f), flux = 0.05f)
        }
        assertTrue(hits.isNotEmpty(), "a clean crescendo produced no gesture")
        assertEquals(GestureKind.SWELL, hits.first().kind)
        assertTrue(hits.first().rise > 0f, "a brightening riser reported no centroid rise")
    }

    /** The same ramp with drums under it is a section getting louder, not a swell. */
    @Test
    fun `a rise with beats under it is not a swell`() {
        val building = StructureState(phase = SongPhase.BUILDING, buildProgress = 0.8f)
        val hits = run(250, structure = building) { i ->
            val t = i * dt
            frame(i, panAt = { 0f }, energy = (0.2f + 0.12f * t).coerceAtMost(1f),
                flux = 0.6f, beat = i % 25 == 0)
        }
        assertTrue(hits.isEmpty(), "a beat-driven rise fired ${hits.size} gestures")
    }

    // ── Restraint ─────────────────────────────────────────────────────────

    /**
     * The most important test here. Ordinary music with a bit of stereo width and
     * a bit of movement in it must produce nothing at all.
     */
    @Test
    fun `steady music never false triggers`() {
        val rng = Random(0)
        val hits = run(3000) { i ->
            val t = i * dt
            frame(
                i,
                panAt = { b -> 0.35f * sin((t * 0.9f + b * 0.7f).toDouble()).toFloat() + 0.1f * (rng.nextFloat() - 0.5f) },
                melAt = { 0.4f + 0.2f * rng.nextFloat() },
                energy = 0.5f + 0.08f * (rng.nextFloat() * 2f - 1f),
                flux = 0.3f + 0.2f * rng.nextFloat(),
                beat = i % 24 == 0,
            )
        }
        assertTrue(hits.isEmpty(), "a minute of steady music fired ${hits.size} gestures")
    }

    /**
     * A source that crosses the room every six seconds, alternating direction so
     * that each crossing is a genuine one-way trip rather than a sawtooth that
     * snaps back. About ten traversals a minute — far more than any real track,
     * and exactly the pathological input the budget exists for.
     */
    private fun pacingFrame(i: Int): AnalysisFrame {
        val cycle = i / 300
        val t = ((i % 300) * dt / 2f).coerceIn(0f, 1f)
        val dir = if (cycle % 2 == 0) 1f else -1f
        val p = dir * (-0.9f + 1.8f * t)
        return frame(
            i,
            panAt = { b -> if (b in 6..9) p else 0f },
            melAt = { b -> if (b in 6..9) 0.6f else 0.25f },
        )
    }

    @Test
    fun `the budget caps a track that sweeps forever`() {
        val tracker = GestureTracker(dt)
        val hits = run(6000, tracker) { pacingFrame(it) }
        assertTrue(hits.size <= 12, "an endlessly sweeping track fired ${hits.size} gestures in two minutes")
        assertTrue(tracker.budgetHit, "the budget was never reported as hit")
    }

    /**
     * Two overlapping sweeps read as noise, so while one is running nothing else
     * may start — and once it ends there is a quiet period on top of that.
     */
    @Test
    fun `gestures cannot overlap or crowd each other`() {
        val tracker = GestureTracker(dt)
        val fired = ArrayList<Float>()
        var lastId = 0L
        for (i in 0 until 2400) {
            val g = tracker.update(pacingFrame(i), null)
            if (g.kind != GestureKind.NONE && g.id != lastId) { lastId = g.id; fired.add(i * dt) }
        }
        assertTrue(fired.size >= 2, "only ${fired.size} gestures fired, so spacing was never tested")
        for (k in 1 until fired.size) {
            val gap = fired[k] - fired[k - 1]
            assertTrue(gap >= 2f, "two gestures fired ${"%.2f".format(gap)}s apart")
        }
    }

    /**
     * The sawtooth the budget test deliberately avoids: a source that crosses
     * left to right and then *snaps* back to start again. It came back, so it is
     * an oscillation however monotone each leg looks on its own.
     */
    @Test
    fun `a sweep that keeps restarting from the same side stops firing`() {
        val hits = run(3000) { i -> sweepFrame(i, phase = i % 150) }
        assertTrue(hits.size <= 2, "a repeating sawtooth pan fired ${hits.size} gestures in a minute")
    }

    @Test
    fun `reset clears state between tracks`() {
        val tracker = GestureTracker(dt)
        run(200, tracker) { sweepFrame(it) }
        tracker.reset()
        assertEquals(0f, tracker.stereo, 1e-6f)
        assertTrue(!tracker.budgetHit)
        assertEquals(GestureKind.NONE, tracker.update(AnalysisFrame(), null).kind)
    }

    /** Every field the renderer reads must be usable, whatever the input. */
    @Test
    fun `reported values stay in range`() {
        val rng = Random(7)
        val tracker = GestureTracker(dt)
        var worst = 0f
        for (i in 0 until 2000) {
            val g = tracker.update(
                frame(
                    i,
                    panAt = { rng.nextFloat() * 2f - 1f },
                    melAt = { rng.nextFloat() },
                    energy = rng.nextFloat(),
                    centroid = rng.nextFloat(),
                    flux = rng.nextFloat(),
                    beat = rng.nextInt(20) == 0,
                ),
                null,
            )
            assertTrue(g.strength in 0f..1f, "strength ${g.strength}")
            assertTrue(g.fromPan in -1f..1f && g.toPan in -1f..1f, "pan ${g.fromPan}..${g.toPan}")
            assertTrue(g.durationS >= 0f && g.durationS <= 6f, "duration ${g.durationS}")
            assertTrue(g.rise in 0f..1f, "rise ${g.rise}")
            worst = max(worst, g.strength)
        }
        assertTrue(tracker.stereo in 0f..1f, "stereo ${tracker.stereo}")
    }
}
