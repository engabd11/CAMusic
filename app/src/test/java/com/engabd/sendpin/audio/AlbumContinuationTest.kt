package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carrying on in order once the queue runs out — the unshuffled half of "keep the
 * music going".
 *
 * The network half is an [AlbumWalk], so what is covered here is the part with
 * decisions in it: where an album ends, which record follows it, what is filtered
 * out, and when the walk declines rather than guessing. Declining matters as much as
 * answering: the caller falls back to the similarity ladder, and a wrong "next album"
 * would silently take that fallback's place.
 */
class AlbumContinuationTest {

    private fun track(id: String, album: String, n: Int) = MaItem(
        itemId = id, provider = "jellyfin", name = "Track $id", uri = id,
        mediaType = "track", subtitle = "Artist", image = null, duration = 180,
        parentId = album, trackNumber = n,
    )

    private fun album(id: String, artist: String?) = MaItem(
        itemId = id, provider = "jellyfin", name = "Album $id", uri = id,
        mediaType = "album", subtitle = "Artist", image = null, duration = null,
        parentId = artist,
    )

    /**
     * A tiny library: albums in a fixed order, each with three tracks, and a
     * discography per artist. Records what it was asked, because "did it need the
     * whole album list" is itself a property worth holding — that walk is the
     * expensive one.
     */
    private class FakeWalk(
        val albums: List<MaItem>,
        val tracks: Map<String, List<MaItem>>,
        val discographies: Map<String, List<MaItem>> = emptyMap(),
    ) : AlbumWalk {
        val asked = mutableListOf<String>()

        override suspend fun album(id: String): Pair<MaItem?, List<MaItem>> {
            asked += "album:$id"
            return albums.firstOrNull { it.itemId == id } to tracks[id].orEmpty()
        }

        override suspend fun artistAlbums(artistId: String): List<MaItem> {
            asked += "artist:$artistId"
            return discographies[artistId].orEmpty()
        }

        override suspend fun albumsPage(offset: Int, limit: Int): List<MaItem> {
            asked += "page:$offset"
            return albums.drop(offset).take(limit)
        }
    }

    private fun library(): FakeWalk {
        val a = album("a", "artist-1")
        val b = album("b", "artist-1")
        val c = album("c", "artist-2")
        return FakeWalk(
            albums = listOf(a, b, c),
            tracks = mapOf(
                "a" to listOf(track("a1", "a", 1), track("a2", "a", 2), track("a3", "a", 3)),
                "b" to listOf(track("b1", "b", 1), track("b2", "b", 2), track("b3", "b", 3)),
                "c" to listOf(track("c1", "c", 1), track("c2", "c", 2), track("c3", "c", 3)),
            ),
            discographies = mapOf("artist-1" to listOf(a, b), "artist-2" to listOf(c)),
        )
    }

    @Test
    fun `the rest of the record comes first, in order`() = runBlocking {
        val walk = library()
        val next = AlbumContinuation(walk).next(seed = track("a1", "a", 1), count = 2)
        assertEquals(listOf("a2", "a3"), next.map { it.itemId })
        // No need to go looking for another album while this one still has tracks.
        assertTrue(walk.asked.none { it.startsWith("artist:") || it.startsWith("page:") })
    }

    @Test
    fun `the end of a record continues into the artist's next one`() = runBlocking {
        val walk = library()
        val next = AlbumContinuation(walk).next(seed = track("a3", "a", 3), count = 3)
        assertEquals(listOf("b1", "b2", "b3"), next.map { it.itemId })
        // Answered from the discography — the whole-library album list is the
        // fallback, and paying for it here would be a scan per top-up.
        assertTrue(walk.asked.none { it.startsWith("page:") })
    }

    @Test
    fun `the end of an artist continues into the next album in the library`() = runBlocking {
        val walk = library()
        // "b" is artist-1's last record, so the walk falls through to the library's
        // own order, where "c" follows it.
        val next = AlbumContinuation(walk).next(seed = track("b3", "b", 3), count = 3)
        assertEquals(listOf("c1", "c2", "c3"), next.map { it.itemId })
        assertTrue(walk.asked.any { it.startsWith("page:") })
    }

    @Test
    fun `it walks through as many records as it takes to fill the batch`() = runBlocking {
        val walk = library()
        val next = AlbumContinuation(walk).next(seed = track("a2", "a", 2), count = 7)
        // a3, then all of b, then all of c — three records to fill seven slots.
        assertEquals(listOf("a3", "b1", "b2", "b3", "c1", "c2", "c3"), next.map { it.itemId })
    }

    @Test
    fun `what is already queued is never appended twice`() = runBlocking {
        val walk = library()
        val next = AlbumContinuation(walk)
            .next(seed = track("a1", "a", 1), count = 5, exclude = setOf("a2", "b1"))
        assertEquals(listOf("a3", "b2", "b3", "c1", "c2"), next.map { it.itemId })
    }

    @Test
    fun `a track with no album to walk declines rather than guessing`() = runBlocking {
        val walk = library()
        val orphan = MaItem(
            itemId = "x", provider = "jellyfin", name = "X", uri = "x",
            mediaType = "track", subtitle = "Artist", image = null, duration = 100,
            parentId = null,
        )
        // Empty, not "something plausible": the caller's fallback is the similarity
        // ladder, which is a better answer than a record picked for no reason.
        assertEquals(emptyList(), AlbumContinuation(walk).next(orphan, count = 5))
        assertEquals(emptyList(), AlbumContinuation(walk).next(seed = null, count = 5))
    }

    @Test
    fun `the last record in the library ends the walk`() = runBlocking {
        val walk = library()
        val next = AlbumContinuation(walk).next(seed = track("c3", "c", 3), count = 5)
        // Nothing after it, and deliberately not a wrap back round to "a" — a radio
        // that restarts the library from A is a radio that has stopped choosing.
        assertEquals(emptyList(), next.map { it.itemId })
    }

    @Test
    fun `the library album order is fetched once and reused`() = runBlocking {
        val walk = library()
        val continuation = AlbumContinuation(walk)
        continuation.next(seed = track("b3", "b", 3), count = 1)
        val firstScan = walk.asked.count { it.startsWith("page:") }
        continuation.next(seed = track("b3", "b", 3), count = 1)
        assertEquals(firstScan, walk.asked.count { it.startsWith("page:") })
        assertTrue(firstScan > 0)
    }

    @Test
    fun `a library that will not describe the album declines`() = runBlocking {
        val walk = object : AlbumWalk {
            override suspend fun album(id: String) = throw IllegalStateException("server down")
            override suspend fun artistAlbums(artistId: String): List<MaItem> = emptyList()
            override suspend fun albumsPage(offset: Int, limit: Int): List<MaItem> = emptyList()
        }
        assertEquals(emptyList(), AlbumContinuation(walk).next(track("a1", "a", 1), count = 5))
    }
}
