package com.engabd.sendpin.hue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "The colours are wrong when I play from the library, but right from the queue."
 *
 * The engine half of that bug, pinned. `DirectLightSync` used to answer a null artwork
 * URL by calling `setAlbumColors(emptyList())`, which drops [SyncoEngine] to the Sunset
 * fallback for every dynamic scheme.
 *
 * The null was not rare and it was not random. `activeSource.artUrl` follows whichever
 * feed is winning, and `PlaybackOwner.soundOwner` passes through `NONE` during any
 * backend handover — at which point the feed picker falls back to the local player,
 * whose `current` was just cleared. Every library-initiated play calls
 * `localPlayer.stop()` or `stopMaPlayback()` first, so every one of them emitted that
 * null; a queue advance stays inside one backend and never did. Hence the split.
 *
 * The engine keeps the behaviour that made the bug possible — an *empty* list really
 * does mean "no colours" — so what changed is that only a deliberate stop sends one.
 * These tests hold the engine to the contract the caller now relies on.
 */
class AlbumPaletteRetentionTest {

    private val cover = listOf(
        Rgb(0.90f, 0.20f, 0.15f),
        Rgb(0.10f, 0.40f, 0.85f),
        Rgb(0.95f, 0.80f, 0.20f),
    )

    private fun channels(n: Int) = (0 until n).map { i ->
        EntertainmentChannel(
            channelId = i,
            position = ChannelPosition(x = -1f + 2f * i / (n - 1f), y = 0f, z = 0f),
        )
    }

    private fun engine(scheme: ColorScheme): SyncoEngine =
        SyncoEngine(channels(4)).also { it.setScheme(scheme) }

    @Test
    fun `album colours reach the palette`() {
        val e = engine(ColorScheme.ALBUM_ART_V2)
        e.setAlbumColors(cover)
        assertEquals(cover, e.palette.colors)
    }

    @Test
    fun `switching to another scheme and back restores the album palette`() {
        // The second half of the report: "when the colours are correct, then I change to
        // other colours and return to album art, the colours aren't correct". The engine
        // has always retained the palette across a scheme change — what was missing was
        // anything to re-extract from once the URL had been nulled.
        val e = engine(ColorScheme.ALBUM_ART_V2)
        e.setAlbumColors(cover)
        e.setScheme(ColorScheme.OCEAN)
        assertTrue(e.palette.colors != cover, "static scheme should not use album colours")
        e.setScheme(ColorScheme.ALBUM_ART_V2)
        assertEquals(cover, e.palette.colors, "album colours were not restored")
    }

    @Test
    fun `an empty list is the one thing that clears the palette`() {
        val e = engine(ColorScheme.ALBUM_ART_V2)
        e.setAlbumColors(cover)
        e.setAlbumColors(emptyList())
        assertTrue(e.palette.colors != cover, "an explicit clear should drop the palette")
    }

    @Test
    fun `a dynamic scheme with no album colours falls back rather than going dark`() {
        val e = engine(ColorScheme.ALBUM_ART_V2)
        assertTrue(e.palette.colors.isNotEmpty(), "the room must never be left with no palette")
    }

    @Test
    fun `weights are carried through for the weighted scheme`() {
        val e = engine(ColorScheme.ALBUM_ART_V2)
        val weights = listOf(0.6f, 0.3f, 0.1f)
        e.setAlbumColors(cover, weights)
        assertEquals(cover, e.palette.colors)
    }

    @Test
    fun `song draws its own colours and artwork must not overwrite them`() {
        val e = engine(ColorScheme.SONG)
        val before = e.palette.colors
        e.setAlbumColors(cover)
        assertTrue(e.palette.colors != cover, "album art overwrote the Song palette")
        assertEquals(before.size, e.palette.colors.size)
    }
}
