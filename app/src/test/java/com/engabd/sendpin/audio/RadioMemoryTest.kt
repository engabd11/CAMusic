package com.engabd.sendpin.audio

import com.engabd.sendpin.local.db.RecentPlay
import com.engabd.sendpin.local.db.TrackSignalEntity
import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the radio learns from the listener: skips, favourites and what was heard lately. */
class RadioMemoryTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun item(id: String, favorite: Boolean = false) = MaItem(
        itemId = id, provider = "subsonic", name = "Track $id", uri = id, mediaType = "track",
        subtitle = "Artist", image = null, duration = 200, favorite = favorite,
    )

    @Test
    fun `a press of next before 30 percent is a skip, and nothing else is`() {
        assertTrue(RadioMemory.isSkip(positionMs = 10_000, durationMs = 200_000))
        assertTrue(RadioMemory.isSkip(positionMs = 0, durationMs = 200_000))
        assertFalse(RadioMemory.isSkip(positionMs = 60_000, durationMs = 200_000), "exactly 30% is a listen")
        assertFalse(RadioMemory.isSkip(positionMs = 150_000, durationMs = 200_000))
        assertFalse(RadioMemory.isSkip(positionMs = 1_000, durationMs = 0), "no length, no verdict")
    }

    @Test
    fun `the history row's queue position is stripped, and only that`() {
        assertEquals("abc", RadioMemory.historyId("3-abc"))
        assertEquals("abc", RadioMemory.historyId("-1-abc"), "not found in the queue")
        assertEquals("12-34-ab", RadioMemory.historyId("7-12-34-ab"), "an id with dashes stays whole")
        assertEquals("f3a9-11ee-b962", RadioMemory.historyId("0-f3a9-11ee-b962"))
        assertEquals("abc-def", RadioMemory.historyId("abc-def"), "no position, nothing to strip")
    }

    @Test
    fun `a skip costs a track, more for more skips, and fades out over thirty days`() {
        val now = 100 * day
        val once = RadioMemory.skipPenalty(RadioMemory.Skip(1, now), now)
        val thrice = RadioMemory.skipPenalty(RadioMemory.Skip(3, now), now)
        val tenTimes = RadioMemory.skipPenalty(RadioMemory.Skip(10, now), now)
        assertEquals(RadioMemory.SKIP_PENALTY, once, 0.0001f)
        assertTrue(thrice > once)
        assertEquals(thrice, tenTimes, 0.0001f, "capped")
        assertEquals(once / 2, RadioMemory.skipPenalty(RadioMemory.Skip(1, now - 15 * day), now), 0.0001f)
        assertEquals(0f, RadioMemory.skipPenalty(RadioMemory.Skip(3, now - 31 * day), now))
    }

    @Test
    fun `adjust lifts a favourite and sinks a skipped or just-played track`() {
        val now = 100 * day
        val memory = RadioMemory.fromDisk(
            plays = listOf(RecentPlay("0-recent", now - 60_000), RecentPlay("1-old", now - 3 * day)),
            signals = listOf(TrackSignalEntity("skipped", skips = 2, lastSkippedAt = now - day)),
            clock = { now },
        )
        assertEquals(RadioMemory.FAVOURITE_BONUS, memory.adjust(item("fav", favorite = true)), 0.0001f)
        assertEquals(0f, memory.adjust(item("plain")))
        assertEquals(-RadioMemory.RECENT_PENALTY, memory.adjust(item("recent")), 0.0001f)
        assertEquals(0f, memory.adjust(item("old")), "older than a day: no penalty")
        assertTrue(memory.adjust(item("skipped")) < -RadioMemory.SKIP_PENALTY)
    }

    @Test
    fun `the window is the newest plays and serves, and it lets the oldest back when short`() {
        val now = 100 * day
        val memory = RadioMemory.fromDisk(
            plays = listOf(RecentPlay("0-a", now - 3_000), RecentPlay("0-b", now - 2_000)),
            signals = listOf(TrackSignalEntity("c", lastServedAt = now - 1_000)),
            clock = { now },
        )
        val all = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(listOf("d"), memory.admit(all, need = 1).map { it.itemId })
        assertEquals(listOf("d", "a", "b"), memory.admit(all, need = 3).map { it.itemId })

        val narrow = RadioMemory.fromDisk(
            plays = listOf(RecentPlay("0-a", now - 3_000), RecentPlay("0-b", now - 2_000)),
            signals = emptyList(),
            clock = { now },
        ).also { it.served(listOf("c")) }
        assertTrue(narrow.isBlocked(item("c")), "served this session counts at once")
    }
}
