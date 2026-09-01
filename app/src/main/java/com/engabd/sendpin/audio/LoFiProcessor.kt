package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * Lo-fi music mode: bitcrusher, warm saturation, low-pass, and optional
 * vinyl crackle, all behind one intensity slider.
 *
 * At 0, the processor is transparent. At 1, it is full lo-fi hip-hop:
 * downsampled, quantised, warm, and rolled off. The whole chain is one
 * [BaseAudioProcessor] so it can be a single entry in the sink's processor
 * chain alongside [LocalDsp] and [AudioAnalysisTap].
 *
 * When vinyl noise ([VinylNoiseProcessor]) is also in the chain and enabled,
 * the lo-fi processor skips its own crackle stage and lets the dedicated
 * processor handle it. This avoids double-running the noise: two crackle
 * generators on the same signal read as a broken record, not a warm one.
 * The caller sets [shareVinylCrackle] to true when both are active.
 *
 * Sits after [VinylNoiseProcessor] and before [AudioAnalysisTap] in the
 * chain, so the light show reacts to the treated audio.
 *
 * Threading follows the same contract as [LocalDsp]: [queueInput] runs on
 * the audio thread, [setConfig] is safe from any thread, and the active
 * config is read from a `@Volatile` snapshot at the top of a buffer.
 */
@OptIn(UnstableApi::class)
class LoFiProcessor : BaseAudioProcessor() {

    @Serializable
    data class Config(
        val enabled: Boolean = false,
        /** 0..1. Maps to bit depth, decimation, saturation drive, and low-pass cutoff. */
        val intensity: Float = 0.5f,
        /** True when [VinylNoiseProcessor] is also active, so crackle is shared. */
        val shareVinylCrackle: Boolean = false,
    ) {
        fun isActive(): Boolean = enabled && intensity > 0f
    }

    @Volatile
    private var pending: Config? = Config()

    private var active: Config = Config()
    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    // --- Decimation state: hold-and-sample ---
    /**
     * How many consecutive samples to hold before advancing. 1 = transparent,
     * 4 = strong downsampling aliasing. Maps from intensity.
     */
    private var decimate = 1
    private var decimateCounter = 0
    private var heldSample = FloatArray(2)

    // --- Low-pass: one-pole IIR ---
    private var lpState = FloatArray(2)
    private var lpCoeff = 1f  // 1 = passthrough; decreases with intensity

    // --- Internal crackle (only when vinyl noise is NOT sharing) ---
    private val crackleRng = java.util.Random(System.nanoTime())
    private var crackleCounter = 0
    private var crackleThreshold = 800
    private var crackleEnv = 0f

    fun setConfig(config: Config) {
        pending = config
    }

    /** The config the current coefficients were built from. For the sharing logic. */
    fun currentConfigSafe(): Config = active

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(config: Config): String = json.encodeToString(Config.serializer(), config)
        fun decode(raw: String): Config? =
            runCatching { json.decodeFromString(Config.serializer(), raw) }.getOrNull()

        /**
         * Bit depth from intensity: 16 bits (transparent) down to 6 bits (crushed).
         *
         * The mapping is linear in the lower half and steeper in the upper half,
         * so gentle settings stay clean and aggressive settings crush hard.
         */
        internal fun bitDepth(intensity: Float): Int {
            // 0.0 -> 16, 0.5 -> 12, 1.0 -> 6
            val i = intensity.coerceIn(0f, 1f)
            return when {
                i < 0.01f -> 16
                i <= 0.5f -> (16 - (i * 8f)).toInt()  // 16 down to 12
                else -> (12 - ((i - 0.5f) * 12f)).toInt()  // 12 down to 6
            }.coerceIn(6, 16)
        }

        /**
         * Quantisation levels from bit depth: 2^bits - 1, centred at zero.
         */
        internal fun quantise(x: Float, bits: Int): Float {
            if (bits >= 16) return x
            val levels = (1 shl bits) - 1
            return (Math.round(x * levels).toFloat() / levels).coerceIn(-1f, 1f)
        }

        /**
         * Decimation factor from intensity: 1 (transparent) to 4 (strong).
         */
        internal fun decimationFactor(intensity: Float): Int {
            val i = intensity.coerceIn(0f, 1f)
            return when {
                i < 0.01f -> 1
                i < 0.33f -> 2
                i < 0.66f -> 3
                else -> 4
            }
        }

        /**
         * Saturation drive from intensity: 1.0 (transparent) to 3.0 (heavy).
         *
         * The tanh soft clipper is `tanh(drive * x) / tanh(drive)`, which
         * normalises so unity input maps to unity output at any drive.
         * At drive 1 it is nearly linear; at drive 3 it is clearly saturating.
         */
        internal fun saturationDrive(intensity: Float): Float {
            val i = intensity.coerceIn(0f, 1f)
            return 1f + i * 2f  // 1.0 to 3.0
        }

