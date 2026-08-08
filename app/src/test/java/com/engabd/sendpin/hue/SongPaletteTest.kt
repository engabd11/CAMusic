package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.AnalysisFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Song colour source: fresh colours arriving with the music.
 *
 * Deliberately not syncoV2's chroma-derived harmony, which needs a pitch-class
 * projection the live analyzer does not compute. What the room actually reads
 * from that scheme is "the colours keep changing", so this does that directly.
 */
class SongPaletteTest {

    private fun hueOf(c: Rgb): Float {
        val mx = max(c.first, max(c.second, c.third))
        val mn = min(c.first, min(c.second, c.third))
        val d = mx - mn
        if (d < 1e-6f) return 0f
        val h = when (mx) {
            c.first -> ((c.second - c.third) / d) % 6f
            c.second -> (c.third - c.first) / d + 2f
            else -> (c.first - c.second) / d + 4f
        } / 6f
        return if (h < 0f) h + 1f else h
    }

    private fun hueGap(a: Float, b: Float): Float {
        val d = abs(a - b) % 1f
        return min(d, 1f - d)
    }

    @Test
    fun `colours change on a beat`() {
        val p = SongPalette(seed = 1)
        val before = p.colors()
        p.onBeat(peak = false)
        assertTrue(p.colors() != before, "the palette did not change on a beat")
    }

    @Test
    fun `a peak turns the room over harder than an ordinary beat`() {
        // Two colours retire on a peak instead of one, so the big hits visibly
        // do more than the beats between them.
        // Counted as how many of the original colours survive, not by position:
        // the ring shifts down and appends, so every *slot* holds something new
        // after any rotation. What differs is how much of the old set is left.
        val base = SongPalette(seed = 7).colors().toSet()
        val ordinary = SongPalette(seed = 7).let { it.onBeat(peak = false); it.colors() }
        val peak = SongPalette(seed = 7).let { it.onBeat(peak = true); it.colors() }
        val survivedOrdinary = ordinary.count { it in base }
        val survivedPeak = peak.count { it in base }
        assertTrue(
            survivedPeak < survivedOrdinary,
            "a peak left $survivedPeak of the old colours, an ordinary beat $survivedOrdinary",
        )
    }

    @Test
    fun `consecutive colours are visibly different`() {
        // Uniform random hues cluster: without a separation rule a good share of
        // changes land close enough to the last colour to read as no change.
        val p = SongPalette(seed = 3)
        var tooClose = 0
        var previous = hueOf(p.colors().last())
        repeat(200) {
            p.onBeat(peak = false)
            val now = hueOf(p.colors().last())
            if (hueGap(now, previous) < 0.08f) tooClose++
            previous = now
        }
        assertTrue(tooClose < 20, "$tooClose of 200 new colours were near-repeats")
    }

    @Test
    fun `the room holds several colours at once`() {
        // A single colour flooding every lamp would lose the spatial spread that
        // every other scheme has.
        val p = SongPalette(seed = 5)
        val hues = p.colors().map(::hueOf)
        assertTrue(hues.toSet().size > 1, "the palette collapsed to one colour")
        assertTrue(p.colors().size >= 3, "too few colours to spread across a room")
    }

    @Test
    fun `colours are vivid and in range`() {
        val p = SongPalette(seed = 11)
        repeat(100) { p.onBeat(peak = it % 4 == 0) }
        for (c in p.colors()) {
            assertTrue(c.first in 0f..1f && c.second in 0f..1f && c.third in 0f..1f, "out of range: $c")
            val mx = max(c.first, max(c.second, c.third))
            val mn = min(c.first, min(c.second, c.third))
            assertTrue(mx > 0.9f, "colour too dark to light a room: $c")
            assertTrue(mx - mn > 0.5f, "colour too washed out to read as a colour: $c")
        }
    }

    @Test
    fun `a seeded palette is reproducible`() {
        val a = SongPalette(seed = 42).let { repeat(10) { _ -> it.onBeat(false) }; it.colors() }
        val b = SongPalette(seed = 42).let { repeat(10) { _ -> it.onBeat(false) }; it.colors() }
        assertTrue(a == b, "the same seed produced different colours")
    }

    // ── Through the engine ────────────────────────────────────────────────

    private fun channels(n: Int) = (0 until n).map { i ->
        EntertainmentChannel(i, ChannelPosition(-1f + 2f * i / max(1, n - 1), 0f, 0f))
    }

    private fun frame(beat: Boolean) = AnalysisFrame(
        bands = mapOf("sub_bass" to 0.8f, "bass" to 0.8f, "mid" to 0.4f, "high" to 0.3f),
        energy = 0.8f,
        melbank = FloatArray(16) { 0.5f },
        beat = beat,
        beatStrength = if (beat) 2f else 0f,
        bassBeat = beat,
        bassStrength = if (beat) 2f else 0f,
        salience = 1f,
        onsetWidth = 1f,
    )

    @Test
    fun `the engine changes colour over a run of beats`() {
        val eng = SyncoEngine(channels(4)).apply {
            mode = SyncMode.HIGH
            setScheme(ColorScheme.SONG)
        }
        repeat(60) { eng.render(frame(false), 1f / 60f) }  // open the silence gate
        val before = eng.palette.colors
        repeat(20) { i -> eng.render(frame(beat = i % 5 == 0), 1f / 60f) }
        assertTrue(eng.palette.colors != before, "the engine's palette never changed over 4 beats")
    }

    @Test
    fun `silence does not generate colours`() {
        // The "only audio moves lights" invariant applies to colour too: a grid
        // ticking through a gap between tracks must not churn the palette.
        val eng = SyncoEngine(channels(4)).apply {
            mode = SyncMode.HIGH
            setScheme(ColorScheme.SONG)
        }
        val before = eng.palette.colors
        repeat(300) { eng.render(AnalysisFrame(), 1f / 60f) }
        assertTrue(eng.palette.colors == before, "silence changed the palette")
    }

    @Test
    fun `album art does not overwrite song colours`() {
        // Artwork is collected whatever the scheme, so it must not clobber a
        // scheme that draws its own colours.
        val eng = SyncoEngine(channels(4)).apply { setScheme(ColorScheme.SONG) }
        val before = eng.palette.colors
        eng.setAlbumColors(listOf(Triple(1f, 0f, 0f), Triple(0f, 1f, 0f)), listOf(0.9f, 0.1f))
        assertTrue(eng.palette.colors == before, "album art overwrote the Song palette")
    }
}
