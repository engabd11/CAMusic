package com.engabd.sendpin.service

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The pure decision behind the speed-limit alert: given a speed reading, when does
 * it beep. Split out because [SpeedMonitor] is welded to `LocationManager` — there
 * is no other way to test this against a stream of GPS-noise-shaped readings.
 */
object SpeedAlert {

    /**
     * The speed a reading must exceed to trigger — strictly above, never at or
     * below. [limitKmh] × (1 + [tolerancePct]/100), so a driver isn't beeped for
     * running exactly the posted limit; [tolerancePct] absorbs speedometer
     * variance and GPS jitter on top of it.
     */
    fun triggerSpeedKmh(limitKmh: Int, tolerancePct: Int): Float =
        // Integer product first, one divide last. `limit * (1f + pct / 100f)` gave
        // 104.99999 for 100 + 5%, and a reading of exactly 105 was then "over" it —
        // which is the beep-at-the-tolerance bug, in float, before any threshold
        // logic got a say.
        limitKmh * (100 + tolerancePct) / 100f

    /**
     * The lowest whole km/h that counts as over the trigger — the number the
     * driver would have to see on the display to be beeped.
     *
     * GPS speed is a float, and a driver in a 100 zone with 5% tolerance who is
     * doing 105.3 km/h has *not* gone past 105: every speedometer, the overlay and
     * the notification all show "105", and being beeped for it reads as being
     * beeped *at* the tolerance rather than beyond it. So the reading is judged as
     * the whole number it displays as, and that has to be strictly greater than
     * the trigger — 106 for 100 + 5%, 52 for 50 + 3% (trigger 51.5).
     */
    fun firstAlertingSpeedKmh(limitKmh: Int, tolerancePct: Int): Int =
        floor(triggerSpeedKmh(limitKmh, tolerancePct) + 1e-3f).toInt() + 1

    /** Whether a raw reading is past the trigger by the rule in [firstAlertingSpeedKmh]. */
    fun isOver(speedKmh: Float, triggerKmh: Float): Boolean =
        // The epsilon guards the comparison against a trigger that is a whole number
        // in intent but a hair under it in float.
        speedKmh.roundToInt() > triggerKmh + 1e-3f

    /**
     * How long a driver has to be over the trigger before the alert sounds.
     *
     * This used to be a count — five *consecutive* readings — which quietly meant
     * whatever the location update interval happened to be. At the 5-second interval
     * [SpeedMonitor] was using, five readings is **twenty-five seconds** of
     * uninterrupted speeding before a single beep, and a driver who is briefly over
     * the limit through a 60 zone is back under it long before then. The alert was
     * not failing to detect anything; it was waiting for a stretch of speeding
     * almost nobody actually does.
     *
     * Two seconds is the number, and it is deliberately short. Australian
     * enforcement does not have a grace band worth relying on, so an alert that
     * waits is an alert that arrives after the camera. Two fixes at
     * [SpeedMonitor]'s one-a-second interval is still enough that a single bad
     * sample cannot cause it — see [MIN_READINGS], which is what actually guards
     * against that — and it is short enough to be a warning rather than a report.
     *
     * Expressed in time, so tightening the update interval makes the alert more
     * accurate rather than more trigger-happy.
     */
    const val CONFIRM_WINDOW_MS = 2_000L

    /**
     * The fewest readings that window can be made of.
     *
     * Time alone is not enough on its own: two fixes ten seconds apart span the
     * window with nothing in between, and one of them could be noise. Two readings
     * is the floor — a spike has to be sustained across a second fix to count.
     */
    const val MIN_READINGS = 2

    /**
     * How long the speed has to stay back under the trigger to end a streak.
     *
     * A single reading under is as likely to be GPS noise as a real slow-down, and
     * dropping the streak on it was the other half of why the alert stayed quiet: a
     * driver sitting a few km/h over the trigger produces readings that cross it in
     * both directions, and every crossing put the confirmation window back to zero.
     * The speed has to be genuinely under for this long before the streak is over.
     *
     * Forgiving a dip is not the same as counting it. Time spent under the trigger
     * is subtracted from the window rather than credited to it — see [Tracker] —
     * because otherwise a two-second window could be satisfied by two brief
     * excursions with a comfortably legal second and a half between them, which is
     * not what "over the limit for two seconds" means to anybody.
     */
    const val UNDER_GRACE_MS = 4_000L

    /** Minimum time between beeps while speed stays over the trigger. */
    const val REPEAT_INTERVAL_MS = 30_000L

    /**
     * Tracks how long the speed has been over the trigger and the last time the
     * alert fired, and decides whether *this* reading should beep. Stateful by
     * design — the decision depends on the readings that came before it — but every
     * input is a plain value, so it's constructible and steppable in a test without
     * a socket or a location provider.
     */
    class Tracker {
        /** When the current over-the-trigger streak began, or [NONE]. */
        private var overSinceMs = NONE
        /** When the speed first went back under during the current streak, or [NONE]. */
        private var underSinceMs = NONE
        private var readingsInStreak = 0
        // -REPEAT_INTERVAL_MS so the first qualifying streak is never blocked by
        // the repeat-interval gate (which would otherwise see nowMs - 0 < 30s).
        private var lastBeepAtMs = -REPEAT_INTERVAL_MS

        /** @return true if this reading should trigger a beep. */
        fun onReading(speedKmh: Float, triggerKmh: Float, nowMs: Long): Boolean {
            if (!isOver(speedKmh, triggerKmh)) {
                if (overSinceMs == NONE) return false
                if (underSinceMs == NONE) underSinceMs = nowMs
                // Under, but not for long enough to call it a slow-down yet. The
                // streak is held rather than reset — see [UNDER_GRACE_MS].
                if (nowMs - underSinceMs >= UNDER_GRACE_MS) reset(keepLastBeep = true)
                return false
            }
            if (underSinceMs != NONE) {
                // Coming back over after a dip the grace period forgave. The streak
                // survives, but the time spent under does not count toward the
                // window: push the streak's start forward by exactly that long, so
                // what the window measures stays "seconds actually over the trigger".
                overSinceMs += nowMs - underSinceMs
                underSinceMs = NONE
            }
            if (overSinceMs == NONE) {
                overSinceMs = nowMs
                readingsInStreak = 0
            }
            readingsInStreak++
            if (readingsInStreak < MIN_READINGS) return false
            if (nowMs - overSinceMs < CONFIRM_WINDOW_MS) return false
            if (nowMs - lastBeepAtMs < REPEAT_INTERVAL_MS) return false
            lastBeepAtMs = nowMs
            return true
        }

        fun reset() = reset(keepLastBeep = false)

        private fun reset(keepLastBeep: Boolean) {
            overSinceMs = NONE
            underSinceMs = NONE
            readingsInStreak = 0
            // A slow-down inside the repeat interval must not become a way to be
            // beeped twice in five seconds by speeding up again. A full reset — a
            // new drive, or the monitor being switched off — clears that too.
            if (!keepLastBeep) lastBeepAtMs = -REPEAT_INTERVAL_MS
        }

        private companion object {
            const val NONE = Long.MIN_VALUE
        }
    }
}
