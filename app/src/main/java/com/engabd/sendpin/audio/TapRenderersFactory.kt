package com.engabd.sendpin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * The output rate the local player's chain resamples to, or 0 to follow each file.
 *
 * A holder rather than a constructor argument because the processor chain is built
 * once, when the player is constructed, and this is edited long after. Read at
 * configure time, so a change applies to the next track - which the setting says.
 */
object OutputRate {
    @Volatile
    var hz: Int = 0
}

/**
 * The vari-speed ratio Lo-fi's slow-down applies, or 1 for none.
 *
 * Same shape and the same trade-off as [OutputRate]: read once when
 * [TapRenderersFactory.buildAudioSink] builds the chain, so a change from the
 * Lo-fi intensity slider applies from the start of the next track rather than
 * live mid-track. [LoFiProcessor]'s own bitcrush/saturation and
 * [WowFlutterProcessor]'s wobble both update live via `setConfig()`; only
 * this persistent-speed component shares [OutputRate]'s slower path, because
 * it needs its own [androidx.media3.common.audio.SonicAudioProcessor]
 * instance built fresh into the chain rather than a coefficient recomputed
 * inside an already-running processor.
 */
object LoFiSpeed {
    @Volatile
    var ratio: Float = 1f
}

/**
 * A [DefaultRenderersFactory] that injects the [AudioAnalysisTap] into the
 * audio sink's processor chain. ExoPlayer routes all decoded PCM through the
 * sink's audio processors before it reaches the AudioTrack, so the tap sees
 * the same decoded audio the speaker plays.
 *
 * The tap is a pass-through: it never modifies the audio, only copies it for
 * analysis. It stays in the chain whether or not Light Sync is on — the sink
 * asks [AudioAnalysisTap.isActive] once when it configures the pipeline, so a
 * processor that declined then can never be added back mid-song. The standing
 * cost of being in the chain is one buffer copy; the analysis itself is gated
 * separately by [AudioAnalysisTap.setActive] and runs on its own thread.
 *
 * The processor chain order is:
 * 1. [LocalDsp] — the equaliser, so the light show reacts to what is heard.
 * 2. [VinylNoiseProcessor] — surface noise, after EQ so the noise is on the
 *    equalised signal.
 * 3. [WowFlutterProcessor] — pitch wobble, and 4. a dedicated Sonic instance
 *    for [LoFiSpeed]'s persistent slow-down — both ride Lo-fi's own toggle
 *    (see [LoFiSpeed]) and sit before the bitcrusher so it crushes the
 *    already-wobbly, already-slowed signal, the way a real transport feeds a
 *    tape head before anything saturates.
 * 5. [LoFiProcessor] — bitcrusher/saturation/low-pass, after vinyl noise so
 *    it can share the crackle stage.
 * 6. [AudioAnalysisTap] — copies for analysis, sees the treated audio.
 * 7. Sonic — resamples to the fixed output rate if one is set.
 *
 * The decoder's own output used to be read here too, first in the list above
 * (`SignalPathProbe`, now deleted). It never actually could: a processor only
 * sees whatever media3 hands it, and media3 runs its own
 * `toInt16PcmAudioProcessor` ahead of every processor in this chain (see
 * `DefaultAudioSink.configure`), so whatever a processor reported had already
 * been flattened to 16-bit, whatever the file held. [AudioLeadProbe], which
 * wraps the sink [buildAudioSink] returns, sees the format one layer further
 * out — the renderer's own `audioSinkInputFormat`, before media3's converter
 * runs at all — which is the only place left that format is visible. See
 * [SignalPath.onDecoderOutput].
 *
 * [tap], [dsp], [vinylNoise], [wowFlutter], [loFi] and [oldRadio] are all
 * nullable so [LocalPlayer] can build this chain empty for [ExclusiveOutput]:
 * nothing of this app's between
 * the decoder and the DAC, which for a 16-bit file media3 would not otherwise
 * guarantee on its own (float only bypasses processors when the *decode*
 * happens to be high-resolution — see `SignalPath.State.floatEngaged`).
 */
@OptIn(UnstableApi::class)
class TapRenderersFactory(
    context: Context,
    private val tap: AudioAnalysisTap?,
    private val lead: AudioLead,
    /** The local equaliser, ahead of the tap. See [LocalDsp]. */
    private val dsp: LocalDsp? = null,
    /** Vinyl surface noise, after the EQ. See [VinylNoiseProcessor]. */
    private val vinylNoise: VinylNoiseProcessor? = null,
    /** Lo-fi's pitch wobble, ahead of the bitcrusher. See [WowFlutterProcessor]. */
    private val wowFlutter: WowFlutterProcessor? = null,
    /** Lo-fi mode, after vinyl noise. See [LoFiProcessor]. */
    private val loFi: LoFiProcessor? = null,
    /** Old Radio mode, the last coloration stage before the tap. See [OldRadioProcessor]. */
    private val oldRadio: OldRadioProcessor? = null,
    /**
     * [ExclusiveOutput] is on: leave the fixed-output-rate Sonic resampler out
     * of the chain too, explicitly, rather than trusting [OutputRate.hz] to
     * happen to be 0 — the two are set independently, by different settings.
     */
    private val exclusive: Boolean = false,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(
                listOfNotNull(
                    dsp,
                    vinylNoise,
                    wowFlutter,
                    (if (exclusive) null else LoFiSpeed.ratio.takeIf { it < 0.999f })?.let { ratio ->
                        androidx.media3.common.audio.SonicAudioProcessor()
                            .apply { setSpeed(ratio); setPitch(ratio) }
                    },
                    loFi,
                    oldRadio,
                    tap,
                    (if (exclusive) null else OutputRate.hz.takeIf { it > 0 })?.let { rate ->
                        androidx.media3.common.audio.SonicAudioProcessor()
                            .apply { setOutputSampleRateHz(rate) }
                    },
                ).toTypedArray(),
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .build()
        SignalPath.onFloatOutput(enableFloatOutput)
        SignalPath.onExclusive(exclusive)
        return AudioLeadProbe(sink, lead)
    }
}
