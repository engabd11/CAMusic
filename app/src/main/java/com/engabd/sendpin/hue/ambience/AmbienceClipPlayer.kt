package com.engabd.sendpin.hue.ambience

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays a real audio file as an ambience bed, looping.
 *
 * A parallel, independent player alongside the show — not an [AudioSink]. [AudioSink]
 * is a pull-based, blocking-write PCM interface that doubles as the show's pacing
 * clock; ExoPlayer decodes on its own schedule and cannot honour that contract. A
 * session runs with `sink = null` whenever a clip player is the one making sound, and
 * falls back to wall-clock timing for the lights — see `AmbienceSession.nowS()`. A
 * clip bed and the synthesised bed are mutually exclusive per show, so nothing needs
 * to arbitrate between them.
 *
 * Built and released on the main thread — unlike [AudioTrackSink]'s IO-thread
 * `AudioTrack`, this is ExoPlayer's own contract.
 *
 * @param onError called if playback fails, at any point after [start] — a bad "My
 *   Clip" file (or, in principle, a corrupt bundled asset) surfaces this way rather
 *   than as an exception, since ExoPlayer resolves and decodes asynchronously.
 */
class AmbienceClipPlayer(context: Context, private val onError: () -> Unit) {

    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
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
    fun pause() { runCatching { player.pause() } }
    fun resume() { runCatching { player.play() } }
    fun release() { runCatching { player.release() } }
}
