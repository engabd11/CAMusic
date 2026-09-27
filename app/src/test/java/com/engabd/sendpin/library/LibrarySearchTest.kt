package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrarySearchTest {

    private val nav = ServerConfig(id = "nav", kind = ServerKind.NAVIDROME, label = "Home NAS")
    private val jf = ServerConfig(id = "jf", kind = ServerKind.JELLYFIN, label = "Jellyfin")

    private fun track(id: String, provider: String, artist: String? = "Artist") = MaItem(
        itemId = id, provider = provider, name = "Song $id", uri = null, mediaType = "track",
        subtitle = artist, image = null, duration = 200,
    )

    private fun results(vararg tracks: MaItem) = MaSearchResults(emptyList(), emptyList(), tracks.toList(), emptyList())

    @Test
    fun `the active library comes first and unlabelled, the others tagged and named`() {
        val merged = LibrarySearch.merge(
            activeId = "nav",
            hits = listOf(
                LibrarySearch.Hit(jf, results(track("j1", "jellyfin"))),
                LibrarySearch.Hit(nav, results(track("n1", "subsonic"))),
            ),
        )
        assertEquals(listOf("n1", "j1"), merged.tracks.map { it.itemId })
        assertNull(merged.tracks[0].serverId)
        assertEquals("Artist", merged.tracks[0].subtitle)
        assertEquals("jf", merged.tracks[1].serverId)
        // The artist stays the artist — it is what a played track's metadata is made of.
        assertEquals("Artist", merged.tracks[1].subtitle)
        assertEquals("Jellyfin", merged.tracks[1].serverLabel)
    }

    @Test
    fun `an item with no subtitle still carries its library's name`() {
        val merged = LibrarySearch.merge("nav", listOf(LibrarySearch.Hit(jf, results(track("j1", "jellyfin", artist = null)))))
        assertNull(merged.tracks.single().subtitle)
        assertEquals("Jellyfin", merged.tracks.single().serverLabel)
    }

    @Test
    fun `a slow or failing library costs only its own answer`() = runTest {
        val hits = LibrarySearch.fanOut(listOf(nav, jf), timeoutMs = 100) { c ->
            when (c.id) {
                "nav" -> results(track("n1", "subsonic"))
                else -> { delay(5_000); results(track("j1", "jellyfin")) }
            }
        }
        assertEquals(listOf("nav"), hits.map { it.config.id })

        val withError = LibrarySearch.fanOut(listOf(nav, jf), timeoutMs = 100) { c ->
            if (c.id == "jf") error("unreachable") else results(track("n1", "subsonic"))
        }
        assertEquals(listOf("nav"), withError.map { it.config.id })
    }
}
