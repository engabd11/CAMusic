package com.engabd.sendpin.local.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The year in review's SQL, against a real SQLite: that a year's window keeps its
 * plays in and its neighbours' out, and that the new rankings group the way the
 * screen describes them.
 *
 * Instrumented for the same reason as [LocalMediaDatabaseMigrationTest]: Room needs a
 * real database and this project has no Robolectric. The months and years are worked
 * out by `strftime(..., 'localtime')`, so the rows are placed in the device's own zone.
 */
@RunWith(AndroidJUnit4::class)
class PlayHistoryYearQueriesTest {

    private lateinit var db: LocalMediaDatabase
    private lateinit var dao: PlayHistoryDao
    private val zone = ZoneId.systemDefault()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LocalMediaDatabase::class.java,
        ).build()
        dao = db.playHistoryDao()
    }

    @After
    fun close() = db.close()

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun yearOf(y: Int): Pair<Long, Long> =
        LocalDateTime.of(y, 1, 1, 0, 0).atZone(zone).toInstant().toEpochMilli() to
            LocalDateTime.of(y + 1, 1, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun play(ts: Long, title: String, artist: String, album: String, trackId: String = title, ms: Long = 180_000) =
        dao.insert(
            PlayHistoryEntity(
                timestamp = ts, trackId = trackId, title = title, artist = artist, album = album,
                provider = "SUBSONIC", durationPlayedMs = ms,
            ),
        )

    @Test
    fun aYearKeepsItsOwnPlaysAndOnlyThose() = runBlocking {
        play(at(2024, 12, 31, 23, 59), "Before", "A", "X")
        play(at(2025, 1, 1, 0, 0), "First", "A", "X")
        play(at(2025, 12, 31, 23, 59), "Last", "B", "Y")
        play(at(2026, 1, 1, 0, 0), "After", "C", "Z")

        val (since, until) = yearOf(2025)
        assertEquals(2, dao.playCount(since, until))
        assertEquals(2, dao.distinctArtists(since, until))
        assertEquals(listOf("2025-01-01", "2025-12-31"), dao.dailyTotals(since, until).map { it.day })
        // Artist A was first heard in 2024, so only B is new in 2025.
        assertEquals(1, dao.newArtists(since, until))
        // The rolling form is unchanged: no upper bound by default.
        assertEquals(4, dao.playCount(0))
    }

    @Test
    fun monthsAndYearsComeOutInTheLocalCalendar() = runBlocking {
        play(at(2025, 3, 1), "One", "A", "X", ms = 60_000)
        play(at(2025, 3, 20), "Two", "A", "X", ms = 60_000)
        play(at(2025, 11, 5), "Three", "B", "Y", ms = 30_000)
        play(at(2023, 6, 1), "Old", "C", "Z")

        val (since, until) = yearOf(2025)
        val months = dao.monthlyTotals(since, until)
        assertEquals(listOf(3, 11), months.map { it.month })
        assertEquals(listOf(2, 1), months.map { it.plays })
        assertEquals(120_000L, months[0].listenedMs)
        assertEquals(listOf(2025, 2023), dao.yearsWithPlays())
    }

    @Test
    fun topSongsAndAlbumsGroupByNameAcrossLibraries() = runBlocking {
        val (since, until) = yearOf(2025)
        // The same song from two libraries, with two different track ids and a
        // difference in case: one song, three plays.
        play(at(2025, 2, 1), "Song", "Band", "Record", trackId = "nav-1")
        play(at(2025, 2, 2), "Song", "Band", "Record", trackId = "jf-9")
        play(at(2025, 2, 3), "song", "band", "record", trackId = "jf-9")
        play(at(2025, 2, 4), "Other", "Band", "Second")

        val tracks = dao.topTracks(since, until, limit = 5)
        assertEquals(2, tracks.size)
        assertEquals(3, tracks[0].plays)
        assertEquals("Song", tracks[0].title.replaceFirstChar { it.uppercase() })

        val albums = dao.topAlbums(since, until, limit = 5)
        assertEquals(listOf(3, 1), albums.map { it.plays })
    }
}
