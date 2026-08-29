package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The second cause of "I tapped a song and something else played".
 *
 * `MaParse.item` used to default a missing `media_type` to `"track"`. Mixed-type shelves
 * — `music/recently_played_items`, and every `music/recommendations/items` row — carry
 * albums and artists alongside tracks, and MA 2.10's slim summary items do not always
 * include the field. An album that arrived without it became a "track", passed
 * [MaItem.playable], and was sent to `play_media` as a single URI, whereupon the server
 * resolved the album and played its first track.
 */
class MaMediaTypeInferenceTest {

    private fun parse(json: String): MaItem? =
        MaParse.item(Json.parseToJsonElement(json) as JsonObject, null)

    @Test
    fun `an explicit media_type always wins`() {
        val it = parse("""{"item_id":"7","media_type":"album","uri":"library://track/7"}""")
        assertEquals("album", it?.mediaType)
    }

    @Test
    fun `a missing media_type is read off the uri`() {
        val it = parse("""{"item_id":"7","uri":"library://album/7"}""")
        assertEquals("album", it?.mediaType)
    }

    @Test
    fun `an artist row without media_type is an artist, not a track`() {
        val it = parse("""{"item_id":"7","uri":"library://artist/7","name":"Someone"}""")
        assertEquals("artist", it?.mediaType)
        // The point of the whole fix: it must not be playable as a single URI.
        assertFalse(it!!.playable)
        assertTrue(it.browsable)
    }

    @Test
    fun `a provider uri works the same way as a library one`() {
        val it = parse("""{"item_id":"7","uri":"spotify://playlist/7"}""")
        assertEquals("playlist", it?.mediaType)
    }

    @Test
    fun `a blank media_type is treated as absent`() {
        val it = parse("""{"item_id":"7","media_type":"","uri":"library://album/7"}""")
        assertEquals("album", it?.mediaType)
    }

    @Test
    fun `an unrecognised uri host does not become a media type`() {
        // Without the known-types check, "host" would be read as the media type.
        val it = parse("""{"item_id":"7","uri":"http://host/file.mp3"}""")
        assertEquals("track", it?.mediaType)
    }

    @Test
    fun `neither field present still yields something playable`() {
        val it = parse("""{"item_id":"7","name":"No type"}""")
        assertEquals("track", it?.mediaType)
    }

    @Test
    fun `a track uri with no media_type stays a track`() {
        val it = parse("""{"item_id":"7","uri":"library://track/7"}""")
        assertEquals("track", it?.mediaType)
        assertTrue(it!!.playable)
    }
}
