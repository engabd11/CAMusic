package com.engabd.sendpin.plex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Plex `Metadata` mapping, held against payloads shaped like the ones a Plex
 * Media Server sends. See `JellyfinItemParseTest`/`EmbyItemParseTest` for the same
 * argument on the other backends; the property worth pinning down here is different —
 * Plex's hierarchy names both a track's *and* an album's parent `parentRatingKey`,
 * which is easy to wire to the wrong one of "the album" and "the artist".
 */
class PlexItemParseTest {

    private val client = PlexClient("http://nas.local:32400", token = "tok", librarySectionKey = "1")

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a track's parent is its album, not its artist`() {
        val track = client.item(
            obj(
                """
                {
                  "ratingKey": "301", "type": "track", "title": "Downtown Train",
                  "parentRatingKey": "201", "grandparentRatingKey": "101",
                  "parentTitle": "Rain Dogs", "grandparentTitle": "Tom Waits",
                  "index": 5, "parentIndex": 1, "duration": 245000, "year": 1985
                }
                """
            )
        )
        assertNotNull(track)
        assertEquals("201", track.parentId)
        assertEquals("Rain Dogs", track.album)
        assertEquals("Tom Waits", track.subtitle)
        assertEquals(5, track.trackNumber)
        assertEquals(1, track.discNumber)
        // Plex reports duration in milliseconds; MaItem wants whole seconds.
        assertEquals(245, track.duration)
    }

    @Test
    fun `an album's parent is its artist`() {
        val album = client.item(
            obj(
                """
                {"ratingKey": "201", "type": "album", "title": "Rain Dogs", "parentRatingKey": "101", "parentTitle": "Tom Waits"}
                """
            )
        )
        assertNotNull(album)
        assertEquals("101", album.parentId)
        assertEquals("Tom Waits", album.subtitle)
        assertNull(album.album)
    }

    @Test
    fun `an artist has no parent`() {
        val artist = client.item(obj("""{"ratingKey": "101", "type": "artist", "title": "Tom Waits", "childCount": 12}"""))
        assertNotNull(artist)
        assertNull(artist.parentId)
        assertEquals("12 albums", artist.subtitle)
    }

    @Test
    fun `an unknown type is not mapped`() {
        assertNull(client.item(obj("""{"ratingKey": "1", "type": "movie", "title": "Not music"}""")))
    }

    @Test
    fun `bitrate arrives already in kbps, unlike Jellyfin's bits per second`() {
        val track = client.item(
            obj(
                """
                {
                  "ratingKey": "301", "type": "track", "title": "Downtown Train",
                  "Media": [{
                    "audioCodec": "flac", "bitrate": 975, "audioChannels": 2,
                    "Part": [{"key": "/library/parts/301/x/file.flac", "size": 4200000, "container": "flac"}]
                  }]
                }
                """
            )
        )
        assertNotNull(track)
        assertEquals("flac", track.audioFormat?.codec)
        assertEquals(975, track.audioFormat?.bitRate)
        assertEquals(2, track.audioFormat?.channels)
        // Plex's basic Media element never carries sample rate or bit depth — 0
        // rather than a guess, matching every other client's "didn't say" contract.
        assertEquals(0, track.audioFormat?.sampleRate)
        assertEquals(0, track.audioFormat?.bitDepth)
    }

    @Test
    fun `a track's cover falls back to its album's, then its artist's`() {
        val track = client.item(
            obj(
                """{"ratingKey": "301", "type": "track", "title": "T", "parentThumb": "/library/metadata/201/thumb/1"}"""
            )
        )
        assertNotNull(track)
        assertNotNull(track.image)
        assert("url=" in track.image!!) { track.image!! }
    }
}
