package com.engabd.sendpin.service

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
        limitKmh * (1f + tolerancePct / 100f)

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
     * Three seconds is long enough that a spike cannot cause it and short enough to
     * still be a warning. Expressed in time, so tightening the update interval makes
     * the alert more accurate rather than more trigger-happy.
     */
    const val CONFIRM_WINDOW_MS = 3_000L

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
            if (speedKmh <= triggerKmh) {
                if (overSinceMs == NONE) return false
                if (underSinceMs == NONE) underSinceMs = nowMs
                // Under, but not for long enough to call it a slow-down yet. The
                // streak is held rather than reset — see [UNDER_GRACE_MS].
                if (nowMs - underSinceMs >= UNDER_GRACE_MS) reset(keepLastBeep = true)
                return false
            }
            underSinceMs = NONE
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
