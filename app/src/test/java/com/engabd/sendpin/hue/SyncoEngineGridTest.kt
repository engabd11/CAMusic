package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.StructureState
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the beat grid and the structure tracker actually buy the show.
 *
 * The engine used to be purely reactive: a beat was detected, then the lights
 * responded. These tests pin the behaviour that only a *predictive* model can
 * give — a wavefront that is already crossing the room when the kick lands, a
 * bar that has a shape, and a build that tightens before it releases.
 */
class SyncoEngineGridTest {

    private val dt = 1f / 60f

    private fun channels(n: Int) = (0 until n).map { i ->
        EntertainmentChannel(
            channelId = i,
            position = ChannelPosition(
                x = -1f + 2f * i / max(1, n - 1),
                y = if (i % 2 == 0) -1f else 1f,
                z = if (i < n / 2) 0f else 1f,
            ),
        )
    }

    private fun loudFrame(beat: Boolean = false) = AnalysisFrame(
        bands = mapOf("sub_bass" to 0.8f, "bass" to 0.8f, "low_mid" to 0.4f, "mid" to 0.4f, "high" to 0.3f),
        energy = 0.8f,
        melbank = FloatArray(16) { 0.6f },
        beat = beat,
        beatStrength = if (beat) 2f else 0f,
        bassBeat = beat,
        bassStrength = if (beat) 2f else 0f,
        salience = 1f,
        onsetWidth = 1f,
        centroid = 0.3f,
    )

    private fun grid(
        locked: Boolean = true,
        timeToNext: Float = 0.5f,
        predicted: Boolean = false,
        beatInBar: Int = 0,
        accent: Float = 0.8f,
    ) = BeatGrid(
        bpm = 120f,
        confidence = 0.8f,
        locked = locked,
        periodS = 0.5f,
        phase = 1f - timeToNext / 0.5f,
        timeToNextBeat = timeToNext,
        predictedBeat = predicted,
        beatInBar = beatInBar,
        accent = accent,
        accentNow = accent,
        scheduleStrength = 1f,
    )

    private fun fieldOf(colors: Map<Int, Rgb>): Float {
        if (colors.isEmpty()) return 0f
        var s = 0f
        for (c in colors.values) s += max(c.first, max(c.second, c.third))
        return s / colors.size
    }

    /** Warm the envelopes so the silence gate is open. */
    private fun warm(eng: SyncoEngine, frames: Int = 60) {
        repeat(frames) { eng.render(loudFrame(), dt, grid(predicted = false), StructureState()) }
    }

    @Test
    fun `a locked grid fires a wavefront before the beat, not after`() {
        // The whole point of the tempo PLL. A reactive wave always starts once
        // the kick has already sounded; an anticipated one is mid-flight.
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.HIGH }
        warm(eng)

