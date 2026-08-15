package com.engabd.sendpin.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL half of the Jellyfin client, which is pure and is where the failures that
 * look like "the server is down" actually live — a wrong scheme, a lost token, a
 * transcode asked for when the original was wanted.
 *
 * Modelled on `SubsonicUrlTest` deliberately: the two clients are interchangeable
 * behind `MusicSource`, so the properties worth pinning down are the same ones.
 */
class JellyfinUrlTest {

    private fun client(url: String) = JellyfinClient(url, token = "tok123", userId = "u1")

    // ─── Scheme guessing ─────────────────────────────────────────────────────

    @Test
    fun `a LAN address without a scheme is http, not https`() {
        // Same reasoning as Subsonic: nothing self-hosted on 192.168/x is holding a
        // valid certificate, and defaulting to https just fails to connect.
        assertEquals("http://192.168.0.10:8096", client("192.168.0.10:8096").serverUrl)
        assertEquals("http://nas.local:8096", client("nas.local:8096").serverUrl)
        assertEquals("http://localhost:8096", client("localhost:8096").serverUrl)
    }

    @Test
    fun `a public hostname without a scheme is https`() {
        // This one decides whether the access token crosses the internet in the clear.
        assertEquals("https://jelly.example.com", client("jelly.example.com").serverUrl)
    }

    @Test
    fun `an explicit scheme is left alone, and a trailing slash is trimmed`() {
        assertEquals("http://jelly.example.com", client("http://jelly.example.com/").serverUrl)
        assertEquals("https://192.168.0.10:8096", client("https://192.168.0.10:8096").serverUrl)
    }

    // ─── Streaming ───────────────────────────────────────────────────────────

    /**
     * The default has to be the stored file, and it has to go to `/stream`.
     *
     * `static=true` is a parameter of `/Audio/{id}/stream` and *not* of
     * `/Audio/{id}/universal`. Sent to `/universal` it is silently dropped, leaving a
     * request with no container profile at all — so the server has nothing to
     * direct-play against and either transcodes needlessly or answers 4xx. Since every
     * track in a queue is built from this one template, that failure presents as a
     * whole album skipping past in a couple of seconds, which is why this test names
     * the endpoint and not just the parameter.
     */
    @Test
    fun `the default stream asks for the original file, on the static endpoint`() {
        val url = client("192.168.0.10:8096").streamUrl("song1")
        assertTrue(url.startsWith("http://192.168.0.10:8096/Audio/song1/stream"), url)
        assertTrue("static=true" in url, url)
        assertTrue("container=" !in url, url)
    }

    /**
     * `/universal` negotiates against what the *client* can decode, so `container` is
     * a list. Sending only the requested one leaves the server unable to direct-play
     * a source that was already fine.
     */
    @Test
    fun `a codec token asks the universal endpoint to negotiate`() {
        val url = client("192.168.0.10:8096").streamUrl("song1", "flac")
        assertTrue(url.startsWith("http://192.168.0.10:8096/Audio/song1/universal"), url)
        assertTrue("static=true" !in url, url)
        assertTrue("audioCodec=flac" in url, url)
        assertTrue("transcodingContainer=flac" in url, url)
        // The negotiation list, url-encoded commas and all.
        assertTrue("container=flac%2Cmp3" in url, url)
    }

    /**
     * The token carries kbps, Jellyfin wants bits per second. Sending 320 where
     * 320000 was meant asks for a 0.3 kbps stream.
     *
     * It is `maxStreamingBitrate`, not `audioBitRate`: the latter is a parameter of
     * `/stream`, and on `/universal` it is dropped — taking the whole bandwidth cap
     * with it, which is the entire reason the user picked a lossy quality.
     */
    @Test
    fun `a bitrate in the token becomes a max streaming bitrate in bits per second`() {
        val url = client("192.168.0.10:8096").streamUrl("song1", "mp3-320")
        assertTrue("audioCodec=mp3" in url, url)
        assertTrue("maxStreamingBitrate=320000" in url, url)
    }

    @Test
    fun `the token rides on the stream URL, since ExoPlayer opens it without our headers`() {
        assertTrue("api_key=tok123" in client("192.168.0.10:8096").streamUrl("song1"))
    }

    @Test
    fun `the user id rides on the stream URL for permission checks`() {
        val url = client("192.168.0.10:8096").streamUrl("song1")
        assertTrue("userId=u1" in url, url)
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
    fun `a cover URL carries the requested width`() {
        val url = client("192.168.0.10:8096").coverUrl("alb1", 500)!!
        assertTrue(url.startsWith("http://192.168.0.10:8096/Items/alb1/Images/Primary"), url)
        assertTrue("maxWidth=500" in url, url)
    }

    /**
     * Coil opens cover URLs itself, without the `Authorization` header every other
     * request carries. On a server with anonymous image access disabled that is a 401
     * per cover, and the whole library renders blank.
     */
    @Test
    fun `a cover URL carries the token, since Coil opens it without our headers`() {
        assertTrue("api_key=tok123" in client("192.168.0.10:8096").coverUrl("alb1")!!)
    }

    @Test
    fun `a cover URL omits the token when there isn't one yet`() {
        val anon = JellyfinClient("192.168.0.10:8096", token = "", userId = "u1")
        assertEquals(
            "http://192.168.0.10:8096/Items/alb1/Images/Primary?maxWidth=1000",
            anon.coverUrl("alb1"),
        )
    }
}
