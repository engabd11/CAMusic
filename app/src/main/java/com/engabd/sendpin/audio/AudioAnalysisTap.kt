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
 * through unchanged, while a mono copy is downsampled to the analysis rate
 * (22050 Hz) and handed to [AudioAnalyzer].
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
class AudioAnalysisTap : BaseAudioProcessor() {

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
     * Fractional resampler phase. Advances by one per input frame and emits
     * whenever it passes [ratio], carrying the remainder forward.
     *
     * An integer counter compared against a float ratio does not work: at 48 kHz
     * the ratio is 48000/22050 = 2.177, so `counter >= ratio` first passes at 3
     * and the analyzer is fed 16 kHz while believing it has 22050 — every band
     * edge off by 1.378x and every envelope constant running slow. Only 44.1 kHz
     * (ratio exactly 2.0) came out right.
     */
    private var resamplePhase = 0f

    /** Box-filter accumulator over the input frames feeding one output sample. */
    private var accSum = 0f
    private var accCount = 0

    /** Last emitted output sample, held when upsampling (see [feedRing]). */
    private var lastEmitted = 0f

    // ── The ring ──────────────────────────────────────────────────────────

    private val ring = MonoRing(RING_CAPACITY)

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
        if (active) feedRing(inputBuffer, inputBuffer.position(), remaining)

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
        resamplePhase = 0f
        accSum = 0f
        accCount = 0
        lastEmitted = 0f
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
     * a preallocated ring.
     */
    private fun feedRing(buf: ByteBuffer, start: Int, byteCount: Int) {
        val ch = channelCount
        if (ch <= 0) return
        val ratio = sourceRate.toFloat() / TARGET_RATE
        if (ratio <= 0f) return

        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * ch
        if (frameBytes <= 0) return
        val frames = byteCount / frameBytes

        var offset = start
        for (f in 0 until frames) {
            var sum = 0f
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (c in 0 until ch) sum += buf.getFloat(offset + c * 4)
            } else {
                for (c in 0 until ch) sum += buf.getShort(offset + c * 2) / 32768f
            }
            offset += frameBytes

            accSum += sum / ch
            accCount++
            resamplePhase += 1f
            while (resamplePhase >= ratio) {
                resamplePhase -= ratio
                if (accCount > 0) {
                    lastEmitted = accSum / accCount
                    accSum = 0f
                    accCount = 0
                }
                // When ratio < 1 the source is slower than the analysis rate and
                // one input frame owes more than one output sample; repeating the
                // value is a zero-order hold, which beats emitting a gap.
                ring.write(lastEmitted)
            }
        }
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
        val hopBuf = FloatArray(ANALYSIS_HOP)
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
            if (!ring.read(hopBuf)) {
                // Hops arrive every ~20 ms; parking briefly costs far less than
                // spinning and is well inside the tap's lead over the speaker.
                LockSupport.parkNanos(IDLE_PARK_NANOS)
                continue
            }
            // push() copies out of hopBuf immediately, so the buffer is reused.
            val frame = analyzer.push(hopBuf)
            onFrame?.invoke(frame)
        }
    }

    /**
     * Lock-free single-producer/single-consumer float ring.
     *
     * The producer is the playback thread; the consumer is the analysis thread.
     * On overrun the producer drops the incoming sample rather than blocking —
     * a stalled analysis thread must never be able to stall audio. Overrun means
     * the consumer has fallen more than [RING_CAPACITY] samples behind, which at
     * 22050 Hz is over a second and should not happen.
     */
    private class MonoRing(capacity: Int) {
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

        /** Producer side. */
        fun write(v: Float) {
            val w = writeIdx
            if (w - readIdx >= buf.size) {
                dropped++  // overrun: drop rather than block, audio comes first
                return
            }
            buf[(w and mask.toLong()).toInt()] = v
            writeIdx = w + 1  // release: publishes the slot written above
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

        /** Consumer side: fill [out] if a full hop is available. */
        fun read(out: FloatArray): Boolean {
            val r = readIdx
            if (writeIdx - r < out.size) return false  // acquire
            for (i in out.indices) out[i] = buf[((r + i) and mask.toLong()).toInt()]
            readIdx = r + out.size
            return true
        }
    }

    companion object {
        private const val TARGET_RATE = ANALYSIS_SAMPLE_RATE
        private const val ANALYSIS_THREAD_NAME = "light-sync-analysis"

        /**
         * ~3 s at the analysis rate; a power of two for cheap index masking.
         * 256 KB buys enough headroom that a briefly descheduled analysis thread
         * on a loaded phone cannot lose audio, which is worth far more than the
         * memory.
         */
        private const val RING_CAPACITY = 65_536

        /** Hop period is ~20 ms, so a 5 ms park adds negligible latency. */
        private const val IDLE_PARK_NANOS = 5_000_000L

        private const val THREAD_JOIN_MS = 250L
    }
}
