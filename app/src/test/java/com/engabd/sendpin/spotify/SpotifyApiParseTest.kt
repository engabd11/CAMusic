package com.engabd.sendpin.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify's Web API payload mapping, held against fixtures shaped like the real
 * responses (`me/tracks`, `search`). The pins that matter: a saved track arrives
 * wrapped in a `track` envelope and must unwrap, short album-tracks inherit their
 * album's context when the payload omits it, and duration arrives in milliseconds
 * but the app carries seconds.
 */
class SpotifyApiParseTest {

    private val api = SpotifyWebApi(tokenProvider = { "" })

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a saved track unwraps its envelope and converts duration to seconds`() {
        val track = api.parseTrack(
            obj(
                """
                {
                  "added_at": "2026-01-01T00:00:00Z",
                  "track": {
                    "id": "4uLU6hMCjMI75M1A2tKUQC",
                    "name": "Rickroll",
                    "duration_ms": 215000,
                    "track_number": 3, "disc_number": 1,
                    "artists": [{"id": "a1", "name": "Rick Astley"}],
                    "album": {
                      "id": "al1", "name": "Whenever You Need Somebody",
                      "images": [{"url": "https://i.scdn.co/image/ab67616d0000b273", "width": 640}]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", track!!.itemId)
        assertEquals("Rickroll", track.name)
        assertEquals("Rick Astley", track.subtitle)
        assertEquals("Whenever You Need Somebody", track.album)
        assertEquals("al1", track.parentId)
        assertEquals(215, track.duration)
        assertEquals(3, track.trackNumber)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273", track.image)
        assertEquals("spotify", track.provider)
    }

    @Test
    fun `a short album track borrows the album context it was parsed with`() {
        val album = api.parseAlbum(
            obj(
                """
                {
                  "id": "al9", "name": "Discovery",
                  "artists": [{"id": "ar5", "name": "Daft Punk"}],
                  "release_date": "2001-03-12",
                  "images": [{"url": "https://i.scdn.co/image/discovery"}]
                }
                """.trimIndent(),
            ),
        )!!
        val track = api.parseTrack(
            obj(
                """
                {
                  "id": "t7", "name": "One More Time", "duration_ms": 320000,
                  "artists": [{"name": "Daft Punk"}],
                  "album": {"name": "Discovery"}
                }
                """.trimIndent(),
            ),
            albumContext = album,
        )
        // The short album object has no id or image; the context fills both.
        assertEquals("al9", track!!.parentId)
        assertEquals("https://i.scdn.co/image/discovery", track.image)
        assertEquals(320, track.duration)
    }

    @Test
    fun `an album parses with its artist and year`() {
        val album = api.parseAlbum(
            obj(
                """
                {
                  "id": "al2", "name": "Random Access Memories",
                  "artists": [{"id": "ar2", "name": "Daft Punk"}],
                  "release_date": "2013-05-17",
                  "images": [{"url": "https://i.scdn.co/image/ram"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("al2", album!!.itemId)
        assertEquals("Daft Punk", album.subtitle)
        assertEquals("ar2", album.parentId)
        assertEquals(2013, album.year)
        assertEquals("album", album.mediaType)
    }

    @Test
    fun `an artist parses without a subtitle`() {
        val artist = api.parseArtist(
            obj(
                """
                {"id": "ar3", "name": "Chilly Gonzales",
                 "images": [{"url": "https://i.scdn.co/image/chilly"}]}
                """.trimIndent(),
            ),
        )
        assertEquals("ar3", artist!!.itemId)
        assertEquals("Chilly Gonzales", artist.name)
        assertNull(artist.subtitle)
        assertEquals("artist", artist.mediaType)
    }

    @Test
    fun `a playlist names its owner`() {
        val playlist = api.parsePlaylist(
            obj(
                """
                {
                  "id": "pl4", "name": "Late night coding",
                  "owner": {"display_name": "abdullah"},
                  "images": [{"url": "https://i.scdn.co/image/pl"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("pl4", playlist!!.itemId)
        assertEquals("abdullah", playlist.subtitle)
        assertEquals("playlist", playlist.mediaType)
    }

    @Test
    fun `a local file entry with no spotify id is skipped rather than crashed on`() {
        // Spotify's saved-tracks list mixes in local files, whose `track` has a
        // null id — dropping them is the only sane answer.
        assertNull(
            api.parseTrack(
                obj("""{"track": {"id": null, "name": "Local file", "uri": "file:///x.mp3"}}"""),
            ),
        )
    }

    /**
     * librespot rejects any device id that is not 40 hex characters — the app's UUID
     * player id is 36 — and did so on every login until the id was hashed.
     */
    @Test
    fun `the spotify device id is forty hex characters and stable`() {
        val id = com.engabd.sendpin.spotify.SpotifyEngine.spotifyDeviceId("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        assertEquals(40, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, id)
        assertEquals(id, com.engabd.sendpin.spotify.SpotifyEngine.spotifyDeviceId("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
    }
}
