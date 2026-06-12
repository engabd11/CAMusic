package com.cyborgautomation.sendspin.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

class ClockSync(private val client: SendspinClient) {
    @Volatile
    var offsetUs: Long = 0
        private set

    @Volatile
    var lastSyncTimestamp: Long = 0
        private set

    private var initialized = false

    /**
     * Run initial sync: 2 rapid samples at 10ms apart for filter convergence.
     */
    suspend fun initialSync() {
        var offsets = listOf<Long>()
        repeat(2) {
            val offset = sample()
            if (offset != null) {
                offsets = offsets + offset
            }
            if (it < 1) delay(10)
        }
        if (offsets.isNotEmpty()) {
            offsetUs = offsets.average().toLong()
            initialized = true
        }
        lastSyncTimestamp = systemTimeUs()
    }

    /**
     * Periodic sync sample (call every 30s during playback).
     */
    suspend fun periodicSync() {
        val offset = sample() ?: return
        if (!initialized) {
            offsetUs = offset
            initialized = true
        } else {
            // Exponential moving average for smoothing
            offsetUs = (offsetUs * 0.8 + offset * 0.2).toLong()
        }
        lastSyncTimestamp = systemTimeUs()
    }

    /**
     * Convert server timestamp to local monotonic time.
     */
    fun serverTimeToLocal(serverUs: Long): Long = serverUs + offsetUs

    /**
     * Convert local monotonic time to server timestamp.
     */
    fun localTimeToServer(localUs: Long): Long = localUs - offsetUs

    private suspend fun sample(): Long? {
        val t1 = systemTimeUs()
        client.send(Message.ClientTime(t1))

        // Wait for server/time response (timeout: 5 seconds)
        val response: Message.ServerTime? = withTimeoutOrNull(5000) {
            client.messages
                .filterIsInstance<Message.ServerTime>()
                .first()
        }
        if (response == null) return null

        val t4 = systemTimeUs()
        val t2: Long = response.t2
        val t3: Long = response.t3

        // NTP offset: ((t2 - t1) + (t3 - t4)) / 2
        val offset = ((t2 - t1) + (t3 - t4)) / 2L
        return offset
    }

    private fun systemTimeUs(): Long = System.nanoTime() / 1000
}
