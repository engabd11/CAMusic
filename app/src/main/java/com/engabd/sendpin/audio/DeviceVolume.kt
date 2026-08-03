package com.engabd.sendpin.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's own media volume, as a 0..1 fraction.
 *
 * The Now Playing slider is a *player* volume everywhere else: Music Assistant keeps
 * a level per player and the slider sets that. The local player has no such thing —
 * it decodes straight to this device's output — so the slider was left pointed at
 * the MA player's level, where it showed a number belonging to something else and
 * moving it did nothing at all.
 *
 * Pointed at `STREAM_MUSIC` instead, it is the control the listener already knows:
 * the same level the volume rocker moves, reflected live. ExoPlayer's own `volume`
 * is deliberately *not* used for this — that is reserved for the ReplayGain scalar
 * (see [ReplayGain]), and folding the two together would make a quiet album look
 * like a turned-down phone.
 */
class DeviceVolume(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _level = MutableStateFlow(read())
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * Volume changes have no public broadcast, so this watches the settings
     * provider — which is what the rocker, the system panel and any other app all
     * end up writing through.
     */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _level.value = read()
        }
    }

    init {
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, observer,
            )
        }
    }

    /** Set the media volume from a 0..1 fraction. */
    fun set(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val target = Math.round(fraction.coerceIn(0f, 1f) * max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        // Don't wait for the observer: the slider should not lag the finger.
        _level.value = read()
    }

    /** Re-read now — for the moments a screen becomes visible again. */
    fun refresh() { _level.value = read() }

    private fun read(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
}
