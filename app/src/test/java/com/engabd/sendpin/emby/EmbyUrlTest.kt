package com.engabd.sendpin.emby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL half of the Emby client. Modelled on `JellyfinUrlTest`: the two clients sit
 * behind the same `MusicSource` interface, so the properties worth pinning down are
 * the same ones — except streaming, where Emby has no `/universal` negotiator and a
 * transcode has to name its container in the path instead.
 */
class EmbyUrlTest {

    private fun client(url: String) = EmbyClient(url, token = "tok123", userId = "u1")

    // ─── Scheme guessing ─────────────────────────────────────────────────────

    @Test
    fun `a LAN address without a scheme is http, not https`() {
        assertEquals("http://192.168.0.10:8096", client("192.168.0.10:8096").serverUrl)
        assertEquals("http://nas.local:8096", client("nas.local:8096").serverUrl)
        assertEquals("http://localhost:8096", client("localhost:8096").serverUrl)
    }

    @Test
    fun `a public hostname without a scheme is https`() {
        assertEquals("https://emby.example.com", client("emby.example.com").serverUrl)
    }

    @Test
    fun `an explicit scheme is left alone, and a trailing slash is trimmed`() {
        assertEquals("http://emby.example.com", client("http://emby.example.com/").serverUrl)
        assertEquals("https://192.168.0.10:8096", client("https://192.168.0.10:8096").serverUrl)
    }

    // ─── Streaming ───────────────────────────────────────────────────────────

    @Test
    fun `the default stream asks for the original file, on the static endpoint`() {
        val url = client("192.168.0.10:8096").streamUrl("song1")
        assertTrue(url.startsWith("http://192.168.0.10:8096/Audio/song1/stream"), url)
        assertTrue("static=true" in url, url)
        assertTrue("stream." !in url, url)
    }

    /**
     * Emby has no `/universal` negotiator, unlike Jellyfin — a transcode names its
     * container directly in the path, `/Audio/{id}/stream.{container}`, with the
     * codec and bitrate as plain query parameters.
     */
    @Test
    fun `a codec token asks for stream dot container, not universal`() {
        val url = client("192.168.0.10:8096").streamUrl("song1", "flac")
        assertTrue(url.startsWith("http://192.168.0.10:8096/Audio/song1/stream.flac"), url)
        assertTrue("static=true" !in url, url)
        assertTrue("audioCodec=flac" in url, url)
    }

    @Test
    fun `a bitrate in the token becomes an audio bit rate in bits per second`() {
        val url = client("192.168.0.10:8096").streamUrl("song1", "mp3-320")
        assertTrue(url.startsWith("http://192.168.0.10:8096/Audio/song1/stream.mp3"), url)
        assertTrue("audioCodec=mp3" in url, url)
        assertTrue("audioBitRate=320000" in url, url)
    }

    @Test
    fun `the token rides on the stream URL, since ExoPlayer opens it without our headers`() {
        assertTrue("api_key=tok123" in client("192.168.0.10:8096").streamUrl("song1"))
    }

    // ─── Download and cover ──────────────────────────────────────────────────

    @Test
    fun `download is the original file and takes no format hint`() {
        val url = client("192.168.0.10:8096").downloadUrl("song1")
        assertEquals("http://192.168.0.10:8096/Items/song1/Download?api_key=tok123", url)
    }

    @Test
    fun `a blank cover id is no URL rather than a broken one`() {
        assertEquals(null, client("192.168.0.10:8096").coverUrl(null))
        assertEquals(null, client("192.168.0.10:8096").coverUrl(""))
    }

    @Test
    fun `a cover URL carries the requested width and the token`() {
        val url = client("192.168.0.10:8096").coverUrl("alb1", 500)!!
        assertTrue(url.startsWith("http://192.168.0.10:8096/Items/alb1/Images/Primary"), url)
        assertTrue("maxWidth=500" in url, url)
        assertTrue("api_key=tok123" in url, url)
    }

    @Test
    fun `a cover URL omits the token when there isn't one yet`() {
        val anon = EmbyClient("192.168.0.10:8096", token = "", userId = "u1")
        assertEquals(
            "http://192.168.0.10:8096/Items/alb1/Images/Primary?maxWidth=1000",
            anon.coverUrl("alb1"),
        )
    }
}
