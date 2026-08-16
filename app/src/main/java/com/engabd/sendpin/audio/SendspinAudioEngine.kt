package com.engabd.sendpin.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import com.engabd.sendpin.protocol.ClockSync
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Decodes the Sendspin binary audio stream and plays it through an [AudioTrack].
 * Supports 16-bit and 24-bit PCM output: when [bitPerfect24Bit] is true the
 * AudioTrack is built with `ENCODING_PCM_24BIT_PACKED` (API 31+, which is our
 * minSdk), so 24-bit FLAC streams keep their full depth instead of being
 * truncated to 16-bit. FLAC and Opus are decoded with Android's [MediaCodec];
 * PCM is a passthrough. Pure Kotlin — no NDK — so it builds and runs without
 * the native pipeline (the native AAudio-I24 path is kept in `cpp/` for the
 * future bit-perfect phase that bypasses the Android mixer). Modeled on the
 * massdroid engine (MIT).
 *
 * Each binary WebSocket frame is
 * `[type:uint8 = 4][server_ts_us: int64 big-endian][payload…]`.
 *
 * Frames are **scheduled**, not played on arrival: each carries the server-clock
 * instant it should be heard, and [clock] maps that onto the local clock. See
 * [awaitFrameTime] — without it the server's read-ahead buffer (several seconds) is
 * poured straight into the track, so a track starts part-way in and can never stay in
 * step with another speaker.
 */
class SendspinAudioEngine(private val clock: ClockSync) : SendspinPlaybackEngine {

    /**
     * The server-side `sendspin_static_delay` this player is configured with, in ms.
     * Subtracted from every frame's scheduled time, per the spec. Zero until the
     * server tells us otherwise.
     */
    @Volatile override var staticDelayMs: Int = 0

    /**
     * When true, hi-res sources keep their resolution instead of being
     * requantised to 16-bit. Set from
     * [com.engabd.sendpin.data.AppSettings.bitPerfect24Bit] before [start].
     *
     * What this actually does depends on the path:
     *
     *  - **PCM passthrough** — the wire *is* the sample format, so the track is
     *    built with `ENCODING_PCM_24BIT_PACKED` when the server says 24-bit.
     *  - **FLAC** — MediaCodec has no 24-bit-packed output encoding. The decoder
     *    is asked for `ENCODING_PCM_FLOAT`, which AOSP's FLAC decoder honours for
     *    >16-bit sources, and the track is built to match.
     *
     * Either way the track is only ever built from the format the *decoder
     * reports*, never from the depth the server claimed — writing 2-byte samples
     * into a 3-byte-per-sample track is not quiet degradation, it is noise.
     */
    @Volatile override var bitPerfect: Boolean = false

    /**
     * The preferred output device for audio routing (USB DAC support).
     * When non-null, the AudioTrack is routed to this [AudioDeviceInfo] via
     * `setPreferredDevice`. Set from
     * [com.engabd.sendpin.data.AppSettings.preferredAudioDeviceId] before
     * [start]; resolved to an [AudioDeviceInfo] by the caller via
     * [AudioManager.getDevices].
     */
    @Volatile var preferredOutputDevice: AudioDeviceInfo? = null

