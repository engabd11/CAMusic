package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sorting and narrowing a library category on screen. */
class LibraryOrderTest {

    private fun album(id: String, name: String, artist: String?, year: Int? = null, genres: List<String> = emptyList(), fav: Boolean = false) =
        MaItem(
            itemId = id, provider = "subsonic", name = name, uri = "subsonic://album/$id", mediaType = "album",
            subtitle = artist, image = null, duration = null, favorite = fav, year = year, genres = genres,
        )

    private val albums = listOf(
        album("1", "Coastal Drive", "Amber Lanes", 2019, listOf("Pop")),
        album("2", "The Blue Hours", "The Night Owls", 1994, listOf("Jazz"), fav = true),
        album("3", "abbey road", "The Beatles", 1969, listOf("Rock")),
        album("4", "Éclair", "Amber Lanes", null, listOf("pop")),
        album("5", "Signal Bloom", "Circuit Garden", 1998, listOf("Electronic")),
    )
    private val noFav: (MaItem) -> Boolean = { it.favorite }
    private val downloaded: (MaItem) -> Boolean = { it.itemId == "5" }

    private fun ids(list: List<MaItem>) = list.map { it.itemId }

    @Test
    fun `the default order is the server's, untouched`() {
        assertEquals(albums, LibraryOrder.apply(albums, LibraryOrder(), noFav, downloaded))
    }

    @Test
    fun `by name ignores case, accents and a leading article`() {
        val byName = LibraryOrder.apply(albums, LibraryOrder(sort = LibraryOrder.Sort.NAME), noFav, downloaded)
        // abbey road, The Blue Hours (B), Coastal Drive, Éclair (E), Signal Bloom
        assertEquals(listOf("3", "2", "1", "4", "5"), ids(byName))
        val reversed = LibraryOrder.apply(albums, LibraryOrder(sort = LibraryOrder.Sort.NAME, descending = true), noFav, downloaded)
        assertEquals(listOf("5", "4", "1", "2", "3"), ids(reversed))
    }

    @Test
    fun `by artist groups an artist's albums, oldest first`() {
        val byArtist = LibraryOrder.apply(albums, LibraryOrder(sort = LibraryOrder.Sort.ARTIST), noFav, downloaded)
        // Amber Lanes (2019, then undated), The Beatles, Circuit Garden, The Night Owls
        assertEquals(listOf("1", "4", "3", "5", "2"), ids(byArtist))
    }

    @Test
    fun `by year keeps undated albums last either way round`() {
        val oldest = LibraryOrder.apply(albums, LibraryOrder(sort = LibraryOrder.Sort.YEAR), noFav, downloaded)
        assertEquals(listOf("3", "2", "5", "1", "4"), ids(oldest))
        val newest = LibraryOrder.apply(albums, LibraryOrder(sort = LibraryOrder.Sort.YEAR, descending = true), noFav, downloaded)
        assertEquals(listOf("1", "5", "2", "3", "4"), ids(newest))
    }

    @Test
    fun `random is stable for a seed and changes with it`() {
        val order = LibraryOrder(sort = LibraryOrder.Sort.RANDOM)
        val a = LibraryOrder.apply(albums, order, noFav, downloaded, seed = 42)
        assertEquals(a, LibraryOrder.apply(albums, order, noFav, downloaded, seed = 42))
        assertEquals(albums.toSet(), a.toSet())
        val others = (1L..20L).map { ids(LibraryOrder.apply(albums, order, noFav, downloaded, seed = it)) }.toSet()
        assertTrue(others.size > 1)
    }

    @Test
    fun `filters narrow by favourite, on the phone, genre and decade`() {
        fun only(o: LibraryOrder) = ids(LibraryOrder.apply(albums, o, noFav, downloaded))
        assertEquals(listOf("2"), only(LibraryOrder(favouritesOnly = true)))
        assertEquals(listOf("5"), only(LibraryOrder(downloadedOnly = true)))
        // Genre matching ignores case: "Pop" and "pop" are one genre.
        assertEquals(listOf("1", "4"), only(LibraryOrder(genre = "POP")))
        assertEquals(listOf("2", "5"), only(LibraryOrder(decade = 1990)))
        assertEquals(emptyList(), only(LibraryOrder(decade = 1990, genre = "Pop")))
    }

    @Test
    fun `only what the list supports is offered`() {
        val options = LibraryOrder.options(albums, noFav, downloaded)
        assertEquals(LibraryOrder.Sort.entries.toList(), options.sorts)
        assertEquals(listOf("Electronic", "Jazz", "Pop", "Rock"), options.genres)
        assertEquals(listOf(1960, 1990, 2010), options.decades)
        assertTrue(options.canFavourites)
        assertTrue(options.canDownloaded)

        val bare = listOf(MaItem("a", "x", "Playlist A", "x://a", "playlist", null, null, null))
        val plain = LibraryOrder.options(bare, noFav) { false }
        assertEquals(listOf(LibraryOrder.Sort.DEFAULT, LibraryOrder.Sort.NAME, LibraryOrder.Sort.RANDOM), plain.sorts)
        assertTrue(plain.genres.isEmpty())
        assertFalse(plain.canFavourites)
        assertFalse(plain.canDownloaded)
    }

    @Test
    fun `saved orders round-trip, and the default is not stored`() {
        val saved = mapOf("albums" to LibraryOrder(sort = LibraryOrder.Sort.YEAR, descending = true, genre = "Jazz"))
        assertEquals(saved, LibraryOrder.decode(LibraryOrder.encode(saved)))
        assertEquals(emptyMap(), LibraryOrder.decode("not json"))
        assertTrue(LibraryOrder().isDefault)
    }
}
