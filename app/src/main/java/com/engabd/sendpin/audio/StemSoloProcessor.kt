package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stem-separation solo mode: isolate one instrument group so the listener can
 * hear just the drums, just the vocals, just the bass, or any of five stems.
 *
 * The Phantom Stage light layer already does on-device stem separation by
 * frequency-band proxy (bass = low-pass, vocals = mid bandpass, etc). This
 * processor applies the same filtering to the audio output, so the listener
 * hears what the light show is reacting to.
 *
 * When real stems are available (from [TrackScan] section stems), the
 * processor could route the actual separated channels, but the frequency-band
 * proxy is what ships here: it works for any track without requiring a prior
 * separation pass, and it is what the light layer uses too.
 *
 * Zero latency: every filter is a biquad cascade, same as [LocalDsp]. Sits
 * in the processor chain after [LoFiProcessor] and before [AudioAnalysisTap],
 * so the light show reacts to the soloed stem.
 */
@OptIn(UnstableApi::class)
class StemSoloProcessor : BaseAudioProcessor() {

    /** Which stem to isolate. */
    @Serializable
    enum class Stem(val label: String) {
        NONE("Full mix"),
        BASS("Bass"),
        VOCALS("Vocals"),
        DRUMS("Drums"),
        GUITAR("Guitar"),
        SYNTHS("Synths"),
    }

    @Serializable
    data class Config(
        val stem: Stem = Stem.NONE,
        val enabled: Boolean = false,
    ) {
        /** True when a stem is selected and the processor should filter. */
        fun isActive(): Boolean = enabled && stem != Stem.NONE
    }

    @Volatile
    private var pending: Config? = Config()

    private var active: Config = Config()
    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * One biquad per channel per section. The number of sections varies by
     * stem: bass is one low-pass, vocals is a bandpass (high-pass + low-pass),
     * etc. Pre-allocated to the maximum (two sections) and rebuilt on config
     * change.
     */
    private var sections: Array<Array<Biquad>> = emptyArray()

    fun setConfig(config: Config) {
        pending = config
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(config: Config): String = json.encodeToString(Config.serializer(), config)
        fun decode(raw: String): Config? =
            runCatching { json.decodeFromString(Config.serializer(), raw) }.getOrNull()

        /**
         * Build the biquad cascade for a stem at a given sample rate.
         *
         * The frequency ranges follow the Phantom Stage layer's mapping:
         * bass below 200 Hz, vocals 300 Hz to 3 kHz, drums above 3 kHz
         * (transients), guitar 500 Hz to 2 kHz, synths above 2 kHz.
         */
        internal fun buildCascade(stem: Stem, sampleRate: Int): List<Biquad> = when (stem) {
            Stem.NONE -> emptyList()
            Stem.BASS -> listOf(Biquad().apply { setLowPass(sampleRate, 200f, 0.707f) })
            Stem.VOCALS -> listOf(
                Biquad().apply { setHighPass(sampleRate, 300f, 0.707f) },
                Biquad().apply { setLowPass(sampleRate, 3_000f, 0.707f) },
            )
            Stem.DRUMS -> listOf(
                Biquad().apply { setHighPass(sampleRate, 3_000f, 0.707f) },
            )
            Stem.GUITAR -> listOf(
                Biquad().apply { setHighPass(sampleRate, 500f, 0.707f) },
                Biquad().apply { setLowPass(sampleRate, 2_000f, 0.707f) },
            )
            Stem.SYNTHS -> listOf(
                Biquad().apply { setHighPass(sampleRate, 2_000f, 0.707f) },
            )
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let {
            active = it
            rebuild(it.stem)
            pending = null
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val out = replaceOutputBuffer(remaining)
        if (!active.isActive() || sections.isEmpty()) {
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

    private fun rebuild(stem: Stem) {
        if (!active.isActive() || stem == Stem.NONE) {
            sections = emptyArray()
            return
        }
        val cascade = buildCascade(stem, sampleRate)
        sections = Array(channelCount.coerceAtLeast(1)) {
            cascade.map { b ->
                Biquad().apply {
                    // Copy the coefficients from the template biquad. We can't
                    // share stateful biquads across channels, so each channel
                    // gets its own copy of the same coefficients.
                    val template = b
                    // Re-derive from the stem rather than trying to copy internals.
                    // buildCascade is cheap and returns fresh biquads.
                }
            }.toTypedArray()
        }.also { _ ->
            // Actually rebuild from buildCascade directly per channel, since
            // the template approach above does not copy coefficients.
            sections = Array(channelCount.coerceAtLeast(1)) {
                buildCascade(stem, sampleRate).toTypedArray()
            }
        }
    }

    private fun processFloat(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.hasRemaining()) {
            val x = input.float
            val y = runChannel(channel, x)
            out.putFloat(y.coerceIn(-1f, 1f))
            channel = (channel + 1) % channelCount
        }
    }

    private fun processShort(input: ByteBuffer, out: ByteBuffer, order: ByteOrder) {
        out.order(order)
        var channel = 0
        while (input.remaining() >= 2) {
            val x = input.short / 32_768f
            val y = runChannel(channel, x)
            val clamped = (y.coerceIn(-1f, 1f) * 32_767f)
            out.putShort(clamped.toInt().toShort())
            channel = (channel + 1) % channelCount
        }
        while (input.hasRemaining()) out.put(input.get())
    }

    private fun runChannel(channel: Int, x: Float): Float {
        val cascade = sections.getOrNull(channel) ?: return x
        var y = x
        for (section in cascade) y = section.process(y)
        return y
    }

    override fun onFlush() {
        for (cascade in sections) for (section in cascade) section.reset()
    }

    override fun onReset() {
        active = Config()
        sections = emptyArray()
    }
}