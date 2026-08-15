package com.engabd.sendpin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/**
 * A [DefaultRenderersFactory] that routes decoded audio to [OboeAudioSink]
 * instead of the platform `AudioTrack` - see that class for the bridge itself.
 *
 * Unlike [TapRenderersFactory], the sink returned here is *not* wrapped in
 * [AudioLeadProbe]: that probe measures lead by comparing buffer position
 * against the wrapped sink's [AudioSink.getCurrentPositionUs], which
 * [OboeAudioSink] can only approximate (frames written minus buffered, not a
 * true hardware timestamp) - wrapping it would report a lead precise-looking
 * enough to mislead a future Light Sync integration that isn't wired up yet
 * anyway (see [SendspinExoEngine]'s docstring).
 */
@OptIn(UnstableApi::class)
class OboeRenderersFactory(
    context: Context,
    private val sink: OboeAudioSink,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = sink
}
