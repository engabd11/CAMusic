package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * App-kept playlists: the pure store operations, and a wrapped library routing its
 * own playlists to the store and the server's to the server.
 */
class AppPlaylistsTest {

    private fun track(id: String) = MaItem(
        itemId = id, provider = "plex", name = "Song $id", uri = id,
        mediaType = "track", subtitle = "Artist", image = "img/$id", duration = 180,
    )

    @Test
    fun `appending keeps order and skips tracks already there`() {
        val p = AppPlaylists.Playlist("app-playlist:1", "Mix", 0, listOf(AppPlaylists.entryOf(track("a"))))
        val next = AppPlaylists.appended(listOf(p), "app-playlist:1", listOf(track("a"), track("b"), track("c")))
        assertEquals(listOf("a", "b", "c"), next.single().tracks.map { it.itemId })
    }

    @Test
    fun `a stored track comes back as a playable row of its library`() {
        val row = AppPlaylists.itemOf(AppPlaylists.entryOf(track("x")), "plex")
        assertEquals("plex", row.provider)
        assertEquals("track", row.mediaType)
        assertEquals("x", row.uri)
        assertTrue(row.playable)
    }

    @Test
    fun `a playlist row says where it lives and wears its first cover`() {
        val p = AppPlaylists.Playlist("app-playlist:2", "Road", 0, listOf(track("a"), track("b")).map(AppPlaylists::entryOf))
        val row = AppPlaylists.rowOf(p, "plex")
        assertEquals("playlist", row.mediaType)
        assertEquals("img/a", row.image)
        assertTrue(row.subtitle!!.contains("2 songs"))
    }

    /** A library with server playlists but no way to write them. */
    private class ReadOnlyServer : MusicSource {
        val deleted = mutableListOf<String>()
        override val kind = ServerKind.PLEX
        override val providerId = "plex"
        override val serverUrl = "http://plex"
        override val capabilities = setOf(Capability.PLAYLIST_READ)
        override suspend fun probe(): SourceError? = null
        override suspend fun artists() = emptyList<MaItem>()
        override suspend fun albums(offset: Int, limit: Int) = emptyList<MaItem>()
        override suspend fun playlists() = listOf(MaItem("srv1", "plex", "Server list", "srv1", "playlist", null, null, null))
        override suspend fun artistDetail(id: String) = null to emptyList<MaItem>()
        override suspend fun albumDetail(id: String) = null to emptyList<MaItem>()
        override suspend fun playlistTracks(id: String) = listOf(MaItem("s", "plex", "From server", "s", "track", null, null, null))
        override suspend fun search(query: String, limit: Int) = MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        override fun streamUrl(id: String, format: String) = ""
        override fun coverUrl(id: String?, size: Int): String? = null
        override suspend fun deletePlaylist(id: String) { deleted += id }
        override suspend fun children(item: MaItem) = emptyList<MaItem>()
        override suspend fun tracksUnder(item: MaItem) = playlistTracks(item.itemId)
        override suspend fun song(id: String): MaItem? = null
        override suspend fun recentlyAdded(limit: Int) = emptyList<MaItem>()
        override suspend fun favorites() = MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        override fun downloadUrl(id: String) = ""
        override var streamFormat: String = "raw"
    }

    /** The store, in memory. */
    private class MemoryStore : AppPlaylists.Store {
        var list = listOf<AppPlaylists.Playlist>()
        override fun all() = list
        override fun create(name: String, tracks: List<MaItem>): String {
            val id = AppPlaylists.ID_PREFIX + (list.size + 1)
            list = list + AppPlaylists.Playlist(id, name, 0, tracks.map(AppPlaylists::entryOf))
            return id
        }
        override fun append(id: String, tracks: List<MaItem>) { list = AppPlaylists.appended(list, id, tracks) }
        override fun delete(id: String) { list = list.filterNot { it.id == id } }
    }

    @Test
    fun `a library that cannot write playlists gets working ones, beside the server's`() = runBlocking {
        val server = ReadOnlyServer()
        val wrapped = WithAppPlaylists(server, MemoryStore())
        assertTrue(Capability.PLAYLIST_WRITE in wrapped.capabilities)

        val id = wrapped.createPlaylistFrom("Road trip", listOf(track("a"), track("b")))!!
        wrapped.addToPlaylistFrom(id, listOf(track("c")))
        assertEquals(listOf("Server list", "Road trip"), wrapped.playlists().map { it.name })

        val row = wrapped.playlists().last()
        assertEquals(listOf("a", "b", "c"), wrapped.tracksUnder(row).map { it.itemId })
        assertEquals(listOf("a", "b", "c"), wrapped.playlistTracks(id).map { it.itemId })

        // The server's playlist is still the server's.
        assertEquals("From server", wrapped.playlistTracks("srv1").single().name)
        wrapped.deletePlaylist("srv1")
        assertEquals(listOf("srv1"), server.deleted)

        wrapped.deletePlaylist(id)
        assertEquals(listOf("Server list"), wrapped.playlists().map { it.name })
    }
}
