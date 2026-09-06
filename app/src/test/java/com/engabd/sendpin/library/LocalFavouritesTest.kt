package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Favourites the app keeps for a library that has none — MPD.
 *
 * The pure half only: the store itself needs a `Context` and there is no Robolectric
 * here, but the store is a JSON blob in SharedPreferences and the parts that can be
 * wrong are the three below.
 */
class LocalFavouritesTest {

    private fun item(id: String, type: String = "album", name: String = id) =
        MaItem(id, "mpd", name, "mpd://$type/$id", type, null, null, null)

    @Test
    fun `marking is what keeps a heart lit across a scroll`() {
        // The single most important behaviour here, and the least obvious. The library
        // view model seeds its heart state from `MaItem.favorite` and *clears* it for
        // anything a load reports as false, so a source that stored a star and then
        // handed the item back unstarred would un-heart it on the next page of results.
        val items = listOf(item("a"), item("b"), item("c"))

        val marked = LocalFavourites.mark(items, setOf("a", "c"))

        assertTrue(marked.first { it.itemId == "a" }.favorite)
        assertFalse(marked.first { it.itemId == "b" }.favorite)
        assertTrue(marked.first { it.itemId == "c" }.favorite)
        // Nothing else about the item is disturbed.
        assertEquals(items.map { it.itemId }, marked.map { it.itemId })
        assertEquals(items.map { it.uri }, marked.map { it.uri })
    }

    @Test
    fun `nothing starred leaves the list exactly as it was`() {
        val items = listOf(item("a"))
        assertEquals(items, LocalFavourites.mark(items, emptySet()))
        assertEquals(emptyList(), LocalFavourites.mark(emptyList(), setOf("a")))
    }

    @Test
    fun `starring twice does not store it twice`() {
        val album = item("a")
        var stored = LocalFavourites.updated(emptyList(), album, starred = true)
        stored = LocalFavourites.updated(stored, album, starred = true)

        assertEquals(1, stored.size)
        assertEquals("a", stored.single().itemId)
    }

    @Test
    fun `unstarring removes it, and unstarring nothing is harmless`() {
        val album = item("a")
        val stored = LocalFavourites.updated(emptyList(), album, starred = true)

        assertTrue(LocalFavourites.updated(stored, album, starred = false).isEmpty())
        assertTrue(LocalFavourites.updated(emptyList(), album, starred = false).isEmpty())
    }

    @Test
    fun `the starred page sorts entries into the buckets it has`() {
        val entries = listOf(
            LocalFavourites.Entry("ar", "artist", "An Artist"),
            LocalFavourites.Entry("al", "album", "An Album"),
            LocalFavourites.Entry("pl", "playlist", "A Playlist"),
            LocalFavourites.Entry("tr", "track", "A Track"),
            // Not a bucket of its own, and dropping it would silently lose a star the
            // user placed — so it rides with the tracks.
            LocalFavourites.Entry("pod", "podcast", "A Podcast"),
        )

        val results = LocalFavourites.results(entries, "mpd")

        assertEquals(listOf("ar"), results.artists.map { it.itemId })
        assertEquals(listOf("al"), results.albums.map { it.itemId })
        assertEquals(listOf("pl"), results.playlists.map { it.itemId })
        assertEquals(listOf("tr", "pod"), results.tracks.map { it.itemId })
        // Everything on the Starred page is starred by definition, so the hearts on it
        // are lit without a second lookup.
        assertTrue(results.albums.all { it.favorite })
        assertEquals("mpd", results.albums.single().provider)
    }
}
