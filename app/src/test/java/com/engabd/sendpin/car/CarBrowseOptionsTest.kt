package com.engabd.sendpin.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that decide what a car's screen looks like.
 *
 * These matter more than most tests here because of where the code runs: the browse
 * tree is built inside a service that only Android Auto binds, so nothing in this
 * app exercises it short of plugging a phone into a car. Everything below is the
 * part of that which is pure, and it is the part where a mistake is silent — a shelf
 * that never appears, a library pushed behind "More", a selection that quietly means
 * its own opposite.
 */
class CarBrowseOptionsTest {

    private val allShelves = CarShelf.entries.map { it.key }

    // ── What a library offers ───────────────────────────────────────────────

    @Test
    fun `Music Assistant offers its own shelves and not the flat browse ones`() {
        val offered = CarShelf.offeredBy(CarLibraryAbilities(musicAssistant = true, favourites = true, playlists = true))
        assertTrue(CarShelf.RECENTLY_PLAYED in offered)
        assertTrue(CarShelf.FAVOURITE_TRACKS in offered)
        // The app has no flat artists/albums browse for MA, and offering one would be
        // a folder that opens onto nothing.
        assertFalse(CarShelf.ARTISTS in offered)
        assertFalse(CarShelf.ALBUMS in offered)
    }

    @Test
    fun `a direct server without favourites is not offered favourite shelves`() {
        val offered = CarShelf.offeredBy(
            CarLibraryAbilities(musicAssistant = false, favourites = false, playlists = false),
        )
        assertEquals(listOf(CarShelf.RECENTLY_ADDED, CarShelf.ARTISTS, CarShelf.ALBUMS), offered)
    }

    @Test
    fun `playlists appear only where the adapter can read them`() {
        val cannotRead = CarShelf.offeredBy(CarLibraryAbilities(false, favourites = true, playlists = false))
        val canRead = CarShelf.offeredBy(CarLibraryAbilities(false, favourites = true, playlists = true))
        assertFalse(CarShelf.PLAYLISTS in cannotRead)
        assertTrue(CarShelf.PLAYLISTS in canRead)
    }

    // ── The stored selection ────────────────────────────────────────────────

    @Test
    fun `an empty selection means everything the library offers`() {
        val offered = CarShelf.offeredBy(CarLibraryAbilities(true, true, true))
        assertEquals(offered, CarBrowseOptions().shelves(offered))
    }

    @Test
    fun `a selection is filtered to what the library can actually fill`() {
        // Set once for every library, so a Navidrome folder must not be asked to draw
        // Music Assistant's "Recently played".
        val options = CarBrowseOptions(
            shelfKeys = listOf(CarShelf.RECENTLY_PLAYED.key, CarShelf.ALBUMS.key, CarShelf.ARTISTS.key),
        )
        val offered = CarShelf.offeredBy(CarLibraryAbilities(false, favourites = false, playlists = false))
        assertEquals(listOf(CarShelf.ALBUMS, CarShelf.ARTISTS), options.shelves(offered))
    }

    @Test
    fun `a selection with nothing left in common falls back to everything`() {
        // The one outcome no setting may produce is a folder that is empty in a car.
        val options = CarBrowseOptions(shelfKeys = listOf(CarShelf.RECENTLY_PLAYED.key))
        val offered = CarShelf.offeredBy(CarLibraryAbilities(false, favourites = false, playlists = false))
        assertEquals(offered, options.shelves(offered))
    }

    @Test
    fun `the selection order is the order shown`() {
        val options = CarBrowseOptions(shelfKeys = listOf(CarShelf.ALBUMS.key, CarShelf.RECENTLY_ADDED.key))
        val offered = CarShelf.offeredBy(CarLibraryAbilities(false, favourites = false, playlists = false))
        assertEquals(listOf(CarShelf.ALBUMS, CarShelf.RECENTLY_ADDED), options.shelves(offered))
    }

    @Test
    fun `libraries are filtered and ordered by the stored ids`() {
        val configured = listOf("nas", "jelly", "ma", "__downloads__")
        val options = CarBrowseOptions(libraryIds = listOf("ma", "nas"))
        assertEquals(listOf("ma", "nas"), options.libraries(configured) { it })
    }

    @Test
    fun `a stored library that no longer exists is skipped, not shown blank`() {
        val options = CarBrowseOptions(libraryIds = listOf("deleted", "nas"))
        assertEquals(listOf("nas"), options.libraries(listOf("nas", "ma")) { it })
    }

    @Test
    fun `every stored library having been deleted falls back to all of them`() {
        val options = CarBrowseOptions(libraryIds = listOf("deleted"))
        assertEquals(listOf("nas", "ma"), options.libraries(listOf("nas", "ma")) { it })
    }

    // ── The root's four slots ───────────────────────────────────────────────

    @Test
    fun `everything fits when there are no more libraries than slots`() {
        val options = CarBrowseOptions()
        assertEquals(
            listOf("a", "b", "c", "d") to emptyList(),
            options.splitForRoot(listOf("a", "b", "c", "d"), limit = 4),
        )
    }

