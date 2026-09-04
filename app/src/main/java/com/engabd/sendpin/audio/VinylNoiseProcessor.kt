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

/**
 * Vinyl surface noise: crackle, dust pops, rumble and hiss added to digital
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
 * The noise is deterministic per session: [VinylNoiseEngine]'s generator is
 * seeded once at construction, so the same crackle pattern does not
 * re-randomise on every buffer. A different seed each session is fine — the
 * point is that within one playback the noise is stable, not that it is
 * reproducible across plays.
 *
 * Four components, summed — see [VinylNoiseEngine] for the mechanics:
 * - **Crackle**: sparse impulses, independent per channel so left and right
 *   are not mirror images of each other — real vinyl surface noise is not
 *   perfectly correlated between channels, and mono-doubled noise reads as
 *   "processed" rather than as a record.
 * - **Dust pops**: the same mechanism, rarer, louder, faster decay.
 * - **Rumble**: continuous low-passed white noise, felt more than heard.
 * - **Surface hiss**: continuous, band-limited broadband noise around
 *   3-9 kHz, independent per channel. The texture between the clicks —
 *   without it the effect reads as "clicky" rather than "surface".
 *
 * Crackle and pop impulses also vary in decay time from one impulse to the
 * next (real grit is not all the same size), and their overall rate breathes
 * slowly over time rather than firing at a constant statistical rate — real
 * groove wear clusters into scratched or dusty runs instead of spreading
 * evenly across a side.
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
        /** 0..1. Controls crackle rate, pop frequency, rumble and hiss level. */
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

    /**
     * The crackle/pop/rumble/hiss mechanics, shared with [LoFiProcessor]'s own
     * texture — see [VinylNoiseEngine]. Session-stable RNG so the crackle
     * pattern does not re-randomise per buffer.
     */
    private val noise = VinylNoiseEngine()

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
         * Surface hiss amplitude: quieter still than rumble. A texture, not
         * a hiss loud enough to mask the music.
         */
        internal fun hissAmplitude(intensity: Float): Float = (intensity * 0.015f).coerceIn(0f, 0.015f)

        /**
         * Average samples between crackle impulses at the given intensity, scaled
         * to [sampleRate] so the crackle reads at the same density in real time
         * whatever the file's rate is. Linear interpolation at the 44.1 kHz
         * reference: intensity 0 = one per 4000 samples (~90 ms, sparse),
         * intensity 1 = one per 300 samples (~7 ms, dense).
         *
         * This used to ignore [sampleRate] entirely and count in raw samples, so
         * a 96 kHz file crackled at roughly twice the rate of a 44.1 kHz one at
         * the same intensity — the same number of samples is half as much time at
         * double the rate. [popInterval] already scaled by sample rate correctly;
         * this now matches it, so that asymmetry between the two is gone.
         */
        internal fun crackleInterval(intensity: Float, sampleRate: Int): Int {
            val baseAt44100 = (4000 - (intensity * 3700f).toInt()).coerceIn(100, 4000)
            return (baseAt44100.toLong() * sampleRate / 44_100).toInt().coerceAtLeast(1)
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
            noise.resize(channelCount)
            noise.configureFilters(sampleRate)
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            // Recompute base thresholds when intensity changes. Per-channel
            // thresholds are re-derived from these the next time each
            // channel's impulse fires, same as before.
            noise.resetCrackleThreshold(crackleInterval(it.intensity, sampleRate))
            noise.resetPopThreshold(popInterval(it.intensity, sampleRate))
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
        while (input.hasRemaining()) {
            val x = input.float
            val noiseSample = generateNoise(channel)
            out.putFloat((x + noiseSample).coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.remaining() >= 2) {
            val x = input.short / 32_768f
            val noiseSample = generateNoise(channel)
            val y = (x + noiseSample).coerceIn(-1f, 1f) * 32_767f
            out.putShort(y.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    /**
     * One sample of vinyl noise on [channel]: crackle + pop + rumble + hiss,
     * via the shared [VinylNoiseEngine]. Rumble stays a single shared filter:
     * it is felt more than heard, and its state already differs
     * sample-to-sample regardless of channel, so a second copy would add cost
     * without adding anything audible. The engine's density walk only needs
     * to run once per frame, so it is gated on channel 0.
     */
    private fun generateNoise(channel: Int): Float {
        val intensity = active.intensity
        if (channel == 0) noise.advanceFrame(sampleRate)
        return noise.crackle(channel, intensity, sampleRate) +
            noise.pop(channel, intensity, sampleRate) +
            noise.rumble(intensity) +
            noise.hiss(channel, intensity)
    }

    override fun onFlush() {
        noise.onFlush()
    }

    override fun onReset() {
        // `active` is deliberately left alone. reset() reaches this processor from
        // DecoderAudioRenderer.onDisabled() — a track change, a stop, a release —
        // by which point `pending` has already been drained into `active` inside
        // queueInput. AppSettings.pref() dedupes the settings Flow that refills
        // `pending`, so unless the user actually changes the mode in the meantime
        // nothing re-emits: wiping `active` back to `Config()` here would switch
        // the mode off with no event left to switch it back on, for the rest of
        // the session. LocalDsp.onReset() draws the same line for the same
        // reason — only state derived *from* the config resets on a reset, never
        // the config itself. `noise`'s per-channel state is genuinely derived,
        // not config — sized to whatever channel count the old format needed —
        // so it still shrinks back here, the same way LoFiProcessor.onReset()
        // clears its own filter state; the next onConfigure() regrows it if the
        // new format needs more.
        noise.onReset()
    }
}
