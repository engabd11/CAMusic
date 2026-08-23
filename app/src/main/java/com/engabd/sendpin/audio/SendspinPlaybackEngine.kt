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

    /**
     * Identifies the stream [start] most recently loaded; 0 before the first one.
     *
     * Everything to do with a tail is stamped with this, because a tail outlives the
     * stream that produced it. The drain `stream/end` begins is still counting when
     * the *next* `stream/start` arrives, and "the current stream" has moved on by the
     * time it fires.
     */
    val currentStreamId: Long get() = 0L

    /** Whether [streamId] is still the stream the engine is playing. */
    fun isCurrentStream(streamId: Long): Boolean = false

    /**
     * Whether the player has played out everything it was given for [streamId].
     *
     * The only honest answer to "has the tail been heard yet". What is left to hear
     * once the last frame has been handed over lives in the decoder and the audio
     * track, not in any queue this app can count, so nothing shallower than the
     * player's own end of playback can tell.
     */
    fun hasPlayedOut(streamId: Long): Boolean = false

    /**
     * Give up on [streamId]'s tail and go silent. Idempotent, and a no-op once a newer
     * stream has started: a late drain must never silence the stream that replaced it.
     */
    fun cutTail(streamId: Long) = Unit

    fun flush()
    fun setVolume(v: Float)
    fun setSyncMuted(muted: Boolean)
    fun setPreferredDevice(device: AudioDeviceInfo?)
    fun release()

    var staticDelayMs: Int
    var bitPerfect: Boolean
}