    companion object {
        const val TAG = "SendspinAudio"
        const val OPUS_MAX_INPUT = 64 * 1024
        const val FLAC_MAX_INPUT = 256 * 1024
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val QUEUE_CAPACITY = 2048

        /**
         * How late a frame may be and still be worth playing. Below this the audio is
         * already behind and playing it only pushes everything after it further out;
         * the spec says to drop it.
         */
        const val LATE_TOLERANCE_US = 120_000L

        /**
         * A scheduled time further ahead than this is not believed. The clock filter
         * needs a few round-trips to converge, and sleeping on a bad offset would
         * stall playback completely — so an implausible lead plays now instead.
         */
        const val MAX_LEAD_US = 15_000_000L

        /**
         * How long the decode loop naps between re-asking the clock while the head
         * gate holds. Short enough that the head is released promptly once the filter
         * converges — that delay is dead air at the start of the track.
         */
        const val HOLD_POLL_MS = 25L

        /**
         * How long a stream is kept alive after `stream/end` before the codec and
         * track are torn down.
         *
         * Music Assistant ends the stream between *every* track, so `stream/end` is
         * not "playback stopped" — it is usually a track boundary with the next
         * `stream/start` a few milliseconds behind. Tearing down on it truncated the
         * tail (the track still held ~1 s of PCM) and rebuilt for the next track,
         * which is the gap. Lingering longer than the track buffer means a genuine
         * stop still drains completely, and a track change finds everything alive.
         */
        const val END_LINGER_MS = 2_000L

        /** Poll interval while waiting for the track to play out its tail. */
        const val DRAIN_POLL_MS = 20L

        /** Cap on pumping the decoder's own pipeline out after end-of-stream. */
        const val DRAIN_EOS_TIMEOUT_MS = 200L

        /** Slack past the buffer's own length before giving up on a drain. */
        const val DRAIN_TAIL_MARGIN_MS = 250L

        /**
         * How long to wait for the decode thread to leave the loop before releasing
         * the track under it. The worker can be parked inside a blocking
         * `AudioTrack.write`, and releasing the track from another thread while it
         * is in there is a use-after-free in native code.
         */
        const val WORKER_JOIN_MS = 500L

        /** Float is 4 bytes per sample, 24-bit packed 3, everything else 2. */
        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
    }

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
    internal object StreamContinuity {
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
     * Split out because of [bufferedUs]: while a track is rebuilt for every stream
     * it is always empty when the head is written, and scheduling against the raw
     * timestamp is right. The moment a track survives a track change, a sample
     * written now is not heard until the queued audio ahead of it has played, so
     * scheduling against the raw timestamp puts every continuation exactly one
     * buffer late. Subtracting what is already queued is what keeps the two cases
     * on the same clock.
     */
    internal object Scheduling {
        fun waitUs(headLocalUs: Long, nowUs: Long, bufferedUs: Long, staticDelayUs: Long): Long =
            headLocalUs - staticDelayUs - bufferedUs - nowUs
    }

    /**
     * What to do with the frame at the head of a stream.
     *
     * Split out as a pure function because it is the whole of the fix for tracks that
     * began a couple of seconds in, and the engine around it is welded to [AudioTrack]
     * and [MediaCodec] — there is no other way to test the decision.
     */
    internal object HeadGate {
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

    private class Frame(val serverTsUs: Long, val payload: ByteArray)

    private val queue = LinkedBlockingQueue<Frame>(QUEUE_CAPACITY)
    @Volatile private var running = false
    private var worker: Thread? = null

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var isPcm = false
    /** The AudioFormat encoding the current track was built with. */
    private var trackEncoding = AudioFormat.ENCODING_PCM_16BIT
    /** What the AudioTrack was built for, to check the decoder against. */
    private var trackSampleRate = 0
    private var trackChannels = 0
    /**
     * The next frame is the head of a stream and should be held until its scheduled
     * moment. Cleared once that frame is released; set again by [start] and [flush],
     * which are the two points where playback restarts from a known position.
     */
    @Volatile private var awaitStart = false
    /** When the current head-of-stream wait began, for the give-up deadline. */
    @Volatile private var headArmedAtMs = 0L
    @Volatile private var volume = 1.0f

    /**
     * Muted because the clock hasn't converged, per the spec's "mute output, keep
     * buffering" rule. Kept apart from [volume] so restoring it can't clobber what
     * the user or the server set.
     */
    @Volatile private var syncMuted = false

    /** `stream/end` seen; the teardown is deferred by [END_LINGER_MS]. */
    @Volatile private var endPending = false
    @Volatile private var endPendingAtMs = 0L

    /** Frames handed to the track since it was built, for [bufferedUs]. */
    @Volatile private var framesWritten = 0L

    /**
     * Whether this stream has already logged its first write to the track — see
     * [logFirstWrite]. Reset by [start], so there is one line per stream.
     */
    @Volatile private var firstWriteLogged = false

    /** The format the live codec/track were built for, for the reuse decision. */
    private var currentFormat: StreamStartPlayerInfo? = null
    private var currentHiRes = false