        internal fun saturate(x: Float, drive: Float): Float {
            if (drive <= 1.01f) return x
            val d = tanh(drive.toDouble())
            return (tanh(drive * x.toDouble()) / d).toFloat()
        }

        /**
         * Low-pass coefficient from intensity: 1.0 (passthrough) down to
         * a cutoff near 3 kHz at full intensity.
         *
         * Uses the same one-pole formula as [VinylNoiseProcessor.lowPassCoeff],
         * but the cutoff sweeps with intensity rather than being fixed.
         */
        internal fun lowPassCoeff(intensity: Float, sampleRate: Int): Float {
            val i = intensity.coerceIn(0f, 1f)
            if (i < 0.01f) return 1f
            // Cutoff from 20 kHz (inaudible) at intensity 0 down to 3 kHz at intensity 1.
            val cutoffHz = 20_000f - (i * 17_000f)
            val wc = 2.0 * Math.PI * cutoffHz / sampleRate
            return (1.0 - Math.exp(-wc)).toFloat()
        }

        /**
         * Crackle amplitude for the internal lo-fi crackle (when vinyl noise
         * is not sharing). Quieter than the dedicated processor: lo-fi crackle
         * is a light dusting, not the main event.
         */
        internal fun loFiCrackleAmplitude(intensity: Float): Float = (intensity * 0.06f).coerceIn(0f, 0.06f)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            if (channelCount > heldSample.size) {
                heldSample = FloatArray(channelCount)
                lpState = FloatArray(channelCount)
            }
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            decimate = decimationFactor(it.intensity)
            lpCoeff = lowPassCoeff(it.intensity, sampleRate)
            crackleThreshold = VinylNoiseProcessor.crackleInterval(it.intensity, sampleRate)
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
        val bits = bitDepth(active.intensity)
        val drive = saturationDrive(active.intensity)
        val addCrackle = active.isActive() && !active.shareVinylCrackle

        while (input.hasRemaining()) {
            val x = input.float
            val y = processSample(x, channel, bits, drive, addCrackle)
            out.putFloat(y.coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        val bits = bitDepth(active.intensity)
        val drive = saturationDrive(active.intensity)
        val addCrackle = active.isActive() && !active.shareVinylCrackle

        while (input.remaining() >= 2) {
            val x = input.short / 32_768f
            val y = processSample(x, channel, bits, drive, addCrackle)
            val clamped = (y.coerceIn(-1f, 1f) * 32_767f)
            out.putShort(clamped.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    /**
     * One sample through the lo-fi chain: decimate, quantise, saturate, low-pass.
     */
    private fun processSample(
        x: Float,
        channel: Int,
        bits: Int,
        drive: Float,
        addCrackle: Boolean,
    ): Float {
        val ch = channel.coerceIn(0, heldSample.lastIndex)

        // 1. Decimation: hold the sample for `decimate` consecutive samples.
        decimateCounter++
        if (decimateCounter >= decimate) {
            decimateCounter = 0
            heldSample[ch] = x
        }
        var y = heldSample[ch]

        // 2. Bit depth reduction.
        y = quantise(y, bits)

        // 3. Warm saturation: soft clipper adds harmonics, the opposite
        //    of the bitcrusher's subtraction.
        y = saturate(y, drive)

        // 4. Low-pass: roll off the harshness the bitcrusher added.
        lpState[ch] = lpCoeff * y + (1f - lpCoeff) * lpState[ch]
        y = lpState[ch]

        // 5. Internal crackle (only when vinyl noise is not sharing).
        if (addCrackle && ch == 0) {
            crackleCounter++
            if (crackleCounter >= crackleThreshold) {
                crackleCounter = 0
                crackleThreshold = (VinylNoiseProcessor.crackleInterval(active.intensity, sampleRate) *
                    (0.5f + crackleRng.nextFloat())).toInt().coerceAtLeast(1)
                crackleEnv = loFiCrackleAmplitude(active.intensity) * (0.5f + crackleRng.nextFloat() * 0.5f)
            }
            crackleEnv *= 0.92f
            if (crackleEnv > 0f) {
                val sign = if (crackleRng.nextBoolean()) 1f else -1f
                y += sign * crackleEnv
            }
        }

        return y
    }

    override fun onFlush() {
        decimateCounter = 0
        heldSample.fill(0f)
        lpState.fill(0f)
        crackleCounter = 0
        crackleEnv = 0f
    }

    override fun onReset() {
        active = Config()
        heldSample = FloatArray(2)
        lpState = FloatArray(2)
    }
}