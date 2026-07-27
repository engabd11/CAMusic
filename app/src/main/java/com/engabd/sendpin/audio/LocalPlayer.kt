package com.engabd.sendpin.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A simple standalone / offline player (Android [MediaPlayer]) used when there is
 * no Music Assistant to route audio to: Navidrome-direct streaming and playback of
 * offline downloaded files. Handles http(s) URLs and local file paths (FLAC/MP3/…).
 */
class LocalPlayer {
    private var mp: MediaPlayer? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    fun play(source: String, title: String = "") {
        stop()
        _title.value = title
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener { start(); _playing.value = true }
            setOnCompletionListener { _playing.value = false }
            setOnErrorListener { _, _, _ -> _playing.value = false; true }
            try {
                setDataSource(source)
                prepareAsync()
            } catch (_: Exception) {
                _playing.value = false
            }
        }
    }

    fun pause() { mp?.let { if (it.isPlaying) { it.pause(); _playing.value = false } } }
    fun resume() { mp?.let { if (!it.isPlaying) { it.start(); _playing.value = true } } }
    fun toggle() { if (_playing.value) pause() else resume() }
    fun setVolume(v: Float) { mp?.setVolume(v, v) }

    fun stop() {
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        _playing.value = false
    }
}
