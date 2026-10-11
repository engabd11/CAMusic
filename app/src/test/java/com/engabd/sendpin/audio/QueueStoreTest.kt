package com.engabd.sendpin.audio

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueStoreTest {

    private fun store(): Pair<QueueStore, File> {
        val file = File(Files.createTempDirectory("qs").toFile(), "last_queue.json")
        return QueueStore(file) to file
    }

    private fun track(id: String) = SavedTrack(id = id, title = "T$id", streamUrl = "http://nas/rest/stream?id=$id", scrobbleId = id, scrobbleProvider = "subsonic")

    @Test
    fun `a saved queue reads back whole, from a new instance`() {
        val (s, file) = store()
        s.save(SavedQueue(listOf(track("1"), track("2"), track("3")), index = 1, positionMs = 42_000, shuffle = true, repeat = "all"))
        val back = QueueStore(file).load()!!
        assertEquals(listOf("1", "2", "3"), back.tracks.map { it.id })
        assertEquals("2", back.current?.id)
        assertEquals(42_000, back.positionMs)
        assertEquals(true, back.shuffle)
        assertEquals("all", back.repeat)
    }

    @Test
    fun `a saved track becomes the same local track`() {
        val t = track("9").toLocalTrack()
        assertEquals("9", t.id)
        assertEquals("subsonic", t.scrobbleProvider)
        assertEquals(SavedTrack.of(t), track("9"))
    }

    @Test
    fun `nothing, an empty queue, or an index out of range restores nothing`() {
        val (s, file) = store()
        assertNull(s.load())
        s.save(SavedQueue(emptyList(), index = 0, positionMs = 0))
        assertNull(s.load())
        s.save(SavedQueue(listOf(track("1")), index = 5, positionMs = 0))
        assertNull(s.load())
        file.writeText("{ not json")
        assertNull(s.load())
    }

    @Test
    fun `a queue that played to its end is saved at the top, as Play would take it`() {
        assertEquals(0 to 0L, ResumePoint.of(ended = true, index = 3, positionMs = 69_999))
    }

    @Test
    fun `a paused or playing queue is saved where it is`() {
        assertEquals(3 to 69_999L, ResumePoint.of(ended = false, index = 3, positionMs = 69_999))
        assertEquals(1 to 42_000L, ResumePoint.of(ended = false, index = 1, positionMs = 42_000))
        // Nothing selected yet, or a position the player reports as negative before it has one.
        assertEquals(0 to 0L, ResumePoint.of(ended = false, index = -1, positionMs = -1))
    }
}
