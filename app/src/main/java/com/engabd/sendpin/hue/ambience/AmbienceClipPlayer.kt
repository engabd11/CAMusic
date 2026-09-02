package com.engabd.sendpin.hue.ambience

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.AudioLeadProbe

/**
 * Plays a real audio file as an ambience bed, looping, **and analyses it**.
 *
 * A parallel, independent player alongside the show — not an [AudioSink]. [AudioSink]
 * is a pull-based, blocking-write PCM interface that doubles as the show's pacing
 * clock; ExoPlayer decodes on its own schedule and cannot honour that contract.
 *
 * That used to mean a clip-backed show ran with `sink = null` and fell back to
 * **wall-clock timing for its lights**, which is where the whole problem came from: the
 * room ran a scripted storm off a wall clock while the speaker played a recording of a
 * different one. The two had nothing in common but a start time and drifted from there.
 *
 * What was missing was never a way to make a recording obey the script. It was the
 * realisation that the recording should be giving the orders. The player now carries the
 * same [AudioAnalysisTap] Light Sync uses for music, so the show can be driven by the
 * very samples reaching the speaker, on the file's own clock, through
 * [AmbienceAudioAnalysis]:
 *
 * - [AudioAnalysisTap.analysisPositionS] says exactly where in the file each analysed
 *   frame came from, so an event can be stamped where its sound is.
 * - [AudioLead] says how far ahead of the ear the tap is running, so
 *   [AmbienceMediaClock] can turn that into the position being *heard*.
 *
 * Built and released on the main thread — unlike `AudioTrackSink`'s IO-thread
 * `AudioTrack`, this is ExoPlayer's own contract.
 *
 * @param onError called if playback fails, at any point after [start] — a bad "My
 *   Clip" file (or, in principle, a corrupt bundled asset) surfaces this way rather
 *   than as an exception, since ExoPlayer resolves and decodes asynchronously.
 */
@OptIn(UnstableApi::class)
class AmbienceClipPlayer(context: Context, private val onError: () -> Unit) {

    private val lead = AudioLead()
    private val tap = AudioAnalysisTap(lead)
    private val clock = AmbienceMediaClock(lead)

    /**
     * The show's view of this recording.
     *
     * Handed to [AmbienceSession], which installs its own frame consumer and reads the
     * clock from the light tick. Nothing else in the app touches it.
     */
    val analysis: AmbienceAudioAnalysis = object : AmbienceAudioAnalysis {

        override fun heardMediaS(): Double = clock.nowS()

        override fun onFrame(consumer: ((AnalysisFrame, Double) -> Unit)?) {
            if (consumer == null) {
                tap.onFrame = null
                tap.setActive(false)
                return
            }
            // `analysisPositionS` describes the frame currently being delivered and
            // nothing else, which is why it is read here rather than sampled anywhere
            // outside this callback. It is exact: the sink publishes the media time of
            // each buffer as it enters the processor chain and the tap knows how far
            // behind the producer it is, so this is where in the file these samples came
            // from, to within a sample.
            tap.onFrame = { frame ->
                val at = tap.analysisPositionS
                if (!at.isNaN()) consumer(frame, at.toDouble())
            }
            tap.onAnalysisReset = { clock.reset() }
            tap.setActive(true)
        }
    }

    private val player = ExoPlayer.Builder(context.applicationContext)
        // A renderers factory of our own rather than `TapRenderersFactory`. That one is
        // the *music* path's chain — it also installs the equaliser, the vinyl and lo-fi
        // stages, and the fixed-output-rate resampler, and it reports into the global
        // `SignalPath` as it builds. Reusing it here would have an ambience effect
        // quietly rewrite the state the Now Playing screen shows for the user's music.
        // All this player wants is the tap.
        .setRenderersFactory(
            object : DefaultRenderersFactory(context.applicationContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean,
                ): AudioSink = AudioLeadProbe(
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf<AudioProcessor>(tap))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
                        .build(),
                    lead,
                )
            },
        )
        .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // MOVIE rather than MUSIC, matching AudioTrackSink: this is ambience,
                    // not something a device's music EQ should be applied to.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // Audio focus is already handled at the app level by AudioFocusGate —
                // letting ExoPlayer also manage focus would just fight it.
                false,
            )
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = onError()
            })
        }

    /** Starts [uri] looping at [volume]. Synchronous failures (a malformed uri) throw. */
    fun start(uri: Uri, volume: Float) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.volume = volume.coerceIn(0f, 1f)
        player.prepare()
        player.play()
    }

    fun setVolume(v: Float) { runCatching { player.volume = v.coerceIn(0f, 1f) } }

    fun pause() {
        runCatching { player.pause() }
        clock.reset()
    }

    fun resume() { runCatching { player.play() } }

    fun release() {
        tap.onFrame = null
        runCatching { tap.setActive(false) }
        runCatching { player.release() }
    }
}
