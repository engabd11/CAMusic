package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import com.engabd.sendpin.protocol.ClockSync
import com.engabd.sendpin.protocol.MonotonicClock
import com.engabd.sendpin.protocol.StreamStartPlayerInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Native (Oboe) Sendspin playback engine — a port of MassDroid's proven
 * `SendspinPlaybackEngine` architecture (github.com/sfortis/massdroid_native, MIT).
 *
 * Replaces the ExoPlayer-based `SendspinExoEngine`, which routed through
 * ExoPlayer's `AudioSink` interface (`OboeAudioSink`). That path had three
 * fatal defects:
 *
 * 1. **Lost server timestamps** — ExoPlayer's `presentationTimeUs` is
 *    media-relative, not server time. The `OboeAudioSink` had to reconstruct
 *    an absolute timestamp from a media-time anchor, which drifted.
 * 2. **Starved native ring** — `SendspinSyncDataSource` blocked with
 *    `Thread.sleep()` to pace frames, but this starved the native ring buffer,
 *    causing it to run dry and stall.
 * 3. **Off by default** — the Oboe path was opt-in and fell back to platform
 *    `AudioTrack` (no drift correction, no GC immunity).
 *
 * This engine fixes all three by replacing ExoPlayer with a dedicated decode
 * thread that feeds decoded PCM directly to [SendspinNativeOutput] (the Oboe
 * native engine), with the native callback owning ALL timeline/drift
 * correction. The decode thread never blocks on the clock — it decodes and
 * writes to the ring immediately, and the native callback does all timing.
 *
 * ## Two modes, one instance
 *
 * Unlike MassDroid, which swaps entire engine instances at a group join/leave
 * boundary, CAMusic uses a single instance with a [grouped] flag that controls
 * which timing policy is active:
 *
 * - **SYNC (grouped = true)**: 200 ms headroom, clock-readiness gate, drift
 *   correction ON. Matches the official client phase so this player shares the
 *   group's playout instant.
 * - **DIRECT (grouped = false)**: local anchor, drift correction OFF, instant
 *   start. No clock dependency, no headroom — playback begins ~now.
 *
 * The mode can change mid-stream without requiring a new `stream/start`:
 * [setGrouped] reconfigures the native output's drift correction mode and
 * re-arms the timeline anchor.
 *
 * ## Clock domains
 *
 * This engine operates in CLOCK_BOOTTIME (via [MonotonicClock]), which is
 * CAMusic's standard time domain. [SendspinNativeOutput.write] converts
 * BOOTTIME → CLOCK_MONOTONIC at the JNI boundary (see its `bootToMonotonicUs`
 * doc for why the two are not interchangeable). The chain is:
 *
 * server timestamp → [ClockSync.serverTimeToLocal] → CLOCK_BOOTTIME →
 * [SendspinNativeOutput.write] → `bootToMonotonicUs()` → CLOCK_MONOTONIC →
 * native engine
 *
 * ## Audio analysis tap
 *
 * [AudioAnalysisTap] (for Hue light sync) is fed directly from the decode
 * thread via [AudioAnalysisTap.analyseExternal] after each chunk is written to
 * the native ring. In the old engine it sat in ExoPlayer's render chain; here
 * it gets the same PCM at the same point in the pipeline.
 *
 * ## Threading
 *
 * The decode/playback thread (`SendspinTimeline`) runs at
 * `THREAD_PRIORITY_AUDIO` and is the sole producer to the native ring.
 * [SendspinNativeOutput]'s Oboe callback runs on a real-time HAL thread and
 * is the sole consumer. Control methods ([start], [submit], [flush], etc.)
 * are safe to call from any thread — they use volatile fields and a
 * main-thread handler for device callback routing.
 *
 * One instance per Sendspin connection (see
 * [com.engabd.sendpin.service.Playback.startSendspin]).
 */
