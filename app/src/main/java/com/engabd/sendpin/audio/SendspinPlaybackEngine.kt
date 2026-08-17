package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * What [com.engabd.sendpin.service.Playback] needs from a Sendspin (MA) playback
 * engine - implemented by [SendspinExoEngine], the only one now that the original
 * hand-built `MediaCodec`+`AudioTrack` engine has been removed as dead code.
 * Audio focus, the noisy-headphones receiver, and the volume/mute/staticDelay/
 * preferredDevice forwarding in `Playback.kt` all go through this interface
 * rather than the concrete engine, so a future second implementation would not
 * need every caller to know which one is live.
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
