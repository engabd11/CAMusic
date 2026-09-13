package com.engabd.sendpin.qobuz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Qobuz wire mapping, held against fixture JSON shaped like the payloads the
 * web app's `api.json/0.2` endpoints answer with (Music Assistant's provider is the
 * reference for every field name). Two things are worth more than the rest here:
 * the request signature is pinned to a precomputed golden MD5 — the one irreversible
 * step in the whole client, where a sorted-key mistake still *looks* right because
 * every signed request then fails server-side with nothing local to compare against —
 * and the Last.fm no-artwork placeholder must never win an image slot.
 */
class QobuzClientParseTest {

    private fun client() = QobuzClient(appId = "TESTAPP", appSecret = "TESTSECRET")

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    // ── Request signing ───────────────────────────────────────────────────

    @Test
    fun `the request signature matches the wire format`() {
        // Golden vector computed against the reference algorithm: endpoint with
        // slashes stripped + sorted "keyvalue" pairs + unix timestamp + app secret,
        // MD5-hexed. track/getFileUrl, format_id=5, intent=stream, track_id=123,
        // ts=1700000000, secret=TESTSECRET → d82f9418771981f722223c9fdfb89b79.
        val signed = client().signedRequest(
            endpoint = "track/getFileUrl",
            params = mapOf(
                "format_id" to "5",
                "intent" to "stream",
                "track_id" to "123",
            ),
            timestampSeconds = 1700000000,
        )
        assertEquals("d82f9418771981f722223c9fdfb89b79", signed.params["request_sig"])
        assertEquals("1700000000", signed.params["request_ts"])
        assertEquals("TESTAPP", signed.params["app_id"])
        // Not logged in on this client, so the token rides empty rather than absent:
        // the signature covered it, so the query must carry it either way.
        assertEquals("", signed.params["user_auth_token"])
    }

    @Test
    fun `signing sorts keys before hashing`() {
        // Same params in reverse insertion order must sign identically to the
        // golden vector — the sort is load-bearing, and hash input order is the
        // one thing a broken sort would show in.
        val signed = client().signedRequest(
            endpoint = "track/getFileUrl",
            params = mapOf(
                "track_id" to "123",
                "intent" to "stream",
                "format_id" to "5",
            ),
            timestampSeconds = 1700000000,
        )
        assertEquals("d82f9418771981f722223c9fdfb89b79", signed.params["request_sig"])
    }

    // ── Track parsing ─────────────────────────────────────────────────────

