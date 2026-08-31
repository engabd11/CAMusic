package com.engabd.sendpin.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every stage between the file and the speaker, as the app can actually observe it.
 *
 * The Output card used to answer "what depth is this?" with
 * `AudioDeviceInfo.getEncodings()` — the *device's* advertised PCM capability. On
 * anything Bluetooth that is always `[ENCODING_PCM_16BIT]`, whatever codec is in
 * use, so a phone streaming LDAC at 96 kHz / 32 bits per sample read "16-bit" and
 * looked like the app was throwing resolution away. It might have been; the card
 * could not tell you either way, because it was describing the wrong thing.
 *
 * This describes the actual chain instead, one stage at a time:
 *
 *  1. **[source]** — what the file is. From the decoder's input `Format`: codec,
 *     sample rate, and the PCM depth the container declares.
 *  2. **[decoded]** — what the decoder handed over. This is the one that usually
 *     explains a disappointment: a 24-bit FLAC decoded by the platform's own
 *     MediaCodec comes out as 16-bit PCM, and no downstream setting can put those
 *     bits back.
 *  3. **[sink]** — what the audio sink was configured with, after the processor
 *     chain. This is what Android is handed.
 *  4. **[floatOutput]** — whether the float path is engaged. Media3 converts
 *     high-resolution PCM down to 16 bits *unless* it is, so this is the switch
 *     that decides whether stage 2's resolution survives stage 3.
 *
 * Everything here is observed, never assumed. A field that could not be read stays
 * null and the UI says so rather than guessing.
 */
object SignalPath {

    /** One stage of the chain. Null fields are "not observed yet", not "zero". */
    data class Stage(
        val codec: String? = null,
        val sampleRateHz: Int? = null,
        /** PCM depth in bits, or null when the stage is a compressed format. */
        val bitDepth: Int? = null,
        val channels: Int? = null,
        /** True when this stage is 32-bit float rather than integer PCM. */
        val isFloat: Boolean = false,
    ) {
        val known: Boolean get() = sampleRateHz != null || bitDepth != null || codec != null

        fun rateLabel(): String? = sampleRateHz?.let { StreamQuality.khz(it) + " kHz" }

        fun depthLabel(): String? = when {
            isFloat -> "32-bit float"
            bitDepth != null -> "$bitDepth-bit"
            else -> null
        }

        /** "FLAC · 96 kHz · 24-bit", skipping whatever is not known. */
        fun summary(): String = listOfNotNull(
            codec?.uppercase(),
            rateLabel(),
            depthLabel(),
        ).joinToString(" · ").ifBlank { "Unknown" }
    }

    data class State(
        val source: Stage = Stage(),
        val decoded: Stage = Stage(),
        val sink: Stage = Stage(),
        val floatOutput: Boolean = false,
        /** The rate the device's mixer runs at, or 0 when it could not be read. */
        val mixerRateHz: Int = 0,
    ) {
        /**
         * True when the decoder produced more resolution than the sink is carrying.
         *
         * The honest test for "am I losing bits here", and the reason the card can
         * say something useful rather than showing four numbers and leaving the
         * reader to work it out.
         */
        val truncating: Boolean
            get() {
                val from = decoded.bitDepth ?: return false
                if (decoded.isFloat) return false
                val to = if (sink.isFloat) 32 else sink.bitDepth ?: return false
                return to < from
            }

        /** True when Android's mixer is running at a different rate to the stream. */
        val resampling: Boolean
            get() {
                val from = sink.sampleRateHz ?: return false
                return mixerRateHz > 0 && mixerRateHz != from
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The decoder's input format — what the file says it is. */
    fun onSourceFormat(format: Format) {
        _state.value = _state.value.copy(
            source = Stage(
                codec = format.sampleMimeType?.substringAfter('/'),
                sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                bitDepth = depthOf(format.pcmEncoding),
                channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT,
            ),
        )
    }

    /**
     * What entered the processor chain — the decoder's real output.
     *
     * Published from the head of the sink's processor chain rather than from the
     * renderer, because the renderer reports what it was *asked* for and this is
     * what actually arrived.
     */
    fun onDecodedFormat(sampleRateHz: Int, encoding: Int, channels: Int) {
        _state.value = _state.value.copy(
            decoded = Stage(
                sampleRateHz = sampleRateHz,
                bitDepth = depthOf(encoding),
                channels = channels,
                isFloat = encoding == C.ENCODING_PCM_FLOAT,
            ),
        )
    }

    /** What the audio sink was configured with, after the processor chain. */
    fun onSinkFormat(format: Format) {
        _state.value = _state.value.copy(
            sink = Stage(
                sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                bitDepth = depthOf(format.pcmEncoding),
                channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT,
            ),
        )
    }

    fun onFloatOutput(enabled: Boolean) {
        _state.value = _state.value.copy(floatOutput = enabled)
    }

    fun onMixerRate(rateHz: Int) {
        _state.value = _state.value.copy(mixerRateHz = rateHz)
    }

    /** Nothing is playing on this path any more; stop reporting a stale chain. */
    fun clear() {
        _state.value = State(
            floatOutput = _state.value.floatOutput,
            mixerRateHz = _state.value.mixerRateHz,
        )
    }

    /** Bits per sample for a media3 PCM encoding, or null for a compressed one. */
    private fun depthOf(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /**
     * Why the chain is doing what it is, in one sentence, or null when it is clean.
     *
     * Written as a diagnosis rather than a warning: every one of these is a real
     * situation with a real cause, and two of the three are not the app's doing.
     */
    fun State.explain(isBluetooth: Boolean): String? = when {
        truncating && !floatOutput ->
            "The decoder produced ${decoded.depthLabel()} and the sink is carrying " +
                "${sink.depthLabel()}. Turn on high-resolution output under Output to keep it."
        truncating ->
            "The decoder produced ${decoded.depthLabel()} and the sink is carrying " +
                "${sink.depthLabel()} anyway — the decoder for this format cannot hand " +
                "over more, whatever the file holds."
        resampling && isBluetooth ->
            "Android's mixer is at ${StreamQuality.khz(mixerRateHz)} kHz and the stream is " +
                "${sink.rateLabel()}, so it is being resampled before the Bluetooth codec sees it."
        resampling ->
            "Android's mixer is at ${StreamQuality.khz(mixerRateHz)} kHz and the stream is " +
                "${sink.rateLabel()}, so it is being resampled on the way out."
        else -> null
    }
}

/**
 * Publishes the head of the sink's processor chain into [SignalPath].
 *
 * A processor rather than a renderer listener, because this is the one place the
 * *actual* decoded PCM format is visible: the renderer reports the format it asked
 * the decoder for, and the decoder is free to hand back something else.
 *
 * Passes every buffer through untouched — it is a probe, not a stage.
 */
class SignalPathProbe : androidx.media3.common.audio.BaseAudioProcessor() {

    override fun onConfigure(
        inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat,
    ): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        SignalPath.onDecodedFormat(
            sampleRateHz = inputAudioFormat.sampleRate,
            encoding = inputAudioFormat.encoding,
            channels = inputAudioFormat.channelCount,
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }
}

/** Media3 [AudioSink] wrapper that reports the configured output format. */
internal fun Format.publishAsSinkFormat() = SignalPath.onSinkFormat(this)