    /** Arm the head-of-stream gate and restart its deadline. */
    private fun armHead() {
        awaitStart = true
        headArmedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** The volume actually applied, honouring a sync mute without losing [volume]. */
    private fun effectiveVolume(): Float = if (syncMuted) 0f else volume

    /**
     * Mute/unmute for clock-convergence reasons. Decoding and writing continue —
     * the spec's rule is "mute output and continue buffering", not "stop".
     */
    override fun setSyncMuted(muted: Boolean) {
        if (syncMuted == muted) return
        syncMuted = muted
        track?.setVolume(effectiveVolume())
    }

    /**
     * How much audio is queued in the track but not yet heard, in microseconds.
     *
     * Zero for a freshly built track, so the cold-start path behaves exactly as it
     * did before continuations existed.
     */
    private fun bufferedUs(): Long {
        val t = track ?: return 0L
        if (trackSampleRate <= 0) return 0L
        // getPlaybackHeadPosition is a *wrapping* Int frame counter; mask it back to
        // unsigned before comparing against our own running total.
        val head = t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        return ((framesWritten - head).coerceAtLeast(0L)) * 1_000_000L / trackSampleRate
    }

    /** Bytes per frame for the encoding the live track was built with. */
    private fun bytesPerFrame(): Int = bytesPerSample(trackEncoding) * trackChannels.coerceAtLeast(1)

    /**
     * Deliberately **not** `@Synchronized` as a whole — see the phase split inside.
     *
     * The continuation fast path is taken under the monitor and returns there. A
     * genuine (re)start cannot be, because it has to stop the decode worker first,
     * and [releaseInternal]'s join must not run while the caller holds the monitor
     * the worker itself takes in [ensureTrackFor].
     */
    override fun start(format: StreamStartPlayerInfo) {
        // Hi-res is only worth asking for when the source actually has the bits:
        // a 16-bit file widened on the way out is padding, not resolution.
        val wantHiRes = bitPerfect && format.bitDepth >= 24

        // Phase 1 — a track boundary: MA ended the last stream moments ago and the
        // track is still playing its tail. Carry straight on through it. Nothing here
        // touches the worker, so it is decided and applied in one synchronized step,
        // which is what keeps the common case cheap.
        synchronized(this) {
            val plan = StreamContinuity.plan(currentFormat, format, currentHiRes, wantHiRes)
            if (endPending && running && plan.reuseTrack) {
                endPending = false
                if (plan.reuseCodec) {
                    runCatching { codec?.flush(); codec?.start() }
                } else {
                    rebuildCodecKeeping(format, wantHiRes)
                }
                currentFormat = format
                currentHiRes = wantHiRes
                // [endOfStream] left the track paused and empty, so bring it back and
                // schedule the next frame like the head it now is. The saving here is the
                // rebuild — the codec and the AudioTrack survive a track change — not the
                // buffer, which cannot survive one without breaking pause.
                runCatching { track?.play() }
                armHead()
                Log.d(TAG, "continue ${format.codec} ${format.sampleRate}/${format.bitDepth} (codec reused=${plan.reuseCodec})")
                return
            }
        }

        // Phase 2 — a genuine (re)start. Drain first so a pending end doesn't lose its
        // tail. Outside the monitor on purpose: [releaseInternal] joins the decode
        // worker, and the worker takes this same monitor in [ensureTrackFor], so
        // joining while holding it deadlocks until the join times out — after which
        // this method would build a *second* worker on top of one that is still alive,
        // two decoders interleaving into a single track. `releaseInternal` is written
        // to join outside the monitor for exactly this reason; calling it from a
        // wholly-synchronized `start` was what defeated that.
        releaseInternal(drain = endPending)

        synchronized(this) {
            val channels = format.channels.coerceIn(1, 2)
            isPcm = format.codec.equals("pcm", ignoreCase = true)
            codec = when (format.codec.lowercase()) {
                "opus" -> createOpusDecoder(format.sampleRate, channels, format.codecHeader)
                "flac" -> createFlacDecoder(format.sampleRate, channels, format.bitDepth, format.codecHeader, wantHiRes)
                else -> null   // pcm passthrough, or a codec we cannot decode
            }
            // The server may only stream a format this client advertised (see
            // FormatNegotiator.supportedFormats), so this should be unreachable — but
            // it used to be reached *silently*: no decoder and no passthrough means
            // every frame is discarded in the decode loop, which is a connection that
            // looks perfectly healthy and plays nothing at all.
            if (codec == null && !isPcm) {
                Log.e(TAG, "no decoder for codec '${format.codec}' - this stream will be silent")
            }
            trackSampleRate = format.sampleRate
            trackChannels = channels
            framesWritten = 0L
            firstWriteLogged = false
            // Passthrough has no decoder to ask, so the server's claim is the only
            // description of the bytes and the track is built now. Decoded streams
            // wait for INFO_OUTPUT_FORMAT_CHANGED — see ensureTrackFor.
            track = if (isPcm) {
                val enc = if (wantHiRes) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT
                trackEncoding = enc
                createTrack(format.sampleRate, channels, enc).also { it.setVolume(effectiveVolume()); it.play() }
            } else {
                null
            }
            currentFormat = format
            currentHiRes = wantHiRes
            endPending = false
            armHead()
            running = true
            // Guard: a continuation leaves the worker alive, and a second one writing to
            // the same track would interleave two decoders into one stream.
            if (worker == null) worker = thread(name = "sendspin-audio", isDaemon = true) { runLoop() }
            Log.i(TAG, "start ${format.codec} ${format.sampleRate}/${format.bitDepth} ch=$channels hires=$wantHiRes")
        }
    }

    /**
     * Swap the decoder without touching the track.
     *
     * Deliberately leaves [trackSampleRate]/[trackChannels]/[trackEncoding] alone so
     * the new decoder's `INFO_OUTPUT_FORMAT_CHANGED` matches in [ensureTrackFor] and
     * keeps the live track — that track is holding the previous song's tail.
     */
    private fun rebuildCodecKeeping(format: StreamStartPlayerInfo, wantHiRes: Boolean) {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        val channels = format.channels.coerceIn(1, 2)
        isPcm = format.codec.equals("pcm", ignoreCase = true)
        codec = runCatching {
            when (format.codec.lowercase()) {
                "opus" -> createOpusDecoder(format.sampleRate, channels, format.codecHeader)
                "flac" -> createFlacDecoder(format.sampleRate, channels, format.bitDepth, format.codecHeader, wantHiRes)
                else -> null
            }
        }.onFailure { Log.w(TAG, "codec rebuild failed: ${it.message}") }.getOrNull()
    }

    /**
     * `stream/end` — stop making sound now, but keep the codec and track alive for a
     * moment in case another stream follows.
     *
     * **This drops what is buffered, and it has to.** `stream/end` is not only a
     * track boundary: Music Assistant sends exactly this message when the user
     * *pauses*, with no `stream/clear` alongside it. Since the client advertises a
     * 4 MB `buffer_capacity`, the server streams up to ~30 s ahead — so a version of
     * this that played the buffer out (0.4.0 and 0.4.1 did) carries on for another
     * half-minute after the listener has pressed pause, ignores the next seek because
     * the audio it wanted is behind that backlog, and can only be silenced by
     * disconnecting the player. Which is what "stop the announcements notification"
     * did, and why it looked like a second audio path.
     *
     * There is no way to tell a pause from a track boundary at this point in the
     * protocol — the message is byte-identical, and the `playback_speed: 0` that
     * distinguishes them arrives afterwards, in a separate `server/state`. Between
     * cutting a track's last moment short and ignoring the pause button, the pause
     * button wins every time.
     *
     * What is still kept is the codec and the AudioTrack themselves, so a genuine
     * track change does not pay for a rebuild — the part of the 0.4.0 work that was
     * sound. Teardown happens in the decode loop once [END_LINGER_MS] has passed, or
     * never, if the next `stream/start` arrives first.
     */
    override fun endOfStream() {
        if (!running) return
        endPending = true
        endPendingAtMs = android.os.SystemClock.elapsedRealtime()
        queue.clear()
        // pause + flush, not stop: the point is to be silent *now*.
        runCatching { track?.pause(); track?.flush() }
        framesWritten = 0L
        // Whatever comes next starts from a known point, so it is a head again.
        armHead()
    }

    override fun submit(frame: ByteArray) {
        if (!running) return
        val (serverTsUs, payload) = SendspinAudioFrame.parse(frame) ?: return
        val f = Frame(serverTsUs, payload)
        if (queue.offer(f)) return
        // Full. Which end to sacrifice depends on what the queue is holding: normally
        // the oldest frame is the most stale and goes, but while a gate is armed the
        // oldest frame is the *start of the track* the gate exists to protect —
        // dropping it there would reintroduce the bug from the other side.
        if (awaitStart) return
        queue.poll()
        queue.offer(f)
    }

    /**
     * `stream/clear` — a seek or track jump: drop buffered audio and reset.
     *
     * This discard is correct and stays: the point of a seek is that everything
     * already queued is now wrong. That is the opposite of [endOfStream], where the
     * queued audio is the tail the listener is still owed.
     */
    override fun flush() {
        queue.clear()
        // A seek restarts from a known point, so the next frame is a head-of-stream
        // again and gets scheduled like one.
        endPending = false
        armHead()
        try { codec?.flush(); codec?.start() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
        // The track's frame counter resets with the flush, so ours has to as well or
        // bufferedUs() reads negative and the next head schedules early.
        framesWritten = 0L
    }

    /**
     * Tear the engine down for good — disconnect, or permanent audio-focus loss.
     * Drains rather than discards, so a stop at the end of a track still plays out.
     */
    override fun release() = releaseInternal(drain = true)

    /**
     * @param drain let the track play out what it holds before releasing it.
     *   False only when the audio is already known to be unwanted.
     */
    private fun releaseInternal(drain: Boolean) {
        // The join must happen *outside* the monitor: the worker calls the
        // synchronized ensureTrackFor, so joining while holding the lock deadlocks.
        running = false
        val w = worker
        worker = null
        w?.interrupt()
        try { w?.join(WORKER_JOIN_MS) } catch (_: InterruptedException) {}
        synchronized(this) {
            queue.clear()
            if (drain) drainCodecAndTrack()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            // Without the drain above this is the discard that truncated tails; with
            // it, everything worth hearing has already been played out.
            runCatching { track?.pause(); track?.flush(); track?.release() }
            track = null
            currentFormat = null
            endPending = false
            framesWritten = 0L
        }
    }

    /**
     * Release the codec and track for a stream that has genuinely finished — called
     * from the decode loop once `stream/end` has lingered out with nothing following.
     *
     * On the worker itself, under the monitor, rather than on a helper thread. The
     * helper-thread version raced [start]: between the loop deciding to tear down and
     * the helper actually running (a thread start is not instant), a `stream/start`
     * for the next track could land, and either outcome was silence with no error
     * anywhere and nothing to see in the connection state:
     *
     *  - it took [start]'s continuation branch — `running` was still true and the
     *    format matched — which deliberately does *not* restart a worker, because a
     *    continuation normally leaves one alive. This one had already left its loop,
     *    so nothing decoded again; or
     *  - it took the restart branch and built a fresh codec, track and worker, which
     *    the helper then released out from under it on its way through.
     *
     * Being on the worker means there is nothing to join — the only reason the helper
     * thread existed, since [releaseInternal] cannot join the thread calling it. Being
     * under the monitor means [start] either got in first, in which case the re-check
     * below finds the engine alive again and leaves it strictly alone, or it waits and
     * finds a clean slate.
     *
     * @return true when the teardown happened and the loop should exit; false when a
     *   new stream claimed the engine first, in which case the loop carries on and
     *   serves it.
     */
    @Synchronized
    private fun tearDownIdleStream(): Boolean {
        // Re-checked under the lock: [start] may have taken the monitor between the
        // loop's own unsynchronized test and this call.
        if (!running || !endPending || queue.isNotEmpty()) return false
        Log.d(TAG, "stream ended and drained - releasing")
        running = false
        // Cleared here so a later [start] builds a fresh worker: this one is about to
        // leave its loop, and a null handle is how [start] knows that.
        worker = null
        drainCodecAndTrack()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        currentFormat = null
        endPending = false
        framesWritten = 0L
        return true
    }

    /**
     * Play out what the decoder and the track are still holding.
     *
     * Two stages, and both are needed. The decoder has its own internal pipeline
     * that a plain `stop()` discards, so it is drained with an explicit
     * end-of-stream buffer first. Then `AudioTrack.stop()` — which in `MODE_STREAM`
     * plays the remaining data out rather than dropping it, unlike `flush()` — but
     * it returns immediately and drains *asynchronously*, so releasing straight
     * afterwards would truncate anyway. Hence the bounded wait on the playback head.
     */
    private fun drainCodecAndTrack() {
        val t = track ?: return
        val c = codec
        if (c != null && !isPcm) {
            runCatching {
                val deadline = android.os.SystemClock.elapsedRealtime() + DRAIN_EOS_TIMEOUT_MS
                val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                val info = MediaCodec.BufferInfo()
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                    if (outIdx < 0) {
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) continue else break
                    }
                    if (info.size > 0) {
                        c.getOutputBuffer(outIdx)?.let { out ->
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            writeToTrack(t, out, info.size)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        runCatching {
            t.stop()
            val bufferMs = if (trackSampleRate > 0) {
                t.bufferSizeInFrames * 1000L / trackSampleRate
            } else 0L
            val deadline = android.os.SystemClock.elapsedRealtime() + bufferMs + DRAIN_TAIL_MARGIN_MS
            var last = -1L
            var still = 0
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (t.playState == AudioTrack.PLAYSTATE_STOPPED) break
                val head = t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                if (head == last) {
                    if (++still >= 2) break
                } else {
                    still = 0
                    last = head
                }
                Thread.sleep(DRAIN_POLL_MS)
            }
        }
    }

    override fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        track?.setVolume(effectiveVolume())
    }

    /**
     * Route audio output to a specific [AudioDeviceInfo] (USB DAC, Bluetooth
     * headset, etc.). Takes effect on the next [start] call — the AudioTrack is
     * rebuilt per stream. Call with null to revert to the system default route.
     */
    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredOutputDevice = device
    }

    // --- decode loop ------------------------------------------------------

    private fun runLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // ── Head-of-stream gate ────────────────────────────────────────────
            //
            // Only the *first* frame of a stream is scheduled; after that the
            // AudioTrack paces playback itself at the stream's sample rate.
            //
            // Scheduling every frame was wrong and audible: the clock filter needs a
            // few round-trips to converge, so early frames played immediately (unsynced)
            // and then, a second or two in, the loop suddenly started blocking on frames
            // whose scheduled time had already been consumed by the unscheduled head
            // start. That transition is a stall the listener hears as a skip. Deciding
            // once, at the head of the stream, cannot do that: either the whole stream
            // is scheduled or none of it is.
            //
            // The decision is made *before* touching the queue, and that ordering is
            // the whole fix for "the track starts a few seconds in" and "no audio for
            // the first few seconds". This used to poll a frame first and then
            // `continue` on HOLD, which threw that frame away — so every frame that
            // arrived while the clock was converging was silently destroyed. The
            // server streams several seconds ahead, so by the time the offset landed
            // the head of the queue was already seconds into the song; and if the
            // clock never converged at all, the audio was simply gone.
            //
            // Holding without polling lets the frames pile up instead. Whenever the
            // gate opens, the head of the queue is still the head of the *track*.
            // ── Deferred teardown ──────────────────────────────────────────────
            //
            // `stream/end` only marks the stream finished; the tail is still playing.
            // Once the queue has run dry and the linger has passed, nothing more is
            // coming — the next stream/start would have cancelled this — so drain and
            // release. Done here rather than on a timer so it stays serialised with
            // decoding for free.
            if (endPending && queue.isEmpty() &&
                android.os.SystemClock.elapsedRealtime() - endPendingAtMs > END_LINGER_MS
            ) {
                if (tearDownIdleStream()) break
            }

            var scheduleHead = false
            if (awaitStart) {
                val stalledMs = android.os.SystemClock.elapsedRealtime() - headArmedAtMs
                // `isSynced()` is true after a *single* round-trip, long before the
                // filter has converged, so scheduling on it meant scheduling against
                // an offset still seconds wide: the head was either held in silence
                // while the server ran on, or dropped as "late". Playback start has
                // its own, stricter readiness test — this is what it was for.
                when (HeadGate.decide(clockReady = clock.isReadyForPlaybackStart(), stalledMs = stalledMs)) {
                    HeadGate.Decision.HOLD -> {
                        // Don't touch the queue. Let it fill.
                        try { Thread.sleep(HOLD_POLL_MS) } catch (_: InterruptedException) { break }
                        continue
                    }
                    HeadGate.Decision.PLAY_NOW ->
                        Log.w(TAG, "head: clock never converged in ${stalledMs}ms - playing unscheduled")
                    HeadGate.Decision.SCHEDULE -> scheduleHead = true
                }
            }

            val frame = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue                      // nothing yet - the gate re-decides next pass

            if (awaitStart) {
                // A frame is in hand, so the gate has actually done its job and can
                // close. Disarming any earlier would have let a pass that polled
                // nothing leave the rest of the stream unscheduled.
                awaitStart = false
                if (scheduleHead && !awaitFrameTime(frame.serverTsUs)) {
                    // Its moment has passed — which for the head of a stream means we
                    // spent the wait converging, not that this audio is stale. The
                    // spec's "drop late chunks" is about staying in sync mid-stream;
                    // applying it here deletes the start of the song, which is the bug
                    // being fixed. Play it, and accept being a beat behind the group.
                    Log.d(TAG, "head late but earliest available - playing unscheduled")
                } else if (scheduleHead) {
                    Log.i(TAG, "head released: err=${clock.errorUs()}us")
                }
            }

            if (isPcm) {
                // Passthrough: the track was built in start() from the server's
                // stated depth, because there is no decoder to ask.
                val t = track
                if (t != null) {
                    t.write(frame.payload, 0, frame.payload.size)
                    logFirstWrite(t, frame.payload.size)
                    countFrames(frame.payload.size)
                }
                continue
            }

            val c = codec ?: continue
            try {
                val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIdx >= 0) {
                    c.getInputBuffer(inIdx)?.let { buf ->
                        buf.clear(); buf.put(frame.payload)
                        c.queueInputBuffer(inIdx, 0, frame.payload.size, frame.serverTsUs, 0)
                    }
                }
                var outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                // The decoder's own answer about what it is producing — rate,
                // channels *and* sample encoding. This is what the track gets built
                // from, so the two can never disagree: a rate or channel mismatch
                // plays at the wrong pitch, and an encoding mismatch (2-byte samples
                // written into a 3-byte-per-sample track) is white noise. MediaCodec
                // always raises this before the first output buffer.
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ensureTrackFor(c.outputFormat)
                    outIdx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                }
                while (outIdx >= 0) {
                    if (info.size > 0) {
                        // Belt and braces: if a decoder ever hands us output without
                        // having announced its format, ask it directly rather than
                        // dropping the audio.
                        val t = track ?: ensureTrackFor(c.outputFormat)
                        c.getOutputBuffer(outIdx)?.let { out ->
                            // Hand the codec's own buffer straight to the track: no
                            // intermediate ByteArray, so nothing to allocate, copy or
                            // pool on the decode hot path (~112 KB/s at 900 kbps FLAC).
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            if (t != null) writeToTrack(t, out, info.size)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "codec error: ${e.message}")
            }
        }
    }

