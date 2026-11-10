package com.engabd.sendpin.audio

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
    fun `a track played and served is one entry in the window, under its one key`() {
        val now = 100 * day
        val keyOf = { it: MaItem -> "${it.provider}:${it.itemId}" }
        // A window of two: "a" was played, "b" served, then "a" served again.
        val memory = RadioMemory.fromDisk(
            signals = listOf(
                TrackSignalEntity("subsonic:a", lastServedAt = now - 4_000),
                TrackSignalEntity("subsonic:b", lastServedAt = now - 3_000),
            ),
            clock = { now },
            keyOf = keyOf,
        )
        memory.served(listOf(memory.key(item("a"))))
        val limited = RadioMemory(linkedMapOf("subsonic:a" to now - 4_000, "subsonic:b" to now - 3_000), limit = 2, clock = { now }, keyOf = keyOf)
        limited.served(listOf(limited.key(item("a"))))
        // Still one slot each, so "b" is not pushed out of a window of two.
        assertTrue(limited.isBlocked(item("a")))
        assertTrue(limited.isBlocked(item("b")))
        assertTrue(memory.isBlocked(item("a")))
        assertFalse(memory.isBlocked(item("c")))
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
            signals = listOf(
                TrackSignalEntity("recent", lastServedAt = now - 60_000),
                TrackSignalEntity("old", lastServedAt = now - 3 * day),
                TrackSignalEntity("skipped", skips = 2, lastSkippedAt = now - day),
            ),
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
            signals = listOf(
                TrackSignalEntity("a", lastServedAt = now - 3_000),
                TrackSignalEntity("b", lastServedAt = now - 2_000),
                TrackSignalEntity("c", lastServedAt = now - 1_000),
            ),
            clock = { now },
        )
        val all = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(listOf("d"), memory.admit(all, need = 1).map { it.itemId })
        assertEquals(listOf("d", "a", "b"), memory.admit(all, need = 3).map { it.itemId })

        val narrow = RadioMemory.fromDisk(
            signals = listOf(TrackSignalEntity("a", lastServedAt = now - 3_000), TrackSignalEntity("b", lastServedAt = now - 2_000)),
            clock = { now },
        ).also { it.served(listOf("c")) }
        assertTrue(narrow.isBlocked(item("c")), "served this session counts at once")
    }
}
