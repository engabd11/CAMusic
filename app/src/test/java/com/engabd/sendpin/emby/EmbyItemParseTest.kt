package com.engabd.sendpin.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The `BaseItemDto` mapping, held against payloads shaped like the ones Emby sends —
 * the same shape Jellyfin uses, since the two forked from one codebase. See
 * `JellyfinItemParseTest` for the disc-vs-track-number argument this mirrors.
 */
class EmbyItemParseTest {

    private val client = EmbyClient("http://nas.local:8096", token = "tok", userId = "u1")

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
    fun `an unknown type is not mapped`() {
        assertNull(client.item(obj("""{ "Id": "x1", "Name": "Poster", "Type": "Video" }""")))
    }

    @Test
    fun `bitrate over 10000 is treated as bits per second and converted to kbps`() {
        val track = client.item(
            obj(
                """
                {
                  "Id": "a1", "Name": "Rain Dogs", "Type": "Audio",
                  "MediaSources": [{
                    "Container": "flac", "Bitrate": 3011000, "Size": 42,
                    "MediaStreams": [{"Type": "Audio", "Codec": "flac", "SampleRate": 96000, "BitDepth": 24, "BitRate": 3011000, "Channels": 2}]
                  }]
                }
                """
            )
        )
        assertNotNull(track)
        assertEquals("flac", track.audioFormat?.codec)
        assertEquals(3011, track.audioFormat?.bitRate)
        assertEquals(96000, track.audioFormat?.sampleRate)
        assertEquals(24, track.audioFormat?.bitDepth)
    }

    @Test
    fun `a missing channel count reports 0, not a guessed stereo`() {
        val track = client.item(
            obj(
                """
                {
                  "Id": "a1", "Name": "Mono?", "Type": "Audio",
                  "MediaSources": [{"Container": "mp3", "MediaStreams": [{"Type": "Audio", "Codec": "mp3"}]}]
                }
                """
            )
        )
        assertNotNull(track)
        assertEquals(0, track.audioFormat?.channels)
    }
}
