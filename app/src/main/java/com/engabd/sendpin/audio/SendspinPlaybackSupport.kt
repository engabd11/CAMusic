package com.engabd.sendpin.audio

import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * Pure, engine-independent Sendspin playback logic.
 *
 * Originally lived inside `SendspinAudioEngine`, the `MediaCodec`+`AudioTrack` engine
 * that decoded the binary Sendspin stream directly. That engine was superseded by
 * [SendspinExoEngine] (the one actually selected by `Playback.startSendspin`) and
 * removed as dead code, but these three decisions have no equivalent there and are
 * still relied on: [StreamContinuity] and [Scheduling] are consumed by the live
 * gapless/continuation path in `Playback.kt`, and [HeadGate] is mirrored by
 * `SyncGate.MAX_MUTE_MS` (the two deadlines must stay equal — see `SyncGateTest`).
 * Kept apart from any engine on purpose, same as before: none of this needs a device
 * to test.
 */
object SendspinPlaybackSupport {

    /**
     * How long a stream is kept alive after `stream/end` before the codec and track
     * are torn down.
     *
     * Music Assistant ends the stream between *every* track, so `stream/end` is not
     * "playback stopped" — it is usually a track boundary with the next
     * `stream/start` a few milliseconds behind. Tearing down on it truncated the tail
     * (the track still held ~1 s of PCM) and rebuilt for the next track, which is the
     * gap. Lingering longer than the track buffer means a genuine stop still drains
     * completely, and a track change finds everything alive.
     */
    const val END_LINGER_MS = 2_000L

    /**
     * Whether the next stream can carry on through the live codec and track, or
     * needs new ones.
     *
     * Two booleans rather than one because they fail independently. MA's per-track
     * FLAC `codec_header` is a STREAMINFO block carrying that track's own total
     * sample count and MD5, so it differs between tracks even when the *format* is
     * identical — meaning [reuseCodec] is usually false at a track boundary while
     * [reuseTrack] stays true. [reuseTrack] is the one that matters: the AudioTrack
     * is where the previous track's unplayed tail lives, and where a rebuild turns
     * into silence. Rebuilding just the decoder costs tens of milliseconds, hidden
     * entirely under the ~1 s of PCM already queued.
     *
     * Pure, like [HeadGate], because the engine around it cannot be instantiated
     * off-device.
     */
    object StreamContinuity {
        data class Plan(val reuseTrack: Boolean, val reuseCodec: Boolean)

        fun plan(
            prev: StreamStartPlayerInfo?,
            next: StreamStartPlayerInfo,
            prevHiRes: Boolean,
            nextHiRes: Boolean,
        ): Plan {
            if (prev == null) return Plan(reuseTrack = false, reuseCodec = false)
            val prevPcm = prev.codec.equals("pcm", ignoreCase = true)
            val nextPcm = next.codec.equals("pcm", ignoreCase = true)
            // Channel count is compared after the same coercion start() applies, or a
            // server claiming 6 channels twice would look like a change.
            val reuseTrack = prev.sampleRate == next.sampleRate &&
                prev.channels.coerceIn(1, 2) == next.channels.coerceIn(1, 2) &&
                prevHiRes == nextHiRes &&
                prevPcm == nextPcm
            val reuseCodec = reuseTrack &&
                prev.codec.equals(next.codec, ignoreCase = true) &&
                prev.bitDepth == next.bitDepth &&
                prev.codecHeader == next.codecHeader
            return Plan(reuseTrack, reuseCodec)
        }
    }

    /**
     * How long to wait before writing the head of a stream.
     *
     * Split out because of `bufferedUs`: while a track is rebuilt for every stream
     * it is always empty when the head is written, and scheduling against the raw
     * timestamp is right. The moment a track survives a track change, a sample
     * written now is not heard until the queued audio ahead of it has played, so
     * scheduling against the raw timestamp puts every continuation exactly one
     * buffer late. Subtracting what is already queued is what keeps the two cases
     * on the same clock.
     */
    object Scheduling {
        fun waitUs(headLocalUs: Long, nowUs: Long, bufferedUs: Long, staticDelayUs: Long): Long =
            headLocalUs - staticDelayUs - bufferedUs - nowUs
    }

    /**
     * What to do with the frame at the head of a stream.
     *
     * Split out as a pure function because it was the whole of the fix for tracks
     * that began a couple of seconds in, and the engine around it was welded to
     * `AudioTrack` and `MediaCodec` — there was no other way to test the decision.
     */
    object HeadGate {
        /**
         * How long the head of a stream may be held back waiting for the clock filter
         * to converge before we give up and play it unscheduled.
         *
         * `client/time` runs at a 300 ms cadence until 50 samples are in, and
         * `isReadyForPlaybackStart()` wants eight low-error ones — about 2.4 s from a
         * cold connect, and nothing at all on a connection that has been up a while,
         * which is the normal case since the connection service keeps the socket open
         * whether or not music is playing. Three seconds clears the cold-start case
         * with margin; past that the offset is not coming, and indefinite silence is
         * worse than a stream that is merely out of step.
         */
        const val MAX_STALL_MS = 3_000L

        enum class Decision {
            /** Schedule normally: the clock is trustworthy. */
            SCHEDULE,

            /**
             * Leave the queue alone and ask again — the clock isn't ready yet.
             *
             * Emphatically *not* "drop this frame". Holding means the audio waits; it
             * does not mean it is thrown away. See the call site.
             */
            HOLD,

            /** Give up waiting and play it now, unscheduled. */
            PLAY_NOW,
        }

        fun decide(clockReady: Boolean, stalledMs: Long, maxStallMs: Long = MAX_STALL_MS): Decision = when {
            clockReady -> Decision.SCHEDULE
            stalledMs >= maxStallMs -> Decision.PLAY_NOW
            else -> Decision.HOLD
        }
    }
}
