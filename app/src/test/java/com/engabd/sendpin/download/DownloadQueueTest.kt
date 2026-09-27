package com.engabd.sendpin.download

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadQueueTest {

    private fun entry(id: String, provider: String = "subsonic") =
        QueuedDownload(itemId = id, provider = provider, name = "Track $id", url = "http://nas/rest/stream?id=$id")

    private fun tempQueue(): Pair<DownloadQueue, File> {
        val dir = Files.createTempDirectory("dlq").toFile()
        val file = File(dir, "queue.json")
        return DownloadQueue(file) to file
    }

    @Test
    fun `a queue survives being read back by a new instance`() {
        val (q, file) = tempQueue()
        q.add(listOf(entry("1"), entry("2")))
        assertEquals(listOf("1", "2"), DownloadQueue(file).load().map { it.itemId })
    }

    @Test
    fun `the same id from two libraries is two entries`() {
        val (q, _) = tempQueue()
        q.add(listOf(entry("1234", "plex"), entry("1234", "emby"), entry("1234", "plex")))
        assertEquals(listOf("plex", "emby"), q.load().map { it.provider })
    }

    @Test
    fun `removing the last entry deletes the file`() {
        val (q, file) = tempQueue()
        q.add(listOf(entry("1")))
        assertTrue(file.exists())
        q.remove("subsonic", "1")
        assertFalse(file.exists())
        assertEquals(emptyList(), q.load())
    }

    @Test
    fun `an entry rebuilds the item it was made from`() {
        val item = entry("7").copy(album = "Album", trackNumber = 3, discNumber = 2, parentId = "al-1").toItem()
        assertEquals("7", item.itemId)
        assertEquals("track", item.mediaType)
        assertEquals(3, item.trackNumber)
        assertEquals("al-1", item.parentId)
    }
}
