package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * Taps the ExoPlayer audio chain for Light Sync analysis.
 *
 * Sits in the render pipeline as a pass-through [AudioProcessor]: audio flows
 * through unchanged, while a stereo copy is downsampled to the analysis rate
 * (22050 Hz) and handed to [AudioAnalyzer]. Both channels are carried rather
 * than a downmix, because where a hit sits in the stereo field is what decides
 * which side of the room reacts to it.
 *
 * **Nothing but the copy and the downsample happens on the playback thread.**
 * That thread is what keeps the AudioTrack fed; stall it and the track
 * underruns, which is heard as glitching and distortion rather than as a
 * dropped frame. The analysis — an FFT, a filterbank and three median/MAD onset
 * thresholds, together some tens of KB of garbage per hop — used to run right
 * here and was the cause. It now runs on [ANALYSIS_THREAD_NAME], fed through a
 * preallocated single-producer/single-consumer ring. What is left on the
 * playback thread allocates nothing at all.
 *
 * [isActive] stays true for any PCM format this can read, rather than tracking
 * whether Light Sync is on. ExoPlayer builds the sink's processing pipeline once
 * per configuration and asks [isActive] only then — a processor that reported
 * false when the track started is left out of the chain, and no later toggle can
 * put it back. Reporting true keeps the tap in the chain so Light Sync can be
 * switched on mid-song; [setActive] then gates the analysis itself, leaving a
 * buffer copy as the cost of being off.
 *
 * Threading: [queueInput], [onConfigure] and [onFlush] all run on ExoPlayer's
 * playback thread and are the ring's sole producer. The analysis thread is its
 * sole consumer and the only thread that ever touches the [AudioAnalyzer].
 */
