package com.engabd.sendpin.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollaborativeQueueTest {

    @Test
    fun `encode and decode URL round-trips`() {
        val url = CollaborativeQueue.encodeQueueUrl("192.168.0.42", 8080)
        assertEquals("http://192.168.0.42:8080", url)
        val decoded = CollaborativeQueue.decodeQueueUrl(url)
        assertEquals("192.168.0.42" to 8080, decoded)
    }

    @Test
    fun `decode rejects non-http URLs`() {
        assertNull(CollaborativeQueue.decodeQueueUrl("https://example.com:8080"))
        assertNull(CollaborativeQueue.decodeQueueUrl("ftp://192.168.0.1:21"))
    }

    @Test
    fun `decode rejects invalid ports`() {
        assertNull(CollaborativeQueue.decodeQueueUrl("http://192.168.0.1:0"))
        assertNull(CollaborativeQueue.decodeQueueUrl("http://192.168.0.1:99999"))
        assertNull(CollaborativeQueue.decodeQueueUrl("http://192.168.0.1:abc"))
    }

    @Test
    fun `decode rejects URLs without port`() {
        assertNull(CollaborativeQueue.decodeQueueUrl("http://192.168.0.1"))
    }

    @Test
    fun `encode and decode AddTrack event`() {
        val event = QueueEvent.AddTrack(title = "Blue in Green", artist = "Miles Davis", trackId = "song1")
        val encoded = CollaborativeQueue.encodeEvent(event)
        val decoded = CollaborativeQueue.decodeEvent(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `encode and decode RemoveTrack event`() {
        val event = QueueEvent.RemoveTrack(index = 2)
        val encoded = CollaborativeQueue.encodeEvent(event)
        val decoded = CollaborativeQueue.decodeEvent(encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `encode and decode QueueState event`() {
        val state = QueueEvent.QueueState(
            tracks = listOf(
                QueueTrack("1", "Song A", "Artist A", 180_000),
                QueueTrack("2", "Song B", "Artist B", 240_000),
            ),
            currentIndex = 0,
        )
        val encoded = CollaborativeQueue.encodeEvent(state)
        val decoded = CollaborativeQueue.decodeEvent(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `decode returns null on garbage`() {
        assertNull(CollaborativeQueue.decodeEvent("not json"))
    }

    @Test
    fun `decode returns null on unknown event shape`() {
        assertNull(CollaborativeQueue.decodeEvent("""{"unknown": true}"""))
    }
}