package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.abs
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

    /**
     * The render loop measures dt rather than assuming a fixed step, so the engine
     * sees jitter: a 60 Hz tick that ran late, a step clamped after a stall, and
     * the occasional near-zero when two ticks land together. Everything in here
     * integrates against dt, so none of those may push it out of range.
     */
    @Test
    fun `variable frame timing stays in range`() {
        val steps = listOf(1f / 60, 1f / 60, 0.0001f, 0.1f, 1f / 50, 0f, 0.033f, 1f / 60)
        for (mode in SyncMode.entries) {
            val engine = SyncoEngine(channels())
            engine.mode = mode
            repeat(80) { i ->
                val out = engine.render(frame(i, beat = i % 25 == 0), steps[i % steps.size])
                for ((ch, rgb) in out) {
                    val (r, g, b) = rgb
                    assertTrue(
                        !r.isNaN() && !g.isNaN() && !b.isNaN(),
                        "$mode channel $ch went NaN on a ${steps[i % steps.size]}s step",
                    )
                    assertTrue(
                        r in 0f..1f && g in 0f..1f && b in 0f..1f,
                        "$mode channel $ch out of range on a varying step: $rgb",
                    )
                }
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

    @Test
    fun `idle show renders every channel inside unit range`() {
        val engine = SyncoEngine(channels()).apply { setScheme(ColorScheme.SUNSET) }
        val frames = (0..300).map { engine.renderIdleShow(it * 0.05f, intensity = 1f) }
        for (out in frames) {
            assertEquals(5, out.size)
            for ((r, g, b) in out.values) {
                assertTrue(r in 0f..1f && g in 0f..1f && b in 0f..1f)
            }
        }
    }

    @Test
    fun `tunables scale params and default to one`() {
        val engine = SyncoEngine(channels()).apply { mode = SyncMode.HIGH }
        val base = engine.modeParams()
        engine.setTunables(mapOf("reactivity" to 2f, "glow" to 0.5f))
        val tuned = engine.modeParams()
        assertTrue(tuned.spectralPop > base.spectralPop)
        assertTrue(tuned.melbankGain < base.melbankGain)
        assertEquals(base.spectralPop * 2f, tuned.spectralPop, 1e-6f)
        assertEquals(base.melbankGain * 0.5f, tuned.melbankGain, 1e-6f)
    }

    @Test
    fun `tunables reset to base when cleared`() {
        val engine = SyncoEngine(channels()).apply { mode = SyncMode.MEDIUM }
        val base = engine.modeParams()
        engine.setTunables(mapOf("reactivity" to 2f, "colour_speed" to 0f))
        engine.setTunables(emptyMap())
        assertEquals(base, engine.modeParams())
    }

    // ── Frame-rate independence and the brightness slew ───────────────────

    /** Mean of each light's max channel — the field brightness the eye reads. */
    private fun fieldOf(colors: Map<Int, Rgb>): Float {
        if (colors.isEmpty()) return 0f
        var s = 0f
        for (c in colors.values) s += maxOf(c.first, maxOf(c.second, c.third))
        return s / colors.size
    }

    /** Steady loud content, with no transient in it for the envelope to chase. */
    private fun loudSteady(): AnalysisFrame = AnalysisFrame(
        bands = mapOf("sub_bass" to 0.8f, "bass" to 0.8f, "low_mid" to 0.4f, "mid" to 0.4f, "high" to 0.3f),
        energy = 0.8f,
        melbank = FloatArray(16) { 0.6f },
        salience = 1f,
        onsetWidth = 1f,
    )

    @Test
    fun `the brightness envelope follows wall time, not frame count`() {
        // Every easing coefficient is tuned per frame at TUNING_FPS and then
        // re-expressed for the dt actually observed. Before that it was applied
        // once per rendered frame whatever the frame took, so the decay between
        // beats ran at a different speed at the analyser's ~50 Hz than at the
        // render loop's 60 Hz, and drifted again on every scheduling hiccup.
        // That timing sensitivity is what read as shimmer on the lights.
        fun decayAfter(fps: Int, seconds: Float): Float {
            val dt = 1f / fps
            val eng = SyncoEngine(channels()).apply { mode = SyncMode.HIGH }
            var t = 0f
            while (t < 1f) { eng.render(loudSteady(), dt); t += dt }
            var last = 0f
            t = 0f
            while (t < seconds - 1e-4f) { last = fieldOf(eng.render(AnalysisFrame(), dt)); t += dt }
            return last
        }
        for (s in listOf(0.1f, 0.25f, 0.5f, 1f)) {
            val hi = decayAfter(60, s)
            val lo = decayAfter(30, s)
            assertTrue(
                abs(hi - lo) < 0.05f,
                "after ${s}s of silence the field was $hi at 60 fps but $lo at 30 fps",
            )
        }
    }

    @Test
    fun `emitted brightness never moves faster than the rung's ceiling`() {
        // The anti-choppiness guarantee. A single-frame jump is what a bulb
        // renders as a hard edge; capping the rate is what removes it. Extreme
        // is excluded only because it scales by a colour-value term *after* the
        // slew, so its emitted max-channel is not the slewed value itself.
        val dt = 1f / 60f
        for (mode in SyncMode.entries) {
            val p = MODE_PARAMS.getValue(mode)
            if (p.graphReactive) continue
            val eng = SyncoEngine(channels()).apply { this.mode = mode }
            var prev: Map<Int, Rgb>? = null
            repeat(240) { i ->
                // Alternate full blast and silence to demand the fastest moves
                // the engine can be asked for.
                val f = if (i % 2 == 0) frame(i, beat = true) else AnalysisFrame()
                val out = eng.render(f, dt)
                prev?.let { pv ->
                    for ((cid, c) in out) {
                        val now = maxOf(c.first, maxOf(c.second, c.third))
                        val was = pv.getValue(cid).let { maxOf(it.first, maxOf(it.second, it.third)) }
                        val d = now - was
                        assertTrue(
                            d <= p.briRiseRate * dt + 1e-3f,
                            "$mode channel $cid rose $d in one frame, over ${p.briRiseRate * dt}",
                        )
                        assertTrue(
                            -d <= p.briFallRate * dt + 1e-3f,
                            "$mode channel $cid fell ${-d} in one frame, over ${p.briFallRate * dt}",
                        )
                    }
                }
                prev = out
            }
        }
    }

    @Test
    fun `the tuned presets are unchanged at the nominal frame rate`() {
        // frameAlpha is the exact conversion, so re-expressing a coefficient for
        // a 1/60 s step has to return it untouched — otherwise this refactor
        // would have quietly retuned all five rungs.
        val dt = 1f / TUNING_FPS
        for (a in listOf(0.008f, 0.04f, 0.10f, 0.30f, 0.55f, 0.80f, 1f)) {
            assertEquals(a, frameAlpha(a, dt), 1e-6f, "frameAlpha moved $a at nominal rate")
            assertEquals(a, frameDecay(a, dt), 1e-6f, "frameDecay moved $a at nominal rate")
        }
        // …and a double-length step has to compose exactly like two of them.
        val once = frameDecay(0.8f, dt * 2f)
        val twice = frameDecay(0.8f, dt) * frameDecay(0.8f, dt)
        assertEquals(twice, once, 1e-6f, "a 2-frame decay did not compose")
    }
}

/** Reflection helper for tests — exposes the currently active params. */
private fun SyncoEngine.modeParams(): ModeParams {
    val field = SyncoEngine::class.java.getDeclaredField("params")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as ModeParams
}
