package com.engabd.sendpin.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The item mapping, held against payloads shaped like the ones Navidrome actually
 * sends.
 *
 * Written because "multi-disc albums show no disc headers on Navidrome" was a bug
 * report against a client that reads `discNumber` on the line right there: the field
 * being parsed and the field arriving are two different claims, and only one of them
 * was ever checked. These pin the mapping so the next report can be about the server
 * or the screen, and not about this.
 */
class SubsonicItemParseTest {

    private val client = SubsonicClient("http://nas.local:4533", "u", "p")

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ─── Songs ───────────────────────────────────────────────────────────────

    @Test
    fun `a song on the second disc keeps its disc number`() {
        val song = client.songItem(
            obj(
                """
                {
                  "id": "301", "parent": "12", "title": "Cinnamon Girl", "album": "Decade",
                  "artist": "Neil Young", "track": 4, "discNumber": 2, "year": 1977,
                  "duration": 179, "albumId": "12", "coverArt": "al-12"
                }
                """
            )
        )
        assertEquals(2, song.discNumber)
        assertEquals(4, song.trackNumber)
        assertEquals("Decade", song.album)
    }

    @Test
    fun `a song with no disc tag has no disc number, rather than zero`() {
        // Navidrome omits the field entirely when the file carries no disc tag. It must
        // arrive as null: `0` would be read as a disc of its own by anything grouping.
        val song = client.songItem(
            obj("""{ "id": "1", "title": "Untagged", "track": 3 }""")
        )
        assertNull(song.discNumber)
        assertEquals(3, song.trackNumber)
    }

    @Test
    fun `a numeric field sent as a string is still a number`() {
        // Some Subsonic servers stringify integers in their JSON responses.
        val song = client.songItem(
            obj("""{ "id": "1", "title": "T", "track": "7", "discNumber": "3" }""")
        )
        assertEquals(3, song.discNumber)
        assertEquals(7, song.trackNumber)
    }

    // ─── Albums ──────────────────────────────────────────────────────────────

    @Test
    fun `an OpenSubsonic album carries its disc titles`() {
        val album = client.albumItem(
            obj(
                """
                {
                  "id": "12", "name": "Sandinista!", "artist": "The Clash", "year": 1980,
                  "songCount": 36, "duration": 8000,
                  "discTitles": [
                    { "disc": 1, "title": "The Magnificent Seven" },
                    { "disc": 3, "title": "Version" }
                  ]
                }
                """
            )
        )
        assertEquals(mapOf(1 to "The Magnificent Seven", 3 to "Version"), album.discTitles)
    }

    @Test
    fun `an album without disc titles has an empty map, not a null one`() {
        val album = client.albumItem(obj("""{ "id": "12", "name": "Tonight's the Night" }"""))
        assertEquals(emptyMap(), album.discTitles)
    }

    @Test
    fun `a malformed disc title entry is skipped rather than failing the album`() {
        val album = client.albumItem(
            obj(
                """
                {
                  "id": "12", "name": "Odd",
                  "discTitles": [ { "title": "no number" }, { "disc": 2 }, { "disc": 4, "title": "Live" } ]
                }
                """
            )
        )
        assertEquals(mapOf(4 to "Live"), album.discTitles)
    }
}
