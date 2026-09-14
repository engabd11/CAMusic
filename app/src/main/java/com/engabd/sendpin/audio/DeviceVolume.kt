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

    /**
     * Set the media volume from a Sendspin player volume (0–100), on the spec's loudness
     * curve: "volume 50 should be perceived as half as loud as volume 100", which it
     * defines as an amplitude of `(volume / 100)^1.5`. The phone's own volume steps sit
     * on the OS's curve, which [AudioManager.getStreamVolumeDb] exposes, so the step
     * chosen is the one whose gain is nearest the spec's amplitude for this volume —
     * as close to the SHOULD as a stepped control gets. Zero is silence.
     */
    fun setPerceived(volume: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val v = volume.coerceIn(0, 100)
        val target = if (v == 0) 0 else {
            val wantDb = 30.0 * Math.log10(v / 100.0)     // 20·log10(a), a = (v/100)^1.5
            (1..max).minByOrNull { Math.abs(stepDb(it) - wantDb) } ?: Math.round(v / 100f * max)
        }
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        _level.value = read()
    }

    /** The current media volume as the Sendspin player volume (0–100), the inverse of [setPerceived]. */
    fun perceivedPercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0 || index <= 0) return 0
        if (index >= max) return 100
        val db = stepDb(index)
        return Math.round(100.0 * Math.pow(10.0, db / 30.0)).toInt().coerceIn(1, 100)
    }

    /** The OS's gain for one volume step on the current media output, in dB (0 at full). */
    private fun stepDb(index: Int): Double {
        // The output media takes: an attached headset or Bluetooth device wins, else the speaker.
        val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
        val preferred = listOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        val device = preferred.firstOrNull { it in outputs } ?: android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val full = runCatching { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, max, device) }.getOrNull() ?: 0f
        val at = runCatching { audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, device) }.getOrNull()
            ?: return 20.0 * Math.log10(index.toDouble() / max)
        return (at - full).toDouble()
    }

    /** Re-read now — for the moments a screen becomes visible again. */
    fun refresh() { _level.value = read() }

    /** One step of the phone's own volume scale, as a fraction. */
    fun stepFraction(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max <= 0) 0f else 1f / max
    }

    fun read(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
}
