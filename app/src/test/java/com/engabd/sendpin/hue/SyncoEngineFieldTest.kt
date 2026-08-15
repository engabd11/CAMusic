package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The room as a *field* rather than as N independent lamps.
 *
 * `SyncoEngineTest` guards the contract — every rung renders, every channel stays
 * in range. This guards the behaviour the field work was added for, and each test
 * here corresponds to something that was reported as wrong by eye:
 *
 *  - a long vocal lit almost nothing, because every layer that could have carried
 *    it is gated off for tonal material by design;
 *  - mid peaks arrived too dim, because a lamp averages seven melbank bins and a
 *    vocal occupies two or three of them;
 *  - the lamps read as separate instruments rather than one glow, because nothing
 *    coupled them.
 *
 * What these cannot say is whether it *looks* right — synthetic frames can only
 * confirm the shape of the response, not its calibration against real vocals.
 * They can, however, fail loudly if a future change silently restores any of the
 * three behaviours above.
 */
class SyncoEngineFieldTest {

    private fun channels(n: Int = 6): List<EntertainmentChannel> =
        (0 until n).map { i ->
            EntertainmentChannel(
                channelId = i,
                position = ChannelPosition(
                    x = -1f + 2f * i / (n - 1).coerceAtLeast(1),
                    y = 0f,
                    // A little height spread, so the height blend and the colour tilt
                    // have something to work with.
                    z = if (i % 2 == 0) -0.5f else 0.5f,
                ),
            )
        }

    /**
     * A held vocal: narrowband, mid-heavy, harmonically still, no percussion.
     *
     * `onsetWidth` well under `tonalWidthMax` is what makes this tonal — the same
     * measure `eventGates` uses to *suppress* events, read the other way round.
     */
    private fun vocalFrame(i: Int): AnalysisFrame {
        val t = i * 0.02f
        // Two mid bins carrying the voice, everything else quiet — the exact shape a
        // seven-bin mean used to divide down to nothing.
        val mel = FloatArray(16) { bin -> if (bin == 8 || bin == 9) 0.85f else 0.06f }
        val chroma = FloatArray(12) { if (it == 4) 1f else 0.02f }  // one steady pitch
        return AnalysisFrame(
            bands = mapOf(
                "sub_bass" to 0.05f, "bass" to 0.08f,
                "low_mid" to 0.55f, "mid" to 0.70f, "high" to 0.15f,
            ),
            energy = 0.35f,
            beat = false,
            bassBeat = false,
            salience = 0.85f,
            onsetWidth = 0.06f,
            melbank = mel,
            chroma = chroma,
            tAudio = t,
            centroid = 0.35f,
        )
    }

    /** A percussive frame: broadband onsets, a kick, harmonically busy. */
    private fun drumFrame(i: Int): AnalysisFrame {
        val beat = i % 25 == 0
        val mel = FloatArray(16) { 0.25f + if (beat) 0.6f else 0f }
        val chroma = FloatArray(12) { 0.4f + 0.4f * ((i + it) % 5) / 5f }  // moving
        return AnalysisFrame(
            bands = mapOf(
                "sub_bass" to if (beat) 0.95f else 0.2f,
                "bass" to if (beat) 0.9f else 0.25f,
                "low_mid" to 0.35f, "mid" to 0.3f, "high" to 0.4f,
            ),
            energy = 0.6f,
            beat = beat,
            beatStrength = if (beat) 2.4f else 0f,
            flux = if (beat) 2.0f else 0.4f,
            bassFlux = if (beat) 2.6f else 0.3f,
            bassBeat = beat,
            bassStrength = if (beat) 2.6f else 0f,
            salience = 0.85f,
            onsetWidth = 0.65f,
            melbank = mel,
            chroma = chroma,
            tAudio = i * 0.02f,
            centroid = 0.5f,
        )
    }

    private fun engine(mode: SyncMode = SyncMode.HIGH, chans: List<EntertainmentChannel> = channels()) =
        SyncoEngine(chans).apply { this.mode = mode }

    private fun brightnessOf(rgb: Rgb): Float = maxOf(rgb.first, maxOf(rgb.second, rgb.third))

    // ── The sustain bloom ────────────────────────────────────────────────

