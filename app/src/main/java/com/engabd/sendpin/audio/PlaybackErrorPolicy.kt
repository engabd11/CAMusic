package com.engabd.sendpin.audio

import androidx.media3.common.PlaybackException

/**
 * What a playback error should do to the queue.
 *
 * Every error used to skip to the next track, on the theory that one bad file should
 * not end an album. True for a bad file — but most errors on a streamed library are
 * not about the file at all. A Wi-Fi dropout longer than the buffer failed the current
 * track, the skip then failed the next one the same way, and a whole album flicked
 * past in a few seconds while the phone was between access points. A revoked token
 * did the same with a 401 per track.
 *
 * So an error now says which of three things happened:
 *  - [Action.SKIP]: this item cannot be played (missing, undecodable). The next can.
 *  - [Action.WAIT_FOR_NETWORK]: nothing can be played until the network answers.
 *    Stay on this track, at this position, and try again when it does.
 *  - [Action.STOP]: nothing will be played until the user does something (sign in
 *    again). Skipping would only fail every remaining item for the same reason.
 */
object PlaybackErrorPolicy {

    enum class Action { SKIP, WAIT_FOR_NETWORK, STOP }

    /**
     * How many skipped tracks in a row before the queue stops skipping.
     *
     * A queue whose every item fails is not a run of bad files, it is something
     * systemic this classification did not recognise; stopping there caps the damage
     * at a few tracks instead of the rest of the queue.
     */
    const val MAX_CONSECUTIVE_SKIPS = 3

    /** [errorCode] is a `PlaybackException.ERROR_CODE_*`; [httpStatus] the response code, if any. */
    fun classify(errorCode: Int, httpStatus: Int? = null): Action = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> Action.WAIT_FOR_NETWORK

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus) {
            401, 403 -> Action.STOP
            // The server is there but struggling (a restart, a proxy with nothing
            // behind it). Same as no network: this track will play once it answers.
            408, 429, 500, 502, 503, 504 -> Action.WAIT_FOR_NETWORK
            else -> Action.SKIP
        }

        // "Unspecified" IO is what a connection reset mid-stream surfaces as.
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Action.WAIT_FOR_NETWORK

        // Permission to read a local file is not coming back by itself.
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Action.STOP

        else -> Action.SKIP
    }
}
