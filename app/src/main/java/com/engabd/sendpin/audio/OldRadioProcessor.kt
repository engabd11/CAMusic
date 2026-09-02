package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Old Radio: telephone-band EQ, light speaker-cone saturation, AM static and
 * a slow carrier warble — the sound of a distant broadcast rather than a
 * clean recording.
 *
 * The static is mixed in *before* the band-pass and saturation stages
 * (see [processSample]) rather than added on top of the finished signal:
 * a real receiver's static comes through the same narrow audio chain as the
 * demodulated programme, so shaping both together is what makes the two
 * read as one broadcast instead of noise laid over a clean recording.
 *
 * Independent third toggle alongside [VinylNoiseProcessor] and
 * [LoFiProcessor] — not a variant of either — with the same `Config`/wiring
 * shape: `@Serializable data class Config`, `@Volatile pending`/`active`
 * double-buffer, `queueInput`/`onConfigure` on the audio thread,
 * [setConfig] safe from any thread.
 */
@OptIn(UnstableApi::class)
class OldRadioProcessor : BaseAudioProcessor() {

    @Serializable
    data class Config(
        val enabled: Boolean = false,
        /** 0..1. Controls band width, saturation, static and warble depth. */
        val intensity: Float = 0.5f,
    ) {
        fun isActive(): Boolean = enabled && intensity > 0f
    }

    @Volatile
    private var pending: Config? = Config()

    private var active: Config = Config()
    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    private val rng = Random(System.nanoTime())

    private class ChannelBand {
        val highPass = Biquad()
        val lowPass = Biquad()
    }

    private var bands: Array<ChannelBand> = Array(2) { ChannelBand() }

    // --- AM static: Poisson-triggered noise bursts, shared timing across channels ---
    private var staticCounter = 0
    private var staticThreshold = 44_100
    private var staticBurstSamplesLeft = 0
    private var staticBurstAmp = 0f
    /**
     * Whether this frame is inside a static burst, decided once per frame in
     * [advanceFrame].
     *
     * The countdown itself is per-frame, not per-sample, so it cannot be run
     * from [processSample] without picking a channel to run it on — and the
     * channel that ran it would then get one burst sample the others did not,
     * which is a burst that is not quite the same event in both ears.
     */
    private var staticBurstActive = false

    // --- Carrier warble: a slow tremolo shared across channels ---
    private var phaseSamples = 0L
    private var warbleGain = 1f

    fun setConfig(config: Config) {
        pending = config
    }

    fun currentConfigSafe(): Config = active

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(config: Config): String = json.encodeToString(Config.serializer(), config)
        fun decode(raw: String): Config? =
            runCatching { json.decodeFromString(Config.serializer(), raw) }.getOrNull()

        private const val WARBLE_HZ = 4f

        /** High-pass corner: 250 Hz (barely a radio) to 500 Hz (aggressive telephone band). */
        internal fun highPassFreq(intensity: Float): Float =
            250f + intensity.coerceIn(0f, 1f) * 250f

        /** Low-pass corner: 5000 Hz down to 2500 Hz. */
        internal fun lowPassFreq(intensity: Float): Float =
            5_000f - intensity.coerceIn(0f, 1f) * 2_500f

        /** Saturation drive: 1.0 (transparent) to 2.5 (speaker-cone breakup, not heavy distortion). */
        internal fun saturationDrive(intensity: Float): Float = 1f + intensity.coerceIn(0f, 1f) * 1.5f

        internal fun saturate(x: Float, drive: Float): Float {
            if (drive <= 1.01f) return x
            val d = tanh(drive.toDouble())
            return (tanh(drive * x.toDouble()) / d).toFloat()
        }

        /** Average samples between static bursts: 3s (sparse) down to 0.6s (frequent) at max intensity. */
        internal fun staticInterval(intensity: Float, sampleRate: Int): Int {
            val seconds = 3f - intensity.coerceIn(0f, 1f) * 2.4f
            return (seconds * sampleRate).toInt().coerceAtLeast(1)
        }

        /** Burst duration: 20ms to 80ms — long enough to read as a signal dip, not a click. */
        internal fun staticBurstSamples(intensity: Float, sampleRate: Int): Int {
            val seconds = 0.02f + intensity.coerceIn(0f, 1f) * 0.06f
            return (seconds * sampleRate).toInt().coerceAtLeast(1)
        }

        /** Burst amplitude, capped at 10% of full scale — a dip, not a wipeout. */
        internal fun staticBurstAmplitude(intensity: Float): Float = (intensity * 0.10f).coerceIn(0f, 0.10f)

