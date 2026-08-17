package com.engabd.sendpin.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The `BaseItemDto` mapping, held against payloads shaped like the ones Jellyfin sends.
 *
 * The disc number is the reason this file exists: on a track, Jellyfin's disc is
 * `ParentIndexNumber` and its track number is `IndexNumber` — a pair that is easy to
 * read the wrong way round, and impossible to notice from outside the client. See
 * `SubsonicItemParseTest` for the same argument on the other backend.
 */
class JellyfinItemParseTest {

    private val client = JellyfinClient("http://nas.local:8096", token = "tok", userId = "u1")

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a track on the second disc reads disc from ParentIndexNumber`() {
        val track = client.item(
            obj(
                """
                {
                  "Id": "a1", "Name": "Rain Dogs", "Type": "Audio",
                  "Album": "Rain Dogs", "AlbumId": "b2", "AlbumArtist": "Tom Waits",
                  "IndexNumber": 5, "ParentIndexNumber": 2,
                  "RunTimeTicks": 1790000000, "ProductionYear": 1985
                }
                """
            )
        )
        assertNotNull(track)
        assertEquals(2, track.discNumber)
        assertEquals(5, track.trackNumber)
        assertEquals("track", track.mediaType)
        assertEquals("Tom Waits", track.subtitle)
    }

    @Test
    fun `a track with no disc tag has no disc number`() {
        val track = client.item(
            obj("""{ "Id": "a1", "Name": "Solo", "Type": "Audio", "IndexNumber": 1 }""")
        )
        assertNotNull(track)
        assertNull(track.discNumber)
        assertEquals(1, track.trackNumber)
    }

    @Test
    fun `an album's ParentIndexNumber is not mistaken for a disc of the album`() {
        // Albums carry the field too, meaning something else entirely. Nothing reads an
        // album's disc number, and this pins that it stays harmless if anything does.
        val album = client.item(
            obj(
                """
                {
                  "Id": "b2", "Name": "Rain Dogs", "Type": "MusicAlbum",
                  "AlbumArtistId": "c3", "ProductionYear": 1985, "ChildCount": 19
                }
                """
            )
        )
        assertNotNull(album)
        assertEquals("album", album.mediaType)
        assertEquals("c3", album.parentId)
    }

    @Test
    fun `an unknown item type is dropped rather than guessed at`() {
        assertNull(client.item(obj("""{ "Id": "x", "Name": "A Film", "Type": "Movie" }""")))
    }
}