    @Test
    fun `a track carries its performer, album and hi-res format`() {
        val track = client().parseTrack(
            obj(
                """
                {
                  "id": 4812793, "title": "Light My Fire", "version": "Remastered",
                  "duration": 427, "track_number": 1, "media_number": 2,
                  "maximum_sampling_rate": 192.0, "maximum_bit_depth": 24,
                  "streamable": true, "displayable": true,
                  "performer": {"id": 7121, "name": "The Doors"},
                  "album": {
                    "id": "xl3htzq4kcbkc", "title": "The Doors",
                    "artist": {"id": 7120, "name": "The Doors"},
                    "image": {"large": "https://static.qobuz.com/xl.jpg"}
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("4812793", track!!.itemId)
        assertEquals("Light My Fire (Remastered)", track.name)
        assertEquals("track", track.mediaType)
        assertEquals("qobuz", track.provider)
        // The performer wins the subtitle over the album artist.
        assertEquals("The Doors", track.subtitle)
        assertEquals("The Doors", track.album)
        assertEquals("xl3htzq4kcbkc", track.parentId)
        assertEquals(427, track.duration)
        // media_number is Qobuz's disc number — this fixture's track sits on disc 2.
        assertEquals(2, track.discNumber)
        assertEquals(1, track.trackNumber)
        val format = track.audioFormat
        assertEquals(192, format!!.sampleRate)
        assertEquals(24, format.bitDepth)
        assertEquals("flac", format.codec)
        assertEquals("https://static.qobuz.com/xl.jpg", track.image)
        assertEquals("https://open.qobuz.com/track/4812793", track.uri)
    }

    @Test
    fun `a track without a performer borrows the album artist`() {
        val track = client().parseTrack(
            obj(
                """
                {
                  "id": 9, "title": "Solo Piano", "duration": 180,
                  "album": {"id": "a", "title": "Album", "artist": {"id": 1, "name": "Chilly Gonzales"}}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("Chilly Gonzales", track!!.subtitle)
        assertNull(track.audioFormat)
    }

    // ── Album parsing ─────────────────────────────────────────────────────

    @Test
    fun `an album keeps its version, artist, year and genre`() {
        val album = client().parseAlbum(
            obj(
                """
                {
                  "id": "q4wtcbumfchbc", "title": "Random Access Memories",
                  "version": "10th Anniversary Edition",
                  "released_at": 1688169600,
                  "maximum_sampling_rate": 88.2, "maximum_bit_depth": 24,
                  "streamable": true, "displayable": true,
                  "genre": {"name": "Electronic"},
                  "artist": {"id": 997, "name": "Daft Punk"},
                  "image": {"large": "https://static.qobuz.com/ram.jpg"}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("q4wtcbumfchbc", album!!.itemId)
        assertEquals("Random Access Memories (10th Anniversary Edition)", album.name)
        assertEquals("Daft Punk", album.subtitle)
        assertEquals(997, album.parentId!!.toInt())
        assertEquals(2023, album.year)
        assertEquals(listOf("Electronic"), album.genres)
        val format = album.audioFormat
        assertEquals(88, format!!.sampleRate)
        assertEquals("https://open.qobuz.com/album/q4wtcbumfchbc", album.uri)
    }

    // ── Image picking ─────────────────────────────────────────────────────

    @Test
    fun `the lastfm no-artwork placeholder never wins a slot`() {
        val image = client().parseImage(
            obj(
                """
                {
                  "image": {
                    "extralarge": "https://last.fm/2a96cbd8b46e442fc41c2b86b821562f.png",
                    "large": "https://static.qobuz.com/real.jpg"
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("https://static.qobuz.com/real.jpg", image)
    }

    @Test
    fun `a placeholder-only object has no image`() {
        val image = client().parseImage(
            obj(
                """
                {"image": {"large": "https://last.fm/2a96cbd8b46e442fc41c2b86b821562f.png"}}
                """.trimIndent(),
            ),
        )
        assertNull(image)
    }

    @Test
    fun `playlists carry their art in images300`() {
        val image = client().parseImage(
            obj("""{"images300": ["https://static.qobuz.com/pl.jpg", "other"]}"""),
        )
        assertEquals("https://static.qobuz.com/pl.jpg", image)
    }

    // ── Artist & playlist parsing ─────────────────────────────────────────

    @Test
    fun `an artist parses with its biography when present`() {
        val artist = client().parseArtist(
            obj(
                """
                {
                  "id": 7120, "name": "The Doors",
                  "image": {"large": "https://static.qobuz.com/doors.jpg"},
                  "biography": {"content": "An American rock band.", "language": "en"}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("7120", artist!!.itemId)
        assertEquals("The Doors", artist.name)
        assertEquals("artist", artist.mediaType)
        assertNull(artist.subtitle)
        assertEquals("https://static.qobuz.com/doors.jpg", artist.image)
    }

    @Test
    fun `a playlist names its owner in the subtitle`() {
        val playlist = client().parsePlaylist(
            obj(
                """
                {
                  "id": 512864, "name": "Late night hi-res",
                  "owner": {"id": 42, "name": "abdullah"},
                  "duration": 3600,
                  "images300": ["https://static.qobuz.com/pl.jpg"]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("512864", playlist!!.itemId)
        assertEquals("Late night hi-res", playlist.name)
        assertEquals("abdullah", playlist.subtitle)
        assertEquals(3600, playlist.duration)
        assertTrue(playlist.browsable)
    }

    @Test
    fun `an object without an id is skipped rather than crashed on`() {
        assertNull(client().parseTrack(obj("""{"title": "no id here"}""")))
        assertNull(client().parseAlbum(obj("""{"id": ""}""")))
        assertNull(client().parseArtist(obj("""{"name": "nameless id"}""")))
    }

    @Test
    fun `the account card reads the login's user object`() {
        val u = Json.parseToJsonElement(
            """{"display_name":"Ada","country_code":"GB",
                "subscription":{"offer":"studio"},
                "credential":{"label":"Qobuz Studio","parameters":{"lossless_streaming":true,"hires_streaming":true}}}""",
        ).jsonObject
        val a = client().parseAccount(u)
        assertEquals("Ada", a.name)
        assertEquals("studio", a.plan)
        assertEquals("GB", a.country)
        assertEquals("Hi-Res", a.maxQuality)
        assertEquals(true, a.hiResAllowed)
    }

    @Test
    fun `a CD-only plan says so, and an unknown one says nothing`() {
        val cd = Json.parseToJsonElement(
            """{"email":"ada@example.com","credential":{"parameters":{"lossless_streaming":true,"hires_streaming":false}}}""",
        ).jsonObject
        val a = client().parseAccount(cd)
        assertEquals("ada@example.com", a.name)
        assertEquals("CD quality", a.maxQuality)
        assertEquals(false, a.hiResAllowed)
        val bare = client().parseAccount(Json.parseToJsonElement("""{"login":"ada"}""").jsonObject)
        assertEquals("ada", bare.name)
        assertNull(bare.maxQuality)
        assertNull(bare.hiResAllowed)
    }
}
