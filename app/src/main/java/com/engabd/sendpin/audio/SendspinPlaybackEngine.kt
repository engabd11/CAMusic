package com.engabd.sendpin.audio

import android.media.AudioDeviceInfo
import com.engabd.sendpin.protocol.StreamStartPlayerInfo

/**
 * What [com.engabd.sendpin.service.Playback] needs from a Sendspin (MA) playback
 * engine - implemented by [SendspinNativeEngine], the native Oboe engine that
 * replaced the ExoPlayer-based `SendspinExoEngine`.
 *
 * Audio focus, the noisy-headphones receiver, and the volume/mute/staticDelay
 * forwarding in `Playback.kt` all go through this interface rather than the
 * concrete engine, so a future second implementation would not need every
 * caller to know which one is live.
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
     * stream that produced it. The drain `stream/end` begins is still counting when the
     * *next* `stream/start` arrives, and "the current stream" has moved on by the
     * time it fires.
     */
    val currentStreamId: Long get() = 0L

    /** Whether [streamId] is still the stream the engine is playing. */
    fun isCurrentStream(streamId: Long): Boolean = false

    /**
     * Whether the player has played out everything it was given for [streamId].
     *
     * The only honest answer to "has the tail been heard yet". What is left to hear
     * once the last frame has been handed over lives in the decoder and the native
     * ring, not in any queue this app can count, so nothing shallower than the
     * native output's own end of playback can tell.
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

    // ---- New methods for the native engine ----

    /**
     * Tell the engine whether this player is in a Sendspin group.
     *
     * Informational. Playback is scheduled against the server clock either way —
     * that is the only timeline the protocol has — so grouping changes nothing
     * about when a sample is due and this must not disturb audio that is already
     * running. It used to swap timing policies and restart the native output
     * mid-stream, which silenced the leader whenever a second speaker joined it.
     */
    fun setGrouped(grouped: Boolean)

    /**
     * Whether this player is currently on a group timeline.
     *
     * Readable, not just settable, because being grouped changes decisions well outside
     * the engine: a group member must not be disconnected when the app is backgrounded,
     * and must not drop to the idle clock cadence, however quiet it happens to be at
     * that moment. See [com.engabd.sendpin.service.Playback.setClientIdleMode].
     */
    val grouped: Boolean get() = false

    /**
     * Freeze the native consumer without dropping the ring: fade to silence
     * and hold the read position, preserving buffered audio across a transient
     * interruption (audio focus loss). Used instead of a flush for transient
     * focus interruptions.
     */
    fun freezeOutput()

    /** Resume from a [freezeOutput] — fade back in from the held position. */
    fun unfreezeOutput()

    /**
     * Dynamic-range compressor level: 0 = off, 1 = soft, 2 = medium, 3 = hard.
     * Amplitude-only output effect; no effect on timing/latency/sync.
     */
    fun setCompressorLevel(level: Int)

    /**
     * High-end output quantization: noise-shaped TPDF dither at the float→int16
     * step. Amplitude-only; no effect on timing/latency/sync.
     */
    fun setDither(enabled: Boolean)

    /**
     * Signal an upcoming discontinuity (seek, track change): the server keeps
     * sending the OLD position's in-flight frames until it processes the
     * command. Drop all frames until the next `stream/start` (configure) or
     * `stream/clear` (flush) arrives, with a bounded timeout backstop.
     */
    fun expectDiscontinuity(reason: String)
}