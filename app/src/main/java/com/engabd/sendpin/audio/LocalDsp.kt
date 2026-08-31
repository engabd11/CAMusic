package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The equaliser for audio this phone decodes itself.
 *
 * Music Assistant has had a per-player DSP pipeline all along, configured over
 * the `config/players/dsp` commands and driven from the sheet on Now Playing. Everything this
 * phone plays on its own — Navidrome, Jellyfin, Plex, Emby, downloads — had no
 * equaliser at all, sitting next to a bit-perfect toggle and USB DAC routing. This
 * is the other half.
 *
 * Sits in the same `DefaultAudioSink` processor chain as [AudioAnalysisTap], ahead
 * of it, so the light show reacts to what is actually heard rather than to what
 * was on disk. A biquad cascade is zero-latency, so nothing about the timing that
 * `AudioLead` measures changes.
 *
 * Threading follows the same contract the tap documents: [queueInput], [onConfigure]
 * and [onFlush] all run on ExoPlayer's audio thread. [setConfig] is the exception —
 * it is called from whichever thread edited the settings, which is why the
 * coefficients are rebuilt from a `@Volatile` snapshot at the top of a buffer
 * rather than mutated in place under the audio thread's feet.
 */
@OptIn(UnstableApi::class)
class LocalDsp : BaseAudioProcessor() {

    /**
     * One band, as the UI describes it. Deliberately the same shape as Music
     * Assistant's `EqBand`, so the two backends can share the editor.
     */
    @Serializable
    data class Band(
        val type: Type = Type.PEAKING,
        val frequency: Float = 1_000f,
        val gainDb: Float = 0f,
        val q: Float = 1f,
        val enabled: Boolean = true,
    ) {
        enum class Type { PEAKING, LOW_SHELF, HIGH_SHELF, HIGH_PASS, LOW_PASS }

        /**
         * Whether this band does anything at all.
         *
         * The pass filters ignore gain entirely - a high-pass at 0 dB is still a
         * high-pass - so they always count. Everything else at flat is a straight
         * wire and is left out of the cascade rather than multiplied by one.
         */
        fun altersSignal(): Boolean = when (type) {
            Type.HIGH_PASS, Type.LOW_PASS -> true
            else -> !Biquad.isFlat(gainDb)
        }
    }

    /**
     * The whole curve.
     *
     * @param preampDb applied before the bands. Negative values buy headroom; see
     *   [Biquad.suggestedPreampDb] for why a boost needs it.
     * @param autoPreamp when true, [preampDb] is ignored and the headroom is
     *   computed from the bands. On by default because the failure it prevents —
     *   clipping — is one a listener would blame on the file rather than on the EQ.
     */
    @Serializable
    data class Config(
        val enabled: Boolean = false,
        val bands: List<Band> = defaultBands(),
        val preampDb: Float = 0f,
        val autoPreamp: Boolean = true,
    ) {
        /** The gain actually applied ahead of the bands. */
        fun effectivePreampDb(): Float =
            if (autoPreamp) Biquad.suggestedPreampDb(activeBands().map { it.gainDb })
            else preampDb

        fun activeBands(): List<Band> = bands.filter { it.enabled }

        /** Nothing to do: off, or on with every band flat. */
        fun isTransparent(): Boolean =
            !enabled || activeBands().none { it.altersSignal() }

        companion object {
            /**
             * Ten bands on the ISO third-octave centres a graphic EQ uses.
             *
             * Ten rather than five because five cannot separate bass from low mids,
             * and rather than thirty-one because nobody moves thirty-one sliders on
             * a phone. All flat, so switching the equaliser on changes nothing until
             * something is moved — which is the honest default for a feature that
             * can only make the sound different, not better.
             */
            fun defaultBands(): List<Band> = listOf(
                31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
            ).map { Band(frequency = it, q = 1.41f) }
        }
    }

    @Volatile
    private var pending: Config? = Config()

    /** The config the current coefficients were built from. */
    private var active: Config = Config()

    /** One cascade per channel: `sections[channel][band]`. */
    private var sections: Array<Array<Biquad>> = emptyArray()
    private var preampLinear = 1f

