package com.engabd.sendpin.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneSyncTest {

    @Test
    fun `offset is positive when leader is ahead`() {
        // Leader's clock is 1000 ms ahead of follower's.
        // t1 (leader sends) = 1000, t2 (follower receives) = 0
        // t3 (follower sends) = 10, t4 (leader receives) = 1010
        val sample = ClockSample(t1 = 1000, t2 = 0, t3 = 10, t4 = 1010)
        assertEquals(1000, sample.offsetMs)
        assertEquals(20, sample.delayMs)
    }

    @Test
    fun `offset is negative when leader is behind`() {
        // Leader's clock is 500 ms behind follower's.
        val sample = ClockSample(t1 = 0, t2 = 500, t3 = 510, t4 = 10)
        assertEquals(-500, sample.offsetMs)
        assertEquals(0, sample.delayMs)
    }

    @Test
    fun `follower playhead advances when leader is playing`() {
        val state = SyncState(
            trackId = "song1",
            playheadMs = 10_000,
            playing = true,
            serverTimeMs = 100_000,
            speed = 1f,
        )
        // Offset is 0 (clocks aligned). Follower's clock is 2000 ms later.
        val playhead = PhoneSync.followerPlayheadMs(state, offsetMs = 0, followerClockMs = 102_000)
        assertEquals(12_000, playhead)
    }

    @Test
    fun `follower playhead is frozen when leader is paused`() {
        val state = SyncState(
            trackId = "song1",
            playheadMs = 15_000,
            playing = false,
            serverTimeMs = 100_000,
        )
        val playhead = PhoneSync.followerPlayheadMs(state, offsetMs = 0, followerClockMs = 200_000)
        assertEquals(15_000, playhead)
    }

    @Test
    fun `follower playhead scales with speed`() {
        val state = SyncState(
            trackId = "song1",
            playheadMs = 10_000,
            playing = true,
            serverTimeMs = 100_000,
            speed = 2f,
        )
        val playhead = PhoneSync.followerPlayheadMs(state, offsetMs = 0, followerClockMs = 101_000)
        assertEquals(12_000, playhead)
    }

    @Test
    fun `returns null for negative elapsed time`() {
        val state = SyncState(
            trackId = "song1",
            playheadMs = 0,
            playing = true,
            serverTimeMs = 200_000,
        )
        // Follower clock is before the server time
        val playhead = PhoneSync.followerPlayheadMs(state, offsetMs = 0, followerClockMs = 100_000)
        assertNull(playhead)
    }

    @Test
    fun `returns null for implausible offset`() {
        val state = SyncState(
            trackId = "song1",
            playheadMs = 0,
            playing = true,
            serverTimeMs = 100_000,
        )
        // 31 second offset is implausible
        val playhead = PhoneSync.followerPlayheadMs(state, offsetMs = 31_000, followerClockMs = 100_000)
        assertNull(playhead)
    }

    @Test
    fun `inSync is true within 250 ms`() {
        assertTrue(PhoneSync.inSync(10_000, 10_200))
        assertTrue(PhoneSync.inSync(10_000, 9_800))
    }

    @Test
    fun `inSync is false beyond 250 ms`() {
        assertEquals(false, PhoneSync.inSync(10_000, 10_300))
        assertEquals(false, PhoneSync.inSync(10_000, 9_700))
    }

    @Test
    fun `encode and decode state round-trips`() {
        val state = SyncState("track1", "Song", "Artist", 42_000, true, 100_000, 1f)
        val encoded = PhoneSync.encodeState(state)
        val decoded = PhoneSync.decodeState(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `decode state returns null on garbage`() {
        assertNull(PhoneSync.decodeState("not json"))
    }

    @Test
    fun `encode and decode sample round-trips`() {
        val sample = ClockSample(1, 2, 3, 4)
        val encoded = PhoneSync.encodeSample(sample)
        val decoded = PhoneSync.decodeSample(encoded)
        assertEquals(sample, decoded)
    }
}