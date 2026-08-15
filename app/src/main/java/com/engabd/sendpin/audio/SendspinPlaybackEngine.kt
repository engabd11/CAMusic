package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * What [com.engabd.sendpin.service.Playback] needs from a Sendspin (MA) playback
 * engine - implemented by [SendspinAudioEngine] (the default) and [SendspinExoEngine]
 * (experimental, opt-in via [com.engabd.sendpin.data.AppSettings.useExoPlayerForSendspin]).
 * Audio focus, the noisy-headphones receiver, and the volume/mute/staticDelay/
 * preferredDevice forwarding in `Playback.kt` all go through this, so none of
 * them need to know which engine is actually live.
 */
interface SendspinPlaybackEngine {
    fun start(format: StreamStartPlayerInfo)
    fun submit(frame: ByteArray)
    fun endOfStream()
    fun flush()
    fun setVolume(v: Float)
    fun setSyncMuted(muted: Boolean)
    fun setPreferredDevice(device: AudioDeviceInfo?)
    fun release()

    var staticDelayMs: Int
    var bitPerfect: Boolean
}
