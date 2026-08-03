package com.engabd.sendpin.audio

import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reuse decision behind gapless playback.
 *
 * Music Assistant sends `stream/end` then `stream/start` between every track, so
 * this runs at every track boundary. Getting it wrong in one direction rebuilds the
 * AudioTrack and throws away the tail still queued in it; getting it wrong in the
 * other feeds one decoder's output into a track built for a different format, which
 * is noise rather than music.
 */
class StreamContinuityTest {

    private fun fmt(
        codec: String = "flac",
        rate: Int = 44_100,
        depth: Int = 16,
        channels: Int = 2,
        header: String? = "AAAA",
    ) = StreamStartPlayerInfo(
        codec = codec,
        sampleRate = rate,
        bitDepth = depth,
        channels = channels,
        codecHeader = header,
    )

    @Test
    fun `identical format reuses both`() {
        val p = StreamContinuityPlan(fmt(), fmt())
        assertTrue(p.reuseTrack)
        assertTrue(p.reuseCodec)
    }

    /**
     * The case that actually happens on every FLAC track change: MA's `codec_header`
     * is a STREAMINFO block carrying that track's own sample count and MD5, so it
     * differs even when nothing about the *format* has. The track must survive —
     * it is holding the previous track's tail — while the decoder is rebuilt.
     */
    @Test
    fun `same format but new codec header keeps the track and rebuilds the codec`() {
        val p = StreamContinuityPlan(fmt(header = "AAAA"), fmt(header = "BBBB"))
        assertTrue(p.reuseTrack, "a new STREAMINFO must not cost the buffered tail")
        assertFalse(p.reuseCodec)
    }

    @Test
    fun `a rate change rebuilds everything`() {
        val p = StreamContinuityPlan(fmt(rate = 44_100), fmt(rate = 96_000))
        assertFalse(p.reuseTrack)
        assertFalse(p.reuseCodec)
    }

    @Test
    fun `a channel change rebuilds everything`() {
        val p = StreamContinuityPlan(fmt(channels = 2), fmt(channels = 1))
        assertFalse(p.reuseTrack)
    }

    /** Above the 1/2 clamp start() applies, 6 and 8 channels are the same thing. */
    @Test
    fun `channel counts are compared after the same clamp start applies`() {
        val p = StreamContinuityPlan(fmt(channels = 6), fmt(channels = 8))
        assertTrue(p.reuseTrack)
    }

    /** Different sample encoding on the way out — the track cannot be shared. */
    @Test
    fun `a bit-perfect change rebuilds the track`() {
        val p = SendspinAudioEngine.StreamContinuity.plan(
            prev = fmt(depth = 24), next = fmt(depth = 24),
            prevHiRes = false, nextHiRes = true,
        )
        assertFalse(p.reuseTrack)
    }

    /** PCM is a passthrough and decoded streams are not; they never share a path. */
    @Test
    fun `pcm and a decoded codec never continue into each other`() {
        val p = StreamContinuityPlan(fmt(codec = "pcm", header = null), fmt(codec = "flac"))
        assertFalse(p.reuseTrack)
    }

    @Test
    fun `codec swap at the same format keeps the track only`() {
        val p = StreamContinuityPlan(fmt(codec = "flac"), fmt(codec = "opus"))
        assertTrue(p.reuseTrack)
        assertFalse(p.reuseCodec)
    }

    /** Nothing playing yet — there is no track to continue into. */
    @Test
    fun `a cold start reuses nothing`() {
        val p = SendspinAudioEngine.StreamContinuity.plan(
            prev = null, next = fmt(), prevHiRes = false, nextHiRes = false,
        )
        assertFalse(p.reuseTrack)
        assertFalse(p.reuseCodec)
    }

    private fun StreamContinuityPlan(
        prev: StreamStartPlayerInfo,
        next: StreamStartPlayerInfo,
    ) = SendspinAudioEngine.StreamContinuity.plan(prev, next, prevHiRes = false, nextHiRes = false)
}
