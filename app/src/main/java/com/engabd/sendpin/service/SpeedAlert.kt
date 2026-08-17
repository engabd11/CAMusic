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
     * How many *consecutive* readings must be over [triggerSpeedKmh] before the
     * alert actually sounds — a single high reading is as likely to be GPS noise
     * as it is a real overage, and beeping on every noisy sample is worse than
     * missing one.
     */
    const val CONSECUTIVE_READINGS_REQUIRED = 5

    /** Minimum time between beeps while speed stays over the trigger. */
    const val REPEAT_INTERVAL_MS = 30_000L

    /**
     * Tracks consecutive over-trigger readings and the last time the alert fired,
     * and decides whether *this* reading should beep. Stateful by design — the
     * decision depends on the readings that came before it — but every input is a
     * plain value, so it's constructible and steppable in a test without a socket
     * or a location provider.
     */
    class Tracker {
        private var consecutiveOver = 0
        private var lastBeepAtMs = 0L

        /** @return true if this reading should trigger a beep. */
        fun onReading(speedKmh: Float, triggerKmh: Float, nowMs: Long): Boolean {
            if (speedKmh <= triggerKmh) {
                consecutiveOver = 0
                return false
            }
            consecutiveOver++
            if (consecutiveOver < CONSECUTIVE_READINGS_REQUIRED) return false
            if (nowMs - lastBeepAtMs < REPEAT_INTERVAL_MS) return false
            lastBeepAtMs = nowMs
            return true
        }

        fun reset() {
            consecutiveOver = 0
            lastBeepAtMs = 0L
        }
    }
}
