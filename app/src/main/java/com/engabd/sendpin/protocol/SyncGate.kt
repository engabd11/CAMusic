package com.engabd.sendpin.protocol

import com.engabd.sendpin.audio.SendspinAudioEngine

/**
 * What to report in `client/state`, and whether to mute, while the clock filter is
 * still converging.
 *
 * The spec is explicit that a player which cannot yet place audio on the shared
 * timeline must say so — *"clients experiencing synchronization issues should send
 * `state: 'error'`, mute audio output, and continue buffering until synchronized"*.
 * Reporting `synchronized` from the moment the socket opens, as this used to,
 * tells the server the player is ready to join a group while its offset is still
 * seconds wide: in a group that is audible, and it is the listener who finds out.
 *
 * Pure, like [SendspinAudioEngine.HeadGate], so it can be tested without a socket
 * or a clock.
 */
object SyncGate {
    /**
     * How long to keep the output muted waiting for convergence.
     *
     * Deliberately the same deadline [SendspinAudioEngine.HeadGate] uses to give up
     * holding the head of a stream. If this were longer, the head gate would release
     * audio into a still-muted track and the opening of the song would be silently
     * eaten; if shorter, the mute would lift before there was anything to hear.
     */
    const val MAX_MUTE_MS = SendspinAudioEngine.HeadGate.MAX_STALL_MS

    enum class Report {
        /** The clock is trustworthy — normal operation. */
        SYNCHRONIZED,

        /** Not yet placed on the shared timeline. */
        ERROR,
    }

    data class Decision(val report: Report, val muted: Boolean)

    fun decide(clockReady: Boolean, unreadyMs: Long, maxMuteMs: Long = MAX_MUTE_MS): Decision = when {
        clockReady -> Decision(Report.SYNCHRONIZED, muted = false)
        unreadyMs < maxMuteMs -> Decision(Report.ERROR, muted = true)
        // Past the deadline the offset is not coming. Keep telling the server the
        // truth, but stop muting: a solo player that never converges has nothing to
        // be out of step with, and permanent silence is a worse answer than audio
        // that is merely ungrouped. Mirrors HeadGate's PLAY_NOW escape exactly.
        else -> Decision(Report.ERROR, muted = false)
    }
}
