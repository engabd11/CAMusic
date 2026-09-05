package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keys a saved palette is filed under.
 *
 * This is not bookkeeping — it is the whole correctness of the feature. The editor
 * and `DirectLightSync` know different things about the same track, so they build
 * their key lists from different inputs, and if those lists do not meet, the user
 * saves a palette that is written correctly, read never, and applied never. There is
 * no error anywhere on that path: the lights simply carry on showing the extracted
 * colours, which is indistinguishable from the editor not having saved.
 *
 * That is exactly what happened on the Music Assistant feed, and the last two tests
 * here are the ones that would have caught it.
 */
class CoverPaletteOverrideTest {

    private val url = "http://ma.local/imageproxy?path=cover.jpg"

    @Test
    fun `album and artist lead, because they survive artwork churn`() {
        val keys = CoverPaletteOverride.keysFor(
            album = "Kind of Blue",
            artist = "Miles Davis",
            coverUrl = url,
            trackId = "track-1",
        )
        assertEquals(listOf("Kind of Blue|Miles Davis", url, "track-1"), keys)
    }

    @Test
    fun `a blank album falls through to the artwork url`() {
        val keys = CoverPaletteOverride.keysFor(
            album = "  ",
            artist = "Miles Davis",
            coverUrl = url,
            trackId = null,
        )
        assertEquals(listOf(url), keys)
    }

    @Test
    fun `an album with no artist still keys on the album`() {
        val keys = CoverPaletteOverride.keysFor(
            album = "Untitled",
            artist = null,
            coverUrl = null,
            trackId = null,
        )
        assertEquals(listOf("Untitled|"), keys)
    }

    @Test
    fun `nothing known means nothing to file under`() {
        assertTrue(
            CoverPaletteOverride.keysFor(null, null, null, null).isEmpty(),
            "an override with no key would be written and never found",
        )
    }

    /**
     * The Music Assistant case, which is where this broke.
     *
     * The engine has no `LocalTrack` on that feed, so the artwork URL is the only key
     * it can possibly look under. The editor knows the album name and used to save
     * under *only* its own best key — `"album|artist"` — which the engine never asks
     * for. Saving under the whole list is what makes the two meet.
     */
    @Test
    fun `the editor's keys cover every key the engine can ask for`() {
        val editorKeys = CoverPaletteOverride.keysFor(
            album = "Kind of Blue",
            artist = "Miles Davis",
            coverUrl = url,
            trackId = "track-1",
        )
        // What DirectLightSync.findOverride builds when there is no LocalTrack.
        val engineKeysOnMa = CoverPaletteOverride.keysFor(
            album = null,
            artist = null,
            coverUrl = url,
            trackId = null,
        )

        assertTrue(engineKeysOnMa.isNotEmpty())
        assertTrue(
            editorKeys.containsAll(engineKeysOnMa),
            "the engine would look under $engineKeysOnMa and find none of $editorKeys",
        )
        // And the old behaviour — saving under the first key only — would not have.
        assertTrue(
            !listOf(editorKeys.first()).containsAll(engineKeysOnMa),
            "this test no longer reproduces the bug it was written for",
        )
    }

    @Test
    fun `the local path finds it under the album key`() {
        val editorKeys = CoverPaletteOverride.keysFor(
            album = "Kind of Blue", artist = "Miles Davis", coverUrl = url, trackId = "track-1",
        )
        // The engine on the local feed, where the LocalTrack carries the album.
        val engineKeys = CoverPaletteOverride.keysFor(
            album = "Kind of Blue", artist = "Miles Davis", coverUrl = url, trackId = "track-1",
        )
        assertEquals(
            editorKeys.first(),
            engineKeys.first(),
            "both sides must prefer the same key, or two saves could disagree",
        )
    }

