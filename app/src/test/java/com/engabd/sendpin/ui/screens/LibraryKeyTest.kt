package com.engabd.sendpin.ui.screens

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The library grid's keys have to stay unique whatever the server sends.
 *
 * A duplicate key is a hard crash in a lazy list, and the library is the one screen
 * whose lists it cannot vet: `music/recommendations` flattens several folders into the
 * "For you" shelf, and Music Assistant numbers library items per media type, so
 * `provider|itemId` alone repeats. Scrolling the root down to that shelf killed the app
 * in 0.7.0.
 */
class LibraryKeyTest {

    private fun item(id: String, type: String = "album", provider: String = "library") =
        MaItem(id, provider, "Name", "uri", type, null, null, null)

    @Test
    fun `the same item twice in one list still gets two keys`() {
        val album = item("5")
        assertNotEquals(itemKey("For you", 0, album), itemKey("For you", 1, album))
    }

    @Test
    fun `an album and a track sharing a library id get two keys`() {
        val album = item("5", type = "album")
        val track = item("5", type = "track")
        assertNotEquals(itemKey("For you", 0, album), itemKey("For you", 1, track))
    }

    @Test
    fun `shelves on screen together do not collide on a shared album`() {
        val album = item("5")
        assertNotEquals(itemKey("Favourite albums", 0, album), itemKey("Recently added", 0, album))
    }

    @Test
    fun `the item still identifies the slot, so a changed row recomposes`() {
        assertNotEquals(itemKey("r", 3, item("5")), itemKey("r", 3, item("6")))
        assertEquals(itemKey("r", 3, item("5")), itemKey("r", 3, item("5")))
    }

    @Test
    fun `a whole shelf of one repeated item is entirely distinct`() {
        val shelf = List(12) { item("5") }
        val keys = shelf.mapIndexed { i, it -> itemKey("For you", i, it) }
        assertEquals(shelf.size, keys.toSet().size)
    }
}
