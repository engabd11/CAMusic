package com.engabd.sendpin.local

import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.ma.MaAudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure mapping tests for the Room download entity.
 *
 * Full Room DAO tests need an Android / Robolectric runner, which this module does
 * not currently pull in. These tests at least guard the entity/model conversion.
 */
class DownloadMappersTest {

    @Test
    fun `entity round-trip preserves format fields`() {
        val track = DownloadedTrack(
            id = "t1",
            title = "Song",
            artist = "Artist",
            filePath = "/music/t1.flac",
            album = "Album",
            durationMs = 240_000,
            format = MaAudioFormat(codec = "FLAC", sampleRate = 44100, bitDepth = 16, bitRate = 1200, channels = 2, sizeBytes = 36_000_000),
            sourceProvider = "subsonic",
        )
        assertEquals(track, track.toEntity().toModel())
    }

    @Test
    fun `null format maps to null`() {
        val track = DownloadedTrack(id = "t2", title = "A", filePath = "/a.mp3")
        assertEquals(track, track.toEntity().toModel())
    }
}