    /**
     * Block until [serverTsUs] — the instant the server wants this chunk heard —
     * arrives on the local clock. Returns false if the frame is too late to play.
     *
     * Called for the head of a stream only — see the call site. The spec is explicit: "Binary audio
     * messages contain timestamps in the server's time domain indicating when the
     * audio should be played… Clients must translate this server timestamp to their
     * local clock using the offset computed from clock synchronization", and "Audio
     * chunks may arrive with timestamps in the past… clients should drop these late
     * chunks to maintain sync."
     *
     * The loop used to write every frame the moment it arrived. Since the server
     * streams as far ahead as `buffer_capacity` allows — several seconds — the whole
     * prefetched buffer was played out immediately, so a track began some seconds into
     * itself and the phone could never stay in step with another speaker. [ClockSync]
     * was built for exactly this and had no reader.
     *
     * Waiting is bounded: a timestamp implausibly far ahead means the server's epoch
     * isn't what we think, and sleeping on it would stall playback outright — better
     * to play immediately and let the next frames settle.
     *
     * Whether the clock is trustworthy enough to schedule against at all is the
     * caller's decision, via [HeadGate] — this only converts and waits.
     */
    private fun awaitFrameTime(serverTsUs: Long): Boolean {
        if (serverTsUs <= 0L) return true                         // nothing to schedule against
        val localUs = clock.serverTimeToLocal(serverTsUs)
        val staticUs = staticDelayMs * 1_000L
        // Whatever is already queued in the track plays before this frame does, so
        // it counts against the wait. Zero on a fresh track.
        val buffered = bufferedUs()
        var waitUs = Scheduling.waitUs(localUs, clock.nowUs(), buffered, staticUs)
        if (waitUs < -LATE_TOLERANCE_US) {                        // too late to be useful
            Log.d(TAG, "head late by ${-waitUs / 1000}ms - dropped")
            return false
        }
        if (waitUs > MAX_LEAD_US) return true                     // implausible: don't stall on it
        while (running && waitUs > 0) {
            // Sleep in slices so release()/flush() are still responsive mid-wait.
            val slice = waitUs.coerceAtMost(20_000L)
            try {
                Thread.sleep(slice / 1_000L, ((slice % 1_000L) * 1_000L).toInt())
            } catch (_: InterruptedException) {
                return false
            }
            waitUs = Scheduling.waitUs(localUs, clock.nowUs(), bufferedUs(), staticUs)
        }
        return running
    }

