package com.engabd.sendpin.mpd

import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MpdSource
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * URL-building and config tests for the MPD client — the same shape as
 * `SubsonicUrlTest`, because the URL half is pure and is where "the server is
 * down" bugs actually live.
 */
class MpdUrlTest {

    private fun client(address: String, password: String = "") = MpdClient(address, password)

    @Test
    fun `a LAN address without a scheme is http`() {
        // MPD is always plain TCP — no TLS — so the scheme is always http.
        assertEquals("http://192.168.0.202:6600", client("192.168.0.202:6600").serverUrl)
        assertEquals("http://nas.local:6600", client("nas.local:6600").serverUrl)
        assertEquals("http://localhost:6600", client("localhost:6600").serverUrl)
    }

    @Test
    fun `an explicit scheme is stripped and replaced with http`() {
        // MPD doesn't support TLS, so even if the user types https://, the
        // connection is plain TCP. The URL normalises to http.
        assertEquals("http://192.168.0.202:6600", client("http://192.168.0.202:6600").serverUrl)
        assertEquals("http://192.168.0.202:6600", client("https://192.168.0.202:6600").serverUrl)
    }

    @Test
    fun `a trailing slash is trimmed`() {
        assertEquals("http://192.168.0.202:6600", client("192.168.0.202:6600/").serverUrl)
    }

    @Test
    fun `the stream URL uses the configured HTTP port`() {
        val c = client("192.168.0.202:6600")
        c.httpPort = 8000
        assertEquals("http://192.168.0.202:8000", c.streamUrl("any/file.flac"))
    }

    @Test
    fun `the stream URL is independent of the track id`() {
        // MPD's HTTP output is a continuous stream — the URL doesn't change per
        // track. The track is selected by adding it to MPD's queue, not by URL.
        val c = client("192.168.0.202:6600")
        val url1 = c.streamUrl("track1.flac")
        val url2 = c.streamUrl("track2.flac")
        assertEquals(url1, url2)
    }

    @Test
    fun `the download URL is the same as the stream URL`() {
        // MPD's HTTP output serves the original file — no transcode endpoint.
        val c = client("192.168.0.202:6600")
        assertEquals(c.streamUrl("file.flac"), c.downloadUrl("file.flac"))
    }

    @Test
    fun `cover URL is null`() {
        // MPD doesn't serve cover art — the UI handles this with a placeholder.
        assertEquals(null, client("192.168.0.202:6600").coverUrl("anything"))
        assertEquals(null, client("192.168.0.202:6600").coverUrl(null))
    }

    @Test
    fun `a custom HTTP port is used when configured`() {
        val c = client("192.168.0.202:6600")
        c.httpPort = 8080
        assertEquals("http://192.168.0.202:8080", c.streamUrl("file.flac"))
    }

    @Test
    fun `serverUrl has no port when none is given`() {
        // The serverUrl is for display; the TCP port defaults to 6600 internally.
        val c = client("192.168.0.202")
        assertEquals("http://192.168.0.202", c.serverUrl)
    }

    @Test
    fun `MPD is registered as a supported ServerKind`() {
        // The picker shows it, and it plays locally (not through MA speakers).
        assertTrue(ServerKind.MPD.supported, "MPD must be marked as supported")
        assertTrue(ServerKind.MPD.playsLocally, "MPD plays on this phone via ExoPlayer")
    }

    @Test
    fun `MPD uses optional user password auth`() {
        // MPD servers may or may not require a password — the form should show
        // the fields but not demand them.
        assertEquals(
            com.engabd.sendpin.library.AuthStyle.OPTIONAL_USER_PASSWORD,
            ServerKind.MPD.auth,
        )
    }

    @Test
    fun `MPD needs an address`() {
        assertTrue(ServerKind.MPD.needsAddress, "MPD needs a host:port to connect to")
    }

    @Test
    fun `MPD is the one source that has to prepare playback`() {
        // The local player installs its prepare hook only for a source that says
        // this — with the hook set it holds the first track back until MPD has been
        // told what to play, and every other library keeps the straight path.
        assertTrue(MpdSource(client("192.168.0.202:6600")).needsPreparePlayback)
    }

    @Test
    fun `MPD offers no downloads and no favourites`() {
        // The HTTP output is a live stream, so there is no file to fetch, and MPD
        // has no starred concept without stickers. Both are gated on the
        // capability, so claiming either would put a button on screen that fails.
        val source = MpdSource(client("192.168.0.202:6600"))
        assertFalse(source.has(Capability.DOWNLOAD))
        assertFalse(source.has(Capability.FAVORITES))
        assertTrue(source.has(Capability.RICH_FORMAT), "tags carry codec, rate and depth")
    }

    @Test
    fun `OPT_MPD_HTTP_PORT is defined for the HTTP streaming port`() {
        // The setting key exists so the HTTP port can be stored per-server.
        assertEquals("mpdHttpPort", ServerConfig.OPT_MPD_HTTP_PORT)
    }
}