package com.engabd.sendpin.mpd

import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MpdSource
import com.engabd.sendpin.library.ServerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `there is no stream URL, because nothing streams`() {
        // MPD plays its own queue to its own outputs and this phone drives it, so
        // there is no URL for anything here to open. It used to answer with MPD's
        // httpd stream — one URL for every track, carrying whatever MPD happened
        // to be playing — which played the music a second time out of the phone,
        // a second behind the DAC, with a scrub bar that could not move.
        val source = MpdSource(client("192.168.0.202:6600"))
        assertEquals("", source.streamUrl("any/file.flac"))
        assertEquals("", source.streamUrl("another/file.flac"))
    }

    @Test
    fun `there is nothing to download either`() {
        // MPD serves audio to its own outputs; it has no endpoint that hands a
        // file over, which is why DOWNLOAD is not among its capabilities.
        assertEquals("", MpdSource(client("192.168.0.202:6600")).downloadUrl("file.flac"))
    }

    @Test
    fun `cover art has a URL of its own scheme`() {
        // MPD does serve artwork — down the protocol socket, not over HTTP — so
        // items carry an mpd-art URL and the image loader is taught to fetch it.
        // Returning null here is what left the whole library drawing placeholders.
        val url = MpdSource(client("192.168.0.202:6600")).coverUrl("Miles/Kind of Blue/01.flac")
        assertEquals("mpd-art://cover/Miles%2FKind+of+Blue%2F01.flac", url)
        assertEquals(null, MpdSource(client("192.168.0.202:6600")).coverUrl(null))
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
    fun `MPD is the one source that plays its own music`() {
        // The player attaches whatever a source hands back here and drives that
        // instead of decoding: transport out, status back. Every other library
        // answers null and this phone keeps playing the music itself.
        assertNotNull(MpdSource(client("192.168.0.202:6600")).remotePlayback())
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
}