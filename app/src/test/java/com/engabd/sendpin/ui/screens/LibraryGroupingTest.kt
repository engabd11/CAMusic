package com.engabd.sendpin.ui.screens

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Starred list is the one browse node that mixes media types, and it used to be
 * shown as a single run of rows under a "Play all N tracks" bar whose N counted only
 * the tracks — a number that read as wrong against a list that was mostly albums.
 *
 * These pin the split: sections in a fixed order, each holding one kind, and nothing
 * split that was not mixed to begin with.
 */
class LibraryGroupingTest {

    private fun item(id: String, type: String) =
        MaItem(id, "subsonic", "Name $id", "uri", type, null, null, null)

    @Test
    fun `a starred list splits into artists, albums, playlists and tracks`() {
        val items = listOf(
            item("a1", "artist"),
            item("al1", "album"), item("al2", "album"),
            item("p1", "playlist"),
            item("t1", "track"), item("t2", "track"), item("t3", "track"),
        )
        val groups = typedGroups(items)
        assertEquals(listOf("Artists", "Albums", "Playlists", "Tracks"), groups.map { it.first })
        assertEquals(listOf(1, 2, 1, 3), groups.map { it.second.size })
    }

    @Test
    fun `every item lands in exactly one section`() {
        val items = listOf(item("a1", "artist"), item("t1", "track"), item("al1", "album"))
        assertEquals(items.size, typedGroups(items).sumOf { it.second.size })
    }

    @Test
    fun `an album's track list is left alone`() {
        val tracks = List(8) { item("t$it", "track") }
        assertTrue(typedGroups(tracks).isEmpty())
    }

    @Test
    fun `a type with no section of its own leaves the list flat`() {
        val items = listOf(item("g1", "genre"), item("t1", "track"))
        assertTrue(typedGroups(items).isEmpty())
    }

    @Test
    fun `a single item is not a mixture`() {
        assertTrue(typedGroups(listOf(item("t1", "track"))).isEmpty())
        assertTrue(typedGroups(emptyList()).isEmpty())
    }
}
