package com.engabd.sendpin.protocol

import android.os.SystemClock

/**
 * The local clock every part of the Sendspin timing path reads.
 *
 * `CLOCK_BOOTTIME` (`SystemClock.elapsedRealtimeNanos`), **not** `System.nanoTime`,
 * and the difference is audible. `System.nanoTime` is `CLOCK_MONOTONIC`, which
 * *stops while the device is suspended* — so a phone that dozes for two minutes
 * between one song and the next comes back with a local clock two minutes behind
 * where the server thinks it is. The Kalman filter has no idea: its covariance is
 * still tiny, so it reports itself converged and keeps handing out an offset that
 * is seconds wrong for the best part of a minute while it crawls back. The audio
 * engine schedules the head of the stream against that offset, which is why a
 * track tapped shortly after the phone woke up could sit silent for several
 * seconds before it started.
 *
 * `CLOCK_BOOTTIME` counts suspended time, so there is no step to recover from.
 * The three places that stamp or read the local clock for Sendspin timing — the
 * `client/time` T1, the `server/time` T4, and [ClockSync.nowUs] — must all come
 * from here, or the arithmetic between them is comparing two different clocks.
 * (The step detector in [ClockKalmanFilter] stays as the backstop for a *server*
 * clock that jumps, which nothing on this side can prevent.)
 */
object MonotonicClock {
    /** Microseconds since boot, including time spent suspended. */
    fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
}
