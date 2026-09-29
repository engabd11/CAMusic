package com.engabd.sendpin.lyrics

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrclibTest {
    private val server = MockWebServer()

    @AfterTest fun tearDown() = server.close()

    private fun client() = Lrclib(OkHttpClient(), server.url("/"))

    @Test
    fun `an exact match returns the synced lyrics`() = runBlocking {
        server.start()
        server.enqueue(
            MockResponse.Builder().body(
                """{"id":1,"trackName":"Song","syncedLyrics":"[00:01.00]One\n[00:02.00]Two","plainLyrics":"One\nTwo"}""",
            ).build(),
        )
        val l = client().find("Song", "Artist", "Album", 180)!!
        assertTrue(l.synced)
        assertEquals(2, l.lines.size)
        val req = server.takeRequest()
        assertEquals("/api/get", req.url.encodedPath)
        assertEquals("Artist", req.url.queryParameter("artist_name"))
        assertEquals("180", req.url.queryParameter("duration"))
        assertTrue(req.headers["User-Agent"]!!.startsWith("CAMusic"))
    }

    @Test
    fun `no exact match falls back to a search within three seconds of the length`() = runBlocking {
        server.start()
        server.enqueue(MockResponse.Builder().code(404).body("""{"code":404}""").build())
        server.enqueue(
            MockResponse.Builder().body(
                """[{"duration":240,"plainLyrics":"live version"},{"duration":181.5,"plainLyrics":"studio words here"}]""",
            ).build(),
        )
        val l = client().find("Song", "Artist", null, 180)!!
        assertEquals("studio words here", l.text)
    }

    @Test
    fun `an instrumental has no lyrics`() = runBlocking {
        server.start()
        server.enqueue(MockResponse.Builder().body("""{"instrumental":true,"plainLyrics":null}""").build())
        server.enqueue(MockResponse.Builder().body("[]").build())
        assertNull(client().find("Song", "Artist", null, 180))
    }
}
