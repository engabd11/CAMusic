package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every intensity rung has to survive real frames.
 *
 * The rungs are not five settings on one renderer — each is a distinct parameter
 * set, and several fields only ever come into play on one of them (`melbankGain`
 * and `spectralPop` are zero on Subtle, `predropDepth` and `phraseBars` only exist
 * from Medium up). A rung the user can select but that throws on the first kick,
 * or that renders a channel the bridge won't accept, is the failure this guards.
 *
 * These are contract tests, not perceptual ones: they cannot say whether Intense
 * *looks* like syncoV2's Intense. What they can say is that every rung is
 * selectable, renders every channel, and stays inside the 0..1 range the encoder
 * requires — which is what has to hold before judging any of it by eye.
 */
class SyncoEngineTest {

    private fun channels(n: Int = 5): List<EntertainmentChannel> =
        (0 until n).map { i ->
            EntertainmentChannel(
                channelId = i,
                // Spread left→right: the engine ranks channels by x to assign
                // bass/mid/vocal roles, so identical positions would test one role.
                position = ChannelPosition(x = -1f + 2f * i / (n - 1).coerceAtLeast(1), y = 0f, z = 0f),
            )
        }

    /** A frame of plausible music: a kick on the beat, energy in every band. */
    private fun frame(i: Int, beat: Boolean): AnalysisFrame {
        val phase = i * 0.02f
        val energy = 0.35f + 0.3f * sin(phase * 2f)
        return AnalysisFrame(
            bands = mapOf(
                "sub_bass" to if (beat) 0.9f else 0.25f,
                "bass" to if (beat) 0.85f else 0.3f,
                "low_mid" to 0.4f,
                "mid" to 0.45f,
                "high_mid" to 0.3f,
                "high" to 0.25f,
            ),
            energy = energy,
            beat = beat,
            beatStrength = if (beat) 2.2f else 0f,
            flux = if (beat) 1.8f else 0.4f,
            tAudio = phase,
            bassFlux = if (beat) 2.4f else 0.3f,
            bassBeat = beat,
            bassStrength = if (beat) 2.5f else 0f,
            midFlux = 0.6f,
            midBeat = i % 12 == 0,
            midStrength = if (i % 12 == 0) 1.6f else 0f,
            salience = 0.8f,
            onsetWidth = 0.5f,
            melbank = FloatArray(16) { 0.2f + 0.5f * sin(phase + it * 0.4f) },
            centroid = 0.45f,
        )
    }

    /** Two seconds of audio at the analysis rate, with a kick every ~500 ms. */
    private fun render(mode: SyncMode): List<Map<Int, Rgb>> {
        val engine = SyncoEngine(channels())
        engine.mode = mode
        return (0 until 100).map { i -> engine.render(frame(i, beat = i % 25 == 0), dt = 0.02f) }
    }

    @Test
    fun `every rung has parameters`() {
        // `mode` does MODE_PARAMS[value]!! — a rung missing from the table is not a
        // degraded look, it is an NPE the moment the user taps that pill.
        for (mode in SyncMode.entries) {
            assertTrue(MODE_PARAMS.containsKey(mode), "no ModeParams for $mode")
        }
    }

    @Test
    fun `every rung renders every channel`() {
        for (mode in SyncMode.entries) {
            val frames = render(mode)
            assertEquals(100, frames.size, "$mode")
            for ((i, out) in frames.withIndex()) {
                assertEquals(5, out.size, "$mode dropped a channel at frame $i")
            }
        }
    }

