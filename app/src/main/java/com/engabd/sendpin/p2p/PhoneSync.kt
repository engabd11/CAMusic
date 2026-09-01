package com.engabd.sendpin.p2p

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Phone-to-phone listening sync: two phones on the same network play the
 * same album in lockstep, each with their own Hue show.
 *
 * The protocol reuses the same four-point clock exchange as the Sendspin
 * client, but over a plain WebSocket rather than the Sendspin protocol:
 * the leader publishes its playback state and a clock sample, and the
 * follower aligns its [com.engabd.sendpin.audio.LocalPlayer] to the
 * leader's timeline.
 *
 * This file holds the pure state and offset logic. The network layer
 * ([PhoneSyncServer] / [PhoneSyncClient]) is the thin shell around it.
 */

/**
 * What the leader tells a follower about where it is.
 *
 * Everything is in wall-clock milliseconds so the follower can compute the
 * offset once and apply it to its own clock. The [playheadMs] is the
 * position within the track at [serverTimeMs].
 */
@Serializable
data class SyncState(
    val trackId: String,
    val trackTitle: String = "",
    val artist: String? = null,
    /** Position within the track, in milliseconds. */
    val playheadMs: Long,
    /** Whether the leader is currently playing (vs paused). */
    val playing: Boolean,
    /** The leader's wall-clock time when this state was captured, epoch ms. */
    val serverTimeMs: Long,
    /** The leader's playback speed (1.0 = normal, 1.5 = speed up). */
    val speed: Float = 1f,
)

/**
 * One round-trip clock measurement, the four timestamps the NTP-style
 * exchange needs.
 *
 * t1: leader sends (its clock)
 * t2: follower receives (its clock)
 * t3: follower sends reply (its clock)
 * t4: leader receives (its clock)
 *
 * Offset = ((t2 - t1) + (t3 - t4)) / 2
 * Delay = (t4 - t1) - (t3 - t2)
 */
@Serializable
data class ClockSample(
    val t1: Long,
    val t2: Long,
    val t3: Long,
    val t4: Long,
) {
    /**
     * The estimated offset: leader's clock minus follower's clock, in ms.
     *
     * Positive means the leader is ahead. The follower adds this to its own
     * clock to get the leader's time.
     */
    val offsetMs: Long get() = ((t2 - t1) + (t3 - t4)) / 2

    /** The round-trip delay, in ms. A large delay means the measurement is noisy. */
    val delayMs: Long get() = (t4 - t1) - (t3 - t2)
}

/**
 * Pure offset computation and sync logic, testable without a network.
 */
object PhoneSync {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeState(state: SyncState): String = json.encodeToString(SyncState.serializer(), state)
    fun decodeState(raw: String): SyncState? =
        runCatching { json.decodeFromString(SyncState.serializer(), raw) }.getOrNull()

    fun encodeSample(sample: ClockSample): String = json.encodeToString(ClockSample.serializer(), sample)
    fun decodeSample(raw: String): ClockSample? =
        runCatching { json.decodeFromString(ClockSample.serializer(), raw) }.getOrNull()

    /**
     * Where the follower should be, in the leader's timeline.
     *
     * Given the leader's last-known state and the measured offset, compute
     * the playhead the follower should seek to right now. If the leader is
     * playing, the playhead has advanced by the elapsed wall-clock time
     * since [SyncState.serverTimeMs], scaled by [SyncState.speed].
     *
     * Returns null if the state is stale (negative elapsed) or the offset
     * is implausible (more than 30 seconds, the same guard the Kalman filter
     * uses for the Sendspin path).
     */
    fun followerPlayheadMs(
        state: SyncState,
        offsetMs: Long,
        followerClockMs: Long,
    ): Long? {
        val leaderNowMs = followerClockMs + offsetMs
        val elapsedMs = leaderNowMs - state.serverTimeMs
        if (elapsedMs < 0) return null
        if (abs(offsetMs) > 30_000) return null

        return if (state.playing) {
            state.playheadMs + (elapsedMs * state.speed).toLong()
        } else {
            state.playheadMs
        }
    }

    /**
     * Whether the follower is close enough to the leader that it does not
     * need to re-seek. 250 ms is the threshold a listener does not notice
     * as a gap when two speakers are in different rooms.
     */
    fun inSync(leaderPlayheadMs: Long, followerPlayheadMs: Long): Boolean =
        abs(leaderPlayheadMs - followerPlayheadMs) <= 250
}