    @Test
    fun `an overflow costs a slot, because More is itself a row`() {
        // Five into four is three plus "More", not four plus one nobody can reach.
        val options = CarBrowseOptions()
        assertEquals(
            listOf("a", "b", "c") to listOf("d", "e"),
            options.splitForRoot(listOf("a", "b", "c", "d", "e"), limit = 4),
        )
    }

    @Test
    fun `a browser claiming a single slot still shows one library and More`() {
        val options = CarBrowseOptions()
        assertEquals(
            listOf("a") to listOf("b", "c"),
            options.splitForRoot(listOf("a", "b", "c"), limit = 1),
        )
        // …and a nonsensical hint is treated as one slot rather than none.
        assertEquals(
            listOf("a") to listOf("b"),
            options.splitForRoot(listOf("a", "b"), limit = 0),
        )
    }

    @Test
    fun `the driver's order decides which library is one tap away`() {
        val options = CarBrowseOptions(libraryIds = listOf("ma", "nas", "jelly"))
        val ordered = options.libraries(listOf("nas", "jelly", "ma")) { it }
        assertEquals(listOf("ma") to listOf("nas", "jelly"), options.splitForRoot(ordered, limit = 2))
    }

    // ── Row styles ──────────────────────────────────────────────────────────

    @Test
    fun `adaptive gives a shelf of covers a grid and a shelf of names a list`() {
        val options = CarBrowseOptions()
        assertEquals(CarRowStyle.GRID, options.shelfChildStyle(CarShelf.ALBUMS))
        assertEquals(CarRowStyle.LIST, options.shelfChildStyle(CarShelf.FAVOURITE_TRACKS))
    }

    @Test
    fun `people get the category style, which is what makes the artwork round`() {
        val options = CarBrowseOptions()
        assertEquals(CarRowStyle.CATEGORY_GRID, options.shelfChildStyle(CarShelf.FAVOURITE_ARTISTS))
        // Artists browse is a long alphabetical list, so it stays a list — round, but
        // a list.
        assertEquals(CarRowStyle.CATEGORY_LIST, options.shelfChildStyle(CarShelf.ARTISTS))
        assertEquals(CarRowStyle.CATEGORY_GRID, options.itemStyle("genre"))
    }

    @Test
    fun `switching circles off leaves people as ordinary rows`() {
        val options = CarBrowseOptions(peopleAsCircles = false)
        assertEquals(CarRowStyle.GRID, options.shelfChildStyle(CarShelf.FAVOURITE_ARTISTS))
        assertEquals(CarRowStyle.LIST, options.shelfChildStyle(CarShelf.ARTISTS))
    }

    @Test
    fun `an explicit style overrides what the content would have preferred`() {
        val grid = CarBrowseOptions(style = CarBrowseStyle.GRID)
        val list = CarBrowseOptions(style = CarBrowseStyle.LIST)
        assertEquals(CarRowStyle.GRID, grid.shelfChildStyle(CarShelf.FAVOURITE_TRACKS))
        assertEquals(CarRowStyle.LIST, list.shelfChildStyle(CarShelf.ALBUMS))
        // …but "this is a person" is not a style, so it survives both.
        assertEquals(CarRowStyle.CATEGORY_GRID, grid.shelfChildStyle(CarShelf.ARTISTS))
        assertEquals(CarRowStyle.CATEGORY_LIST, list.shelfChildStyle(CarShelf.FAVOURITE_ARTISTS))
    }

    @Test
    fun `folder rows are a list unless covers were asked for everywhere`() {
        assertEquals(CarRowStyle.LIST, CarBrowseOptions().folderStyle())
        assertEquals(CarRowStyle.LIST, CarBrowseOptions(style = CarBrowseStyle.LIST).folderStyle())
        assertEquals(CarRowStyle.GRID, CarBrowseOptions(style = CarBrowseStyle.GRID).folderStyle())
    }

    @Test
    fun `what is inside an item is described, not the item itself`() {
        // The hint on a browsable node is about its children. An album opens onto
        // tracks — a list — even though the album row itself is a cover in a grid.
        val options = CarBrowseOptions()
        assertEquals(CarRowStyle.LIST, options.childStyleFor("album"))
        assertEquals(CarRowStyle.LIST, options.childStyleFor("playlist"))
        assertEquals(CarRowStyle.LIST, options.childStyleFor("podcast"))
        // An artist opens onto albums, which are covers.
        assertEquals(CarRowStyle.GRID, options.childStyleFor("artist"))
        // The artist *row* is still a circle — a different question, different answer.
        assertEquals(CarRowStyle.CATEGORY_GRID, options.itemStyle("artist"))
    }

