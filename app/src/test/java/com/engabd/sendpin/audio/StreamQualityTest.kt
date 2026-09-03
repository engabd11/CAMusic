package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamQualityTest {

    @Test
    fun `lossless shows rate depth and bitrate`() {
        val q = StreamQuality("flac", sampleRateHz = 96_000, bitDepth = 24, bitrateKbps = 1411)
        assertEquals("FLAC • 96/24 • 1.4 Mb/s", q.label)
    }

    /**
     * Music Assistant's stream details sometimes omit `sample_rate`, which drops the
     * detail back to the bitrate. Appending the bitrate again gave `FLAC • 1.4 Mb/s • 1.4 Mb/s`.
     */
    @Test
    fun `bitrate is not repeated when it is already the detail`() {
        val q = StreamQuality("flac", sampleRateHz = 0, bitDepth = 0, bitrateKbps = 1411)
        assertEquals("FLAC • 1.4 Mb/s", q.label)
    }

    @Test
    fun `lossless without a bit depth still gains the bitrate`() {
        val q = StreamQuality("flac", sampleRateHz = 96_000, bitDepth = 0, bitrateKbps = 1411)
        assertEquals("FLAC • 96kHz • 1.4 Mb/s", q.label)
    }

    @Test
    fun `lossy keeps its single bitrate`() {
        val q = StreamQuality("opus", sampleRateHz = 48_000, bitDepth = 0, bitrateKbps = 320)
        assertEquals("OPUS • 320 kb/s", q.label)
    }

    @Test
    fun `codec alone survives missing details`() {
        assertEquals("FLAC", StreamQuality("flac").label)
    }

    @Test
    fun `fractional rates keep one decimal`() {
        assertEquals("FLAC • 44.1/16", StreamQuality("flac", 44_100, 16).label)
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

    // ─── The bitrate, in units a listener reads ──────────────────────────────

    @Test
    fun `under a megabit stays in kilobits`() {
        assertEquals("805 kb/s", StreamQuality("flac", bitrateKbps = 805).bitrateLabel)
        assertEquals("999 kb/s", StreamQuality("flac", bitrateKbps = 999).bitrateLabel)
    }

    @Test
    fun `a whole megabit drops its decimal`() {
        // 3.0 Mb/s is a decimal carrying no information.
        assertEquals("3 Mb/s", StreamQuality("flac", bitrateKbps = 3000).bitrateLabel)
        assertEquals("1 Mb/s", StreamQuality("flac", bitrateKbps = 1000).bitrateLabel)
    }

    @Test
    fun `a part megabit keeps one decimal`() {
        assertEquals("1.4 Mb/s", StreamQuality("flac", bitrateKbps = 1411).bitrateLabel)
        assertEquals("2.8 Mb/s", StreamQuality("flac", bitrateKbps = 2822).bitrateLabel)
    }

    @Test
    fun `no bitrate is no label rather than a zero`() {
        assertEquals(null, StreamQuality("flac", 44_100, 16).bitrateLabel)
    }

    @Test
    fun `the CD case reads the way it was asked for`() {
        val cd = StreamQuality("flac", 44_100, 16, bitrateKbps = 805)
        assertEquals("FLAC • 44.1/16 • 805 kb/s", cd.label)
    }

    @Test
    fun `a hi-res case reads the way it was asked for`() {
        val hires = StreamQuality("flac", 96_000, 24, bitrateKbps = 3000)
        assertEquals("FLAC • 96/24 • 3 Mb/s", hires.label)
    }

    // ─── Channels ────────────────────────────────────────────────────────────

    @Test
    fun `channel counts read as names where there is one`() {
        assertEquals("Stereo", StreamQuality("flac", channels = 2).channelLabel)
        assertEquals("Mono", StreamQuality("flac", channels = 1).channelLabel)
        assertEquals("5.1", StreamQuality("flac", channels = 6).channelLabel)
        assertEquals("3 channels", StreamQuality("flac", channels = 3).channelLabel)
    }

    @Test
    fun `an unreported channel count says nothing`() {
        // Subsonic servers without the OpenSubsonic fields send no channel count, and
        // claiming stereo on their behalf would be a guess dressed as a reading.
        assertEquals(null, StreamQuality("flac").channelLabel)
    }

    // ─── The live reading, when the library is its own player ────────────────

    @Test
    fun `a live reading keeps the codec the tags name`() {
        val tagged = StreamQuality("flac", 44_100, 16)
        val live = StreamQuality.live(tagged, RemoteAudioFormat(44_100, 16, 2, bitrateKbps = 1_007))
        assertNotNull(live)
        assertEquals("FLAC", live.codec)
        // The whole point: the number that moves while the track plays.
        assertEquals(1_007, live.bitrateKbps)
        assertEquals("FLAC • 44.1/16 • 1 Mb/s", live.label)
    }

    @Test
    fun `the server's own rate wins over the tags`() {
        // MPD resampling a 44.1 file to 48 is exactly the case the badge existed
        // to catch, and the tags cannot see it.
        val tagged = StreamQuality("flac", 44_100, 16)
        val live = StreamQuality.live(tagged, RemoteAudioFormat(48_000, 24, 2, bitrateKbps = 2_300))
        assertNotNull(live)
        assertEquals(48_000, live.sampleRateHz)
        assertEquals(24, live.bitDepth)
    }

    @Test
    fun `a field the server declined to report falls back to the tags`() {
        // MPD writes `f` for its own float pipeline, which parses as 0.
        val tagged = StreamQuality("flac", 44_100, 16, channels = 2)
        val live = StreamQuality.live(tagged, RemoteAudioFormat(44_100, 0, 0, bitrateKbps = 900))
        assertNotNull(live)
        assertEquals(16, live.bitDepth)
        assertEquals(2, live.channels)
    }

    @Test
    fun `nothing live leaves the tags exactly as they were`() {
        val tagged = StreamQuality("flac", 44_100, 16)
        assertEquals(tagged, StreamQuality.live(tagged, null))
        assertEquals(tagged, StreamQuality.live(tagged, RemoteAudioFormat()))
    }

    @Test
    fun `a live reading with no tags behind it is still named`() {
        val live = StreamQuality.live(null, RemoteAudioFormat(44_100, 16, 2, bitrateKbps = 1_411))
        assertNotNull(live)
        assertEquals("PCM", live.codec)
        assertTrue(live.lossless)
    }

    @Test
    fun `a stale bitrate is never carried into a live reading`() {
        // The badge promises a live number. A tagged one that stopped moving is
        // worse than none, so a server that reports no bitrate reports none.
        val tagged = StreamQuality("mp3", 44_100, 0, bitrateKbps = 320)
        val live = StreamQuality.live(tagged, RemoteAudioFormat(44_100, 0, 2, bitrateKbps = 0))
        assertNotNull(live)
        assertEquals(0, live.bitrateKbps)
    }
}