class SendspinNativeEngine(
    private val context: Context,
    private val clock: ClockSync,
    /**
     * Called when the engine has exhausted retries and cannot recover.
     * The caller (Playback.kt) forces a fresh Sendspin socket reconnect so
     * Music Assistant gets another chance to send `stream/start`.
     */
    private val onFatalError: () -> Unit = {},
    /**
     * Fired the moment this engine actually starts producing audio.
     * `stream/start` precedes decoder and audio-track warm-up by over a second;
     * Playback.kt holds the playhead until this fires.
     */
    private val onAudible: () -> Unit = {},
) : SendspinPlaybackEngine {

    companion object {
        private const val TAG = "SendspinNative"
        private const val HEADER_SIZE = 9
        private const val TYPE_PLAYER_AUDIO = 4

        // Hard memory ceiling for the encoded frame queue. The server streams
        // ahead up to the requested buffer_capacity (~4 MB ~= 30 s of FLAC), so
        // this must stay well above it; it is only a runaway backstop.
        private const val MAX_ENCODED_BUFFER_BYTES = 10_000_000L
        private const val OPUS_MAX_INPUT_SIZE = 64 * 1024
        private const val FLAC_MAX_INPUT_SIZE = 256 * 1024

        // Producer backpressure: keep roughly this much decoded PCM in the
        // native ring and leave everything else encoded in frameQueue. Must
        // stay below the native RING_SECONDS so write() never has to drop.
        private const val RING_TARGET_MS = 2_500L

        // After playback has been IDLE this long, stop the real-time Oboe output
        // callback (requestStop, stream stays open) and let the producer thread
        // exit, so a connected-but-not-playing app draws no audio-HAL / CPU power.
        private const val IDLE_OUTPUT_STOP_GRACE_MS = 5_000L

        // Output is muted across every hard boundary and (re)start so the
        // transient at a skip/seek/relock (ring refill, decoder priming, clock
        // convergence) is never audible. The Oboe stream runs continuously, so
        // the flag MUST be applied to the native volume.
        private const val SYNC_STARTUP_MUTE_MS = 350L
        private const val DIRECT_STARTUP_MUTE_MS = 200L
        private const val SYNC_SAMPLE_CALLBACK_MS = 1_000L

        // Bounded backstop for the fresh-stream gate.
        private const val GATE_FRESH_STREAM_TIMEOUT_MS = 4_000L

        // Debounced reopen for route changes (BT connect flapping).
        private const val REOPEN_SETTLE_MS = 350L
        private const val REOPEN_MAX_WAIT_MS = 8_000L

        // SYNC mode: 200 ms headroom (official client phase), clock gate.
        private const val SYNC_START_BUFFER_MS = 250L
        private const val SYNC_CLOCK_WAIT_MS = 3_000L

        /**
         * A startup trim this long is a lost intro, not housekeeping.
         *
         * A quarter of a second is about where a dropped opening stops being
         * imperceptible and starts being "it skipped the beginning".
         */
        private const val NOTABLE_START_TRIM_MS = 250L
        private const val SYNC_CLOCK_ERROR_US = 15_000L
        private const val START_TARGET_HEADROOM_US = 50_000L
        private const val SCHEDULE_HEADROOM_US = 200_000L

        // DIRECT mode: local anchor, instant start.
        private const val DIRECT_START_BUFFER_MS = 350L
        private const val DIRECT_START_HEADROOM_US = 60_000L
    }

    // ---- Public state exposed to Playback.kt ----

    val audioLead = AudioLead()
    val audioAnalysisTap = AudioAnalysisTap(audioLead)

    @Volatile override var bitPerfect: Boolean = false

    /**
     * Whether this player is in a Sendspin group — selects SYNC vs DIRECT
     * timing policy. Set by Playback.kt from `group/update`'s `group_id`.
     * Can change mid-stream: [setGrouped] reconfigures the native output
     * and re-arms the timeline without requiring a new `stream/start`.
     */
    @Volatile private var _grouped: Boolean = false
    override val grouped: Boolean get() = _grouped

    @Volatile override var staticDelayMs: Int = 0

    // ---- Internal state ----

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nativeOutput = SendspinNativeOutput()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameQueue = PriorityBlockingQueue<EncodedFrame>()
    private val frameQueueBytes = AtomicLong(0)
    private val decoderMarks = ArrayDeque<DecoderMark>()
    private val codecLock = Any()
    private val playbackThreadLock = Any()
    private var pcmScratch = ByteArray(16384)
    private var pcmAligned = ByteArray(16384)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var configured = false
    @Volatile private var playbackActive = false
    @Volatile private var playbackStarted = false
    @Volatile private var outputPausedForIdle = false
    @Volatile private var reopenInFlight = false
    private var reopenSettleRunnable: Runnable? = null
    @Volatile private var reopenRequestedAtMs = 0L
    @Volatile private var outputFrozen = false
    @Volatile private var awaitingFreshStream = false
    @Volatile private var awaitingFreshStreamSinceMs = 0L
    @Volatile private var paused = false
    @Volatile private var configureGeneration = 0L
    @Volatile private var playbackGeneration = 0L
    @Volatile private var activeCodec = "flac"
    @Volatile private var activeBitDepth = 16
    @Volatile private var activeSampleRate = 48_000
    @Volatile private var activeChannels = 2
    @Volatile private var lastEnqueuedTimestampUs = 0L
    @Volatile private var estimatedFrameDurationUs = 20_000L
    @Volatile private var startupWaitStartedMs = 0L
    @Volatile private var halOutputLatencyUs = 0L
    @Volatile private var syncMuteStartedMs = 0L
    @Volatile private var receivedFrameCount = 0L
    @Volatile private var decodedChunkCount = 0L
    @Volatile private var writtenChunkCount = 0L
    @Volatile private var lastTimingLogMs = 0L
    @Volatile private var lastStartupLogMs = 0L
    @Volatile private var lastSyncSampleCallbackMs = 0L
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var deviceCallbackRegistered = false

    @Volatile private var currentVolume = 1f
    @Volatile private var userMuted = false
    @Volatile private var syncMuted = false

    // DIRECT mode local timeline anchor (re-armed on every flush).
    @Volatile private var anchorServerUs = 0L
    @Volatile private var anchorLocalUs = 0L

    // Stream tracking
    private val streamIds = AtomicLong(0)
    @Volatile private var playedOutStreamId = 0L
    @Volatile private var drainSignalled = false
    @Volatile private var endOfStreamSignalled = false

    // Has the current stream produced audio yet?
    @Volatile private var streamAudible = false

    // A held frame from a previous iteration (codec input was full).
    private var pendingFrame: EncodedFrame? = null

    // Media-time anchor for AudioAnalysisTap (0 = first sample of this stream).
    @Volatile private var mediaAnchorUs = 0L

    // Pending frames for the fresh-stream gate timeout.
    @Volatile private var routedDeviceType = -1
    @Volatile private var routedProductName: String? = null

    var onRoutingChanged: (() -> Unit)? = null

    // ---- Encoded frame and timing types ----

    private data class EncodedFrame(
        val serverTimestampUs: Long,
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val generation: Long,
    ) : Comparable<EncodedFrame> {
        override fun compareTo(other: EncodedFrame): Int =
            serverTimestampUs.compareTo(other.serverTimestampUs)
    }

    private data class DecoderMark(
        val serverTimestampUs: Long,
        val generation: Long,
    )

    private data class TimingPlan(
        val localOutputUs: Long,
        val staticDelayUs: Long,
        val outputLatencyUs: Long,
        val headroomUs: Long,
        val presentationUs: Long,
    )

    private data class LocalPlan(val localOutputUs: Long, val headroomUs: Long)

    // ---- SendspinPlaybackEngine interface ----

    override val currentStreamId: Long get() = streamIds.get()

    override fun isCurrentStream(streamId: Long) = streamId == streamIds.get()

    override fun hasPlayedOut(streamId: Long): Boolean {
        if (streamId == 0L || streamId != streamIds.get()) return false
        if (!drainSignalled) return false
        // The native ring has drained and no frames remain to decode.
        return frameQueue.isEmpty() && nativeOutput.bufferedFrames() == 0L
    }

    override fun start(format: StreamStartPlayerInfo) {
        streamAudible = false
        drainSignalled = false
        endOfStreamSignalled = false
        tailCut = false
        mediaAnchorUs = 0L
        val id = streamIds.incrementAndGet()

        val sameFormat = configured &&
            format.codec == activeCodec &&
            format.sampleRate == activeSampleRate &&
            format.channels == activeChannels &&
            format.bitDepth == activeBitDepth

        configureGeneration++
        cancelIdleStop()
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        startupWaitStartedMs = 0L
        paused = false
        resetSyncMetrics()
        onFlush()

        if (sameFormat) {
            Log.d(TAG, "configure reuse codec=${format.codec} ${format.sampleRate}Hz/${format.bitDepth}bit")
            ensureOutputRunning()
            flushQueuesAndDecoder()
            playbackStarted = false
            beginStartupMute()
            ensurePlaybackThread()
            return
        }

        Log.d(TAG, "configure rebuild codec=${format.codec} ${format.sampleRate}Hz/${format.bitDepth}bit ch=${format.channels}")
        releaseInternal()
        activeCodec = format.codec
        activeSampleRate = format.sampleRate
        activeChannels = format.channels
        activeBitDepth = format.bitDepth

        startNativeOutput()
        registerDeviceCallback()
        refreshRoutedDevice()

        codec = createCodec(format.codec, format.sampleRate, format.channels, format.bitDepth, format.codecHeader)
        configured = true
        playbackActive = true
        playbackStarted = false
        paused = false
        beginStartupMute()
        ensurePlaybackThread()
    }

    override fun submit(frame: ByteArray) {
        if (!configured || paused) return
        if (awaitingFreshStream && !freshStreamGateExpired()) return

        // Parse the Sendspin binary frame: [type:uint8][server_ts:big-endian int64][payload]
        if (frame.size <= HEADER_SIZE || (frame[0].toInt() and 0xFF) != TYPE_PLAYER_AUDIO) return

        val serverTimestampUs = parseTimestampUs(frame)
        val payloadLength = frame.size - HEADER_SIZE

        if (outputPausedForIdle) mainHandler.post { ensureOutputRunning() }

        // Cancel any pending idle stop — audio is flowing again.
        cancelIdleStop()

        // Overflow backstop
        if (frameQueueBytes.get() > MAX_ENCODED_BUFFER_BYTES) {
            Log.w(TAG, "Encoded buffer ceiling hit (${frameQueueBytes.get()}B) — dropping incoming frame")
            return
        }

        receivedFrameCount++
        val previousTail = lastEnqueuedTimestampUs
        if (previousTail > 0L) {
            val spacing = serverTimestampUs - previousTail
            if (spacing in 5_000L..500_000L) estimatedFrameDurationUs = spacing
        }

        frameQueue.offer(EncodedFrame(serverTimestampUs, frame, HEADER_SIZE, payloadLength, configureGeneration))
        frameQueueBytes.addAndGet(payloadLength.toLong())
        lastEnqueuedTimestampUs = serverTimestampUs
    }

    override fun endOfStream(drain: Boolean) {
        if (drain) {
            endOfStreamSignalled = true
            drainSignalled = true
            // Schedule idle stop for when the drain completes — the playback
            // loop will call scheduleIdleStop itself once the queue empties.
            return
        }
        cutTail(currentStreamId)
    }

    @Volatile private var tailCut = false

    override fun cutTail(streamId: Long) {
        if (streamId != streamIds.get()) return
        if (tailCut) return // already cut for this stream
        tailCut = true
        flushQueuesAndDecoder()
        playbackStarted = false
        syncMuted = true
        applyOutputVolume()
    }

    override fun flush() {
        configureGeneration++
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        flushQueuesAndDecoder()
        playbackStarted = false
        startupWaitStartedMs = 0L
        resetSyncMetrics()
        beginStartupMute()
    }

    override fun setVolume(v: Float) {
        currentVolume = v.coerceIn(0f, 1f)
        applyOutputVolume()
    }

    override fun setSyncMuted(muted: Boolean) {
        if (syncMuted == muted) return
        syncMuted = muted
        if (muted) syncMuteStartedMs = 0L // external mute, not startup mute
        applyOutputVolume()
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        // The native Oboe output binds to the system default route. Device
        // preference is handled via AudioDeviceCallback route reopening.
        // This is a no-op for the native engine — see handleDeviceChange.
    }

    // ---- New interface methods ----

    override fun setGrouped(grouped: Boolean) {
        if (_grouped == grouped) return
        _grouped = grouped
        Log.d(TAG, "Mode swap: grouped=$grouped")
        if (configured && nativeOutput.isStarted) {
            // Reconfigure drift correction mode on the live native output.
            // This requires a restart of the native stream.
            startNativeOutput()
            // Re-arm the timeline anchor so the new mode takes effect immediately.
            onFlush()
            if (playbackStarted) {
                playbackStarted = false
                beginStartupMute()
            }
        }
    }

    override fun freezeOutput() {
        Log.d(TAG, "Freeze output (preserve buffer)")
        outputFrozen = true
        nativeOutput.setFrozen(true)
    }

    override fun unfreezeOutput() {
        Log.d(TAG, "Unfreeze output (resume from preserved buffer) grouped=$_grouped")
        outputFrozen = false
        if (_grouped) {
            // Grouped: the leader kept playing, so the preserved buffer is
            // behind the live group timeline. Re-mute and reset sync metrics
            // so the native correction skips forward inaudibly.
            beginStartupMute()
            resetSyncMetrics()
        }
        nativeOutput.setFrozen(false)
    }

    override fun setCompressorLevel(level: Int) {
        nativeOutput.setCompressorLevel(level)
    }

    override fun setDither(enabled: Boolean) {
        nativeOutput.setDither(enabled)
    }

    override fun expectDiscontinuity(reason: String) {
        Log.d(TAG, "Discontinuity: $reason")
        flush()
        awaitingFreshStream = true
        Log.d(TAG, "Awaiting fresh stream after '$reason' — dropping in-flight frames")
    }

    override fun release() {
        releaseInternal()
    }

    // ---- Mode-specific timing ----

    private val isSync: Boolean get() = _grouped
    private val startupMuteMs: Long get() = if (isSync) SYNC_STARTUP_MUTE_MS else DIRECT_STARTUP_MUTE_MS
    private val startBufferMs: Long get() = if (isSync) SYNC_START_BUFFER_MS else DIRECT_START_BUFFER_MS

    /**
     * Role-specific local output time + headroom for [serverTimestampUs].
     *
     * SYNC: play at `serverToLocal(serverTs) + 200 ms headroom` (official
     * client phase). The native callback aligns the real DAC presentation
     * time to this, cancelling measured HAL latency.
     *
     * DIRECT: anchor the first frame to ~now and keep relative chunk spacing.
     * No clock dependency, no headroom — playback starts immediately.
     */
    private fun computeLocalPlan(serverTimestampUs: Long, outputLatencyUs: Long): LocalPlan {
        if (isSync) {
            val localOutputUs = clock.serverTimeToLocal(serverTimestampUs)
            return LocalPlan(localOutputUs, SCHEDULE_HEADROOM_US)
        } else {
            if (anchorServerUs == 0L) {
                anchorServerUs = serverTimestampUs
                anchorLocalUs = nowUs() + outputLatencyUs + DIRECT_START_HEADROOM_US
            }
            val localOutputUs = anchorLocalUs + (serverTimestampUs - anchorServerUs)
            return LocalPlan(localOutputUs, 0L)
        }
    }

    /**
     * Extra start gate after the buffer threshold is met.
     *
     * SYNC: wait for clock convergence (with a 3 s timeout backstop).
     * DIRECT: ready immediately.
     */
    private fun startupGate(neededMs: Long): Boolean {
        if (!isSync) return true
        if (clock.isReadyForPlaybackStart()) return trimStartupLateFrames(neededMs, START_TARGET_HEADROOM_US)
        val now = System.currentTimeMillis()
        if (startupWaitStartedMs == 0L) startupWaitStartedMs = now
        val timedOutReady = now - startupWaitStartedMs >= SYNC_CLOCK_WAIT_MS &&
            clock.isSynced() &&
            clock.errorUs() <= SYNC_CLOCK_ERROR_US
        if (!timedOutReady) maybeLogStartupWait("clock", neededMs)
        return timedOutReady && trimStartupLateFrames(neededMs, START_TARGET_HEADROOM_US)
    }

    /** Called from flushQueuesAndDecoder so mode-specific state can be reset. */
    private fun onFlush() {
        anchorServerUs = 0L
        anchorLocalUs = 0L
        // What ExoPlayer used to do for us. The tap was an AudioProcessor on the old MA
        // path, so the player flushed it on every seek, track change and reconfigure,
        // and the analyzer, the tempo/structure/gesture trackers and the layer chain all
        // started each track clean. Nothing called into the tap once this engine took
        // over, so MA track changes carried the previous song's state forward — the
        // second half of "light sync regressed for the MA player".
        audioAnalysisTap.resetAnalysis()
        // The buffered position is about to stop being true.
        audioLead.leadUs = AudioLead.UNKNOWN
        audioLead.mediaTimeUs = AudioLead.UNKNOWN
        mediaAnchorUs = 0L
    }

    // ---- Volume management ----

    private fun applyOutputVolume() {
        nativeOutput.setVolume(if (userMuted || syncMuted) 0f else currentVolume)
    }

    private fun beginStartupMute() {
        syncMuted = true
        syncMuteStartedMs = System.currentTimeMillis()
        applyOutputVolume()
    }

    // ---- Fresh-stream gate ----

    private fun freshStreamGateExpired(): Boolean {
        val now = System.currentTimeMillis()
        val since = awaitingFreshStreamSinceMs
        if (since == 0L) {
            awaitingFreshStreamSinceMs = now
            return false
        }
        if (now - since < GATE_FRESH_STREAM_TIMEOUT_MS) return false
        Log.w(TAG, "Fresh-stream gate timeout (${now - since}ms) — accepting continued stream")
        awaitingFreshStream = false
        awaitingFreshStreamSinceMs = 0L
        return true
    }

    // ---- Timing plan ----

    private fun timingPlan(serverTimestampUs: Long): TimingPlan {
        val outputLatencyUs = nativeOutput.outputLatencyUs()
        val local = computeLocalPlan(serverTimestampUs, outputLatencyUs)
        // Acoustic correction and unreported HAL gap exist ONLY for SYNC
        // (group timeline alignment). DIRECT is pure FIFO.
        // routeAcousticExtraUs is not configured in CAMusic (no acoustic
        // calibration), so staticDelayUs is always 0 for now.
        val staticDelayUs = 0L
        val unreportedLatencyUs = if (isSync) {
            (halOutputLatencyUs - outputLatencyUs).coerceAtLeast(0L)
        } else {
            0L
        }
        // Static delay: positive = this path adds latency = play earlier (subtract).
        val staticDelayOffsetUs = staticDelayMs.toLong() * 1000L
        val presentationUs = local.localOutputUs +
            local.headroomUs -
            staticDelayUs -
            unreportedLatencyUs -
            staticDelayOffsetUs
        return TimingPlan(local.localOutputUs, staticDelayUs, outputLatencyUs, local.headroomUs, presentationUs)
    }

    // ---- Startup trim ----

    /**
     * Drop frames whose intended presentation time is already in the past
     * so the first fed chunk is schedulable on the timeline.
     */
    private fun trimStartupLateFrames(neededMs: Long, headroomUs: Long): Boolean {
        if (playbackStarted) return true
        val minPresentationUs = nowUs() + headroomUs
        var droppedFrames = 0
        var droppedBytes = 0L
        while (true) {
            val head = frameQueue.peek() ?: break
            val plan = timingPlan(head.serverTimestampUs)
            if (plan.presentationUs >= minPresentationUs) break
            val dropped = frameQueue.poll() ?: break
            frameQueueBytes.addAndGet(-dropped.length.toLong())
            droppedFrames++
            droppedBytes += dropped.length.toLong()
        }
        if (droppedFrames > 0) {
            // Every dropped frame is audio the listener will not hear: the stream starts
            // that far in. A little is unavoidable when joining a group already playing.
            // A lot means this speaker spent too long getting ready — usually waiting on
            // clock convergence, which [ClockKalmanFilter.READY_SEEDED_MIN_SAMPLES] is
            // there to shorten — and is worth saying out loud rather than filing at
            // debug with the routine trims.
            val lostMs = droppedFrames * estimatedFrameDurationUs / 1000L
            val line = "start-trim frames=$droppedFrames bytes=$droppedBytes " +
                "lost=${lostMs}ms headroom=${headroomUs / 1000}ms buf=${bufferDurationMs()}ms"
            if (lostMs >= NOTABLE_START_TRIM_MS) Log.w(TAG, "$line - start of track lost")
            else Log.d(TAG, line)
        }
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("post-trim-buffer", neededMs)
            return false
        }
        return true
    }

    // ---- Buffer diagnostics ----

    private fun bufferDurationMs(): Long {
        val head = frameQueue.peek()?.serverTimestampUs ?: return 0L
        val tail = lastEnqueuedTimestampUs
        if (tail <= 0L || tail < head) return 0L
        return ((tail - head + estimatedFrameDurationUs) / 1000L).coerceAtLeast(0L)
    }

    private fun bufferedBytes(): Long = frameQueueBytes.get()

    // ---- Playback thread ----

    private fun ensurePlaybackThread() {
        synchronized(playbackThreadLock) {
            if (playbackThread?.isAlive == true) return
            val generation = ++playbackGeneration
            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                playbackLoop(generation)
            }, "SendspinTimeline").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    private fun playbackLoop(generation: Long) {
        while (playbackActive && generation == playbackGeneration) {
            if (paused || !configured) {
                sleepMs(10)
                continue
            }
            // Oboe disconnected — reopen.
            if (nativeOutput.isDisconnected() && !reopenInFlight) {
                reopenInFlight = true
                mainHandler.post { reopenAfterDisconnect() }
                sleepMs(50)
                continue
            }
            // Park while a reopen is in flight.
            if (reopenInFlight) {
                sleepMs(10)
                continue
            }
            // Start buffer gate (only before first startTrack).
            if (!playbackStarted) {
                if (!startupReady()) {
                    sleepMs(10)
                    continue
                }
                startTrack()
            }

            // Backpressure: keep the native ring around RING_TARGET_MS.
            val ringTargetFrames = activeSampleRate.toLong() * RING_TARGET_MS / 1000L
            if (nativeOutput.bufferedFrames() >= ringTargetFrames) {
                sleepMs(10)
                continue
            }

            val drained = drainDecoder(generation)
            if (drained) continue

            // Pull next encoded frame.
            val frame: EncodedFrame
            val held = pendingFrame
            if (held != null) {
                frame = held
                pendingFrame = null
            } else {
                val polled = try {
                    frameQueue.poll(10, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: run {
                    // Queue empty. If the stream has ended, schedule idle stop
                    // so the Oboe callback and producer thread exit after the
                    // grace period, saving power while connected but not playing.
                    if (endOfStreamSignalled && decoderMarks.isEmpty()) {
                        scheduleIdleStop()
                    }
                    continue
                }
                frameQueueBytes.addAndGet(-polled.length.toLong())
                frame = polled
            }
            if (frame.generation != configureGeneration) continue

            if (activeCodec == "pcm") {
                writeChunk(frame.serverTimestampUs, frame.data, frame.offset, frame.length, frame.generation, generation)
            } else {
                if (!queueCodecInput(frame)) pendingFrame = frame
                drainDecoder(generation)
            }
        }
    }

    private fun startupReady(): Boolean {
        val neededMs = startBufferMs
        if (bufferDurationMs() < neededMs) {
            maybeLogStartupWait("buffer", neededMs)
            return false
        }
        return startupGate(neededMs)
    }

    private fun startTrack() {
        beginStartupMute()
        playbackStarted = true
        Log.d(TAG, "Synchronized codec=$activeCodec buf=${bufferDurationMs()}ms")
    }

    // ---- Codec ----

    private fun queueCodecInput(frame: EncodedFrame): Boolean {
        return synchronized(codecLock) {
            val mc = codec ?: return@synchronized true
            try {
                val inputIndex = mc.dequeueInputBuffer(10_000)
                if (inputIndex < 0) return@synchronized false
                val input = mc.getInputBuffer(inputIndex) ?: return@synchronized false
                input.clear()
                if (frame.length > input.remaining()) {
                    Log.w(TAG, "Oversized $activeCodec frame dropped: ${frame.length}B")
                    return@synchronized true
                }
                input.put(frame.data, frame.offset, frame.length)
                decoderMarks.addLast(DecoderMark(frame.serverTimestampUs, frame.generation))
                mc.queueInputBuffer(inputIndex, 0, frame.length, frame.serverTimestampUs, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "queueCodecInput skipped: ${e.message}")
                return@synchronized true
            }
            true
        }
    }

    private fun drainDecoder(generation: Long): Boolean {
        if (codec == null) return false
        var wroteAny = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            var ready = false
            var chunkTs = 0L
            var chunkLen = 0
            var chunkGen = 0L
            val more = synchronized(codecLock) {
                val mc = codec ?: return@synchronized false
                val outputIndex = try {
                    mc.dequeueOutputBuffer(info, 0)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "drainDecoder stop: ${e.message}")
                    return@synchronized false
                }
                if (outputIndex < 0) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt = mc.outputFormat
                        Log.d(TAG, "Decoder output: ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
                    }
                    return@synchronized false
                }
                if (info.size > 0) {
                    val mark = decoderMarks.pollFirst()
                    if (mark != null) {
                        if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                        mc.getOutputBuffer(outputIndex)?.get(pcmScratch, 0, info.size)
                        chunkTs = mark.serverTimestampUs
                        chunkLen = info.size
                        chunkGen = mark.generation
                        ready = true
                        decodedChunkCount++
                    }
                } else if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // Decoder EOS — no more output coming.
                    mc.releaseOutputBuffer(outputIndex, false)
                    return@synchronized false
                }
                mc.releaseOutputBuffer(outputIndex, false)
                true
            }
            if (!more) break
            if (ready && chunkGen == configureGeneration && generation == playbackGeneration) {
                writeChunk(chunkTs, pcmScratch, 0, chunkLen, chunkGen, generation)
                wroteAny = true
            }
        }
        return wroteAny
    }

    /**
     * Hands a decoded PCM chunk to the native output, tagged with its intended
     * absolute presentation time. Also feeds the AudioAnalysisTap for Light Sync.
     *
     * No sleep, no late-drop, no latency subtraction: the native callback owns
     * the timeline alignment and DAC latency. This thread only decodes and
     * feeds, so a GC pause here is absorbed by the ring instead of underrunning
     * the DAC.
     */
    private fun writeChunk(
        serverTimestampUs: Long,
        pcm: ByteArray,
        offset: Int,
        length: Int,
        chunkGeneration: Long,
        generation: Long,
    ) {
        if (length <= 0 || generation != playbackGeneration || paused) return
        if (chunkGeneration != configureGeneration) return
        val plan = timingPlan(serverTimestampUs)

        // 24-bit PCM → 16-bit conversion (native output only supports int16).
        val writePcm: ByteArray
        val writeOffset: Int
        val writeLength: Int
        if (activeBitDepth == 24 && activeCodec == "pcm") {
            val slice = if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length)
            writePcm = convertPcm24To16(slice)
            writeOffset = 0
            writeLength = writePcm.size
        } else if (offset % 2 != 0) {
            if (pcmAligned.size < length) pcmAligned = ByteArray(length)
            System.arraycopy(pcm, offset, pcmAligned, 0, length)
            writePcm = pcmAligned
            writeOffset = 0
            writeLength = length
        } else {
            writePcm = pcm
            writeOffset = offset
            writeLength = length
        }

        nativeOutput.write(writePcm, writeOffset, writeLength, plan.presentationUs)

        // Feed the AudioAnalysisTap for Hue Light Sync. analyseExternal checks
        // internally whether the tap is active, so no guard is needed here.
        // mediaTimeUs is media-relative (0 = first sample of this stream), which
        // is what the tap's internal clock expects. Tracked from the first write.
        val frames = writeLength / (activeChannels * 2).coerceAtLeast(1)
        if (mediaAnchorUs == 0L) mediaAnchorUs = plan.presentationUs
        val mediaTimeUs = plan.presentationUs - mediaAnchorUs
        val buf = ByteBuffer.wrap(writePcm, writeOffset, writeLength)
        audioAnalysisTap.analyseExternal(
            buf,
            activeSampleRate,
            activeChannels,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            mediaTimeUs,
        )

        // Update AudioLead: how far ahead of the speaker the tap is.
        //
        // The distance from *now* to when this chunk is scheduled to be heard, plus the
        // output path's own latency — which is what [AudioLeadProbe] measured on the
        // ExoPlayer path it replaced (`presentationTimeUs - getCurrentPositionUs()`).
        //
        // It used to report `bufferedFrames()`, the whole depth of the native ring. That
        // is a different quantity, and the producer deliberately keeps the ring at
        // RING_TARGET_MS = 2500 — so the lead read as ~2.5 s regardless of how far ahead
        // the tap actually was. [com.engabd.sendpin.hue.FrameDelayQueue] then held light
        // frames for 2.4 s while its own MAX_ENTRIES caps at roughly 2 s, so Music
        // Assistant's lights ran about two seconds behind the music *and* dropped frames
        // off the head of the queue. Local playback was unaffected because LocalPlayer
        // still runs the real probe, which is why this looked like "the lights regressed
        // for MA" specifically.
        val scheduledAheadUs = (plan.presentationUs - nowUs()).coerceAtLeast(0L)
        audioLead.leadUs = scheduledAheadUs + plan.outputLatencyUs
        // Track-relative, per the field's contract — the same value handed to the tap
        // just above, not the absolute output-clock stamp it was being given.
        audioLead.mediaTimeUs = mediaTimeUs

        writtenChunkCount++

        // Signal audible on first chunk written.
        if (!streamAudible) {
            streamAudible = true
            onAudible()
        }

        maybeSampleNativeSync()
        maybeLogWriteTiming(serverTimestampUs, plan, nowUs(), writeLength, frames)

        // Unmute once actually locked (SYNC) or after time cushion (DIRECT).
        if (syncMuted &&
            syncMuteStartedMs > 0L &&
            System.currentTimeMillis() - syncMuteStartedMs > startupMuteMs &&
            (!isSync || abs(nativeOutput.driftEmaUs()) < 5_000L)
        ) {
            syncMuted = false
            syncMuteStartedMs = 0L
            applyOutputVolume()
        }
    }

    // ---- Sync sampling ----

    private fun maybeSampleNativeSync() {
        if (!isSync) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSyncSampleCallbackMs < SYNC_SAMPLE_CALLBACK_MS) return
        lastSyncSampleCallbackMs = nowMs
        // Native drift = intended - DAC presentation. That IS the live sync error.
        val driftMs = nativeOutput.driftEmaUs() / 1000.0
        Log.d(TAG, "sync sample drift=${"%.2f".format(driftMs)}ms lat=${nativeOutput.outputLatencyUs() / 1000}ms clockErr=${clock.errorUs() / 1000}ms")
    }

    // ---- Logging ----

    private fun maybeLogStartupWait(reason: String, neededMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastStartupLogMs < 1_000L) return
        lastStartupLogMs = now
        Log.d(TAG, "start-wait reason=$reason grouped=$isSync buf=${bufferDurationMs()}ms need=${neededMs}ms codec=$activeCodec clockErr=${clock.errorUs()}us")
    }

    private fun maybeLogWriteTiming(
        serverTimestampUs: Long,
        plan: TimingPlan,
        nowUsValue: Long,
        written: Int,
        frames: Int,
    ) {
        val nowMs = System.currentTimeMillis()
        val force = writtenChunkCount <= 12 || writtenChunkCount % 250 == 0L
        if (!force && nowMs - lastTimingLogMs < 1_000L) return
        lastTimingLogMs = nowMs
        Log.d(TAG, "write chunk#$writtenChunkCount grouped=$isSync codec=$activeCodec serverTs=${serverTimestampUs / 1000}ms " +
            "present=${plan.presentationUs / 1000}ms now=${nowUsValue / 1000}ms " +
            "lead=${(plan.presentationUs - nowUsValue) / 1000}ms frames=$frames " +
            "ring=${nativeOutput.bufferedFrames() * 1000L / activeSampleRate}ms " +
            "buf=${bufferDurationMs()}ms syncMuted=$syncMuted")
    }

    // ---- Flush and release ----

    private fun flushQueuesAndDecoder() {
        outputFrozen = false
        nativeOutput.setFrozen(false)
        frameQueue.clear()
        frameQueueBytes.set(0)
        decoderMarks.clear()
        lastEnqueuedTimestampUs = 0L
        estimatedFrameDurationUs = 20_000L
        onFlush()
        nativeOutput.flush()
        synchronized(codecLock) {
            try { codec?.flush() } catch (_: Exception) {}
        }
    }

    private fun releaseInternal() {
        cancelIdleStop()
        outputPausedForIdle = false
        reopenSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        reopenSettleRunnable = null
        reopenRequestedAtMs = 0L
        reopenInFlight = false
        outputFrozen = false
        playbackActive = false
        paused = false
        playbackStarted = false
        playbackGeneration++
        // Stop the producer and wait for it to exit before releasing the codec.
        val producer = synchronized(playbackThreadLock) {
            val t = playbackThread
            playbackThread = null
            t
        }
        producer?.interrupt()
        if (producer != null && producer !== Thread.currentThread()) {
            try { producer.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        flushQueuesAndDecoder()
        synchronized(codecLock) {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
        unregisterDeviceCallback()
        nativeOutput.release()
        routedDeviceType = -1
        routedProductName = null
        configured = false
        syncMuted = false
        syncMuteStartedMs = 0L
    }

    // ---- Idle power management ----

    private val idleStopRunnable = Runnable { stopOutputForIdle() }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        mainHandler.postDelayed(idleStopRunnable, IDLE_OUTPUT_STOP_GRACE_MS)
    }

    private fun cancelIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
    }

    private fun stopOutputForIdle() {
        if (playbackStarted || outputPausedForIdle) return
        if (frameQueue.isNotEmpty()) return // still have encoded frames to decode
        if (nativeOutput.bufferedFrames() > 0) return // still have PCM in the ring
        Log.d(TAG, "Idle ${IDLE_OUTPUT_STOP_GRACE_MS}ms: stopping native output + producer (power save)")
        outputPausedForIdle = true
        playbackActive = false
        nativeOutput.pauseStream()
    }

    private fun ensureOutputRunning() {
        cancelIdleStop()
        if (!outputPausedForIdle) return
        outputPausedForIdle = false
        nativeOutput.resumeStream()
        playbackActive = true
        ensurePlaybackThread()
        Log.d(TAG, "Output resumed from idle stop")
    }

    // ---- Native output ----

    private fun startNativeOutput() {
        if (!nativeOutput.start(activeSampleRate, activeChannels, isSync)) {
            Log.e(TAG, "Native output failed to start ${activeSampleRate}Hz ch=$activeChannels; stream will be silent")
        }
        if (outputFrozen) nativeOutput.setFrozen(true)
        val halMs = queryHalOutputLatencyMs()
        halOutputLatencyUs = if (halMs > 0) halMs * 1000L else 0L
        Log.d(TAG, "HAL output latency=${halMs}ms (getTimestamp sees ~${nativeOutput.outputLatencyUs() / 1000}ms)")
    }

    private fun queryHalOutputLatencyMs(): Int = try {
        val m = AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
        m.invoke(audioManager, AudioManager.STREAM_MUSIC) as Int
    } catch (e: Exception) {
        -1
    }

    // ---- Route change handling ----

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post { handleDeviceChange() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post { handleDeviceChange() }
        }
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) return
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        deviceCallbackRegistered = true
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        deviceCallbackRegistered = false
    }

    private fun handleDeviceChange() {
        if (!configured) return
        if (reopenInFlight) {
            scheduleReopen("device-change while settling")
            return
        }
        val boundId = nativeOutput.deviceId()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val stillPresent = boundId > 0 && outputs.any { it.id == boundId }
        if (!stillPresent) {
            scheduleReopen("bound device $boundId gone")
            return
        }
        val boundType = outputs.firstOrNull { it.id == boundId }?.type
        val boundIsExternal = boundType != null && isExternalSink(boundType)
        val externalSinkPresent = outputs.any { isExternalSink(it.type) }
        if (!boundIsExternal && externalSinkPresent) {
            scheduleReopen("external sink appeared while bound to built-in output")
        } else {
            refreshRoutedDevice()
        }
    }

    private fun reopenAfterDisconnect() {
        if (!configured) {
            reopenInFlight = false
            return
        }
        scheduleReopen("oboe disconnect")
    }

    private fun scheduleReopen(reason: String) {
        if (!configured) {
            reopenInFlight = false
            return
        }
        val now = System.currentTimeMillis()
        if (!reopenInFlight || reopenRequestedAtMs == 0L) {
            reopenInFlight = true
            reopenRequestedAtMs = now
            Log.d(TAG, "Reopen requested ($reason): waiting for route to settle")
        }
        reopenSettleRunnable?.let { mainHandler.removeCallbacks(it) }
        reopenSettleRunnable = null
        if (now - reopenRequestedAtMs >= REOPEN_MAX_WAIT_MS) {
            Log.d(TAG, "Reopen: max settle wait elapsed, reopening on current route")
            commitReopen(reason)
            return
        }
        val r = Runnable { commitReopen(reason) }
        reopenSettleRunnable = r
        mainHandler.postDelayed(r, REOPEN_SETTLE_MS)
    }

    private fun commitReopen(reason: String) {
        reopenSettleRunnable = null
        if (!configured) {
            reopenInFlight = false
            reopenRequestedAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - reopenRequestedAtMs < REOPEN_MAX_WAIT_MS) {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val boundId = nativeOutput.deviceId()
            val boundGone = boundId <= 0 || outputs.none { it.id == boundId }
            val hasExternalSink = outputs.any { isExternalSink(it.type) }
            if (boundGone && !hasExternalSink) {
                Log.d(TAG, "Reopen ($reason): no external sink established yet, waiting")
                val r = Runnable { commitReopen(reason) }
                reopenSettleRunnable = r
                mainHandler.postDelayed(r, REOPEN_SETTLE_MS)
                return
            }
        }
        try {
            Log.d(TAG, "Reopen committing ($reason)")
            startNativeOutput()
            refreshRoutedDevice()
            onRoutingChanged?.invoke()
        } finally {
            reopenInFlight = false
            reopenRequestedAtMs = 0L
        }
    }

    private fun isExternalSink(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> true
        else -> false
    }

    private fun refreshRoutedDevice() {
        if (!configured) return
        val id = nativeOutput.deviceId()
        val info = if (id > 0) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        } else {
            null
        }
        val newType = info?.type ?: -1
        val newName = info?.productName?.toString()
        if (newType == routedDeviceType && newName == routedProductName) return
        routedDeviceType = newType
        routedProductName = newName
        Log.d(TAG, "Routed device: type=$newType ($newName) id=$id")
        onRoutingChanged?.invoke()
    }

    // ---- Codec creation ----

    private fun createCodec(
        codecName: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
    ): MediaCodec? = when (codecName.lowercase()) {
        "opus" -> createOpusDecoder(sampleRate, channels, codecHeader)
        "flac" -> createFlacDecoder(sampleRate, channels, bitDepth, codecHeader)
        "pcm" -> null
        else -> {
            Log.w(TAG, "Unsupported codec $codecName; treating stream as silent")
            null
        }
    }

    private fun createOpusDecoder(sampleRate: Int, channels: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OPUS_MAX_INPUT_SIZE)
        val csd0 = codecHeader?.let {
            try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
        } ?: createOpusHeader(channels, sampleRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        val preSkipNs = 3840L * 1_000_000_000L / sampleRate.toLong()
        format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(preSkipNs); rewind() })
        format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(80_000_000L); rewind() })
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createFlacDecoder(sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: String?): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FLAC_MAX_INPUT_SIZE)
        // The bit depth is encoded in the FLAC STREAMINFO header (csd-0), so
        // the decoder recovers it from there. Setting a non-standard "bit-depth"
        // key is ignored by the platform decoder and can cause confusion.
        codecHeader?.let {
            try { format.setByteBuffer("csd-0", ByteBuffer.wrap(Base64.decode(it, Base64.DEFAULT))) } catch (_: Exception) {}
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun createOpusHeader(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(channels.toByte())
            putShort(3840.toShort())
            putInt(sampleRate)
            putShort(0)
            put(0)
        }.array()

    private fun convertPcm24To16(data: ByteArray): ByteArray {
        val out = ByteArray(data.size / 3 * 2)
        var src = 0
        var dst = 0
        while (src + 2 < data.size) {
            out[dst++] = data[src + 1]
            out[dst++] = data[src + 2]
            src += 3
        }
        return out
    }

    // ---- Helpers ----

    private fun parseTimestampUs(data: ByteArray): Long {
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (data[i].toLong() and 0xffL)
        return ts
    }

    private fun resetSyncMetrics() {
        // Placeholder for sync metrics if needed in the future.
    }

    private fun nowUs(): Long = MonotonicClock.nowUs()

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}