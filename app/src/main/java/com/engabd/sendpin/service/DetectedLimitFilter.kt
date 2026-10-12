package com.engabd.sendpin.service

/**
 * Steadies the speed limit read from the zone data, one lookup at a time.
 *
 * Every lookup used to *be* the limit. One fix that landed nearer the cross street
 * at a junction, nearer the service road beside a freeway, or 35 m off the map
 * under a bridge was enough to swap the limit — or to drop it to the manual
 * fallback — for that second, and the alert judged the car against whatever the
 * last single lookup said. That is what reads to a driver as "the zones are wrong":
 * the data was right, the one fix was not.
 *
 * Now a lookup is evidence, not a verdict:
 *  * the first limit found is taken straight away — there is nothing to protect;
 *  * a *different* limit replaces the current one only after [CONFIRMS_TO_CHANGE]
 *    lookups in a row agree on it — about two seconds at a fix a second, about
 *    30 m at 60 km/h, which is far shorter than any real zone and longer than a
 *    junction;
 *  * a known limit is given up only after [MISSES_TO_DROP] misses in a row, so a
 *    gap in the data or one bad fix does not fall back to the manual limit.
 *
 * Pure and stepped by plain values, like [SpeedAlert.Tracker].
 */
class DetectedLimitFilter {

    /** The limit in effect, or null when there is none. */
    var current: Int? = null
        private set

    private var pending: Int? = null
    private var pendingCount = 0
    private var misses = 0

    /** Feed one lookup result; returns the limit now in effect. */
    fun onLookup(result: Int?): Int? {
        if (result == null) {
            pending = null
            pendingCount = 0
            if (current != null && ++misses >= MISSES_TO_DROP) {
                current = null
                misses = 0
            }
            return current
        }
        misses = 0
        when {
            current == null || result == current -> {
                current = result
                pending = null
                pendingCount = 0
            }
            result == pending -> if (++pendingCount >= CONFIRMS_TO_CHANGE) {
                current = result
                pending = null
                pendingCount = 0
            }
            else -> {
                pending = result
                pendingCount = 1
            }
        }
        return current
    }

    fun reset() {
        current = null
        pending = null
        pendingCount = 0
        misses = 0
    }

    companion object {
        /** Lookups in a row that must agree on a new limit before it takes over. */
        const val CONFIRMS_TO_CHANGE = 2

        /** Misses in a row before a known limit is given up for the manual one. */
        const val MISSES_TO_DROP = 3
    }
}