    /** Write to the track and keep the frame count [bufferedUs] depends on. */
    private fun writeToTrack(t: AudioTrack, buf: ByteBuffer, size: Int) {
        val written = t.write(buf, size, AudioTrack.WRITE_BLOCKING)
        if (written > 0) { logFirstWrite(t, written); countFrames(written) }
    }

    /**
     * One line per stream, the first time decoded audio actually reaches the track.
     *
     * This is the single log that separates the two shapes of "MA plays but there is
     * no sound", which otherwise look identical from outside: nothing ever decoded
     * (no line — the stream never started, the gate never opened, or the codec was
     * never built), versus audio reaching the output and being inaudible anyway (a
     * line, with the gain and route that were applied to it). Cheap enough to keep
     * on: it is once per track, not once per buffer.
     */
    private fun logFirstWrite(t: AudioTrack, bytes: Int) {
        if (firstWriteLogged) return
        firstWriteLogged = true
        Log.i(
            TAG,
            "first write: ${bytes}B @ $trackSampleRate/${trackChannels}ch/enc=$trackEncoding " +
                "vol=${effectiveVolume()} (user=$volume syncMuted=$syncMuted) " +
                "route=${t.routedDevice?.let { AudioOutputs.describe(it) } ?: "system default"} " +
                "state=${t.playState}",
        )
    }

