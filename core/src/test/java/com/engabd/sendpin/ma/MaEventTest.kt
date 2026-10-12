package com.engabd.sendpin.ma

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Telling one Music Assistant event from another.
 *
 * The app used to decide what an event meant by looking for the substrings "player"
 * or "queue" anywhere in the frame's JSON, so every event hit the same branch. That
 * made `queue_items_updated` — the one that says the queue's *contents* changed —
 * indistinguishable from the `queue_time_updated` MA emits about once a second, and
 * it is why adding an album left an open queue panel showing the old list.
 */
class MaEventTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun event(body: String) = MaParse.event(json.parseToJsonElement(body).jsonObject)

    @Test
    fun `an event carries its name and the object it is about`() {
        val e = event("""{"event": "queue_items_updated", "object_id": "kitchen", "data": {}}""")
        assertEquals("queue_items_updated", e?.name)
        assertEquals("kitchen", e?.objectId)
    }

    @Test
    fun `a queue items update is a contents change, a time update is not`() {
        assertTrue(event("""{"event": "queue_items_updated"}""")!!.changesQueueContents)
        assertTrue(event("""{"event": "queue_added"}""")!!.changesQueueContents)
        // The noisy one. Reloading the item list on every tick would be a fetch a
        // second per active queue.
        assertFalse(event("""{"event": "queue_time_updated"}""")!!.changesQueueContents)
        assertFalse(event("""{"event": "player_updated"}""")!!.changesQueueContents)
    }

    @Test
    fun `player and queue events both drive the metadata refresh`() {
        assertTrue(event("""{"event": "player_updated"}""")!!.isPlayerOrQueue)
        assertTrue(event("""{"event": "queue_updated"}""")!!.isPlayerOrQueue)
        assertTrue(event("""{"event": "queue_time_updated"}""")!!.isPlayerOrQueue)
        // Something else entirely — a library sync, say — must not trigger a poll.
        assertFalse(event("""{"event": "media_item_added"}""")!!.isPlayerOrQueue)
    }

    @Test
    fun `a frame that is not an event parses to nothing`() {
        assertNull(event("""{"message_id": "3", "result": []}"""))
        // An `event` key of the wrong shape must not throw — the socket carries
        // whatever the server feels like sending.
        assertNull(event("""{"event": {"nested": "object"}}"""))
    }

    @Test
    fun `an event with no object id is not attributed to a queue`() {
        val e = event("""{"event": "queue_items_updated"}""")
        assertNull(e?.objectId)
    }

    @Test
    fun `the playhead events are told apart`() {
        assertTrue(event("""{"event": "queue_updated"}""")!!.isQueueUpdated)
        assertTrue(event("""{"event": "queue_added"}""")!!.isQueueUpdated)
        assertTrue(event("""{"event": "queue_items_updated"}""")!!.isQueueUpdated)
        assertFalse(event("""{"event": "queue_time_updated"}""")!!.isQueueUpdated)
        assertTrue(event("""{"event": "queue_time_updated"}""")!!.isQueueTime)
        assertTrue(event("""{"event": "player_updated"}""")!!.isPlayerUpdated)
        assertFalse(event("""{"event": "player_updated"}""")!!.isQueueUpdated)
    }

    @Test
    fun `queue_time_updated carries elapsed seconds`() {
        val e = event("""{"event": "queue_time_updated", "object_id": "kitchen", "data": 12.345}""")!!
        assertEquals(12_345L, MaParse.queueTimeMs(e))
        assertNull(MaParse.queueTimeMs(event("""{"event": "queue_time_updated", "data": {}}""")!!))
    }

    @Test
    fun `queue_updated carries a whole queue`() {
        val e = event(
            """{"event": "queue_updated", "object_id": "kitchen", "data": {
                "queue_id": "kitchen", "state": "playing", "elapsed_time": 42.5,
                "elapsed_time_last_updated": 1757700000.25,
                "current_item": {"queue_item_id": "item-9", "duration": 200}
            }}"""
        )!!
        val q = MaParse.queue(e.data!!.jsonObject)!!
        assertEquals("kitchen", q.queueId)
        assertTrue(q.isPlaying)
        assertEquals(42_500L, q.elapsedMs)
        assertEquals(1757700000.25, q.elapsedTimeLastUpdated)
        assertEquals("item-9", q.currentQueueItemId)
        assertEquals(200_000L, q.currentItemDurationMs)
    }

    @Test
    fun `player_updated carries a whole player`() {
        val e = event(
            """{"event": "player_updated", "object_id": "up1", "data": {
                "player_id": "up1", "playback_state": "paused", "display_name": "Kitchen"
            }}"""
        )!!
        val p = MaParse.player(e.data!!.jsonObject)!!
        assertEquals("up1", p.playerId)
        assertFalse(p.isPlaying)
    }
}
