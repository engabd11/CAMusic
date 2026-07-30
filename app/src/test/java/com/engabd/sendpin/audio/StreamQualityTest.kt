package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamQualityTest {

    @Test
    fun `lossless shows rate depth and bitrate`() {
        val q = StreamQuality("flac", sampleRateHz = 96_000, bitDepth = 24, bitrateKbps = 1411)
        assertEquals("FLAC · 96/24 · 1411k", q.label)
    }

    /**
     * Music Assistant's stream details sometimes omit `sample_rate`, which drops the
     * detail back to the bitrate. Appending the bitrate again gave `FLAC · 1411k · 1411k`.
     */
    @Test
    fun `bitrate is not repeated when it is already the detail`() {
        val q = StreamQuality("flac", sampleRateHz = 0, bitDepth = 0, bitrateKbps = 1411)
        assertEquals("FLAC · 1411k", q.label)
    }

    @Test
    fun `lossless without a bit depth still gains the bitrate`() {
        val q = StreamQuality("flac", sampleRateHz = 96_000, bitDepth = 0, bitrateKbps = 1411)
        assertEquals("FLAC · 96kHz · 1411k", q.label)
    }

    @Test
    fun `lossy keeps its single bitrate`() {
        val q = StreamQuality("opus", sampleRateHz = 48_000, bitDepth = 0, bitrateKbps = 320)
        assertEquals("OPUS · 320k", q.label)
    }

    @Test
    fun `codec alone survives missing details`() {
        assertEquals("FLAC", StreamQuality("flac").label)
    }

    @Test
    fun `fractional rates keep one decimal`() {
        assertEquals("FLAC · 44.1/16", StreamQuality("flac", 44_100, 16).label)
    }

    /**
     * The two readings the quality popup compares come from different places: the
     * playing format is read off the queue's stream details and carries a bitrate,
     * the source format comes from `provider_mappings.audio_format` and never does.
     * Comparing labels therefore reported a transcode for every track, which is what
     * kept the redundant Source row on screen.
     */
    @Test
    fun `the same audio matches even when only one side knows its bitrate`() {
        val playing = StreamQuality("flac", 44_100, 16, bitrateKbps = 1411)
        val source = StreamQuality("flac", 44_100, 16)
        assertTrue(playing.sameFormatAs(source))
        assertTrue(source.sameFormatAs(playing))
    }

    @Test
    fun `a mime type and a bare codec name are the same codec`() {
        assertTrue(StreamQuality("audio/flac", 48_000, 24).sameFormatAs(StreamQuality("FLAC", 48_000, 24)))
    }

    @Test
    fun `a real transcode does not match`() {
        val playing = StreamQuality("flac", 48_000, 16)
        assertFalse(playing.sameFormatAs(StreamQuality("flac", 96_000, 24)))  // resampled
        assertFalse(playing.sameFormatAs(StreamQuality("mp3", 48_000, 16)))   // re-encoded
        assertFalse(playing.sameFormatAs(StreamQuality("flac", 48_000, 24)))  // requantised
    }

    @Test
    fun `a mime type still resolves as lossless`() {
        assertTrue(StreamQuality("audio/flac", 44_100, 16).lossless)
    }

    @Test
    fun `hi-res needs better than CD from a lossless codec`() {
        assertTrue(StreamQuality("flac", 96_000, 24).hiRes)
        assertTrue(StreamQuality("flac", 44_100, 24).hiRes)
        assertFalse(StreamQuality("flac", 44_100, 16).hiRes)
        assertFalse(StreamQuality("mp3", 96_000, 24).hiRes)
    }
}
