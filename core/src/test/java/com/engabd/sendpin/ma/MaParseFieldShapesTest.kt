package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Music Assistant sends the same field in more than one shape depending on the
 * provider and the endpoint, and kotlinx's `.jsonPrimitive` *throws* on an object or
 * an array rather than returning null. A field arriving in an unexpected shape must
 * therefore be skipped, not allowed to take the parse — and the screen — down.
 */
class MaParseFieldShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun item(body: String) = MaParse.item(json.parseToJsonElement(body), "http://ma.local:8095")

    @Test
    fun `genres survive a mix of strings and objects in one array`() {
        val parsed = item(
            """
            {
              "item_id": "7", "provider": "library", "name": "Get Rich or Die Tryin'",
              "media_type": "album",
              "genres": ["Hip Hop", {"name": "Gangsta Rap"}, {"uri": "genre://12"}, 3]
            }
            """
        )
        // The nameless object is skipped; everything readable is kept.
        assertEquals(listOf("Hip Hop", "Gangsta Rap", "3"), parsed?.genres)
    }

    @Test
    fun `a genre array of only unreadable objects yields no genres`() {
        val parsed = item(
            """
            {
              "item_id": "7", "provider": "library", "name": "Album", "media_type": "album",
              "genres": [{"uri": "genre://12"}, {"nested": {"name": "Rap"}}]
            }
            """
        )
        assertEquals(emptyList<String>(), parsed?.genres)
    }

    @Test
    fun `genres also arrive as one comma-separated string`() {
        val parsed = item(
            """
            {"item_id": "7", "provider": "library", "name": "Album", "media_type": "album",
             "genres": "Hip Hop, Gangsta Rap"}
            """
        )
        assertEquals(listOf("Hip Hop", "Gangsta Rap"), parsed?.genres)
    }

    @Test
    fun `an artist credit with an object where a name belongs is skipped`() {
        val parsed = item(
            """
            {
              "item_id": "7", "provider": "library", "name": "In Da Club", "media_type": "track",
              "artists": [{"name": "50 Cent"}, {"name": {"text": "G-Unit"}}, {"item_id": "9"}]
            }
            """
        )
        assertEquals("50 Cent", parsed?.subtitle)
    }

    @Test
    fun `player list fields survive objects where strings belong`() {
        val players = MaParse.players(
            json.parseToJsonElement(
                """
                [{
                  "player_id": "p1", "name": "Kitchen",
                  "supported_features": ["volume_set", {"feature": "power"}],
                  "can_group_with": [{"player_id": "p2"}]
                }]
                """
            )
        )
        assertEquals(listOf("volume_set"), players.single().supportedFeatures)
        assertEquals(emptyList<String>(), players.single().canGroupWith)
    }
}
