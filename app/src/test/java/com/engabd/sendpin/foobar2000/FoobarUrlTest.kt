package com.engabd.sendpin.foobar2000

import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.FoobarSource
import com.engabd.sendpin.library.ServerKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * URL-building, parsing, and config tests for the foobar2000 client — the same
 * shape as `MpdUrlTest`, because the parsing half is pure and is where "the
 * server is down" bugs actually live.
 */
class FoobarUrlTest {

    private fun client(address: String, password: String = "", username: String = "") =
        FoobarClient(address, password, username)

    @Test
    fun `a LAN address without a scheme gets http`() {
        assertEquals("http://192.168.0.138:8880", client("192.168.0.138:8880").serverUrl)
        assertEquals("http://nas.local:8880", client("nas.local:8880").serverUrl)
        assertEquals("http://localhost:8880", client("localhost:8880").serverUrl)
    }

    @Test
    fun `an explicit scheme is preserved`() {
        // Unlike MPD (always plain TCP, no TLS), Beefweb is HTTP, so the
        // scheme the user typed is honoured — https stays https.
        assertEquals("http://192.168.0.138:8880", client("http://192.168.0.138:8880").serverUrl)
        assertEquals("https://music.example.com", client("https://music.example.com").serverUrl)
    }

    @Test
    fun `a trailing slash is trimmed`() {
        assertEquals("http://192.168.0.138:8880", client("192.168.0.138:8880/").serverUrl)
        assertEquals("http://192.168.0.138:8880", client("http://192.168.0.138:8880/").serverUrl)
    }

    @Test
    fun `serverUrl has no port when none is given`() {
        assertEquals("http://192.168.0.138", client("192.168.0.138").serverUrl)
    }

    @Test
    fun `foobar2000 is registered as a supported ServerKind`() {
        assertTrue(ServerKind.FOOBAR2000.supported, "foobar2000 must be marked as supported")
        assertTrue(ServerKind.FOOBAR2000.playsLocally, "foobar2000 plays via its own output, driven remotely")
    }

    @Test
    fun `foobar2000 uses optional user password auth`() {
        // Beefweb's remote access may or may not require basic auth — the form
        // should show the fields but not demand them.
        assertEquals(
            com.engabd.sendpin.library.AuthStyle.OPTIONAL_USER_PASSWORD,
            ServerKind.FOOBAR2000.auth,
        )
    }

    @Test
    fun `foobar2000 needs an address`() {
        assertTrue(ServerKind.FOOBAR2000.hasAddress, "foobar2000 needs a host:port to connect to")
    }

    @Test
    fun `foobar2000 is the second source that plays its own music`() {
        // Like MPD, foobar2000 hands over a RemotePlayback instead of a stream URL.
        assertNotNull(FoobarSource(client("192.168.0.138:8880")).remotePlayback())
    }

    @Test
    fun `there is no stream URL, because nothing streams`() {
        // foobar2000 plays its own playlist to its own output — same as MPD.
        val source = FoobarSource(client("192.168.0.138:8880"))
        assertEquals("", source.streamUrl("C:\\Music\\track.flac"))
        assertEquals("", source.streamUrl("any/file.flac"))
    }

    @Test
    fun `there is nothing to download either`() {
        assertEquals("", FoobarSource(client("192.168.0.138:8880")).downloadUrl("file.flac"))
    }

    @Test
    fun `foobar2000 offers no downloads and no search`() {
        val source = FoobarSource(client("192.168.0.138:8880"))
        assertFalse(source.has(Capability.DOWNLOAD))
        assertFalse(source.has(Capability.SEARCH))
        assertTrue(source.has(Capability.RICH_FORMAT), "foobar2000 reports codec, rate and depth")
    }

    @Test
    fun `foobar2000 does not declare ReplayGain`() {
        // foobar2000 has its own ReplayGain, but Beefweb's API does not expose
        // a toggle for it. Declaring the capability would put a switch on
        // screen that can only ever do nothing.
        assertFalse(FoobarSource(client("192.168.0.138:8880")).has(Capability.REPLAY_GAIN))
    }

    // ── Parsing tests ─────────────────────────────────────────────────────

