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
 * 1. [SignalPathProbe] — reports the decoder's actual output format.
 * 2. [LocalDsp] — the equaliser, so the light show reacts to what is heard.
 * 3. [VinylNoiseProcessor] — surface noise, after EQ so the noise is on the
 *    equalised signal.
 * 4. [LoFiProcessor] — bitcrusher/saturation/low-pass, after vinyl noise so
 *    it can share the crackle stage.
 * 5. [AudioAnalysisTap] — copies for analysis, sees the treated audio.
 * 6. Sonic — resamples to the fixed output rate if one is set.
 */
@OptIn(UnstableApi::class)
class TapRenderersFactory(
    context: Context,
    private val tap: AudioAnalysisTap,
    private val lead: AudioLead,
    /** The local equaliser, ahead of the tap. See [LocalDsp]. */
    private val dsp: LocalDsp? = null,
    /** Vinyl surface noise, after the EQ. See [VinylNoiseProcessor]. */
    private val vinylNoise: VinylNoiseProcessor? = null,
    /** Lo-fi mode, after vinyl noise. See [LoFiProcessor]. */
    private val loFi: LoFiProcessor? = null,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(
                listOfNotNull(
                    SignalPathProbe(),
                    dsp,
                    vinylNoise,
                    loFi,
                    tap,
                    OutputRate.hz.takeIf { it > 0 }?.let { rate ->
                        androidx.media3.common.audio.SonicAudioProcessor()
                            .apply { setOutputSampleRateHz(rate) }
                    },
                ).toTypedArray(),
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .build()
        SignalPath.onFloatOutput(enableFloatOutput)
        return AudioLeadProbe(sink, lead)
    }
}