    @Test
    fun `a compact list flattens what is inside an artist too`() {
        val list = CarBrowseOptions(style = CarBrowseStyle.LIST)
        assertEquals(CarRowStyle.LIST, list.childStyleFor("artist"))
        assertEquals(CarRowStyle.GRID, CarBrowseOptions(style = CarBrowseStyle.GRID).childStyleFor("album"))
    }

    @Test
    fun `a track shelf loads twice as many, capped`() {
        assertEquals(100, CarBrowseOptions(shelfItemLimit = 50).trackItemLimit)
        assertEquals(
            CarBrowseOptions.MAX_SHELF_ITEMS,
            CarBrowseOptions(shelfItemLimit = CarBrowseOptions.MAX_SHELF_ITEMS).trackItemLimit,
        )
    }

    // ── Editing the stored list ─────────────────────────────────────────────

    @Test
    fun `switching one off from the default materialises the rest`() {
        val next = CarBrowseOptions.toggle(emptyList(), allShelves, CarShelf.ALBUMS.key)
        assertEquals(allShelves - CarShelf.ALBUMS.key, next)
    }

    @Test
    fun `switching one back on restores its place, and so the default`() {
        // Otherwise a list that happens to name everything freezes the set at what
        // this version offers, and a shelf added later never appears.
        val without = CarBrowseOptions.toggle(emptyList(), allShelves, CarShelf.ALBUMS.key)
        val back = CarBrowseOptions.toggle(without, allShelves, CarShelf.ALBUMS.key)
        assertEquals(emptyList(), back)
    }

    @Test
    fun `the last enabled entry cannot be switched off`() {
        val only = listOf(CarShelf.ALBUMS.key)
        assertEquals(only, CarBrowseOptions.toggle(only, allShelves, CarShelf.ALBUMS.key))
    }

    @Test
    fun `moving an entry reorders it and leaves the rest alone`() {
        val stored = listOf("a", "b", "c")
        val all = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c"), CarBrowseOptions.move(stored, all, "b", -1))
        assertEquals(listOf("a", "c", "b"), CarBrowseOptions.move(stored, all, "b", 1))
    }

    @Test
    fun `moving past either end is a no-op rather than a wrap`() {
        val stored = listOf("a", "b", "c")
        val all = listOf("a", "b", "c")
        assertEquals(stored, CarBrowseOptions.move(stored, all, "a", -1))
        assertEquals(stored, CarBrowseOptions.move(stored, all, "c", 1))
    }

    @Test
    fun `the picker shows what is on first, then what is off`() {
        val stored = listOf("c", "a")
        val all = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), CarBrowseOptions.displayOrder(stored, all))
        assertEquals(listOf("c", "a"), CarBrowseOptions.enabledOrder(stored, all))
    }

    @Test
    fun `keys survive a round trip through storage`() {
        val keys = listOf(CarShelf.ALBUMS.key, CarShelf.ARTISTS.key)
        assertEquals(keys, CarBrowseOptions.decodeKeys(CarBrowseOptions.encodeKeys(keys)))
        assertEquals(emptyList(), CarBrowseOptions.decodeKeys(null))
        assertEquals(emptyList(), CarBrowseOptions.decodeKeys(""))
        // Blanks and repeats come from hand-edited or half-written values, and either
        // one would otherwise become a shelf key that matches nothing.
        assertEquals(listOf("a", "b"), CarBrowseOptions.decodeKeys(" a , ,b, a "))
    }

    @Test
    fun `an unknown style key is the adaptive default rather than a crash`() {
        assertEquals(CarBrowseStyle.ADAPTIVE, CarBrowseStyle.byKey(null))
        assertEquals(CarBrowseStyle.ADAPTIVE, CarBrowseStyle.byKey("from-a-future-version"))
        assertEquals(CarBrowseStyle.LIST, CarBrowseStyle.byKey("list"))
    }

    @Test
    fun `shelf keys are the wire format and must not drift`() {
        // These go into a cmid:// media id and into stored settings. Renaming one
        // silently drops a driver's saved selection and breaks any id a browser is
        // still holding.
        assertEquals("favoriteAlbums", CarShelf.FAVOURITE_ALBUMS.key)
        assertEquals("favoriteArtists", CarShelf.FAVOURITE_ARTISTS.key)
        assertEquals("favoritePlaylists", CarShelf.FAVOURITE_PLAYLISTS.key)
        assertEquals("favoriteTracks", CarShelf.FAVOURITE_TRACKS.key)
        assertEquals("recentlyAdded", CarShelf.RECENTLY_ADDED.key)
        assertEquals("recentlyPlayed", CarShelf.RECENTLY_PLAYED.key)
        assertEquals("artists", CarShelf.ARTISTS.key)
        assertEquals("albums", CarShelf.ALBUMS.key)
        assertEquals("playlists", CarShelf.PLAYLISTS.key)
    }

    @Test
    fun `seek seconds become the milliseconds the player reports`() {
        assertEquals(0L, CarBrowseOptions().seekMs)
        assertEquals(15_000L, CarBrowseOptions(seekSeconds = 15).seekMs)
    }
}
