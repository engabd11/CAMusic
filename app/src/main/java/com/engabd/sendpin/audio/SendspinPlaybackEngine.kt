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

    /**
     * `stream/end`. With [drain] true the buffered tail is allowed to play out and the
     * caller is expected to follow with [cutTail]; with it false the tail is dropped
     * immediately, which is what a pause needs. See [com.engabd.sendpin.audio.StreamEndPolicy].
     */
    fun endOfStream(drain: Boolean = false)

    /** Give up on whatever tail is left and go silent. Idempotent. */
    fun cutTail() = Unit

    /** Frames still queued for the decoder, or 0 where the engine cannot say. */
    val queuedFrames: Int get() = 0
    fun flush()
    fun setVolume(v: Float)
    fun setSyncMuted(muted: Boolean)
    fun setPreferredDevice(device: AudioDeviceInfo?)
    fun release()

    var staticDelayMs: Int
    var bitPerfect: Boolean
}
