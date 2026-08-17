package com.engabd.sendpin.ui.screens

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A two-disc album showed "Disc 1 / Disc 2" on Music Assistant and a flat list on
 * Navidrome and Jellyfin. Both of those parse the field; both drop it when the file has
 * no disc tag, which is the case the flat list was.
 *
 * These pin both halves: the field wins where it exists, and where it does not, a track
 * list that starts counting again is read as a second disc — but only on evidence strong
 * enough that a single-disc record can never sprout a header.
 */
class DiscGroupingTest {

    private fun track(name: String, trackNumber: Int?, discNumber: Int? = null) =
        MaItem(
            itemId = name, provider = "subsonic", name = name, uri = name,
            mediaType = "track", subtitle = null, image = null, duration = null,
            trackNumber = trackNumber, discNumber = discNumber,
        )

    private fun disc(number: Int, count: Int, from: Int = 1) =
        (from until from + count).map { track("d$number-t$it", it, number) }

    @Test
    fun `disc numbers from the server are used as given`() {
        val groups = discGroups(disc(1, 3) + disc(2, 2))
        assertEquals(listOf(1, 2), groups.map { it.number })
        assertEquals(listOf(3, 2), groups.map { it.tracks.size })
        assertFalse(groups.any { it.derived })
    }

    @Test
    fun `one disc is one group, so the screen has nothing to head`() {
        assertEquals(1, discGroups(disc(1, 10)).size)
        assertEquals(1, discGroups(List(6) { track("t$it", it + 1) }).size)
    }

    @Test
    fun `an empty album has no discs at all`() {
        assertTrue(discGroups(emptyList()).isEmpty())
    }

    @Test
    fun `restarting track numbers are read as a second disc`() {
        val flat = (1..12).map { track("a$it", it) } + (1..10).map { track("b$it", it) }
        val groups = discGroups(flat)
        assertEquals(listOf(1, 2), groups.map { it.number })
        assertEquals(listOf(12, 10), groups.map { it.tracks.size })
        assertTrue(groups.all { it.derived })
    }

    @Test
    fun `a run that does not begin at one is not a disc`() {
        // Numbering that dips — a compilation with sloppy tags — must not split.
        val odd = listOf(track("a", 1), track("b", 5), track("c", 3), track("d", 4))
        assertEquals(1, discGroups(odd).size)
    }

    @Test
    fun `a single stray track does not become its own disc`() {
        val strays = (1..8).map { track("a$it", it) } + track("b", 1)
        assertEquals(1, discGroups(strays).size)
    }

    @Test
    fun `nothing is derived when a track has no number`() {
        val partial = listOf(track("a", 1), track("b", 2), track("c", null), track("d", 1), track("e", 2))
        assertEquals(1, discGroups(partial).size)
    }

    @Test
    fun `a shelf of singles is not a box set`() {
        val singles = (1..12).flatMap { listOf(track("a$it", 1), track("b$it", 2)) }
        assertEquals(1, discGroups(singles).size)
    }

    @Test
    fun `a gap in the disc tags files those tracks under the first disc`() {
        val mixed = listOf(track("a", 1, null), track("b", 2, 1)) + disc(2, 2)
        val groups = discGroups(mixed)
        assertEquals(listOf(1, 2), groups.map { it.number })
        assertEquals(2, groups.first().tracks.size)
    }

    @Test
    fun `a disc zero is the first disc, not a disc of its own`() {
        val groups = discGroups(listOf(track("a", 1, 0), track("b", 2, 0)))
        assertEquals(listOf(1), groups.map { it.number })
    }

    @Test
    fun `tracks are put in track order within a disc`() {
        val shuffled = listOf(track("c", 3, 1), track("a", 1, 1), track("b", 2, 1))
        assertEquals(listOf("a", "b", "c"), discGroups(shuffled).first().tracks.map { it.name })
    }

    @Test
    fun `an untagged album keeps the order the server sent`() {
        val asSent = listOf(track("c", null), track("a", null), track("b", null))
        assertEquals(listOf("c", "a", "b"), discGroups(asSent).first().tracks.map { it.name })
    }

    @Test
    fun `disc titles are attached to the disc they name`() {
        val groups = discGroups(disc(1, 2) + disc(2, 2), mapOf(2 to "Live at the Fillmore"))
        assertEquals(null, groups[0].title)
        assertEquals("Live at the Fillmore", groups[1].title)
    }
}