    /**
     * The other half of the Music Assistant bug, and the half the test above could
     * not see.
     *
     * Covering every key the engine asks for only helps while the engine still asks
     * for a key that lasts. On MA it asked for the artwork URL and nothing else — and
     * MA re-issues those through `/imageproxy` with a fresh item id and token, so the
     * correction saved on one session was filed under an address the next session
     * never mentions. `ActiveLightSyncSource` now carries the album and artist for
     * exactly this, so both sides agree on a key that outlives the URL.
     */
    @Test
    fun `a durable key survives an artwork url the server re-issues`() {
        val monday = "http://ma.local/imageproxy?path=cover.jpg&token=aaa"
        val tuesday = "http://ma.local/imageproxy?path=cover.jpg&token=bbb"

        val saved = CoverPaletteOverride.keysFor(
            album = "Kind of Blue", artist = "Miles Davis", coverUrl = monday, trackId = null,
        )
        // What the engine looks under next time, with the same record playing.
        val looked = CoverPaletteOverride.keysFor(
            album = "Kind of Blue", artist = "Miles Davis", coverUrl = tuesday, trackId = null,
        )
        assertTrue(
            looked.any { it in saved },
            "a correction saved under $saved is orphaned by $looked",
        )

        // And the old shape — the URL as MA's only key — is exactly what was lost.
        val urlOnlySaved = CoverPaletteOverride.keysFor(null, null, monday, null)
        val urlOnlyLooked = CoverPaletteOverride.keysFor(null, null, tuesday, null)
        assertTrue(
            urlOnlyLooked.none { it in urlOnlySaved },
            "this test no longer reproduces the bug it was written for",
        )
    }

    @Test
    fun `colours convert to the engine's unit triples`() {
        val override = CoverPaletteOverride(colors = listOf(0xFFFF8000.toInt(), 0xFF000000.toInt()))
        val rgb = override.toAlbumColours()
        assertEquals(2, rgb.colors.size)
        assertEquals(1f, rgb.colors[0].first)
        assertEquals(0f, rgb.colors[1].first)
        // Even weights: hand-picked colours carry no population data.
        assertEquals(0.5f, rgb.weights[0])
        assertEquals(0.5f, rgb.weights[1])
    }

    // ── Weights ─────────────────────────────────────────────────────────────

    @Test
    fun `no weights means even, which is what every old override decodes to`() {
        val o = CoverPaletteOverride(colors = listOf(1, 2, 3, 4))
        assertTrue(!o.hasWeights)
        assertEquals(listOf(0.25f, 0.25f, 0.25f, 0.25f), o.normalisedWeights())
    }

    @Test
    fun `raw shares normalise to a distribution`() {
        val o = CoverPaletteOverride(colors = listOf(1, 2), weights = listOf(3f, 1f))
        assertTrue(o.hasWeights)
        assertEquals(listOf(0.75f, 0.25f), o.normalisedWeights())
        assertEquals(1f, o.normalisedWeights().sum())
    }

    @Test
    fun `a colour set to nothing keeps its place at zero`() {
        // "None of this one" is a real answer, and dropping the whole set because
        // one slider is at the bottom would lose the other three.
        val o = CoverPaletteOverride(colors = listOf(1, 2, 3), weights = listOf(1f, 0f, 1f))
        assertEquals(listOf(0.5f, 0f, 0.5f), o.normalisedWeights())
    }

    @Test
    fun `weights that cannot mean anything fall back to even`() {
        // Wrong length — a colour added or removed by a build that did not migrate.
        val mismatched = CoverPaletteOverride(colors = listOf(1, 2, 3), weights = listOf(1f, 1f))
        assertTrue(!mismatched.hasWeights)
        assertEquals(listOf(1f / 3f, 1f / 3f, 1f / 3f), mismatched.normalisedWeights())

        // Every slider at the bottom: there is no distribution to be had.
        val allZero = CoverPaletteOverride(colors = listOf(1, 2), weights = listOf(0f, 0f))
        assertTrue(!allZero.hasWeights)
        assertEquals(listOf(0.5f, 0.5f), allZero.normalisedWeights())
    }

    @Test
    fun `the palette carries its weights through to the engine's shape`() {
        val o = CoverPaletteOverride(colors = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), weights = listOf(3f, 1f))
        val album = o.toAlbumColours()
        assertEquals(2, album.colors.size)
        assertEquals(listOf(0.75f, 0.25f), album.weights)
    }

    @Test
    fun `an empty palette has no weights to normalise`() {
        assertTrue(CoverPaletteOverride.EMPTY.normalisedWeights().isEmpty())
        assertTrue(!CoverPaletteOverride.EMPTY.hasWeights)
    }
}