        /** Continuous carrier-noise floor, always present when active. Quiet: up to 2%. */
        internal fun hissFloorAmplitude(intensity: Float): Float = (intensity * 0.02f).coerceIn(0f, 0.02f)

        /** Tremolo depth from the wobbling carrier: up to 3% gain modulation. */
        internal fun warbleDepth(intensity: Float): Float = (intensity * 0.03f).coerceIn(0f, 0.03f)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            if (channelCount > bands.size) {
                bands = Array(channelCount) { ChannelBand() }
            }
            updateBandCoefficients()
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    private fun updateBandCoefficients() {
        val hp = highPassFreq(active.intensity)
        val lp = lowPassFreq(active.intensity)
        for (b in bands) {
            b.highPass.setHighPass(sampleRate, hp, q = 0.707f)
            b.lowPass.setLowPass(sampleRate, lp, q = 0.707f)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            updateBandCoefficients()
            staticThreshold = staticInterval(it.intensity, sampleRate)
            pending = null
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val order = inputBuffer.order()
        val out = replaceOutputBuffer(remaining)
        // The output buffer comes back in native order; the bytes copied into it
        // are the input's. Carrying the input's order across means a pass-through
        // reads back as the same samples, not as byte-swapped ones — the active
        // paths below already do this, and the two disagreeing is what a
        // big-endian caller would see as silent corruption.
        out.order(order)
        if (!active.isActive()) {
            out.put(inputBuffer).flip()
            return
        }

        when (encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, out, order)
            else -> processShort(inputBuffer, out, order)
        }
        out.flip()
    }

    private fun processFloat(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        val drive = saturationDrive(active.intensity)
        while (input.hasRemaining()) {
            if (channel == 0) advanceFrame()
            out.putFloat(processSample(channel, input.float, drive).coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        val drive = saturationDrive(active.intensity)
        while (input.remaining() >= 2) {
            if (channel == 0) advanceFrame()
            val x = input.short / 32_768f
            val y = processSample(channel, x, drive).coerceIn(-1f, 1f) * 32_767f
            out.putShort(y.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    /** Advances the shared static trigger and carrier warble once per frame. */
    private fun advanceFrame() {
        val intensity = active.intensity

        staticCounter++
        if (staticCounter >= staticThreshold) {
            staticCounter = 0
            staticThreshold = (staticInterval(intensity, sampleRate) *
                (0.5f + rng.nextFloat())).toInt().coerceAtLeast(1)
            staticBurstSamplesLeft = staticBurstSamples(intensity, sampleRate)
            staticBurstAmp = staticBurstAmplitude(intensity) * (0.6f + rng.nextFloat() * 0.4f)
        }
        staticBurstActive = staticBurstSamplesLeft > 0
        if (staticBurstActive) staticBurstSamplesLeft--

        phaseSamples++
        val t = phaseSamples.toDouble() / sampleRate
        warbleGain = 1f + (warbleDepth(intensity) * sin(2.0 * PI * WARBLE_HZ * t)).toFloat()
    }

    /**
     * One sample through the chain: static mixed in first, then band-pass,
     * then saturation, then the carrier warble — see the class doc for why
     * static goes in before the band-pass rather than on top of it.
     */
    private fun processSample(channel: Int, x: Float, drive: Float): Float {
        val intensity = active.intensity
        val b = bands[channel.coerceIn(0, bands.lastIndex)]

        var withStatic = x + hissFloorAmplitude(intensity) * (rng.nextFloat() * 2f - 1f)
        if (staticBurstActive) {
            withStatic += staticBurstAmp * (rng.nextFloat() * 2f - 1f)
        }

        val banded = b.lowPass.process(b.highPass.process(withStatic))
        val saturated = saturate(banded, drive)
        return saturated * warbleGain
    }

    override fun onFlush() {
        for (b in bands) {
            b.highPass.reset()
            b.lowPass.reset()
        }
        staticCounter = 0
        staticBurstSamplesLeft = 0
        staticBurstActive = false
        staticBurstAmp = 0f
        phaseSamples = 0
        warbleGain = 1f
    }

    override fun onReset() {
        // `active` is deliberately left alone — see VinylNoiseProcessor.onReset()
        // for the full reasoning. `bands` is genuinely derived, not config, so
        // it still shrinks back here; the next onConfigure() regrows it.
        bands = Array(2) { ChannelBand() }
    }
}
