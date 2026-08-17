package com.engabd.sendpin.ui.screens

import com.engabd.sendpin.download.DownloadedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downloads, gathered back into the records they came from.
 *
 * Two properties matter and pull in opposite directions: the album order must be
 * whatever the chosen sort produced — otherwise "Recent" stops meaning recent — while
 * the tracks *inside* an album must be in the album's own order, which is disc first
 * and then track. A two-disc download sorted by track number alone interleaves the
 * discs, which is what it did before it carried a disc number at all.
 */
class DownloadGroupingTest {

    private fun track(
        id: String,
        title: String = "Track $id",
        album: String? = "Rain Dogs",
        albumId: String? = "al-1",
        artist: String? = "Tom Waits",
        trackNumber: Int? = null,
        discNumber: Int? = null,
    ) = DownloadedTrack(
        id = id, title = title, artist = artist, filePath = "/tmp/$id.flac",
        album = album, albumId = albumId, trackNumber = trackNumber, discNumber = discNumber,
    )

    @Test
    fun `tracks gather into the album they came from`() {
        val albums = downloadAlbums(
            listOf(
                track("1", trackNumber = 1),
                track("2", trackNumber = 2),
                track("9", album = "Swordfishtrombones", albumId = "al-2", trackNumber = 1),
            )
        )
        assertEquals(listOf("Rain Dogs", "Swordfishtrombones"), albums.map { it.title })
        assertEquals(listOf(2, 1), albums.map { it.tracks.size })
    }

    @Test
    fun `a two-disc album plays disc one before disc two`() {
        val albums = downloadAlbums(
            listOf(
                track("d2t1", trackNumber = 1, discNumber = 2),
                track("d1t2", trackNumber = 2, discNumber = 1),
                track("d1t1", trackNumber = 1, discNumber = 1),
                track("d2t2", trackNumber = 2, discNumber = 2),
            )
        )
        assertEquals(listOf("d1t1", "d1t2", "d2t1", "d2t2"), albums.single().tracks.map { it.id })
    }

    @Test
    fun `downloads made before disc numbers were recorded still sort by track`() {
        val albums = downloadAlbums(
            listOf(track("c", trackNumber = 3), track("a", trackNumber = 1), track("b", trackNumber = 2))
        )
        assertEquals(listOf("a", "b", "c"), albums.single().tracks.map { it.id })
    }

    @Test
    fun `the album order follows the order the list arrived in`() {
        // Whatever sort produced the list decides which record is at the top; grouping
        // must not quietly alphabetise it.
        val albums = downloadAlbums(
            listOf(
                track("z", album = "Zuma", albumId = "al-z", trackNumber = 1),
                track("a", album = "Achtung", albumId = "al-a", trackNumber = 1),
            )
        )
        assertEquals(listOf("Zuma", "Achtung"), albums.map { it.title })
    }

    @Test
    fun `two records with the same name stay apart when their ids differ`() {
        val albums = downloadAlbums(
            listOf(
                track("1", album = "Greatest Hits", albumId = "al-1", artist = "Queen"),
                track("2", album = "Greatest Hits", albumId = "al-2", artist = "Neil Young"),
            )
        )
        assertEquals(2, albums.size)
        assertEquals(listOf("Queen", "Neil Young"), albums.map { it.artist })
    }

    @Test
    fun `an older download with no album id groups by name instead`() {
        val albums = downloadAlbums(
            listOf(
                track("1", albumId = null, trackNumber = 1),
                track("2", albumId = null, trackNumber = 2),
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Rain Dogs", albums.single().title)
    }

    @Test
    fun `tracks belonging to no album land together under Singles`() {
        val albums = downloadAlbums(
            listOf(
                track("1", album = null, albumId = null, title = "One"),
                track("2", album = "", albumId = "", title = "Two"),
            )
        )
        assertEquals(1, albums.size)
        assertEquals("Singles", albums.single().title)
        assertEquals(2, albums.single().tracks.size)
    }

    @Test
    fun `an album's size is the sum of its files`() {
        val albums = downloadAlbums(
            listOf(track("1", trackNumber = 1), track("2", trackNumber = 2)),
            sizes = mapOf("1" to 30_000_000L, "2" to 12_000_000L),
        )
        assertEquals(42_000_000L, albums.single().bytes)
    }

    @Test
    fun `sorts that cut across albums are not grouped`() {
        // Heading a title-sorted list by album would produce one heading per row.
        assertTrue(DownloadSort.ADDED.grouped)
        assertTrue(DownloadSort.ALBUM.grouped)
        assertTrue(DownloadSort.entries.none { it.grouped && it == DownloadSort.TITLE })
        assertEquals(
            listOf(DownloadSort.TITLE, DownloadSort.ARTIST, DownloadSort.SIZE),
            DownloadSort.entries.filterNot { it.grouped },
        )
    }
}