    @Test
    fun `buildTrack parses columns into a MaItem`() {
        val columns = listOf(
            "C:\\Music\\Miles Davis\\Kind of Blue\\01 - So What.flac",  // path
            "So What",          // title
            "Miles Davis",      // artist
            "Kind of Blue",     // album
            "Miles Davis",      // album artist
            "545",              // length_seconds
            "1/5",              // tracknumber
            "1",                // discnumber
            "1959",             // date
            "Jazz",             // genre
            "flac",             // codec
            "44100",            // samplerate
            "16",               // bitspersample
            "2",                // channels
            "900",              // bitrate
        )

        val track = FoobarClient.buildTrack(columns)
        assertEquals("C:\\Music\\Miles Davis\\Kind of Blue\\01 - So What.flac", track.itemId)
        assertEquals("So What", track.name)
        assertEquals("Miles Davis", track.subtitle)
        assertEquals("Kind of Blue", track.album)
        assertEquals(545, track.duration)
        assertEquals(1, track.trackNumber)
        assertEquals(1959, track.year)
        assertEquals(listOf("Jazz"), track.genres)
        assertNotNull(track.audioFormat)
        assertEquals("flac", track.audioFormat?.codec)
        assertEquals(44100, track.audioFormat?.sampleRate)
        assertEquals(16, track.audioFormat?.bitDepth)
        assertEquals(900, track.audioFormat?.bitRate)
    }

    @Test
    fun `buildTrack falls back to filename when title is blank`() {
        val columns = listOf("C:\\Music\\unknown.flac", "", "", "", "", "0", "", "", "", "", "", "", "", "", "")
        val track = FoobarClient.buildTrack(columns)
        assertEquals("unknown", track.name)
    }

    @Test
    fun `buildTrack has no audio format when codec is blank`() {
        val columns = listOf("track.wav", "Title", "Artist", "Album", "Artist", "180", "1", "1", "2020", "", "", "", "", "", "")
        val track = FoobarClient.buildTrack(columns)
        assertEquals(null, track.audioFormat)
    }

    @Test
    fun `buildAlbumId combines album and artist with NUL separator`() {
        assertEquals("Kind of Blue\u0000Miles Davis", FoobarClient.buildAlbumId("Kind of Blue", "Miles Davis"))
        assertEquals("Kind of Blue", FoobarClient.buildAlbumId("Kind of Blue", null))
    }

    // ── Player state parsing ──────────────────────────────────────────────

    @Test
    fun `readPlayerState parses a playing state`() {
        val player = JsonObject(
            mapOf(
                "playbackState" to JsonPrimitive("playing"),
                "activeItem" to JsonObject(
                    mapOf(
                        "playlistId" to JsonPrimitive("pl1"),
                        "playlistIndex" to JsonPrimitive(0),
                        "index" to JsonPrimitive(3),
                        "position" to JsonPrimitive(45.5),
                        "duration" to JsonPrimitive(325.0),
                    ),
                ),
                "volume" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("db"),
                        "min" to JsonPrimitive(-60.0),
                        "max" to JsonPrimitive(0.0),
                        "value" to JsonPrimitive(-12.0),
                        "isMuted" to JsonPrimitive(false),
                    ),
                ),
            ),
        )

        val state = FoobarClient.readPlayerState(player)
        assertEquals("playing", state.state)
        assertEquals("pl1", state.playlistId)
        assertEquals(3, state.itemIndex)
        assertEquals(45.5, state.positionSeconds)
        assertEquals(325.0, state.durationSeconds)
        assertEquals(-12.0, state.volumeValue)
        assertEquals(-60.0, state.volumeMin)
        assertEquals(0.0, state.volumeMax)
        assertFalse(state.isMuted)
        assertTrue(state.playing)
        assertFalse(state.stopped)
    }

    @Test
    fun `readPlayerState parses a stopped state with no active item`() {
        val player = JsonObject(
            mapOf(
                "playbackState" to JsonPrimitive("stopped"),
                "volume" to JsonObject(
                    mapOf(
                        "min" to JsonPrimitive(-60.0),
                        "max" to JsonPrimitive(0.0),
                        "value" to JsonPrimitive(-60.0),
                        "isMuted" to JsonPrimitive(true),
                    ),
                ),
            ),
        )

        val state = FoobarClient.readPlayerState(player)
        assertEquals("stopped", state.state)
        assertEquals(-1, state.itemIndex)
        assertEquals(0.0, state.positionSeconds)
        assertTrue(state.stopped)
        assertTrue(state.isMuted)
    }

    @Test
    fun `readPlayerState handles missing volume gracefully`() {
        val player = JsonObject(
            mapOf("playbackState" to JsonPrimitive("paused")),
        )

        val state = FoobarClient.readPlayerState(player)
        assertEquals("paused", state.state)
        assertEquals(null, state.volumeValue)
        assertEquals(null, state.volumeMin)
        assertEquals(null, state.volumeMax)
        assertFalse(state.isMuted)
        assertTrue(state.paused)
    }
}