    /**
     * The encoder maps these straight onto xy + brightness. A component outside
     * 0..1 there is not a wrong colour, it is a malformed frame on the wire.
     */
    @Test
    fun `every rung stays inside the unit range`() {
        for (mode in SyncMode.entries) {
            for ((i, out) in render(mode).withIndex()) {
                for ((ch, rgb) in out) {
                    val (r, g, b) = rgb
                    assertTrue(
                        r in 0f..1f && g in 0f..1f && b in 0f..1f,
                        "$mode channel $ch frame $i out of range: $rgb",
                    )
                    assertTrue(
                        !r.isNaN() && !g.isNaN() && !b.isNaN(),
                        "$mode channel $ch frame $i is NaN: $rgb",
                    )
                }
            }
        }
    }

    /**
     * Subtle is the one rung that must *not* react to beats — it holds a steady
     * level and lets colour drift. Its params zero out every beat term, so if a
     * kick moves its brightness, something is reading past the mode table.
     */
    @Test
    fun `subtle holds steady through a kick`() {
        val engine = SyncoEngine(channels())
        engine.mode = SyncMode.SUBTLE
        repeat(30) { engine.render(frame(it, beat = false), 0.02f) }

        val quiet = engine.render(frame(30, beat = false), 0.02f)
        val kick = engine.render(frame(31, beat = true), 0.02f)

        for (ch in quiet.keys) {
            val before = quiet.getValue(ch).let { (r, g, b) -> maxOf(r, g, b) }
            val after = kick.getValue(ch).let { (r, g, b) -> maxOf(r, g, b) }
            assertTrue(
                kotlin.math.abs(after - before) < 0.15f,
                "Subtle channel $ch jumped on a kick: $before → $after",
            )
        }
    }

    /**
     * Switching rungs mid-song is the normal way these are used — the picker sits
     * on the Lights tab while the music plays. The swap recomputes params and role
     * assignments on a live engine, so it gets its own test rather than riding on
     * the fresh-engine ones.
     */
    @Test
    fun `rungs can be switched on a running engine`() {
        val engine = SyncoEngine(channels())
        var i = 0
        for (mode in SyncMode.entries) {
            engine.mode = mode
            repeat(20) {
                val out = engine.render(frame(i++, beat = it % 5 == 0), 0.02f)
                assertEquals(5, out.size, "$mode dropped a channel after a live switch")
            }
        }
    }

    /** Brightness is a ceiling: nothing may exceed it, whatever the rung does. */
    @Test
    fun `brightness ceiling bounds every rung`() {
        for (mode in SyncMode.entries) {
            val engine = SyncoEngine(channels())
            engine.mode = mode
            engine.brightness = 0.4f
            repeat(60) { i ->
                for ((ch, rgb) in engine.render(frame(i, beat = i % 25 == 0), 0.02f)) {
                    val peak = rgb.let { (r, g, b) -> maxOf(r, g, b) }
                    assertTrue(peak <= 0.4f + 1e-3f, "$mode channel $ch exceeded the ceiling: $peak")
                }
            }
        }
    }

    @Test
    fun `brightness is clamped to the unit range`() {
        val engine = SyncoEngine(channels())
        engine.brightness = 5f
        assertEquals(1f, engine.brightness)
        engine.brightness = -2f
        assertEquals(0f, engine.brightness)
    }

    /** A single-light area is a real setup, and the role split divides by count. */
    @Test
    fun `a one-channel area renders`() {
        for (mode in SyncMode.entries) {
            val engine = SyncoEngine(channels(1))
            engine.mode = mode
            repeat(30) { i ->
                assertEquals(1, engine.render(frame(i, beat = i % 25 == 0), 0.02f).size, "$mode")
            }
        }
    }

    /** Silence must not divide by zero or drift the AGC into NaN. */
    @Test
    fun `silence renders without NaN`() {
        for (mode in SyncMode.entries) {
            val engine = SyncoEngine(channels())
            engine.mode = mode
            repeat(50) {
                for ((_, rgb) in engine.render(AnalysisFrame(), 0.02f)) {
                    val (r, g, b) = rgb
                    assertTrue(!r.isNaN() && !g.isNaN() && !b.isNaN(), "$mode went NaN on silence")
                }
            }
        }
    }
}
