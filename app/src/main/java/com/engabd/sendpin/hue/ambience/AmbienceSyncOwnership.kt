package com.engabd.sendpin.hue.ambience

/**
 * Who is responsible for turning the master "Sync lights to music" switch back off
 * once a running ambience show ends.
 *
 * `startAmbience` self-opens the bridge session if it isn't already running, so an
 * ambience show has always worked with the master switch off — but until this
 * existed, nothing told the switch itself that this had happened, and stopping the
 * show never turned it back off either. Worse, if the user *manually* flipped the
 * switch on while a show was auto-running and then off again, the switch's own
 * off-path forcibly killed the show, since it had no way to know the show already
 * held the session for an unrelated reason.
 *
 * A tri-state rather than a bool because "sync was already on when the show
 * started" and "sync was off, the show turned it on, and the user then confirmed
 * that by touching the switch themselves" need different endings: the first must
 * never be turned off by a show ending, and the second — once adopted — must not
 * be either.
 */
enum class AmbienceSyncOwnership {
    /** No ambience show is running, or it hasn't touched the switch. */
    NONE,

    /** The switch was off; starting the show turned it on. Stopping turns it back off. */
    AUTO_ENABLED,

    /**
     * Sync is on for a reason the show doesn't own — either it was already on when
     * the show started, or the user touched the switch themselves while an
     * auto-enabled show was running. Stopping the show leaves it exactly as is.
     */
    USER_ADOPTED,
}
