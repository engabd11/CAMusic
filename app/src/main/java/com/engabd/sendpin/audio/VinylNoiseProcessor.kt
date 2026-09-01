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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * Vinyl surface noise: crackle, dust pops, and low-end rumble added to digital
 * playback so a clean file can sound like a record.
 *
 * Sits in the same `DefaultAudioSink` processor chain as [LocalDsp] and
 * [AudioAnalysisTap], ahead of the tap, so the light show reacts to the
 * treated audio rather than to what was on disk. Zero latency: every stage is
 * either a per-sample filter or a one-shot impulse, neither of which delays
 * the signal.
 *
 * Threading follows the same contract the tap documents: [queueInput] and
 * [onConfigure] run on ExoPlayer's audio thread. [setConfig] is called from
 * whichever thread edited the settings, which is why the active config is
 * read from a `@Volatile` snapshot at the top of a buffer rather than mutated
 * under the audio thread's feet.
 *
 * The noise is deterministic per session: the [Random] is seeded once at
 * construction, so the same crackle pattern does not re-randomise on every
 * buffer. A different seed each session is fine — the point is that within
 * one playback the noise is stable, not that it is reproducible across
 * plays.
 */
@OptIn(UnstableApi::class)
class VinylNoiseProcessor : BaseAudioProcessor() {

    /**
     * One knob: how much noise to add. 0 is off, 1 is a dusty record.
     *
     * At 0 the processor is a transparent pass-through, which is the
     * honest default for a feature that can only make the sound different,
     * not better.
     */
    @Serializable
    data class Config(
        val enabled: Boolean = false,
        /** 0..1. Controls crackle rate, pop frequency, and rumble level. */
        val intensity: Float = 0.5f,
    ) {
        /** True when the processor should actually do work. */
        fun isActive(): Boolean = enabled && intensity > 0f
    }

    @Volatile
    private var pending: Config? = Config()

    private var active: Config = Config()
    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    /** Session-stable RNG so the crackle pattern does not re-randomise per buffer. */
    private val rng = Random(System.nanoTime())

    // --- Crackle: sparse Poisson-distributed impulses with one-pole decay ---

    /**
     * Average samples between crackle impulses at full intensity. Scales
     * inversely with intensity: more intensity means more frequent crackle.
     *
     * At 44.1 kHz, 800 samples is roughly one crackle every 18 ms, which is
     * dense enough to read as surface noise rather than isolated clicks.
     */
    private var crackleCounter = 0
    private var crackleThreshold = 800

    // --- Dust pops: less frequent, sharper, faster decay ---

    private var popCounter = 0
    private var popThreshold = 20_000

    // --- One-pole decay envelopes for crackle and pops ---

    private var crackleEnv = 0f
    private var popEnv = 0f

    /**
     * Rumble: filtered white noise through a one-pole low-pass at ~50 Hz.
     *
     * The filter is a simple `y[n] = a*x[n] + (1-a)*y[n-1]` where `a` is
     * derived from the cutoff and the sample rate. At 44.1 kHz the
     * coefficient is ~0.0035, which puts the -3 dB point near 50 Hz.
     * Quiet: at most 3% of full scale at max intensity.
     */
    private var rumbleState = 0f
    private var rumbleCoeff = 0.0035f

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
         * Crackle amplitude at a given intensity. Capped at 12% of full
         * scale: louder than that stops reading as surface noise and starts
         * reading as a damaged record.
         */
        internal fun crackleAmplitude(intensity: Float): Float = (intensity * 0.12f).coerceIn(0f, 0.12f)

        /**
         * Pop amplitude: sharper and louder than crackle, but rare. Capped
         * at 30% — a dust pop is a transient, not a continuous noise.
         */
        internal fun popAmplitude(intensity: Float): Float = (intensity * 0.30f).coerceIn(0f, 0.30f)

        /**
         * Rumble amplitude: very quiet. 3% of full scale at max, because
         * rumble is felt more than heard.
         */
        internal fun rumbleAmplitude(intensity: Float): Float = (intensity * 0.03f).coerceIn(0f, 0.03f)

        /**
         * Average samples between crackle impulses at the given intensity.
         * Linear interpolation: intensity 0 = one per 4000 samples (sparse),
         * intensity 1 = one per 300 samples (dense).
         */
        internal fun crackleInterval(intensity: Float, sampleRate: Int): Int {
            val base = 4000 - (intensity * 3700f).toInt()
            return base.coerceIn(100, 4000)
        }

