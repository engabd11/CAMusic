package com.engabd.sendpin.library

import com.engabd.sendpin.subsonic.SubsonicClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading the folder listings Subsonic servers send. The MPD half of what was one test
 * file lives with MpdClient, in :providers:mpd, since each tests its own module's internals.
 */
class SubsonicFolderListingParseTest {

    private val subsonic = SubsonicClient("http://nas.local:4533", "u", "p")

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
}
