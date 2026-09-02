package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Wow and flutter: the slow-and-fast pitch wobble of a turntable or tape
 * transport that never quite runs at a perfectly constant speed.
 *
 * "Wow" is the slow component (motor/platter eccentricity, a warped record),
 * "flutter" the fast one (capstan or motor jitter). Both are modelled as a
 * short, continuously modulated delay line — the same technique a
 * chorus/vibrato effect uses — rather than a genuine variable-rate resample:
 * the modulation is a bounded oscillation around a fixed nominal delay, so
 * the read position never drifts unboundedly away from the write position,
 * and a small fixed-size ring buffer per channel is all that is needed. Both
 * channels share one modulation signal — a real transport drags the whole
 * stereo signal together, not one channel independently — which is also why,
 * unlike [VinylNoiseProcessor]'s crackle, this is *not* decorrelated between
 * channels.
 *
 * Not persisted on its own: [Config] is always derived from
 * [LoFiProcessor.Config] by [LocalPlayer] (see its wiring), riding the
 * existing "Lo-fi" toggle rather than adding a new one. Same threading
 * contract as the other processors in this chain: [queueInput] runs on
 * ExoPlayer's audio thread, [setConfig] is safe from any thread.
 */
@OptIn(UnstableApi::class)
class WowFlutterProcessor : BaseAudioProcessor() {

    data class Config(
        val enabled: Boolean = false,
        /** 0..1, the same intensity [LoFiProcessor.Config] uses. */
        val intensity: Float = 0f,
    ) {
        fun isActive(): Boolean = enabled && intensity > 0f
    }

    @Volatile
    private var pending: Config? = Config()

    private var active: Config = Config()
    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * Ring buffer length. Only needs to comfortably exceed
     * `baseDelaySamples` plus the largest possible modulation depth — see
     * [recomputeDepths] — so this has generous headroom without costing much
     * memory (a few KB per channel at most).
     */
    private val bufferSize = 512
    private val baseDelaySamples = 128f

    private var buffers: Array<FloatArray> = Array(2) { FloatArray(bufferSize) }
    private var writePos = 0
    private var phaseSamples = 0L

    private var wowDepthSamples = 0f
    private var flutterDepthSamples = 0f
    private var modSamples = 0f

    fun setConfig(config: Config) {
        pending = config
    }

    companion object {
        /** Slow component: turntable/platter/tape-transport eccentricity. */
        private const val WOW_HZ = 0.6f
        /** Fast component: motor/capstan jitter. */
        private const val FLUTTER_HZ = 6.0f

        /**
         * Peak fractional rate deviation at intensity 1. Consumer tape decks
         * spec wow-and-flutter under ~0.3% WRMS combined; more than that
         * stops sounding nostalgic and starts sounding like a dying motor.
         */
        private const val MAX_WOW_RATE_DEPTH = 0.0015f
        private const val MAX_FLUTTER_RATE_DEPTH = 0.0008f

        /**
         * The delay-modulation amplitude, in samples, that produces a given
         * peak fractional rate deviation at a given LFO frequency.
         *
         * For `delay(t) = A * sin(2*pi*f*t)` (A in samples), the instantaneous
         * rate deviation is `delay'(t) / sampleRate`, which peaks at
         * `A * 2*pi*f / sampleRate`. Solving for A from a target peak rate
         * deviation keeps the *perceived pitch wobble* consistent regardless
         * of which LFO frequency it is driving, rather than tuning a delay
         * amplitude by ear per-LFO.
         */
        internal fun depthSamples(rateDepth: Float, lfoHz: Float, sampleRate: Int): Float =
            rateDepth * sampleRate / (2f * PI.toFloat() * lfoHz)

        internal fun wowDepthSamples(intensity: Float, sampleRate: Int): Float =
            depthSamples(intensity.coerceIn(0f, 1f) * MAX_WOW_RATE_DEPTH, WOW_HZ, sampleRate)

        internal fun flutterDepthSamples(intensity: Float, sampleRate: Int): Float =
            depthSamples(intensity.coerceIn(0f, 1f) * MAX_FLUTTER_RATE_DEPTH, FLUTTER_HZ, sampleRate)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            if (channelCount > buffers.size) {
                buffers = Array(channelCount) { FloatArray(bufferSize) }
            }
            recomputeDepths()
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    private fun recomputeDepths() {
        wowDepthSamples = wowDepthSamples(active.intensity, sampleRate)
        flutterDepthSamples = flutterDepthSamples(active.intensity, sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            recomputeDepths()
            pending = null
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val out = replaceOutputBuffer(remaining)
        if (!active.isActive()) {
            out.put(inputBuffer).flip()
            return
        }

        val order = inputBuffer.order()
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, out, order)
            else -> processShort(inputBuffer, out, order)
        }
        out.flip()
    }

    private fun processFloat(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.hasRemaining()) {
            if (channel == 0) advanceFrame()
            out.putFloat(processSample(channel, input.float).coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.remaining() >= 2) {
            if (channel == 0) advanceFrame()
            val x = input.short / 32_768f
            val y = processSample(channel, x).coerceIn(-1f, 1f) * 32_767f
            out.putShort(y.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    /**
     * Advances the write position and the shared LFO phase once per frame —
     * every channel of one sample instant reads the same modulation, which
     * is what keeps the wobble in phase between left and right.
     */
    private fun advanceFrame() {
        writePos = (writePos + 1) % bufferSize
        phaseSamples++
        val t = phaseSamples.toDouble() / sampleRate
        modSamples = (
            wowDepthSamples * sin(2.0 * PI * WOW_HZ * t) +
                flutterDepthSamples * sin(2.0 * PI * FLUTTER_HZ * t)
            ).toFloat()
    }

    private fun processSample(channel: Int, x: Float): Float {
        val buf = buffers[channel.coerceIn(0, buffers.lastIndex)]
        buf[writePos] = x

        var readPos = writePos - baseDelaySamples - modSamples
        readPos %= bufferSize.toFloat()
        if (readPos < 0f) readPos += bufferSize
        val i0 = readPos.toInt().coerceIn(0, bufferSize - 1)
        val frac = readPos - i0
        val i1 = (i0 + 1) % bufferSize
        return buf[i0] * (1f - frac) + buf[i1] * frac
    }

    override fun onFlush() {
        for (buf in buffers) buf.fill(0f)
        writePos = 0
        phaseSamples = 0
        modSamples = 0f
    }

    override fun onReset() {
        // `active` is deliberately left alone — see VinylNoiseProcessor.onReset()
        // for the full reasoning. `buffers` is genuinely derived, not config, so
        // it still shrinks back here; the next onConfigure() regrows it.
        buffers = Array(2) { FloatArray(bufferSize) }
    }
}
