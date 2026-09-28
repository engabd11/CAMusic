package com.engabd.sendpin.jellyfin

import com.engabd.sendpin.library.JellyfinSource
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Jellyfin playlist edits, against the request shapes checked live on Jellyfin 10.11:
 * entries are read from `/Playlists/{id}/Items` (the only endpoint that answers with a
 * `PlaylistItemId`), and removed and moved by that id.
 */
class JellyfinPlaylistEditTest {

    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.close()

    private val entries = """
        {"Items":[
          {"Id":"t1","Name":"One","Type":"Audio","PlaylistItemId":"e1"},
          {"Id":"t2","Name":"Two","Type":"Audio","PlaylistItemId":"e2"},
          {"Id":"t1","Name":"One","Type":"Audio","PlaylistItemId":"e3"}
        ],"TotalRecordCount":3}
    """.trimIndent()

    private fun source(): JellyfinSource {
        server.start()
        return JellyfinSource(JellyfinClient(server.url("/").toString().trimEnd('/'), token = "t", userId = "u"))
    }

    @Test
    fun `entries come from the playlist endpoint with their own ids`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(entries).build())
        val tracks = source().playlistTracks("pl")
        assertEquals("/Playlists/pl/Items", server.takeRequest().url.encodedPath)
        // The same track twice, told apart by its entry.
        assertEquals(listOf("e1", "e2", "e3"), tracks.map { it.entryId })
    }

    @Test
    fun `removing the second copy of a track removes that entry, not the track`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(entries).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        val src = source()
        val tracks = src.playlistTracks("pl")
        server.takeRequest()
        src.removeFromPlaylist("pl", listOf(2), tracks)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/Playlists/pl/Items", req.url.encodedPath)
        assertEquals("e3", req.url.queryParameter("EntryIds"))
    }

    @Test
    fun `moving and renaming hit the playlist's own endpoints`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(entries).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        val src = source()
        val tracks = src.playlistTracks("pl")
        server.takeRequest()
        src.movePlaylistEntry("pl", 1, 0, tracks)
        assertEquals("/Playlists/pl/Items/e2/Move/0", server.takeRequest().url.encodedPath)
        assertEquals("pl", src.renamePlaylist("pl", "New name"))
        val rename = server.takeRequest()
        assertEquals("/Playlists/pl", rename.url.encodedPath)
        assertEquals(true, rename.body?.utf8()?.contains("\"Name\":\"New name\""))
    }
}
