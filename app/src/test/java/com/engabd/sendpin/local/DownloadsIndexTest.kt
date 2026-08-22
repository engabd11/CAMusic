package com.engabd.sendpin.local

import com.engabd.sendpin.download.DownloadedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Downloads arranged as a library.
 *
 * The grouping is the whole feature — without it Downloads is the flat list it always
 * was — and all of it is derived from tags that two different servers wrote at two
 * different times, so it has to survive holes.
 */
class DownloadsIndexTest {

    private fun track(
        id: String,
        title: String,
        artist: String? = "Boards of Canada",
        album: String? = "Music Has the Right to Children",
        albumId: String? = "nav-42",
        track: Int? = null,
        disc: Int? = null,
    ) = DownloadedTrack(
        id = id,
        title = title,
        artist = artist,
        filePath = "/data/$id.flac",
        album = album,
        albumId = albumId,
        durationMs = 200_000,
        trackNumber = track,
        discNumber = disc,
    )

    @Test
    fun `the source server's album id is preferred over the name`() {
        // A real identity, and it groups two pressings of the same record correctly.
        assertEquals("nav-42", DownloadsIndex.albumId(track("a", "Wildlife Analysis")))
    }

    @Test
    fun `rows written before album ids existed still group`() {
        val a = track("a", "One", albumId = null)
        val b = track("b", "Two", albumId = null)
        assertEquals(DownloadsIndex.albumId(a), DownloadsIndex.albumId(b))
        assertEquals(1, DownloadsIndex.albums(listOf(a, b)).size)
    }

    @Test
    fun `a two-disc album does not interleave`() {
        // Disc before track. Sorting on track number alone plays track 1 of disc 1,
        // track 1 of disc 2, track 2 of disc 1 — which is what the ordering this
        // inherited from the old flat list exists to prevent.
        val items = DownloadsIndex.items(
            listOf(
                track("d2t1", "D2T1", track = 1, disc = 2),
                track("d1t2", "D1T2", track = 2, disc = 1),
                track("d1t1", "D1T1", track = 1, disc = 1),
            ),
        )
        assertEquals(listOf("D1T1", "D1T2", "D2T1"), items.map { it.name })
    }

    @Test
    fun `artists and albums are derived from the tags`() {
        val list = listOf(
            track("a", "One"),
            track("b", "Two"),
            track("c", "Elsewhere", artist = "Aphex Twin", album = "Selected Ambient Works", albumId = "nav-7"),
        )
        assertEquals(listOf("Aphex Twin", "Boards of Canada"), DownloadsIndex.artists(list).map { it.name })
        assertEquals(2, DownloadsIndex.albums(list).size)
        assertEquals(2, DownloadsIndex.albumTracks(list, "nav-42").size)
    }

    @Test
    fun `an untagged download still appears somewhere`() {
        // Not silently dropped: a file with no tags is still a file the user
        // downloaded, and a library that hides it is worse than one that files it
        // under "Unknown".
        val list = listOf(track("x", "Untitled", artist = null, album = null, albumId = null))
        assertEquals("Unknown artist", DownloadsIndex.artists(list).single().name)
        assertEquals("Unknown album", DownloadsIndex.albums(list).single().name)
    }

    @Test
    fun `artist detail returns that artist's albums only`() {
        val list = listOf(
            track("a", "One"),
            track("c", "Elsewhere", artist = "Aphex Twin", album = "Selected Ambient Works", albumId = "nav-7"),
        )
        val id = DownloadsIndex.artistId("Aphex Twin")
        val (artist, albums) = DownloadsIndex.artistDetail(list, id)
        assertEquals("Aphex Twin", artist?.name)
        assertEquals(listOf("Selected Ambient Works"), albums.map { it.name })
    }

    @Test
    fun `search looks at title, artist and album`() {
        val list = listOf(track("a", "Wildlife Analysis"))
        assertEquals(1, DownloadsIndex.search(list, "wildlife", 10).tracks.size)
        assertEquals(1, DownloadsIndex.search(list, "boards", 10).artists.size)
        assertEquals(1, DownloadsIndex.search(list, "children", 10).albums.size)
        assertTrue(DownloadsIndex.search(list, "nothing here", 10).tracks.isEmpty())
    }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        val list = listOf(track("a", "One"))
        assertTrue(DownloadsIndex.search(list, "   ", 10).tracks.isEmpty())
    }

    @Test
    fun `a track item keeps the bare path the rest of the app compares against`() {
        // DownloadsSource.streamUrl returns the file:// form; this deliberately does
        // not, because existing comparisons are against the bare path.
        assertEquals("/data/a.flac", DownloadsIndex.item(track("a", "One")).uri)
    }
}
