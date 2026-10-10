package com.engabd.sendpin.library

import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.subsonic.SubsonicClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading the folder listings Subsonic servers and MPD send. */
class FolderListingParseTest {

    private val subsonic = SubsonicClient("http://nas.local:4533", "u", "p")
    private val mpd = MpdClient(address = "192.168.0.202:6600")

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a Subsonic index becomes folders, then the files at the top`() {
        // Navidrome 0.64's shape: index letters, each with its entries.
        val items = subsonic.indexItems(
            obj(
                """{"indexes":{"index":[
                    {"name":"A","artist":[{"id":"16f","name":"Amber Lanes"}]},
                    {"name":"C","artist":[{"id":"3zb","name":"Circuit Garden","coverArt":"ar-3zb"}]}
                ],"child":[{"id":"s1","title":"Loose track","isDir":false}]}}""",
            ),
        )
        assertEquals(listOf("folder", "folder", "track"), items.map { it.mediaType })
        assertEquals(listOf("Amber Lanes", "Circuit Garden", "Loose track"), items.map { it.name })
        assertEquals(listOf("16f", "3zb", "s1"), items.map { it.itemId })
    }

    @Test
    fun `a Subsonic directory lists sub-folders, then songs, and leaves videos out`() {
        val items = subsonic.directoryItems(
            obj(
                """{"directory":{"id":"16f","name":"Amber Lanes","child":[
                    {"id":"1gf","title":"Coastal Drive","isDir":true,"coverArt":"al-1gf"},
                    {"id":"t1","title":"Morning Tide","isDir":false,"track":1},
                    {"id":"v1","title":"Live video","isDir":false,"isVideo":true}
                ]}}""",
            ),
        )
        assertEquals(listOf("folder" to "Coastal Drive", "track" to "Morning Tide"), items.map { it.mediaType to it.name })
        assertEquals(true, items.first().browsable)
    }

    @Test
    fun `an MPD listing splits into folders and songs, dropping a folder's own lines`() {
        val (dirs, songs) = mpd.splitListing(
            MpdClient.parseLines(
                listOf(
                    "directory: Albums",
                    "Last-Modified: 2026-10-01T10:00:00Z",
                    "file: Albums/Coastal Drive/01 Morning Tide.flac",
                    "Title: Morning Tide",
                    "Time: 95",
                    "directory: Albums/Loose",
                    "Last-Modified: 2026-10-02T10:00:00Z",
                    "playlist: Road trip",
                    "Last-Modified: 2026-10-03T10:00:00Z",
                ),
            ),
        )
        assertEquals(listOf("Albums", "Albums/Loose"), dirs)
        val tracks = mpd.parseTracks(songs)
        assertEquals(listOf("Morning Tide"), tracks.map { it.name })
        assertEquals(listOf("file", "title", "time"), songs.map { it.first })
    }
}
