package com.engabd.sendpin.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The arithmetic behind the seek bar.
 *
 * The bar used to render `track_progress` verbatim, as though the reading described
 * the instant it was parsed. It doesn't — it describes `metadata.timestamp`, which by
 * the time the message has crossed the network is already in the past. Rendering a
 * stale number as "now" is what made the bar run ahead and then get yanked back on the
 * next message.
 */
class ProgressProjectionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun anchor(
        positionMs: Long = 0,
        atLocalUs: Long = 0,
        speedMilli: Long = 1000,
        durationMs: Long = 0,
    ) = ProgressProjection.Anchor(positionMs, atLocalUs, speedMilli, durationMs)

    @Test
    fun `a reading is aged by how long ago it was true`() {
        // 10s into the track, as of 2s ago → 12s now. Reading it as 10s is the bug.
        val a = anchor(positionMs = 10_000, atLocalUs = 1_000_000)
        assertEquals(12_000, ProgressProjection.project(a, nowLocalUs = 3_000_000, playing = true))
    }

    @Test
    fun `a fresh reading is left alone`() {
        val a = anchor(positionMs = 10_000, atLocalUs = 1_000_000)
        assertEquals(10_000, ProgressProjection.project(a, nowLocalUs = 1_000_000, playing = true))
    }

    @Test
    fun `paused holds at the anchor instead of running on`() {
        val a = anchor(positionMs = 10_000, atLocalUs = 1_000_000)
        assertEquals(10_000, ProgressProjection.project(a, nowLocalUs = 9_000_000, playing = false))
    }

    @Test
    fun `speed is the spec's thousandths, not a bare multiplier`() {
        // playback_speed 1500 = 1.5x, so 2s of wall clock is 3s of track.
        val a = anchor(positionMs = 0, atLocalUs = 0, speedMilli = 1500)
        assertEquals(3_000, ProgressProjection.project(a, nowLocalUs = 2_000_000, playing = true))
    }

    @Test
    fun `the projection is clamped to the track when its length is known`() {
        val a = anchor(positionMs = 100_000, atLocalUs = 0, durationMs = 120_000)
        // Ten minutes past the anchor would run far off the end of a two-minute track.
        assertEquals(120_000, ProgressProjection.project(a, nowLocalUs = 600_000_000, playing = true))
    }

    @Test
    fun `an unknown duration is projected but never negative`() {
        val a = anchor(positionMs = 5_000, atLocalUs = 10_000_000, durationMs = 0)
        assertEquals(5_000, ProgressProjection.project(a, nowLocalUs = 0, playing = true))
    }

    @Test
    fun `a timestamp slightly in the future does not run the bar backwards`() {
        // A converging clock filter can put the anchor a hair ahead of now. Ticking
        // down would look like a fault; standing still for a moment does not.
        val a = anchor(positionMs = 30_000, atLocalUs = 5_000_000)
        assertEquals(30_000, ProgressProjection.project(a, nowLocalUs = 4_900_000, playing = true))
    }

    // ── playback_speed parsing ──────────────────────────────────────────────

    @Test
    fun `playback_speed is read as thousandths`() {
        val p = json.decodeFromString(
            MetadataProgressPayload.serializer(),
            """{"track_progress":1000,"track_duration":2000,"playback_speed":1000}""",
        )
        assertEquals(1000L, p.speedMilli)
    }

    @Test
    fun `a float playback_speed does not take the whole message down with it`() {
        // The spec says integer, but MA is documented to send floats where it says
        // integer (see docs/protocol-alignment.md and FlexibleLongSerializer). A strict
        // Int here threw, and the throw discarded the entire `server/state` — title,
        // artist and progress lost over one awkward field.
        val msg = SendspinIncoming.parse(
            """{"type":"server/state","payload":{"metadata":{"title":"Song",
               "timestamp":1234567,"progress":{"track_progress":5000.0,
               "track_duration":180000.0,"playback_speed":1000.0}}}}""",
            json,
        )
        val state = assertIs<SendspinIncoming.ServerState>(msg)
        val meta = state.payload.metadata
        assertEquals("Song", meta?.title)
        assertEquals(1234567L, meta?.timestamp)
        assertEquals(5000L, meta?.progress?.trackProgress)
        assertEquals(1000L, meta?.progress?.speedMilli)
    }

    @Test
    fun `a bare 1 is read as normal speed, not one-thousandth`() {
        // Sending "1" for normal speed is the obvious mistake to make with a field
        // documented as a multiplier x1000. Taken literally the bar would not move.
        val p = json.decodeFromString(
            MetadataProgressPayload.serializer(),
            """{"track_progress":0,"playback_speed":1}""",
        )
        assertEquals(1000L, p.speedMilli)
    }

    @Test
    fun `a missing or nonsense playback_speed falls back to normal`() {
        val absent = json.decodeFromString(
            MetadataProgressPayload.serializer(), """{"track_progress":0}""",
        )
        assertEquals(1000L, absent.speedMilli)
        val zero = json.decodeFromString(
            MetadataProgressPayload.serializer(), """{"track_progress":0,"playback_speed":0}""",
        )
        assertEquals(1000L, zero.speedMilli)
    }
}
