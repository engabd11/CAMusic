package com.engabd.sendpin.library

import com.engabd.sendpin.mpd.MpdClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading the folder listings MPD sends. The Subsonic half of what was one test file
 * lives with SubsonicClient, in :providers:subsonic.
 */
class MpdFolderListingParseTest {

    private val mpd = MpdClient(address = "192.168.0.202:6600")

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
