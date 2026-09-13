package com.engabd.sendpin.protocol

import com.engabd.sendpin.audio.SendspinPlaybackSupport

/**
 * Whether to mute the output while the clock filter is still converging.
 *
 * Local output policy only. This used to also decide what `client/state` said —
 * `state: "error"` until the clock was ready, and `available: false` before it had
 * ever been — on the spec's advice that a player "experiencing synchronization
 * issues" should say so. Music Assistant's answer to both was to rebuild or stop
 * the stream, and the official Music Assistant app never sends either; see
 * [ClientStatePayload]. What remains here is the part that was always right: a
 * player that cannot yet place a sample on the shared timeline should not be heard
 * placing it wrongly.
 *
 * Pure, like [SendspinPlaybackSupport.HeadGate], so it can be tested without a
 * socket or a clock.
 */
object SyncGate {
    /**
     * How long to keep the output muted waiting for convergence.
     *
     * Deliberately the same deadline [SendspinPlaybackSupport.HeadGate] uses to give
     * up holding the head of a stream. If this were longer, the head gate would
     * release audio into a still-muted track and the opening of the song would be
     * silently eaten; if shorter, the mute would lift before there was anything to
     * hear.
     */
    const val MAX_MUTE_MS = SendspinPlaybackSupport.HeadGate.MAX_STALL_MS

    data class Decision(val muted: Boolean)

    fun decide(clockReady: Boolean, unreadyMs: Long, maxMuteMs: Long = MAX_MUTE_MS): Decision = when {
        clockReady -> Decision(muted = false)
        unreadyMs < maxMuteMs -> Decision(muted = true)
        // Past the deadline the offset is not coming. Stop muting: a solo player that
        // never converges has nothing to be out of step with, and permanent silence
        // is a worse answer than audio that is merely ungrouped. Mirrors HeadGate's
        // PLAY_NOW escape exactly.
        else -> Decision(muted = false)
    }
}
