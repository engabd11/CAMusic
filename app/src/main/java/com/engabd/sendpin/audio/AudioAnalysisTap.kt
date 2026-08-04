package com.engabd.sendpin.audio

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat as M3AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Taps the ExoPlayer audio chain for Light Sync analysis.
 *
 * Sits in the render pipeline as a pass-through [AudioProcessor]: audio
 * flows through unchanged, but each buffer is downsampled to the analysis
 * sample rate (22050 Hz) and fed to the [AudioAnalyzer]. The analyzer runs
 * on the audio thread — it's all float arithmetic and an FFT, which is
 * fast enough at 50 Hz to stay well under a frame budget.
 *
 * When Light Sync (direct mode) is off, [isActive] returns false and
 * ExoPlayer bypasses this processor entirely — zero overhead.
 *
 * The PCM tap is stereo: ExoPlayer downmixes to mono for the analyzer
 * (the analysis is mono-only, matching syncoV2).
 */

@OptIn(UnstableApi::class)
class AudioAnalysisTap : AudioProcessor {

    @Volatile
    private var active = false

    /** The analyzer, created when first activated. */
    private var analyzer: AudioAnalyzer? = null

    /** Resample buffer: accumulates input samples at the source rate. */
    private var resampleAccumulator = 0f
    private var resampleCounter = 0

    /** Source sample rate (from ExoPlayer's audio format). */
    private var sourceRate = 48000

    /** Target analysis rate. */
    private val targetRate = 22050

    /** Analysis hop size in samples at the target rate. */
    private val hop = 441

    /** Monotonic buffer for the analyzer hop. */
    private val hopBuf = FloatArray(hop)

    /** Callback for each completed analysis frame. */
    @Volatile
    var onFrame: ((AnalysisFrame) -> Unit)? = null

    private var inputAudioFormat: M3AudioFormat? = null
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var flushed = false

    /**
     * Activate or deactivate the tap. When deactivated, ExoPlayer bypasses
     * this processor (isActive → false) and no analysis runs.
     */
    fun setActive(on: Boolean) {
        active = on
        if (on && analyzer == null) {
            analyzer = AudioAnalyzer()
        }
        if (!on) {
            analyzer?.reset()
            resampleAccumulator = 0f
            resampleCounter = 0
        }
    }

    override fun isActive(): Boolean = active

    override fun configure(inputFormat: M3AudioFormat): M3AudioFormat {
        inputAudioFormat = inputFormat
        sourceRate = inputFormat.sampleRate
        return inputFormat  // pass-through: same format out
    }

    override fun queueEndOfStream(): ByteBuffer {
        // Pass-through: no buffering, signal end immediately with an empty buffer.
        return ByteBuffer.allocateDirect(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Always pass the input through to the output unchanged.
        val remaining = inputBuffer.remaining()
        if (remaining > 0) {
            // Create output buffer matching input
            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
            }
            outputBuffer.clear()
            outputBuffer.put(inputBuffer.duplicate())
            outputBuffer.flip()
            inputBuffer.position(inputBuffer.limit())

            // Feed the analyzer if active
            val an = analyzer
            if (active && an != null) {
                outputBuffer.rewind()
                analyzeBuffer(outputBuffer)
            }
        }
    }

    private fun analyzeBuffer(buf: ByteBuffer) {
        val format = inputAudioFormat ?: return
        val channels = format.channelCount
        val encoding = format.encoding

        // Only handle 16-bit PCM and float (the two ExoPlayer uses)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> analyze16Bit(buf, channels)
            C.ENCODING_PCM_FLOAT -> analyzeFloat(buf, channels)
            else -> {}  // unsupported encoding, skip
        }
    }

    private fun analyze16Bit(buf: ByteBuffer, channels: Int) {
        val an = analyzer ?: return
        val ratio = sourceRate.toFloat() / targetRate.toFloat()

        while (buf.remaining() >= channels * 2) {
            // Read one frame, downmix to mono
            var sum = 0f
            for (c in 0 until channels) {
                val sample = buf.short.toInt() // big-endian by default in media3
                sum += sample / 32768f
            }
            val mono = sum / channels

            // Linear resample to target rate
            resampleAccumulator += mono
            resampleCounter++
            if (resampleCounter >= ratio) {
                val sample = resampleAccumulator / resampleCounter
                resampleAccumulator = 0f
                resampleCounter = 0

                // Push into hop buffer
                val hopPos = (analyzerHopCounter++) % hop
                hopBuf[hopPos] = sample

                if (hopPos == hop - 1) {
                    // Complete hop → analyze
                    val frame = an.push(hopBuf.copyOf())
                    onFrame?.invoke(frame)
                }
            }
        }
    }

    private fun analyzeFloat(buf: ByteBuffer, channels: Int) {
        val an = analyzer ?: return
        val ratio = sourceRate.toFloat() / targetRate.toFloat()

        while (buf.remaining() >= channels * 4) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += buf.float
            }
            val mono = sum / channels

            resampleAccumulator += mono
            resampleCounter++
            if (resampleCounter >= ratio) {
                val sample = resampleAccumulator / resampleCounter
                resampleAccumulator = 0f
                resampleCounter = 0

                val hopPos = (analyzerHopCounter++) % hop
                hopBuf[hopPos] = sample

                if (hopPos == hop - 1) {
                    val frame = an.push(hopBuf.copyOf())
                    onFrame?.invoke(frame)
                }
            }
        }
    }

    private var analyzerHopCounter = 0

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = true

    override fun flush() {
        flushed = true
        outputBuffer = EMPTY_BUFFER
        analyzer?.reset()
        resampleAccumulator = 0f
        resampleCounter = 0
        analyzerHopCounter = 0
    }

    override fun reset() {
        flush()
        analyzer = null
        active = false
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}