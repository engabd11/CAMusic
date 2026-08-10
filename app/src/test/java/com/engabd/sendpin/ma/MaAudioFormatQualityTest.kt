package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bridge between what a library reports about a file and what the badge shows.
 *
 * [StreamQuality][com.engabd.sendpin.audio.StreamQuality] has always formatted the
 * bitrate correctly (see `StreamQualityTest`), and `SubsonicClient` has always parsed
 * it — but the conversion between the two was hand-rolled at one call site
 * (`DownloadManager.toLocalTrack`), listed positionally, and stopped after `bitDepth`.
 * So every Navidrome and offline track lost its bitrate, its channel count and its
 * file size on the way to the screen, and a 3 Mb/s FLAC read "FLAC • 96/24".
 *
 * That call site now uses [MaAudioFormat.quality]. This is the test that fails if
 * anyone hand-rolls the conversion again.
 */
class MaAudioFormatQualityTest {

    private val hiRes = MaAudioFormat(
        codec = "flac",
        sampleRate = 96_000,
        bitDepth = 24,
        bitRate = 3_000,
        channels = 2,
        sizeBytes = 84_123_456,
        replayGainTrack = -3.5f,
        replayGainAlbum = -2.0f,
    )

    @Test
    fun `every field survives the conversion`() {
        val q = hiRes.quality
        assertEquals("flac", q.codec)
        assertEquals(96_000, q.sampleRateHz)
        assertEquals(24, q.bitDepth)
        assertEquals(3_000, q.bitrateKbps)
        assertEquals(2, q.channels)
        assertEquals(84_123_456L, q.sizeBytes)
        assertEquals(-3.5f, q.replayGainTrack)
        assertEquals(-2.0f, q.replayGainAlbum)
    }

    /** The whole point: the badge string the user asked for. */
    @Test
    fun `the badge reads codec, rate depth and bitrate`() {
        assertEquals("FLAC • 96/24 • 3 Mb/s", hiRes.quality.label)
    }

    @Test
    fun `the detail card gets its channels and file size too`() {
        assertEquals("Stereo", hiRes.quality.channelLabel)
        assertEquals(84_123_456L, hiRes.quality.sizeBytes)
    }

    /**
     * A plain Subsonic server sends none of the OpenSubsonic fields, so the format
     * arrives as a codec and four zeroes. Those must stay absent rather than become
     * "0 kb/s" or a claimed stereo — see `SubsonicClient.audioFormat`.
     */
    @Test
    fun `a server that said nothing still says nothing`() {
        val bare = MaAudioFormat(codec = "mp3", sampleRate = 0, bitDepth = 0, channels = 0)
        val q = bare.quality
        assertEquals("MP3", q.label)
        assertEquals(null, q.bitrateLabel)
        assertEquals(null, q.channelLabel)
    }
}
