package com.engabd.sendpin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

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
 */
@OptIn(UnstableApi::class)
class TapRenderersFactory(
    context: Context,
    private val tap: AudioAnalysisTap,
    private val lead: AudioLead,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(tap))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .build()
        // Wrapped rather than folded into the processor chain: the lead needs a
        // buffer's presentation time alongside the sink's current position, and
        // an AudioProcessor is given neither.
        return AudioLeadProbe(sink, lead)
    }
}