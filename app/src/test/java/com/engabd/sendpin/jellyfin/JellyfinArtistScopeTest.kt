package com.engabd.sendpin.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The artist list follows the chosen library.
 *
 * `Items?IncludeItemTypes=MusicArtist` ignores ParentId on a real server (checked
 * against Jellyfin 10.11: 263 artists for either of two libraries), so the list used
 * to hold every library's artists while their albums stayed scoped.
 */
class JellyfinArtistScopeTest {

    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.close()

    private val oneArtist = """{"Items":[{"Id":"a1","Name":"Adele","Type":"MusicArtist"}],"TotalRecordCount":1}"""

    @Test
    fun `with a library chosen, artists come from the scoped endpoint`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(oneArtist).build())
        server.start()
        val client = JellyfinClient(server.url("/").toString().trimEnd('/'), token = "t", userId = "u", libraryId = "lib1")
        val artists = client.artists()
        val req = server.takeRequest()
        assertEquals("/Artists/AlbumArtists", req.url.encodedPath)
        assertEquals("lib1", req.url.queryParameter("ParentId"))
        assertEquals("Adele", artists.single().name)
    }

    @Test
    fun `with no library chosen, the whole server is listed as before`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(oneArtist).build())
        server.start()
        val client = JellyfinClient(server.url("/").toString().trimEnd('/'), token = "t", userId = "u", libraryId = "")
        client.artists()
        val req = server.takeRequest()
        assertTrue(req.url.encodedPath.endsWith("/Items"), req.url.encodedPath)
        assertEquals("MusicArtist", req.url.queryParameter("IncludeItemTypes"))
    }
}
