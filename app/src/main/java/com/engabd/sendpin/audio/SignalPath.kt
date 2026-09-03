package com.engabd.sendpin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
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
 *  2. **[decoded]** — what the decoder handed over, read from
 *     [AudioLeadProbe.configure], the sink wrapper's own `configure()` override.
 *     This used to be read one layer further in, from a processor
 *     (`SignalPathProbe`, now deleted) sitting inside `DefaultAudioSink`'s chain —
 *     which is *behind* media3's own 16-bit converter (see
 *     `DefaultAudioSink.configure`'s `toInt16PcmAudioProcessor`), so whatever a
 *     processor saw had already been flattened to 16-bit, whatever the file held.
 *     A processor cannot see the true decoder output; the sink wrapper, one layer
 *     further out, is the only place that can.
 *  3. **[State.sink]** — what Android is actually handed, *derived* rather than
 *     read. There is no honest way to observe this independently any more (see
 *     its doc); it is media3's own rule, replicated.
 *  4. **[State.highResRequested]** — what was passed to `setEnableAudioFloatOutput`.
 *     Not the same as *engaging* — see [State.floatEngaged].
 *
 * Everything here is observed or derived from an observation, never assumed. A
 * field that could not be read stays null and the UI says so rather than guessing.
 */
@OptIn(UnstableApi::class)
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
        /** What was passed to `setEnableAudioFloatOutput` — the *request*, not whether it engaged. */
        val highResRequested: Boolean = false,
        /** The player was built with [ExclusiveOutput] on. */
        val exclusive: Boolean = false,
        /** The player was built with AAudio bit-perfect direct output. */
        val aaudioBitperfect: Boolean = false,
        /** The rate the device's mixer runs at, or 0 when it could not be read. */
        val mixerRateHz: Int = 0,
    ) {
        /**
         * Whether media3 actually took the float path, mirroring
         * `DefaultAudioSink.shouldUseFloatOutput`: `enableFloatOutput &&
         * Util.isEncodingHighResolutionPcm(inputFormat.pcmEncoding)`.
         *
         * [highResRequested] is this app's half of that test; [decoded] being a
         * high-resolution PCM encoding is the platform's. Both have to hold — a
         * 16-bit file with high-resolution output turned on does *not* engage the
         * float path, because there is nothing in it for media3 to carry.
         */
        val floatEngaged: Boolean get() = highResRequested && isHighResolutionPcm(decoded)

        /**
         * What the sink was actually configured with — **derived, not read.**
         *
         * `AudioLeadProbe` used to report this directly, but there is no longer an
         * honest way to observe it independently: the only thing that changed is
         * that [decoded] is now read from the same wrapper, one layer further out
         * than `DefaultAudioSink` sees, which means there is nothing left inside
         * the sink able to tell this object what it did with that format. So this
         * replicates `DefaultAudioSink.configure`'s own rule instead: 32-bit float
         * when [floatEngaged], otherwise media3's `toInt16PcmAudioProcessor`, which
         * always converts to 16-bit integer — at the decoder's own sample rate and
         * channel count, since media3 converts *depth* here, never rate. Empty
         * when [decoded] is not known yet, so `Stage.known` still answers false
         * rather than inventing a number.
         */
        val sink: Stage
            get() {
                if (!decoded.known) return Stage()
                return if (floatEngaged) {
                    Stage(
                        sampleRateHz = decoded.sampleRateHz,
                        bitDepth = 32,
                        channels = decoded.channels,
                        isFloat = true,
                    )
                } else {
                    Stage(
                        sampleRateHz = decoded.sampleRateHz,
                        bitDepth = 16,
                        channels = decoded.channels,
                        isFloat = false,
                    )
                }
            }

        /**
         * True when the decoder produced more resolution than the sink is carrying.
         *
         * The honest test for "am I losing bits here". Because [sink] is derived
         * from the same rule that decides [floatEngaged], this can only be true
         * when [highResRequested] is false: turning high-resolution output on is
         * exactly what stops it firing. (Provable from the definitions above: with
         * [highResRequested] true, [sink] is either 32-bit float — never less than
         * [decoded] — or 16-bit because [decoded] was already 16-bit or less, in
         * which case there is nothing left to truncate either.) So this now
         * finally fires for the one case it was written for: hi-res off, a decode
         * wider than 16 bits.
         */
        val truncating: Boolean
            get() {
                val from = decoded.bitDepth ?: return false
                // A float decode counts. This used to bail out here, from when [sink]
                // was observed rather than derived and a float decode almost always
                // arrived at a float sink anyway. It no longer does: a 32-bit float
                // WAV played with high-resolution output off decodes as float and is
                // converted straight down to 16-bit integer by media3's
                // `toInt16PcmAudioProcessor`, which is exactly the loss this property
                // is for. Float only escapes when the sink is carrying float too.
                if (decoded.isFloat && sink.isFloat) return false
                val to = if (sink.isFloat) 32 else sink.bitDepth ?: return false
                return to < from
            }

        /**
         * True when the decoder handed over fewer bits than the file declares.
         *
         * The one the Output card exists to answer. A 24-bit FLAC decoded by the
         * phone's own MediaCodec routinely comes out as 16-bit PCM: the platform
         * decoder is asked for more (media3 sets `KEY_PCM_ENCODING` to float
         * whenever the sink would take it) and is free to decline, and plenty do.
         * Nothing downstream can put those bits back, so no setting on this page
         * will change it — which is exactly why it is worth saying out loud
         * rather than leaving someone to compare two rows and infer it.
         *
         * Distinct from [truncating], which is this app's own doing and *is*
         * fixable from here. This one is the platform's.
         */
        val decoderLostBits: Boolean
            get() {
                val declared = source.bitDepth ?: return false
                val got = decoded.bitDepth ?: return false
                if (source.isFloat || decoded.isFloat) return false
                return got < declared
            }

        /**
         * True whenever media3 is not running any of this app's audio processors
         * at all — the equaliser, the Light Sync tap, none of it.
         *
         * [floatEngaged] is media3's own doing: on the float branch,
         * `audioProcessorChain.getAudioProcessors()` is never added to the sink's
         * pipeline in the first place (see the class doc on [decoded]).
         * [exclusive] is this app's doing, for the same end — see
         * [ExclusiveOutput] — and deliberately, since exclusive mode's whole point
         * is nothing of ours between the decoder and the DAC.
         * [aaudioBitperfect] bypasses media3 entirely, so the same applies.
         */
        val processorsBypassed: Boolean get() = floatEngaged || exclusive || aaudioBitperfect

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
     * What the decoder actually handed over — the renderer's own
     * `audioSinkInputFormat`, published from [AudioLeadProbe.configure], before
     * any part of the processor chain has touched a sample.
     *
     * That is the whole reason this is read here rather than from a processor:
     * media3 runs its own 16-bit converter ahead of every processor of ours (see
     * the class doc), so a processor only ever sees audio already flattened to 16
     * bits, whatever the file held. [AudioLeadProbe] wraps the sink one layer
     * further out, which is the only place left where the decoder's true
     * resolution is visible.
     */
    fun onDecoderOutput(format: Format) {
        _state.value = _state.value.copy(
            decoded = Stage(
                sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                bitDepth = depthOf(format.pcmEncoding),
                channels = format.channelCount.takeIf { it != Format.NO_VALUE },
                isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT,
            ),
        )
    }

    /** What was passed to `setEnableAudioFloatOutput` — the request, not whether it engaged. */
    fun onFloatOutput(enabled: Boolean) {
        _state.value = _state.value.copy(highResRequested = enabled)
    }

    /** Whether the player currently in use was built with [ExclusiveOutput] on. */
    fun onExclusive(enabled: Boolean) {
        _state.value = _state.value.copy(exclusive = enabled)
    }

    /** Whether the player currently in use was built with AAudio bit-perfect direct output. */
    fun onAaudioBitperfect(enabled: Boolean) {
        _state.value = _state.value.copy(aaudioBitperfect = enabled)
    }

    fun onMixerRate(rateHz: Int) {
        _state.value = _state.value.copy(mixerRateHz = rateHz)
    }

    /** Nothing is playing on this path any more; stop reporting a stale chain. */
    fun clear() {
        _state.value = State(
            highResRequested = _state.value.highResRequested,
            exclusive = _state.value.exclusive,
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
     * Mirrors `Util.isEncodingHighResolutionPcm` (media3) — the exact test
     * `DefaultAudioSink.shouldUseFloatOutput` runs against the decoded encoding
     * to decide whether the float path engages. A private, pure function rather
     * than a call into media3's `Util`, so it is unit-testable with no
     * Android/media3 runtime — see `SignalPathTest`. media3's version matches on
     * the encoding directly (24-bit, 24-bit big-endian, 32-bit, 32-bit
     * big-endian, or float); this matches on the same set via [Stage.bitDepth] /
     * [Stage.isFloat], which [depthOf] already collapses those encodings into.
     */
    private fun isHighResolutionPcm(stage: Stage): Boolean {
        if (stage.isFloat) return true
        val bits = stage.bitDepth ?: return false
        return bits > 16
    }

    /**
     * Why the chain is doing what it is, in one sentence, or null when it is clean.
     *
     * Written as a diagnosis rather than a warning: every one of these is a real
     * situation with a real cause, and none of it is a fault of the file.
     */
    fun State.explain(isBluetooth: Boolean): String? = when {
        truncating ->
            "The decoder produced ${decoded.depthLabel()} and the sink is carrying " +
                "${sink.depthLabel()}. Turn on high-resolution output under Output to keep it."
        decoderLostBits ->
            "The file is ${source.depthLabel()} and the decoder handed over " +
                "${decoded.depthLabel()}. That is this phone's own decoder for this " +
                "format declining to give more, not a setting on this page — nothing " +
                "downstream can put those bits back, and high-resolution output has " +
                "nothing wider to carry."
        processorsBypassed ->
            if (floatEngaged)
                "The equaliser and the Light Sync audio analysis are out of the chain here. " +
                    "On the float output path media3 does not run any of this app's audio " +
                    "processors at all — that is media3's own behaviour, not a setting, and " +
                    "it is the price of carrying the extra bits."
        else if (aaudioBitperfect) ->
            "The equaliser and the Light Sync audio analysis are out of the chain — " +
                "AAudio bit-perfect output bypasses media3 and sends the decoded PCM straight to the DAC."
        else
            "The equaliser and the Light Sync audio analysis are out of the chain — " +
                "Exclusive output asked for nothing of this app's between the decoder " +
                "and the DAC."
        resampling && isBluetooth ->
            "Android's mixer is at ${StreamQuality.khz(mixerRateHz)} kHz and the stream is " +
                "${sink.rateLabel()}, so it is being resampled before the Bluetooth codec sees it."
        resampling ->
            "Android's mixer is at ${StreamQuality.khz(mixerRateHz)} kHz and the stream is " +
                "${sink.rateLabel()}, so it is being resampled on the way out."
        else -> null
    }
}