        // Approach the beat from outside the anticipation window (80 ms on High).
        var before = fieldOf(eng.render(loudFrame(), dt, grid(timeToNext = 0.30f), StructureState()))
        repeat(5) {
            before = fieldOf(eng.render(loudFrame(), dt, grid(timeToNext = 0.30f), StructureState()))
        }
        // Now step inside it, still with no detected beat at all.
        var insideWindow = before
        repeat(4) {
            insideWindow = fieldOf(eng.render(loudFrame(), dt, grid(timeToNext = 0.05f), StructureState()))
        }
        assertTrue(
            insideWindow > before,
            "no anticipatory wave: field $before outside the window, $insideWindow inside it",
        )
    }

    @Test
    fun `the wavefront sweeps rather than lighting the room at once`() {
        // A wave has to arrive at different lamps at different times, or it is
        // just a flash with extra steps.
        val eng = SyncoEngine(channels(8)).apply { mode = SyncMode.HIGH }
        warm(eng)
        // Fire one, then watch which lamp peaks when.
        eng.render(loudFrame(), dt, grid(timeToNext = 0.02f), StructureState())
        val peakAt = HashMap<Int, Int>()
        val peak = HashMap<Int, Float>()
        for (step in 0 until 40) {
            val out = eng.render(loudFrame(), dt, grid(timeToNext = 0.4f), StructureState())
            for ((cid, c) in out) {
                val v = max(c.first, max(c.second, c.third))
                if (v > (peak[cid] ?: 0f)) { peak[cid] = v; peakAt[cid] = step }
            }
        }
        assertTrue(peakAt.values.distinct().size > 1, "every lamp peaked on the same frame — no sweep")
    }

    @Test
    fun `an unlocked grid still renders reactively`() {
        // Ambient, speech and sparse material never lock. The show must degrade
        // to the old reactive behaviour rather than going dark.
        val eng = SyncoEngine(channels(4)).apply { mode = SyncMode.HIGH }
        repeat(60) { eng.render(loudFrame(), dt, grid(locked = false), StructureState()) }
        val quiet = fieldOf(eng.render(loudFrame(beat = false), dt, grid(locked = false), StructureState()))
        val hit = fieldOf(eng.render(loudFrame(beat = true), dt, grid(locked = false), StructureState()))
        assertTrue(hit > quiet, "an unlocked grid produced no reaction to a detected beat")
    }

    @Test
    fun `a null grid behaves exactly as before`() {
        // The engine has to stay usable with no rhythm model at all — that is
        // what runs before the PLL locks, every single track.
        val eng = SyncoEngine(channels(4)).apply { mode = SyncMode.HIGH }
        repeat(60) { eng.render(loudFrame(), dt, null, null) }
        val out = eng.render(loudFrame(beat = true), dt, null, null)
        assertTrue(out.size == 4, "expected every channel rendered")
        for (c in out.values) {
            assertTrue(c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f, "out of range: $c")
        }
    }

    @Test
    fun `the downbeat hits harder than the other beats of the bar`() {
        // A bar with four identical hits is a metronome, not music.
        fun beatField(beatInBar: Int): Float {
            val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.HIGH }
            warm(eng)
            return fieldOf(
                eng.render(loudFrame(), dt, grid(predicted = true, beatInBar = beatInBar), StructureState())
            )
        }
        val down = beatField(0)
        val two = beatField(1)
        assertTrue(down > two, "downbeat ($down) did not out-hit beat two ($two)")
    }

    @Test
    fun `a build desaturates and the drop releases a swell`() {
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.HIGH }
        warm(eng)

        // Mid-build: tension should pull colour toward white.
        var building = mapOf<Int, Rgb>()
        repeat(10) {
            building = eng.render(
                loudFrame(), dt, grid(),
                StructureState(phase = com.engabd.sendpin.audio.SongPhase.BUILDING, buildProgress = 0.9f),
            )
        }
        val buildSat = building.values.map { c ->
            val m = max(c.first, max(c.second, c.third))
            val lo = minOf(c.first, c.second, c.third)
            if (m > 1e-6f) (m - lo) / m else 0f
        }.average()

        // Steady: colour should be fuller.
        val eng2 = SyncoEngine(channels(6)).apply { mode = SyncMode.HIGH }
        warm(eng2)
        var steady = mapOf<Int, Rgb>()
        repeat(10) { steady = eng2.render(loudFrame(), dt, grid(), StructureState()) }
        val steadySat = steady.values.map { c ->
            val m = max(c.first, max(c.second, c.third))
            val lo = minOf(c.first, c.second, c.third)
            if (m > 1e-6f) (m - lo) / m else 0f
        }.average()

        assertTrue(buildSat < steadySat, "a build did not desaturate: $buildSat vs steady $steadySat")

        // The drop itself should light the room harder than the steady state.
        val beforeDrop = fieldOf(eng.render(loudFrame(), dt, grid(), StructureState()))
        val atDrop = fieldOf(
            eng.render(loudFrame(), dt, grid(), StructureState(phase = com.engabd.sendpin.audio.SongPhase.DROP, dropNow = true))
        )
        assertTrue(atDrop > beforeDrop, "the drop produced no swell: $beforeDrop -> $atDrop")
    }

    @Test
    fun `silence produces no motion even with a locked grid predicting beats`() {
        // The "only audio moves lights" invariant, now that beats can be
        // predicted rather than merely detected. A grid still ticking through a
        // pause or a gap between tracks must not strobe an empty room.
        //
        // The assertion is about *motion*, not absolute darkness: a resting
        // floor is a deliberate per-rung choice — Subtle sits at 0.80 — so what
        // must hold is that nothing varies while there is no audio.
        val eng = SyncoEngine(channels(4)).apply { mode = SyncMode.HIGH }
        val silence = AnalysisFrame()
        repeat(60) { eng.render(silence, dt, grid(), StructureState()) }  // settle

        val settled = eng.render(silence, dt, grid(), StructureState())
            .mapValues { (_, c) -> max(c.first, max(c.second, c.third)) }
        repeat(120) { i ->
            val out = eng.render(silence, dt, grid(predicted = i % 30 == 0), StructureState())
            for ((cid, c) in out) {
                val v = max(c.first, max(c.second, c.third))
                assertTrue(
                    kotlin.math.abs(v - settled.getValue(cid)) < 0.01f,
                    "silence moved channel $cid from ${settled.getValue(cid)} to $v",
                )
            }
        }
    }

    @Test
    fun `the ambient melbank floor is gated by the music`() {
        // syncoV2 applies (melbank_floor + melbank_gain * drive) * music as one
        // term. Hoisting the floor outside the gate leaves the room lit through
        // silence and between tracks.
        val eng = SyncoEngine(channels(4)).apply { mode = SyncMode.HIGH }
        val silence = AnalysisFrame()
        repeat(180) { eng.render(silence, dt, null, null) }
        val quiet = fieldOf(eng.render(silence, dt, null, null))

        val eng2 = SyncoEngine(channels(4)).apply { mode = SyncMode.HIGH }
        warm(eng2, 180)
        val playing = fieldOf(eng2.render(loudFrame(), dt, grid(), StructureState()))

        assertTrue(quiet < playing * 0.5f, "silence ($quiet) was not clearly darker than music ($playing)")
    }

    @Test
    fun `output stays in range across every rung with a grid`() {
        for (mode in SyncMode.entries) {
            val eng = SyncoEngine(channels(5)).apply { this.mode = mode }
            for (i in 0 until 300) {
                val out = eng.render(
                    loudFrame(beat = i % 30 == 0),
                    dt,
                    grid(predicted = i % 30 == 0, beatInBar = (i / 30) % 4),
                    StructureState(buildProgress = (i % 100) / 100f, dropNow = i % 137 == 0),
                )
                for (c in out.values) {
                    assertTrue(
                        c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f,
                        "$mode produced $c at frame $i",
                    )
                    assertTrue(!c.first.isNaN() && !c.second.isNaN() && !c.third.isNaN(), "$mode produced NaN")
                }
            }
        }
    }

    @Test
    fun `a lamp pops on a transient in its own slice of the spectrum`() {
        // syncoV2's per-lamp attack layer: a kick lights the low lamps, a cymbal
        // the high ones. Every rung sets spectralPop, but only the Extreme
        // renderer read it, so the music path had no per-instrument detail at
        // all — just the beat flash over a smooth glow. That is most of what
        // "less lively than the HA version" was.
        fun frameWith(hotBin: Int) = AnalysisFrame(
            bands = mapOf("sub_bass" to 0.6f, "bass" to 0.6f, "mid" to 0.4f, "high" to 0.3f),
            energy = 0.7f,
            melbank = FloatArray(16) { if (it == hotBin) 0.95f else 0.1f },
            salience = 1f,
            onsetWidth = 1f,
            centroid = 0.3f,
        )

        val eng = SyncoEngine(channels(8)).apply { mode = SyncMode.HIGH }
        // Settle with a flat spectrum so the per-bin baselines are established.
        repeat(120) {
            eng.render(
                AnalysisFrame(
                    bands = mapOf("sub_bass" to 0.6f, "bass" to 0.6f),
                    energy = 0.7f,
                    melbank = FloatArray(16) { 0.1f },
                    salience = 1f, onsetWidth = 1f,
                ),
                dt, null, StructureState(),
            )
        }
        // A sudden transient low in the spectrum should favour the low lamps.
        val low = eng.render(frameWith(0), dt, null, StructureState())
        val lowBrightest = low.maxByOrNull { max(it.value.first, max(it.value.second, it.value.third)) }?.key

        val eng2 = SyncoEngine(channels(8)).apply { mode = SyncMode.HIGH }
        repeat(120) {
            eng2.render(
                AnalysisFrame(
                    bands = mapOf("sub_bass" to 0.6f, "bass" to 0.6f),
                    energy = 0.7f,
                    melbank = FloatArray(16) { 0.1f },
                    salience = 1f, onsetWidth = 1f,
                ),
                dt, null, StructureState(),
            )
        }
        val high = eng2.render(frameWith(15), dt, null, StructureState())
        val highBrightest = high.maxByOrNull { max(it.value.first, max(it.value.second, it.value.third)) }?.key

        assertTrue(
            lowBrightest != highBrightest,
            "a low and a high transient both peaked at lamp $lowBrightest — no spectral pop",
        )
    }

    @Test
    fun `live waves stay bounded`() {
        // Each wave costs a lookup per lamp per frame, and an unbounded list
        // would grow for the length of a track.
        val eng = SyncoEngine(channels(6)).apply { mode = SyncMode.INTENSE }
        warm(eng)
        // Hammer the anticipation window: a beat every other frame.
        repeat(2000) { i ->
            eng.render(loudFrame(beat = true), dt, grid(timeToNext = 0.01f, predicted = i % 2 == 0), StructureState())
        }
        // Nothing to assert directly on the private list; what matters is that
        // this completes promptly and the output is still sane.
        val out = eng.render(loudFrame(), dt, grid(), StructureState())
        for (c in out.values) assertTrue(c.first in 0f..1f, "runaway waves broke the output: $c")
    }
}
