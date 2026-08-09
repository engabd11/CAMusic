package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
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

    @Volatile
    private var analysisRunning = false

    /**
     * Turn analysis on or off. The processor stays in ExoPlayer's chain either
     * way (see [isActive]); this only decides whether audio is analysed.
     */
    @Synchronized
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) startAnalysis() else stopAnalysis()
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
     * Drains the ring one hop at a time. The analyzer is created and reset here
     * and nowhere else, so it is never touched by two threads.
     */
    private fun analysisLoop() {
        val analyzer = AudioAnalyzer()
        val hopL = FloatArray(ANALYSIS_HOP)
        val hopR = FloatArray(ANALYSIS_HOP)
        var seenClear = ring.clearSeq
        onAnalysisReset?.invoke()
        while (analysisRunning && !Thread.currentThread().isInterrupted) {
            val clear = ring.clearSeq
            if (clear != seenClear) {
                seenClear = clear
                ring.dropAll()
                analyzer.reset()
                onAnalysisReset?.invoke()
                continue
            }
            if (!ring.read(hopL, hopR)) {
                // Hops arrive every ~20 ms; parking briefly costs far less than
                // spinning and is well inside the tap's lead over the speaker.
                LockSupport.parkNanos(IDLE_PARK_NANOS)
                continue
            }
            // Read after the hop leaves the ring, so it reflects what this frame
            // is made of, and published before the callback so a consumer reading
            // it from inside onFrame sees this frame's position.
            framePositionS = readClock()
            // pushStereo copies out of both buffers immediately, so they are reused.
            val frame = analyzer.pushStereo(hopL, hopR)
            onFrame?.invoke(frame)
        }
    }

    /**
     * Lock-free single-producer/single-consumer float ring, interleaved stereo.
     *
     * The producer is the playback thread; the consumer is the analysis thread.
     * On overrun the producer drops the incoming sample rather than blocking —
     * a stalled analysis thread must never be able to stall audio. Overrun means
     * the consumer has fallen more than [RING_CAPACITY] samples behind, which at
     * 22050 Hz is over a second and should not happen.
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
         * Should stay at zero — ExoPlayer paces the sink to roughly real time,
         * so reaching [RING_CAPACITY] means the analysis thread was starved for
         * over a second.
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
         * ~3 s of *stereo* at the analysis rate — two floats per frame — and a
         * power of two for cheap index masking. Half a megabyte buys enough
         * headroom that a briefly descheduled analysis thread on a loaded phone
         * cannot lose audio, which is worth far more than the memory.
         */
        private const val RING_CAPACITY = 131_072

        /** Hop period is ~20 ms, so a 5 ms park adds negligible latency. */
        private const val IDLE_PARK_NANOS = 5_000_000L

        private const val THREAD_JOIN_MS = 250L

        /** Seqlock retries before the position is reported unknown. */
        private const val CLOCK_READ_ATTEMPTS = 4
    }
}