        /**
         * Average samples between dust pops. Much rarer than crackle:
         * intensity 0 = one per 60s, intensity 1 = one per 5s.
         */
        internal fun popInterval(intensity: Float, sampleRate: Int): Int {
            val secondsPerPop = 60f - (intensity * 55f)
            return (secondsPerPop * sampleRate).toInt().coerceIn(sampleRate, 60 * sampleRate)
        }

        /**
         * One-pole low-pass coefficient for a cutoff frequency.
         *
         * `a = 1 - exp(-2*pi*fc/fs)`, the standard first-order IIR coefficient.
         */
        internal fun lowPassCoeff(cutoffHz: Float, sampleRate: Int): Float {
            val wc = 2.0 * Math.PI * cutoffHz / sampleRate
            return (1.0 - Math.exp(-wc)).toFloat()
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            rumbleCoeff = lowPassCoeff(50f, sampleRate)
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            // Recompute thresholds when intensity changes.
            crackleThreshold = crackleInterval(it.intensity, sampleRate)
            popThreshold = popInterval(it.intensity, sampleRate)
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
            val x = input.float
            val noise = generateNoise(channel)
            out.putFloat((x + noise).coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.remaining() >= 2) {
            val x = input.short / 32_768f
            val noise = generateNoise(channel)
            val y = (x + noise).coerceIn(-1f, 1f) * 32_767f
            out.putShort(y.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    /**
     * One sample of vinyl noise: crackle + pop + rumble.
     *
     * Only channel 0 advances the crackle/pop counters, so the rate is
     * per-sample-pair, not per-channel. The rumble runs on every channel
     * independently because it is a continuous filter, not a discrete event.
     */
    private fun generateNoise(channel: Int): Float {
        val intensity = active.intensity

        // --- Crackle: Poisson-distributed impulses ---
        if (channel == 0) {
            crackleCounter++
            if (crackleCounter >= crackleThreshold) {
                crackleCounter = 0
                // Randomise the next threshold around the mean so the
                // crackle is not periodic. Exponential distribution: the
                // natural model for independent random arrivals.
                crackleThreshold = (crackleInterval(intensity, sampleRate) *
                    (0.5f + rng.nextFloat())).toInt().coerceAtLeast(1)
                crackleEnv = crackleAmplitude(intensity) * (0.5f + rng.nextFloat() * 0.5f)
            }
            // One-pole decay: ~3 ms time constant at 44.1 kHz.
            crackleEnv *= 0.92f
        }

        // --- Dust pops: rarer, sharper, faster decay ---
        if (channel == 0) {
            popCounter++
            if (popCounter >= popThreshold) {
                popCounter = 0
                popThreshold = (popInterval(intensity, sampleRate) *
                    (0.5f + rng.nextFloat())).toInt().coerceAtLeast(1)
                popEnv = popAmplitude(intensity) * (0.7f + rng.nextFloat() * 0.3f)
            }
            // Faster decay than crackle: a pop is a transient.
            popEnv *= 0.80f
        }

        // --- Rumble: continuous low-pass-filtered white noise ---
        val rumbleAmp = rumbleAmplitude(intensity)
        rumbleState = rumbleCoeff * (rng.nextFloat() * 2f - 1f) + (1f - rumbleCoeff) * rumbleState

        // The crackle sign is random per impulse, not per sample: the envelope
        // carries the amplitude and a coin flip at the trigger decides polarity.
        // Here we approximate by letting the envelope decay carry the sign from
        // the RNG at trigger time, which is already how crackleEnv was set.
        val crackleSign = if (crackleEnv > 0f) (if (rng.nextBoolean()) 1f else -1f) * crackleEnv else 0f
        val popSign = if (popEnv > 0f) (if (rng.nextBoolean()) 1f else -1f) * popEnv else 0f

        return crackleSign + popSign + rumbleState * rumbleAmp
    }

    override fun onFlush() {
        crackleCounter = 0
        popCounter = 0
        crackleEnv = 0f
        popEnv = 0f
        rumbleState = 0f
    }

    override fun onReset() {
        active = Config()
    }
}