@OptIn(UnstableApi::class)
class AudioAnalysisTap(
    /**
     * The sink probe's view of where in the track the audio being queued is.
     * Optional only so a test can build a tap without a sink; in the app it is
     * always the same [AudioLead] the probe writes, and without it
     * [analysisPositionS] has nothing to report.
     */
    private val lead: AudioLead? = null,
) : BaseAudioProcessor() {

    /** Whether analysis is running. The pass-through happens either way. */
    @Volatile
    private var active = false

    /**
     * Callback for each completed analysis frame. Invoked on the analysis
     * thread, not the playback thread.
     */
    @Volatile
    var onFrame: ((AnalysisFrame) -> Unit)? = null

    private val _frames = MutableStateFlow<AnalysisFrame?>(null)

    /**
     * Fan-out for consumers that don't need to claim the single [onFrame] slot — a
     * Compose visualizer, for instance, alongside whatever (if anything) has claimed
     * [onFrame] for Light Sync. Updated on the analysis thread; safe to collect from
     * anywhere, `StateFlow` handles the cross-thread publish.
     *
     * The frame published here is **not** the one handed to [onFrame]: its melbank
     * is a copy. [AnalysisFrame.melbank] is the analyzer's own live envelope array,
     * rewritten in place every hop, which is safe only for a consumer that reads it
     * synchronously on the analysis thread the way [onFrame] does. A `StateFlow`
     * value is by definition read later and elsewhere, so it has to own its data.
     */
    val frames: StateFlow<AnalysisFrame?> = _frames.asStateFlow()

    /**
     * Track position of the frame just delivered to [onFrame], in seconds, or
     * `NaN` when it cannot be known (no sink probe, or the sink has been flushed
     * and no buffer has arrived since).
     *
     * Read it from inside [onFrame] and nowhere else: it describes *that* frame,
     * and the next hop moves it on.
     *
     * Exact rather than estimated. The sink probe publishes the media time of
     * each buffer as it enters the processor chain; the ring knows how far the
     * analysis thread is behind the producer in samples; the difference is where
     * the frame being analysed sits in the song, to within a sample. Nothing
     * here is inferred from the player's polled position, which is both coarser
     * and sampled on the wrong thread.
     */
    val analysisPositionS: Float get() = framePositionS

    @Volatile
    private var framePositionS = Float.NaN

    /**
     * How far the frame just delivered to [onFrame] sits *behind* the newest
     * sample the producer has handed over, in seconds.
     *
     * Read it from inside [onFrame] and nowhere else, for the same reason as
     * [analysisPositionS]: it describes *that* frame.
     *
     * ## What it is for
     *
     * [AudioLead] measures how long it is until the **newest** sample handed to
     * this tap reaches the ear. The frame being analysed is not that sample — it
     * is this much older, so its audio is heard this much *sooner*. The hold
     * [com.engabd.sendpin.hue.FrameDelayQueue] applies is therefore
     * `lead - analysisLag - pipeline`, and leaving the middle term out is only
     * harmless while the lag is small and steady.
     *
     * On the Music Assistant path it is neither.
     * [com.engabd.sendpin.audio.SendspinNativeEngine]'s producer fills its native
     * ring to `RING_TARGET_MS` as fast as the decoder will go on every
     * `stream/start` — which Music Assistant sends between tracks, on every pause
     * and for every announcement — so seconds of audio land here in a few tens of
     * milliseconds and the lag steps from ~0 to the whole ring depth. The lead
     * steps with it, so the two cancel; without this term the room was held out of
     * step by the entire burst until the pacing servo had ground it back down.
     * ExoPlayer's sink is throttled to real time and never produced a burst, which
     * is why the local player always looked right and the MA player did not.
     */
    val analysisLagS: Float get() = frameLagS

    @Volatile
    private var frameLagS = 0f

    /**
     * Invoked on the analysis thread whenever analysis state is discarded — a
     * seek, a track change, or the start of a session.
     *
     * Consumers that carry their own history across frames (the tempo grid, the
     * structure arc) have to drop it at the same moment the analyzer does, or
     * they will keep predicting beats for a track that is no longer playing.
     * Delivered on the analysis thread and always before the first frame of the
     * new stretch of audio.
     */
    @Volatile
    var onAnalysisReset: (() -> Unit)? = null

    // ── Producer-side state (playback thread only) ────────────────────────

    /** Source sample rate, from ExoPlayer's audio format. */
    private var sourceRate = 48_000

    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * Fractional resampler phase, shared with the offline scanner so a scanned
     * frame and a live frame describe the same samples. See [BoxResampleClock].
     *
     * Rebuilt on a format change, since the ratio is baked into it.
     */
    private var resampler = BoxResampleClock(1f)
    private var resamplerRate = 0

    /** Box-filter accumulators over the input frames feeding one output sample. */
    private var accSumL = 0f
    private var accSumR = 0f
    private var accCount = 0

    /** Last emitted output sample per channel, held when upsampling. */
    private var lastL = 0f
    private var lastR = 0f

    // ── The ring ──────────────────────────────────────────────────────────

    private val ring = StereoRing(RING_CAPACITY)

    /**
     * Analysis samples lost to ring overrun since construction. Expected to stay
     * at zero in normal playback; a non-zero value means the analysis thread was
     * starved long enough to fall a ring behind, which shows up as the lights
     * briefly ignoring the music.
     */
    val droppedSamples: Long get() = ring.dropped

    // ── Consumer-side state (analysis thread only) ────────────────────────

    private var analysisThread: Thread? = null

    /** Which consumers currently want frames. See [setActive] and [setUiActive]. */
    private var syncWants = false
    private var uiWants = false

    @Volatile
    private var analysisRunning = false

    /**
     * Turn analysis on or off for Light Sync. The processor stays in ExoPlayer's
     * chain either way (see [isActive]); this only decides whether audio is
     * analysed.
     */
    @Synchronized
    fun setActive(on: Boolean) {
        if (on == syncWants) return
        syncWants = on
        applyActive()
    }

    /**
     * Turn analysis on or off for an on-screen consumer, currently the visualizer.
     *
     * Kept separate from [setActive] rather than sharing one flag: the two
     * consumers come and go independently, and with a single flag whichever
     * stopped first would take the frames away from the other — closing the
     * visualizer would have stopped Light Sync's analysis mid-track.
     */
    @Synchronized
    fun setUiActive(on: Boolean) {
        if (on == uiWants) return
        uiWants = on
        applyActive()
    }

    private fun applyActive() {
        val want = syncWants || uiWants
        if (want == active) return
        active = want
        if (want) startAnalysis() else stopAnalysis()
    }

    // ── AudioProcessor ────────────────────────────────────────────────────

    /**
     * Returns the input format unchanged for any PCM encoding [feedRing] can
     * read, which is what makes [isActive] true and keeps the tap in the chain
     * regardless of whether Light Sync is currently on.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                sourceRate = inputAudioFormat.sampleRate
                channelCount = inputAudioFormat.channelCount
                encoding = inputAudioFormat.encoding
                inputAudioFormat  // pass-through: same format out
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Read for analysis with absolute gets so the buffer's position is
        // untouched, then hand the whole thing to the output. No duplicate() or
        // asReadOnlyBuffer() — those allocate a wrapper per call.
        if (active) {
            // Sampled before the ring is fed, because the probe wrote it for
            // exactly this buffer on the way in.
            val bufStartUs = lead?.mediaTimeUs ?: AudioLead.UNKNOWN
            val frames = feedRing(inputBuffer, inputBuffer.position(), remaining)
            publishClock(bufStartUs, frames)
        }

        // replaceOutputBuffer keeps a persistent scratch buffer and only grows
        // it when a larger one is needed. The previous hand-rolled version
        // compared against `outputBuffer`, which getOutput() had already reset to
        // the zero-capacity EMPTY_BUFFER — so it allocated a fresh direct
        // ByteBuffer on every single call, for all local playback, Light Sync on
        // or off.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    /**
     * Analyse [buf] without the pass-through copy, for a producer that is not
     * ExoPlayer.
     *
     * The MediaProjection capture path has PCM and no render chain to put it in —
     * there is no downstream consumer, so [queueInput]'s `replaceOutputBuffer` would
     * copy every buffer for nobody to read. Everything else is the same code.
     *
     * **Single producer, singular.** The ring has one writer by contract, so a tap fed
     * this way must never also be installed in a player. The capture path owns its own
     * [AudioAnalysisTap] instance for exactly this reason.
     */
    fun analyseExternal(
        buf: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
        mediaTimeUs: Long,
    ) {
        val remaining = buf.remaining()
        if (remaining == 0 || !active) return
        sourceRate = sampleRate
        channelCount = channels
        encoding = pcmEncoding
        val frames = feedRing(buf, buf.position(), remaining)
        publishClock(mediaTimeUs, frames)
    }

    /**
     * The public form of [onFlush], for a producer that is not ExoPlayer.
     *
     * When Music Assistant played through ExoPlayer, this tap was an `AudioProcessor`
     * and the player called [onFlush] on every seek, track change and reconfigure — so
     * the analyzer, the tempo/structure/gesture trackers and the whole layer chain were
     * reset between tracks. [analyseExternal] has no such caller, so once the MA path
     * moved to [SendspinNativeEngine] nothing reset any of it: every MA track change
     * carried the previous track's tempo estimate, structure and AGC baselines forward,
     * and `DirectLightSync.onAnalysisReset` became dead code on that path.
     *
     * Called by the native engine wherever ExoPlayer would have flushed.
     */
    fun resetAnalysis() = onFlush()

    override fun onFlush() {
        // Discard buffered audio so a seek doesn't feed the analyzer up to a
        // second of stale samples, and tell the analysis thread to start clean.
        ring.clear()
        resampler.reset()
        accSumL = 0f
        accSumR = 0f
        accCount = 0
        lastL = 0f
        lastR = 0f
        // The position of whatever is buffered is about to stop being true.
        publishClock(AudioLead.UNKNOWN, 0)
    }

    override fun onReset() {
        // `active` is owned by DirectLightSync, not by the sink's lifecycle. A
        // reset here means ExoPlayer is reconfiguring, not that the user turned
        // Light Sync off — clearing it would strand a running session with a tap
        // that silently stopped analysing.
        ring.clear()
    }

    // ── Downmix + resample (playback thread) ──────────────────────────────

    /**
     * Downmix to mono, resample to [TARGET_RATE] and write into the ring.
     * Allocation-free by construction: absolute reads, scalar accumulators, and
     * a preallocated ring. Returns the number of *input* frames consumed, which
     * is what the media clock needs to know how long this buffer was.
     */
    private fun feedRing(buf: ByteBuffer, start: Int, byteCount: Int): Int {
        val ch = channelCount
        if (ch <= 0) return 0
        if (sourceRate <= 0) return 0
        if (sourceRate != resamplerRate) {
            resamplerRate = sourceRate
            resampler = BoxResampleClock(sourceRate.toFloat() / TARGET_RATE)
        }

        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * ch
        if (frameBytes <= 0) return 0
        val frames = byteCount / frameBytes

        var offset = start
        for (f in 0 until frames) {
            // Left and right kept apart. A mono or multichannel source folds to
            // the same value on both sides, so downstream never has to care.
            var l: Float
            var r: Float
            if (encoding == C.ENCODING_PCM_FLOAT) {
                l = buf.getFloat(offset)
                r = if (ch > 1) buf.getFloat(offset + 4) else l
            } else {
                l = buf.getShort(offset) / 32768f
                r = if (ch > 1) buf.getShort(offset + 2) / 32768f else l
            }
            offset += frameBytes

            accSumL += l
            accSumR += r
            accCount++
            var due = resampler.advance()
            while (due-- > 0) {
                if (accCount > 0) {
                    lastL = accSumL / accCount
                    lastR = accSumR / accCount
                    accSumL = 0f
                    accSumR = 0f
                    accCount = 0
                }
                // When ratio < 1 the source is slower than the analysis rate and
                // one input frame owes more than one output sample; repeating the
                // value is a zero-order hold, which beats emitting a gap.
                ring.write(lastL, lastR)
            }
        }
        return frames
    }

    // ── The media clock ───────────────────────────────────────────────────
    //
    // A seqlock over one (media time, ring position) pair. Two values that have
    // to be read together, written by the playback thread about a hundred times
    // a second and read by the analysis thread fifty: a torn read would pair a
    // new timestamp with a stale position and put the frame ~10 ms out. A lock
    // is out of the question on the playback thread; an odd/even sequence number
    // costs two volatile writes and cannot block anybody.

    @Volatile private var clockSeq = 0
    @Volatile private var clockNewestUs = AudioLead.UNKNOWN
    @Volatile private var clockWriteSamples = 0L

    /** Producer side: the media time of the newest sample now in the ring. */
    private fun publishClock(bufStartUs: Long, inputFrames: Int) {
        val newestUs = if (bufStartUs == AudioLead.UNKNOWN || sourceRate <= 0) {
            AudioLead.UNKNOWN
        } else {
            bufStartUs + inputFrames * 1_000_000L / sourceRate
        }
        clockSeq++                       // odd: a write is in progress
        clockNewestUs = newestUs
        clockWriteSamples = ring.writeSamples
        clockSeq++                       // even: consistent again
    }

    /**
     * Consumer side: where in the track the samples just read from the ring sit.
     *
     * The producer's newest sample, less however far behind it the consumer is.
     * Gives up after a few attempts rather than spinning — a position that is
     * briefly unknown costs one frame of scheduled grid, and the causal tracker
     * is right there underneath it.
     */
    private fun readClock(): Float {
        repeat(CLOCK_READ_ATTEMPTS) {
            val seq = clockSeq
            if (seq and 1 == 0) {
                val newestUs = clockNewestUs
                val writeSamples = clockWriteSamples
                if (clockSeq == seq) {
                    if (newestUs == AudioLead.UNKNOWN) return Float.NaN
                    val behind = writeSamples - ring.readSamples
                    return (newestUs / 1e6 - behind.toDouble() / TARGET_RATE).toFloat()
                }
            }
        }
        return Float.NaN
    }

    // ── Analysis thread ───────────────────────────────────────────────────

    private fun startAnalysis() {
        if (analysisThread != null) return
        // Safe to reset the indices outright: the previous consumer has been
        // joined and the next has not started, so there is nobody to race. Going
        // through clear()/dropAll() would not do here — the incoming thread reads
        // clearSeq as its baseline and would see no change to react to, and then
        // analyse whatever the last session left unread.
        ring.resetWhileIdle()
        analysisRunning = true
        analysisThread = Thread(::analysisLoop, ANALYSIS_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun stopAnalysis() {
        analysisRunning = false
        analysisThread?.let { t ->
            t.interrupt()
            t.join(THREAD_JOIN_MS)
        }
        analysisThread = null
    }

    /**
     * Drains the ring one hop at a time, **at the rate the music plays**. The
     * analyzer is created and reset here and nowhere else, so it is never touched
     * by two threads.
     *
     * ## Why the pacing is here and not left to the producer
     *
     * This loop used to drain flat out: read a hop whenever one was available,
     * park for 5 ms when one was not. That is only as even as whoever is writing,
     * and the two writers are not alike.
     *
     * [queueInput] is called by ExoPlayer's sink, which the `AudioTrack` throttles
     * to roughly real time, so the local player's hops came out evenly spaced and
     * everything downstream was built on that. [analyseExternal] has no such
     * throttle: [com.engabd.sendpin.audio.SendspinNativeEngine]'s producer thread
     * fills its native ring to `RING_TARGET_MS` as fast as the decoder will go and
     * only *then* starts pacing, and it re-fills from empty on every `stream/start`
     * — which Music Assistant sends between every track, on every pause and for
     * every announcement. Each of those dumps a couple of seconds of audio into
     * this ring in a few tens of milliseconds.
     *
     * The consequences were both of the things "Light Sync is erratic on the MA
     * player" describes, and neither happened on Navidrome:
     *
     *  - **The lamps.** [com.engabd.sendpin.hue.DirectLightSync]'s render loop
     *    samples `latestFrame` 60 times a second against wall-clock `dt`. A burst
     *    it cannot see 120 hops of; it renders two or three of them and the rest
     *    are overwritten before the loop next looks. The show then advances the
     *    music by a hundred milliseconds a step while its own envelopes integrate
     *    16 ms — the definition of jumpy.
     *  - **The status line.** Between bursts nothing arrives, so `framesFresh`
     *    goes false, the room drops to the idle pattern and the screen says
     *    "Waiting for music on this phone" — then the next burst lands and it says
     *    "Reacting to the beat" again.
     *
     * So the consumer keeps its own clock. One hop per [ANALYSIS_HOP] of audio,
     * measured against [System.nanoTime], for every producer alike.
     *
     * ## Why the backlog a burst leaves behind is kept, not drained
     *
     * The rate used to be servoed down to a fixed two-hop backlog, on the reasoning
     * that a consumer sitting behind the producer is a light show running late. That
     * is only true if the hold downstream ignores the gap — and it no longer does:
     * [analysisLagS] reports it per frame and
     * [com.engabd.sendpin.hue.FrameDelayQueue] subtracts it. Once the hold is
     * `lead - lag - pipeline`, *any* backlog is correctly timed, and draining one is
     * not a correction but a distortion:
     *
     *  - **The show raced.** Consuming at four times real time hands
     *    [com.engabd.sendpin.hue.DirectLightSync]'s 60 Hz render loop four hops of
     *    music per tick while its envelopes integrate one tick of wall clock, so
     *    every decay ran four times too fast for the best part of a second after
     *    each `stream/start`.
     *  - **The room went dark early.** Draining to two hops leaves the tap 40 ms
     *    from the end of what it has been given, while the *speaker* is still two
     *    and a half seconds from the end of it. Frames stopped, `framesFresh` went
     *    false, and the idle show painted over the last two seconds of every Music
     *    Assistant track. Holding the backlog keeps frames coming for exactly as
     *    long as there is audio left to hear.
     *
     * So the servo now only guards the ring. It runs at real time for any backlog a
     * real producer can produce — [HOLD_BACKLOG_HOPS] is sized above a whole
     * `SendspinNativeEngine` start burst — and catches up only past that, where the
     * alternative is the ring overrunning and a hole in the beat grid. The local
     * player never reaches it at all, so its behaviour is unchanged.
     *
     * Nothing is ever skipped. The analyzer's `tAudio` is its own sample counter,
     * so dropping hops to catch up would slide its clock — and with it the tempo
     * tracker's beat phase — away from the track. Faster is safe; missing is not.
     */
    private fun analysisLoop() {
        val analyzer = AudioAnalyzer()
        val hopL = FloatArray(ANALYSIS_HOP)
        val hopR = FloatArray(ANALYSIS_HOP)
        var seenClear = ring.clearSeq
        var dueAt = System.nanoTime()
        onAnalysisReset?.invoke()
        while (analysisRunning && !Thread.currentThread().isInterrupted) {
            val clear = ring.clearSeq
            if (clear != seenClear) {
                seenClear = clear
                ring.dropAll()
                analyzer.reset()
                _frames.value = null
                framePositionS = Float.NaN
                frameLagS = 0f
                dueAt = System.nanoTime()
                onAnalysisReset?.invoke()
                continue
            }
            val backlog = ring.availableFrames
            if (backlog < ANALYSIS_HOP) {
                // Nothing to read. Park briefly rather than spin, and hold the
                // schedule at *now* so a resume after a pause starts clean instead
                // of owing the loop however many hops it was idle for.
                dueAt = System.nanoTime()
                LockSupport.parkNanos(IDLE_PARK_NANOS)
                continue
            }
            val now = System.nanoTime()
            if (now < dueAt) {
                LockSupport.parkNanos(minOf(dueAt - now, IDLE_PARK_NANOS))
                continue
            }
            // The next hop is due one hop of audio later, divided by however fast
            // the backlog says to run. Scheduled from `dueAt`, not from `now`, so
            // the rate is the hop rate and not the hop rate plus this loop's
            // own overhead — except after a stall, where catching up on deadlines
            // nobody is waiting for is what a burst looks like all over again.
            val period = (HOP_PERIOD_NANOS / catchupRate(backlog)).toLong()
            dueAt = if (now - dueAt > period) now + period else dueAt + period
            if (!ring.read(hopL, hopR)) continue
            // Read after the hop leaves the ring, so both reflect what this frame
            // is made of, and published before the callback so a consumer reading
            // them from inside onFrame sees this frame's position and lag. What is
            // left in the ring after the read *is* the lag: every sample still
            // waiting was handed over after the ones in this hop.
            framePositionS = readClock()
            frameLagS = ring.availableFrames.toFloat() / TARGET_RATE
            // pushStereo copies out of both buffers immediately, so they are reused.
            val frame = analyzer.pushStereo(hopL, hopR)
            // Detached copy for the flow, live array for the callback — see [frames].
            _frames.value = frame.copy(melbank = frame.melbank.copyOf())
            onFrame?.invoke(frame)
        }
    }

    /**
     * How many times real time to consume at, for a backlog of [backlogFrames].
     *
     * 1.0 at or below [HOLD_BACKLOG_HOPS], rising linearly to [MAX_CATCHUP] a
     * further [CATCHUP_SPAN_HOPS] beyond it. Linear rather than a threshold: a
     * step would have the show lurch every time a producer's chunk size happened
     * to straddle the trip point.
     */
    private fun catchupRate(backlogFrames: Int): Float {
        val target = HOLD_BACKLOG_HOPS * ANALYSIS_HOP
        val over = (backlogFrames - target).toFloat()
        if (over <= 0f) return 1f
        val span = (CATCHUP_SPAN_HOPS * ANALYSIS_HOP).toFloat()
        return 1f + (MAX_CATCHUP - 1f) * (over / span).coerceAtMost(1f)
    }

    /**
     * Lock-free single-producer/single-consumer float ring, interleaved stereo.
     *
     * The producer is the playback thread; the consumer is the analysis thread.
     * On overrun the producer drops the incoming sample rather than blocking —
     * a stalled analysis thread must never be able to stall audio. Overrun means
     * the consumer has fallen more than [RING_CAPACITY] samples behind, which at
     * 22050 Hz is about six seconds and should not happen — see that constant for
     * why the largest legitimate backlog is a good deal smaller.
     */
    private class StereoRing(capacity: Int) {
        init {
            require(capacity > 0 && capacity and (capacity - 1) == 0) {
                "ring capacity must be a power of two, got $capacity"
            }
        }

        private val buf = FloatArray(capacity)
        private val mask = capacity - 1

        @Volatile
        private var writeIdx = 0L

        @Volatile
        private var readIdx = 0L

        /** Bumped by the producer on flush; the consumer resets when it changes. */
        @Volatile
        var clearSeq = 0
            private set

        /**
         * Samples discarded to overrun. Producer-owned; read for diagnostics.
         * Should stay at zero: the consumer paces itself but catches up at up to
         * [MAX_CATCHUP] times real time, so reaching [RING_CAPACITY] means either
         * the analysis thread was starved for seconds or a producer wrote more
         * than a start burst's worth in one go.
         */
        @Volatile
        var dropped = 0L
            private set

        /**
         * Producer side. One stereo frame per call, interleaved.
         *
         * Stereo rather than the pre-downmixed mono this used to carry: the
         * analyzer needs both channels to work out where in the room a hit sat,
         * and downmixing in the tap threw that away before anything could see
         * it. A mono source writes the same value twice, which costs a slot and
         * keeps the reader uniform.
         */
        fun write(l: Float, r: Float) {
            val w = writeIdx
            if (w - readIdx >= buf.size - 1) {
                dropped++  // overrun: drop rather than block, audio comes first
                return
            }
            buf[(w and mask.toLong()).toInt()] = l
            buf[((w + 1) and mask.toLong()).toInt()] = r
            writeIdx = w + 2  // release: publishes both slots written above
        }

        /** Producer side. */
        fun clear() {
            clearSeq++
        }

        /**
         * Full reset, valid **only** with no consumer thread running — it writes
         * both indices, which are otherwise single-owner. Used between analysis
         * sessions, where the old thread has been joined and the new one has not
         * yet started.
         */
        fun resetWhileIdle() {
            readIdx = 0
            writeIdx = 0
            dropped = 0
        }

        /** Consumer side: skip everything currently buffered. */
        fun dropAll() {
            readIdx = writeIdx
        }

        /**
         * Consumer side: whole stereo frames waiting to be read.
         *
         * How far ahead of the analyzer the producer is, which is what
         * [analysisLoop]'s rate servo steers on. Capped at [Int.MAX_VALUE] only
         * so the caller can do integer arithmetic on it; a ring this size can
         * never approach that.
         */
        val availableFrames: Int
            get() = ((writeIdx - readIdx) / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /**
         * Analysis samples written / read since the last full reset. Both are
         * absolute and monotonic, so their difference is how far the consumer is
         * behind — which is what turns a producer-side timestamp into the
         * consumer's own position. Interleaved stereo, hence the halving.
         */
        val writeSamples: Long get() = writeIdx / 2
        val readSamples: Long get() = readIdx / 2

        /** Consumer side: fill both channels if a full hop is available. */
        fun read(outL: FloatArray, outR: FloatArray): Boolean {
            val r = readIdx
            val need = outL.size * 2L
            if (writeIdx - r < need) return false  // acquire
            for (i in outL.indices) {
                outL[i] = buf[((r + 2 * i) and mask.toLong()).toInt()]
                outR[i] = buf[((r + 2 * i + 1) and mask.toLong()).toInt()]
            }
            readIdx = r + need
            return true
        }
    }

    companion object {
        private const val TARGET_RATE = ANALYSIS_SAMPLE_RATE
        private const val ANALYSIS_THREAD_NAME = "light-sync-analysis"

        /**
         * ~6 s of *stereo* at the analysis rate — two floats per frame — and a
         * power of two for cheap index masking. A megabyte buys enough headroom
         * that a briefly descheduled analysis thread on a loaded phone cannot
         * lose audio, which is worth far more than the memory.
         *
         * Doubled from ~3 s when [analysisLoop] started pacing itself. A paced
         * consumer holds a backlog where an unpaced one held none, and the
         * largest backlog that can legitimately appear is a whole
         * `SendspinNativeEngine` start burst — `RING_TARGET_MS` of decoded audio
         * written in one go. Sizing to that plus room to spare is what keeps
         * [dropped] at zero, and a drop here is a hole in the beat grid.
         */
        private const val RING_CAPACITY = 262_144

        /** Hop period is ~20 ms, so a 5 ms park adds negligible latency. */
        private const val IDLE_PARK_NANOS = 5_000_000L

        /** One hop of audio, in nanoseconds — the paced consumer's period. */
        private const val HOP_PERIOD_NANOS =
            ANALYSIS_HOP * 1_000_000_000L / ANALYSIS_SAMPLE_RATE

        /**
         * The largest backlog the consumer will sit on at plain real time, in hops.
         *
         * Sized above a whole `SendspinNativeEngine` start burst — `RING_TARGET_MS`
         * is 2.5 s, or 125 hops — so the backlog Music Assistant's producer leaves
         * behind is held rather than chased. [analysisLagS] is what makes holding it
         * correct; see [analysisLoop] for why chasing it was not.
         *
         * 175 hops is 3.5 s. With [CATCHUP_SPAN_HOPS] on top, full-speed catch-up
         * lands at 4.5 s, comfortably inside [RING_CAPACITY]'s ~5.9 s, so the servo
         * always has room to work before the producer starts dropping.
         */
        private const val HOLD_BACKLOG_HOPS = 175

        /** How far past [HOLD_BACKLOG_HOPS] the backlog must be for full-speed catch-up. */
        private const val CATCHUP_SPAN_HOPS = 50

        /**
         * The fastest the consumer may run, as a multiple of real time.
         *
         * Only ever reached by a backlog no real producer explains, where the
         * alternative is overrunning the ring. Higher would clear it sooner and
         * start feeding the tempo tracker audio faster than its own smoothing
         * constants expect.
         */
        private const val MAX_CATCHUP = 4f

        private const val THREAD_JOIN_MS = 250L

        /** Seqlock retries before the position is reported unknown. */
        private const val CLOCK_READ_ATTEMPTS = 4
    }
}