    /**
     * The headline regression test. Before the bloom existed this was provably
     * flat: a held vocal produces no onset (width-gated), no attack pop (the slow
     * baseline catches up), no wave and no flash, leaving only a narrowband
     * melbank term that a seven-bin mean had already divided down.
     */
    @Test
    fun `a held vocal brightens the room over about a second`() {
        val e = engine()
        // Settle, so the comparison is against a steady state rather than the
        // engine's own start-up ramp.
        repeat(15) { e.render(vocalFrame(it), dt = 0.02f) }
        val early = e.render(vocalFrame(15), dt = 0.02f).values.map(::brightnessOf).average()

        repeat(60) { e.render(vocalFrame(16 + it), dt = 0.02f) }
        val later = e.render(vocalFrame(80), dt = 0.02f).values.map(::brightnessOf).average()

        assertTrue(
            later > early * 1.15f,
            "a sustained vocal should bloom: $early → $later",
        )
    }

    /** And it must hold, not spike and collapse — it is a glow, not a pulse. */
    @Test
    fun `the bloom holds while the vocal does`() {
        val e = engine()
        repeat(90) { e.render(vocalFrame(it), dt = 0.02f) }
        val a = e.render(vocalFrame(90), dt = 0.02f).values.map(::brightnessOf).average()
        repeat(40) { e.render(vocalFrame(91 + it), dt = 0.02f) }
        val b = e.render(vocalFrame(131), dt = 0.02f).values.map(::brightnessOf).average()
        assertTrue(abs(b - a) < 0.10, "the bloom should hold steady: $a then $b")
    }

    /**
     * And it must stay out of the way of percussion, or it would soften exactly the
     * hits the reactive rungs exist to land.
     */
    @Test
    fun `percussion does not bloom`() {
        val vocal = engine()
        repeat(120) { vocal.render(vocalFrame(it), dt = 0.02f) }
        val vocalLevel = vocal.render(vocalFrame(120), dt = 0.02f).values.map(::brightnessOf).average()

        val drums = engine()
        repeat(120) { drums.render(drumFrame(it), dt = 0.02f) }
        // Sample away from a beat, so this measures the resting level rather than a
        // flash.
        val drumLevel = drums.render(drumFrame(123), dt = 0.02f).values.map(::brightnessOf).average()

        assertTrue(
            vocalLevel > drumLevel,
            "tonal material should sit higher between hits than percussive: " +
                "vocal=$vocalLevel drums=$drumLevel",
        )
    }

    /** Silence must stay dark, however tonal it technically is. */
    @Test
    fun `silence does not bloom`() {
        val e = engine()
        val quiet = AnalysisFrame(
            bands = mapOf("sub_bass" to 0f, "bass" to 0f, "low_mid" to 0f, "mid" to 0f, "high" to 0f),
            energy = 0f,
            salience = 0f,
            onsetWidth = 0.02f,          // maximally "tonal"
            melbank = FloatArray(16),
            chroma = FloatArray(12),
        )
        repeat(120) { e.render(quiet, dt = 0.02f) }
        val level = e.render(quiet, dt = 0.02f).values.map(::brightnessOf).average()
        assertTrue(level < 0.12, "a silent room should stay dark, was $level")
    }

    // ── Spatial coupling ─────────────────────────────────────────────────

    /**
     * The objective proxy for "the beams feel connected": with coupling on, the
     * lamps' brightnesses must sit closer together on the same audio.
     *
     * Uses a narrowband frame on purpose — that is the case where each lamp's own
     * melbank window disagrees most with its neighbours', and therefore the case
     * that read as a room of unrelated lamps.
     */
    @Test
    fun `coupling reduces the spread between lamps`() {
        fun spread(coupling: Float): Double {
            val e = engine()
            // `cohesion` scales the rung's own base value, so 0 disables it outright.
            e.setTunables(mapOf("cohesion" to coupling))
            repeat(60) { e.render(vocalFrame(it), dt = 0.02f) }
            val v = e.render(vocalFrame(60), dt = 0.02f).values.map { brightnessOf(it).toDouble() }
            val mean = v.average()
            return v.sumOf { (it - mean) * (it - mean) } / v.size
        }
        val off = spread(0f)
        val on = spread(1f)
        assertTrue(on < off, "coupling should pull lamps together: variance $off → $on")
    }

