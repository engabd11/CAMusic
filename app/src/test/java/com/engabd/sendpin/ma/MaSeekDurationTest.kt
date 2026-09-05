package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which duration the seek bar is measured against.
 *
 * Music Assistant validates a seek against `current_item.duration` — the *queue
 * item's* length — and refuses anything past it ("Can not seek outside of duration
 * range"). The app used to draw the bar, and clamp the seek, against the player's
 * `current_media.duration` instead: a different object, updated on its own schedule,
 * optional in MA's own model, and routinely absent on a group member. When the two
 * disagreed the seek was refused, the refusal was discarded, and the bar sat on a
 * position playback had never reached until the freeze watchdog gave up and snapped
 * it forward — reported as "I seek to 1:49 and it takes me to 3:01".
 *
 * `massdroid_native` reaches for the queue first for the same reason (see
 * `PlayerRepositoryImpl.currentTrackDurationFor`), which is why its player does not
 * have this bug.
 */
class MaSeekDurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun queue(body: String) =
        MaParse.queues(json.parseToJsonElement("[$body]")).single()

    private fun player(body: String) =
        MaParse.players(json.parseToJsonElement("[$body]")).single()

    @Test
    fun `the queue item's own duration is read, in seconds`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {"queue_item_id": "q1", "duration": 332}
            }
            """
        )
        assertEquals(332_000L, q.currentItemDurationMs)
    }

    @Test
    fun `a fractional duration survives`() {
        val q = queue(
            """
            {"queue_id": "p1", "current_item": {"queue_item_id": "q1", "duration": 314.5}}
            """
        )
        assertEquals(314_500L, q.currentItemDurationMs)
    }

    @Test
    fun `the media item stands in when the queue item carries no duration`() {
        val q = queue(
            """
            {
              "queue_id": "p1",
              "current_item": {
                "queue_item_id": "q1",
                "media_item": {"item_id": "7", "provider": "library", "name": "Song", "media_type": "track", "duration": 200}
              }
            }
            """
        )
        assertEquals(200_000L, q.currentItemDurationMs)
    }

    @Test
    fun `no duration at all is unknown, not zero`() {
        // A live radio stream. MA sends 0 for it, and 0 must not read as a real
        // length — clamping a seek to it would pin every seek to the start.
        val q = queue("""{"queue_id": "p1", "current_item": {"queue_item_id": "q1", "duration": 0}}""")
        assertNull(q.currentItemDurationMs)
    }

    @Test
    fun `the queue wins over the player's current_media`() {
        // The case that produced the bug: `current_media` still describing the track
        // that just finished, while the queue has already moved on.
        val q = queue("""{"queue_id": "p1", "current_item": {"queue_item_id": "q1", "duration": 332}}""")
        val p = player(
            """
            {"player_id": "p1", "name": "Kitchen",
             "current_media": {"title": "Previous", "duration": 200}}
            """
        )
        assertEquals(332_000L, seekableDurationMs(q, p))
    }

    @Test
    fun `the player stands in when the queue knows nothing`() {
        val p = player(
            """
            {"player_id": "p1", "name": "Kitchen",
             "current_media": {"title": "Song", "duration": 200}}
            """
        )
        assertEquals(200_000L, seekableDurationMs(null, p))
    }

    @Test
    fun `a group member with no current_media of its own still gets the queue's answer`() {
        // massdroid records `current_media.duration` being 0 for non-leader children;
        // reading only that left the clamp coercing every seek to zero.
        val q = queue("""{"queue_id": "leader", "current_item": {"queue_item_id": "q1", "duration": 332}}""")
        val member = player("""{"player_id": "member", "name": "Bedroom", "synced_to": "leader"}""")
        assertEquals(332_000L, seekableDurationMs(q, member))
    }

    @Test
    fun `a dormant queue does not lend its duration to an external source`() {
        // The speaker has been switched to Spotify Connect. Its MA queue still holds
        // the last thing MA played on it, which says nothing about what is playing
        // now — `current_media` is the only honest reading left.
        val q = queue(
            """
            {"queue_id": "p1", "active": false, "current_item": {"queue_item_id": "q1", "duration": 332}}
            """
        )
        val p = player(
            """
            {"player_id": "p1", "name": "Kitchen",
             "current_media": {"title": "Something else", "duration": 200}}
            """
        )
        assertEquals(200_000L, seekableDurationMs(q, p))
    }

    @Test
    fun `a server that omits active is taken at its word about the queue`() {
        // Absent has to read as "can't tell", not "dormant", or the whole fix would
        // switch itself off against a server that never sends the field.
        val q = queue("""{"queue_id": "p1", "current_item": {"queue_item_id": "q1", "duration": 332}}""")
        val p = player("""{"player_id": "p1", "name": "Kitchen", "current_media": {"title": "S", "duration": 200}}""")
        assertEquals(332_000L, seekableDurationMs(q, p))
    }

    @Test
    fun `nobody knows is zero`() {
        val member = player("""{"player_id": "member", "name": "Bedroom"}""")
        assertEquals(0L, seekableDurationMs(null, member))
    }

    @Test
    fun `the clamp keeps a second of headroom off the end`() {
        assertEquals(331_000L, maxSeekPositionMs(332_000L))
    }

    @Test
    fun `a track shorter than the margin clamps to the start rather than going negative`() {
        assertEquals(0L, maxSeekPositionMs(400L))
    }

    @Test
    fun `an unknown duration does not clamp the seek away`() {
        // Zero means "nobody knows", and a seek the user asked for is better sent and
        // possibly refused than silently dropped — which is what `if (dur <= 0)
        // return` used to do to every seek on an item MA had no duration for.
        assertEquals(Long.MAX_VALUE, maxSeekPositionMs(0L))
    }
}
