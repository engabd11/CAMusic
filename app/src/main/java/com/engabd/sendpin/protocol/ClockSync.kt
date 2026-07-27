package com.engabd.sendpin.protocol

/**
 * Timing facade used by the audio scheduler. The [SendspinClient] owns the
 * `client/time` ↔ `server/time` loop and feeds each round-trip in via
 * [onServerTime]; the scheduler converts between the server and local clocks with
 * [serverTimeToLocal] / [localTimeToServer]. The heavy lifting is in
 * [ClockKalmanFilter] (a 2-D offset+drift Kalman filter, unit-tested on the JVM).
 */
class ClockSync {

    val filter = ClockKalmanFilter()

    /** Feed one server/time round-trip (all microseconds; T4 stamped at WS onMessage). */
    fun onServerTime(clientTransmittedUs: Long, serverReceivedUs: Long, serverTransmittedUs: Long, clientReceivedUs: Long) =
        filter.processTimeResponse(clientTransmittedUs, serverReceivedUs, serverTransmittedUs, clientReceivedUs)

    fun serverTimeToLocal(serverUs: Long): Long = filter.serverToLocalUs(serverUs)
    fun localTimeToServer(localUs: Long): Long = filter.localToServerUs(localUs)

    fun isReadyForPlaybackStart(): Boolean = filter.isReadyForPlaybackStart()
    fun isSynced(): Boolean = filter.isSynced()
    fun currentOffsetUs(): Long = filter.currentOffsetUs()
    fun nowUs(): Long = filter.nowMonotonicUs()
    fun reset() = filter.reset()
}