    private var channelCount = 2
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * Set the curve. Safe to call from any thread.
     *
     * Takes effect at the start of the next buffer rather than immediately: changing
     * coefficients mid-buffer steps the filter's response, which is audible as a
     * click, and a listener dragging a slider would generate one per frame.
     */
    fun setConfig(config: Config) {
        pending = config
    }

    /** What is currently applied, for anything that wants to draw the curve. */
    fun currentConfig(): Config = active

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(config: Config): String = json.encodeToString(Config.serializer(), config)

        /**
         * A stored curve, or **null** when it could not be read.
         *
         * Null rather than a default, for the reason the server list and the show
         * presets both give: a default is a real answer a caller may act on by
         * overwriting, and turning a read failure into "you have no EQ" would
         * silently flatten a curve that was merely unreadable.
         */
        fun decode(raw: String): Config? =
            runCatching { json.decodeFromString(Config.serializer(), raw) }.getOrNull()
    }

    // ── AudioProcessor ────────────────────────────────────────────────────

    /**
     * Stays in the chain for any PCM format it can read.
     *
     * Same reasoning as [AudioAnalysisTap]'s: ExoPlayer configures the chain once
     * per format and asks `isActive` only then, so a processor that dropped out
     * when the equaliser was off would never come back when it was switched on
     * mid-track. Being in the chain and copying the buffer is cheap; not being in
     * it and needing to be is a reconfiguration.
     */
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            encoding = inputAudioFormat.encoding
            // Force a rebuild: the coefficients depend on the sample rate.
            pending = active
            inputAudioFormat
        }
        else -> AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        pending?.let { rebuild(it); pending = null }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val out = replaceOutputBuffer(remaining)
        if (active.isTransparent()) {
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
            val y = runChannel(channel, input.float)
            // Float output is not implicitly bounded the way 16-bit is, and a
            // boosted band on already-hot material will exceed 1.0. Clamping is
            // what a listener hears as "loud"; letting it through is what they
            // hear as the sink tearing.
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
        // An odd trailing byte cannot be half a sample; pass it rather than drop it.
        while (input.hasRemaining()) out.put(input.get())
    }

    private fun runChannel(channel: Int, x: Float): Float {
        var y = x * preampLinear
        val cascade = sections.getOrNull(channel) ?: return y
        for (section in cascade) y = section.process(y)
        return y
    }

    /** Rebuild every cascade from [config]. Runs on the audio thread. */
    private fun rebuild(config: Config) {
        active = config
        preampLinear = Biquad.dbToLinear(config.effectivePreampDb())

        val bands = config.activeBands().filter { it.altersSignal() }
        sections = Array(channelCount.coerceAtLeast(1)) {
            Array(bands.size) { i ->
                Biquad().apply {
                    val b = bands[i]
                    when (b.type) {
                        Band.Type.PEAKING -> setPeaking(sampleRate, b.frequency, b.q, b.gainDb)
                        Band.Type.LOW_SHELF -> setLowShelf(sampleRate, b.frequency, b.q, b.gainDb)
                        Band.Type.HIGH_SHELF -> setHighShelf(sampleRate, b.frequency, b.q, b.gainDb)
                        Band.Type.HIGH_PASS -> setHighPass(sampleRate, b.frequency, b.q)
                        Band.Type.LOW_PASS -> setLowPass(sampleRate, b.frequency, b.q)
                    }
                }
            }
        }
    }

    /**
     * A seek, a track change or a stall. Every section's history now describes
     * audio that is no longer adjacent to what comes next, and letting it ring
     * into the new stream is an audible click at best.
     */
    override fun onFlush() {
        for (cascade in sections) for (section in cascade) section.reset()
    }

    override fun onReset() {
        sections = emptyArray()
        preampLinear = 1f
    }

    /**
     * The curve's total response at [freq], in dB, for drawing it.
     *
     * Sums the sections rather than re-deriving it, so what is drawn is what is
     * applied — including the preamp, which is the part a listener is most likely
     * to be surprised by.
     */
    fun responseDbAt(freq: Float): Float {
        val cascade = sections.firstOrNull() ?: return 0f
        var db = active.effectivePreampDb()
        for (section in cascade) db += section.magnitudeDb(sampleRate, freq)
        return if (abs(db) < 1e-4f) 0f else db
    }
}