    private fun countFrames(bytes: Int) {
        val bpf = bytesPerFrame()
        if (bpf > 0) framesWritten += bytes / bpf
    }

    // --- codec / track creation (derived from the massdroid engine) -------

    /**
     * Build (or rebuild) the track to match what the decoder says it is emitting.
     * Called on `INFO_OUTPUT_FORMAT_CHANGED`, before any output buffer is written.
     *
     * A decoder is free to ignore what we asked for — an OEM FLAC decoder that
     * doesn't do float output will simply report 16-bit, and this follows it
     * rather than corrupting every sample. Returns the live track.
     */
    @Synchronized
    private fun ensureTrackFor(out: MediaFormat): AudioTrack? {
        val rate = runCatching { out.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull() ?: trackSampleRate
        val ch = runCatching { out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull() ?: trackChannels
        val enc = runCatching { out.getInteger(MediaFormat.KEY_PCM_ENCODING) }
            .getOrNull() ?: AudioFormat.ENCODING_PCM_16BIT
        val existing = track
        if (existing != null && rate == trackSampleRate && ch == trackChannels && enc == trackEncoding) {
            return existing
        }
        if (existing != null) {
            Log.w(TAG, "decoder output $rate/${ch}ch/enc=$enc != track " +
                "$trackSampleRate/${trackChannels}ch/enc=$trackEncoding - rebuilding")
            runCatching { existing.pause(); existing.flush(); existing.release() }
        }
        trackSampleRate = rate
        trackChannels = ch
        trackEncoding = enc
        // A new track starts empty, so the counter bufferedUs() reads must too.
        framesWritten = 0L
        return runCatching {
            createTrack(rate, ch, enc).also { it.setVolume(effectiveVolume()); it.play() }
        }.onFailure { Log.e(TAG, "couldn't build track for $rate/${ch}ch/enc=$enc: ${it.message}") }
            .getOrNull()
            .also { track = it }
    }

    /**
     * Build the AudioTrack for an explicit [encoding]. When [preferredOutputDevice]
     * is set, routes output to that device (USB DAC) via `setPreferredDevice`.
     */
    private fun createTrack(sampleRate: Int, channels: Int, encoding: Int): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        // The buffer is sized for ~1 s of headroom whatever the encoding.
        val bytesPerSample = bytesPerSample(encoding)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufSize = max(minBuf, sampleRate * channels * bytesPerSample)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        val t = builder.build()
        // Route to a preferred output device (USB DAC) if one is set. Done after
        // build because `setPreferredDevice` is a runtime call, not a builder one.
        preferredOutputDevice?.let { dev -> t.setPreferredDevice(dev) }
        return t
    }

    private fun createFlacDecoder(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        wantHiRes: Boolean = false,
    ): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MAX_INPUT)
        format.setInteger("bit-depth", bitDepth)
        // MediaCodec has no 24-bit-packed *output* encoding, so the way to get more
        // than 16 bits out of the FLAC decoder is to ask for float — which AOSP's
        // decoder honours for >16-bit sources. Requesting it is only ever a hint:
        // ensureTrackFor reads back what the decoder actually chose.
        if (wantHiRes) {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        // The server sends codec_header in either of two shapes - bare 34-byte
        // STREAMINFO, or a complete 42-byte FLAC header (magic + block header +
        // STREAMINFO) - see SendspinContainerHeader.extractStreamInfo's doc for why
        // both are real, confirmed against this exact failure mode on the ExoPlayer
        // path. csd-0 wants the bare STREAMINFO either way, matching
        // SendspinContainerHeader.flacStreamHeader's own convention for the same field.
        codecHeader?.let {
            try {
                val decoded = Base64.decode(it, Base64.DEFAULT)
                val streamInfo = SendspinContainerHeader.extractStreamInfo(decoded)
                if (streamInfo != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(streamInfo))
                } else {
                    Log.w(TAG, "createFlacDecoder: codec_header decoded to ${decoded.size} bytes, " +
                        "neither bare STREAMINFO (34) nor a full FLAC header (42) - csd-0 left unset")
                }
            } catch (e: Exception) {
                Log.w(TAG, "createFlacDecoder: couldn't decode codec_header: ${e.message}")
            }
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OPUS_MAX_INPUT)
        val csd0 = codecHeader?.let {
            try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
        } ?: opusHead(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(preSkipNs); rewind() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); rewind() })
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0); start()
        }
    }

    private fun opusHead(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(channels.toByte())
            putShort(3840.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0)
        }.array()

}