    /**
     * And it must not move anything in time. A spatial kernel has no temporal
     * memory, so this should hold by construction — the test exists because the
     * timing is the one thing about this render that was already right, and a
     * future change that quietly convolved the output instead of the drive would
     * break it silently.
     */
    @Test
    fun `coupling does not shift when a lamp peaks`() {
        fun peakFrames(coupling: Float): List<Int> {
            val e = engine()
            e.setTunables(mapOf("cohesion" to coupling))
            val series = (0 until 80).map { i ->
                e.render(drumFrame(i), dt = 0.02f).toSortedMap().values.map(::brightnessOf)
            }
            // For each lamp, the frame index at which it was brightest.
            return (0 until series[0].size).map { lamp ->
                series.indices.maxByOrNull { f -> series[f][lamp] } ?: -1
            }
        }
        assertTrue(
            peakFrames(0f) == peakFrames(1f),
            "the frame a lamp peaks on must not depend on coupling",
        )
    }

    // ── Peakiness ────────────────────────────────────────────────────────

    /**
     * One hot bin must beat the same total energy spread flat across the window.
     *
     * This is the mechanism behind "mid peaks are too dim": a pure mean over ~7
     * bins delivers a 2-bin vocal at roughly a third of its height, and no amount
     * of turning the reaction up fixes the shape — it lifts the floor too.
     */
    @Test
    fun `a concentrated band reads brighter than the same energy spread flat`() {
        fun level(mel: FloatArray): Double {
            val e = engine()
            val f = vocalFrame(0)
            val frames = (0 until 40).map { f.copy(melbank = mel, tAudio = it * 0.02f) }
            frames.forEach { e.render(it, dt = 0.02f) }
            return e.render(frames.last(), dt = 0.02f).values.map(::brightnessOf).average()
        }
        // Same sum either way: 2 × 0.8 spread over 16 bins is 0.1 each.
        val peaked = FloatArray(16) { if (it == 8 || it == 9) 0.8f else 0f }
        val flat = FloatArray(16) { 0.1f }
        assertTrue(
            level(peaked) > level(flat),
            "a concentrated band should read brighter: ${level(peaked)} vs ${level(flat)}",
        )
    }

    // ── Tunables that used to do nothing ─────────────────────────────────

    /**
     * `loudness` and `contrast` were read only by `renderExtreme`, so on four of
     * the five rungs both sliders were inert. Moving them is the point.
     */
    @Test
    fun `loudness and contrast move the output on a non-Extreme rung`() {
        fun level(tunables: Map<String, Float>): Double {
            val e = engine(SyncMode.HIGH)
            e.setTunables(tunables)
            // A live band reference needs a melbankRef; supply one directly rather
            // than waiting out the analyzer's warm-up.
            val ref = FloatArray(16) { if (it in 8..9) 1f else 0.15f }
            val frames = (0 until 60).map { vocalFrame(it).copy(melbankRef = ref) }
            frames.forEach { e.render(it, dt = 0.02f) }
            return e.render(frames.last(), dt = 0.02f).values.map(::brightnessOf).average()
        }
        val neutral = level(emptyMap())
        assertTrue(
            abs(level(mapOf("loudness" to 0f)) - neutral) > 1e-4,
            "the loudness tunable must reach the main render path",
        )
        assertTrue(
            abs(level(mapOf("contrast" to 0f)) - neutral) > 1e-4,
            "the contrast tunable must reach the main render path",
        )
    }

    /**
     * A uniform reference carries no information about which band is louder, so
     * after mean-normalisation it must be indistinguishable from having no
     * reference at all. If this fails, the normalisation is wrong and every track
     * without a scan is being quietly re-weighted.
     */
    @Test
    fun `a uniform band reference is the same as none`() {
        fun level(ref: FloatArray): Double {
            val e = engine()
            val frames = (0 until 40).map { vocalFrame(it).copy(melbankRef = ref) }
            frames.forEach { e.render(it, dt = 0.02f) }
            return e.render(frames.last(), dt = 0.02f).values.map(::brightnessOf).average()
        }
        val none = level(FloatArray(0))
        val uniform = level(FloatArray(16) { 0.5f })
        assertTrue(
            abs(none - uniform) < 1e-4,
            "uniform weighting must be a no-op: none=$none uniform=$uniform",
        )
    }
}
