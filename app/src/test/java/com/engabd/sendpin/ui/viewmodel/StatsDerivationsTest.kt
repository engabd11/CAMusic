package com.engabd.sendpin.ui.viewmodel

import com.engabd.sendpin.local.db.FormatPlayCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The figures the stats page derives rather than reads.
 *
 * These are the numbers a listener is most likely to argue with — a streak that says 4
 * when it felt like 5, a completion figure over 100% — so they are the ones worth
 * pinning. Everything here is a pure function; the queries themselves are SQL and are
 * exercised on-device.
 */
class StatsDerivationsTest {

    // --- windows ----------------------------------------------------------

    @Test
    fun `all time counts from the beginning`() {
        assertEquals(0L, StatsWindow.ALL.since(1_700_000_000_000L))
    }

    @Test
    fun `a seven day window starts seven days back`() {
        val now = 1_700_000_000_000L
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, StatsWindow.WEEK.since(now))
    }

    // --- formatting -------------------------------------------------------

    @Test
    fun `listening time reads in hours once there is an hour of it`() {
        assertEquals("2h 5m", formatListeningTime(2 * 3600_000L + 5 * 60_000L))
    }

    @Test
    fun `under an hour is minutes only, and zero is not a bare zero`() {
        assertEquals("12m", formatListeningTime(12 * 60_000L))
        assertEquals("0m", formatListeningTime(0))
    }

    @Test
    fun `a share rounds to a whole percent`() {
        assertEquals("68%", formatShare(0.6789f))
        assertEquals("100%", formatShare(1f))
        assertEquals("0%", formatShare(0f))
    }

    @Test
    fun `a format label carries rate and depth when it has them`() {
        assertEquals("FLAC 96/24", FormatPlayCount("flac", 96_000, 24, 3).label())
        assertEquals("FLAC 44.1/16", FormatPlayCount("flac", 44_100, 16, 3).label())
    }

    @Test
    fun `a format with no rate is just the codec, and no codec is Unknown`() {
        assertEquals("OPUS", FormatPlayCount("opus", 0, 0, 1).label())
        assertEquals("Unknown", FormatPlayCount(null, 0, 0, 1).label())
    }

    // --- day-of-week labels ----------------------------------------------

    @Test
    fun `day labels line up with SQLite's numbering, which starts on Sunday`() {
        // strftime('%w') is 0 = Sunday. Getting this backwards would put every
        // Saturday night's listening on a Sunday morning in the clock.
        assertEquals(7, DOW_LABELS.size)
        assertEquals("Sun", DOW_LABELS[0])
        assertEquals("Sat", DOW_LABELS[6])
    }
}

/**
 * Variety, streaks and completion — the three that are easy to get subtly wrong.
 */
class StatsScienceTest {

    private fun artist(name: String, plays: Int) =
        com.engabd.sendpin.local.db.ArtistPlayCount(name, plays)

    private fun completion(played: Long, duration: Long) =
        com.engabd.sendpin.local.db.Completion("t", "a", played, duration)

    // --- variety ----------------------------------------------------------

    @Test
    fun `one artist is zero bits of variety`() {
        assertEquals(0f, artistEntropy(listOf(artist("A", 20))), 1e-4f)
    }

    @Test
    fun `an even spread across eight artists is three bits`() {
        // log2(8) = 3. The whole reason this is reported in bits: the number means
        // something on its own, rather than only in comparison to another number.
        val even = (1..8).map { artist("A$it", 5) }
        assertEquals(3f, artistEntropy(even), 1e-3f)
    }

    @Test
    fun `a dominant artist pulls variety well below the even case`() {
        val lopsided = listOf(artist("A", 100)) + (1..7).map { artist("B$it", 1) }
        assertTrue(artistEntropy(lopsided) < 1f)
    }

    @Test
    fun `no plays is zero rather than a division by zero`() {
        assertEquals(0f, artistEntropy(emptyList()), 1e-6f)
    }

    // --- streaks ----------------------------------------------------------

    @Test
    fun `consecutive days make one streak`() {
        val days = listOf("2026-08-01", "2026-08-02", "2026-08-03")
        assertEquals(3, longestStreak(days))
    }

    @Test
    fun `a gap breaks the streak and the longest run wins`() {
        val days = listOf("2026-08-01", "2026-08-02", "2026-08-05", "2026-08-06", "2026-08-07")
        assertEquals(3, longestStreak(days))
    }

    @Test
    fun `a repeated day is still one day`() {
        // Several plays on one day come back as one row from SQL, but a caller passing
        // duplicates must not be able to inflate a streak.
        assertEquals(1, longestStreak(listOf("2026-08-01", "2026-08-01", "2026-08-01")))
    }

    @Test
    fun `no days is no streak`() {
        assertEquals(0, longestStreak(emptyList()))
    }

    @Test
    fun `a streak that ended weeks ago is not the current one`() {
        assertEquals(0, currentStreak(listOf("2020-01-01", "2020-01-02")))
    }

    @Test
    fun `a streak running up to today is current`() {
        val today = java.time.LocalDate.now()
        val days = (0L..3L).map { today.minusDays(it).toString() }
        assertEquals(4, currentStreak(days))
    }

    @Test
    fun `yesterday still counts, so a streak is not broken before breakfast`() {
        val yesterday = java.time.LocalDate.now().minusDays(1)
        val days = listOf(yesterday.minusDays(1).toString(), yesterday.toString())
        assertEquals(2, currentStreak(days))
    }

    // --- completion -------------------------------------------------------

    @Test
    fun `completion is the median share, not the mean`() {
        // Four full plays and one ten-second sample. A mean would report 82%; the
        // median says what a typical play actually looked like.
        val rows = List(4) { completion(300_000, 300_000) } + completion(10_000, 300_000)
        val median = medianCompletion(rows)!!
        assertTrue(median > 0.9f, "median was $median")
    }

    @Test
    fun `a play logged past the end is capped at whole`() {
        // The position poll can land after the track ended; 103% is not a fact.
        assertEquals(1f, medianCompletion(listOf(completion(310_000, 300_000)))!!, 1e-4f)
    }

    @Test
    fun `nothing recorded is null rather than zero`() {
        assertNull(medianCompletion(emptyList()))
    }

    // --- lossless ---------------------------------------------------------

    @Test
    fun `lossless share counts the lossless codecs`() {
        val formats = listOf(
            FormatPlayCount("flac", 44_100, 16, 3),
            FormatPlayCount("mp3", 44_100, 16, 1),
        )
        assertEquals(0.75f, losslessShare(formats)!!, 1e-4f)
    }

    @Test
    fun `an unrecorded codec is excluded rather than counted as lossy`() {
        val formats = listOf(
            FormatPlayCount("flac", 44_100, 16, 1),
            FormatPlayCount(null, 0, 0, 99),
        )
        assertEquals(1f, losslessShare(formats)!!, 1e-4f)
    }

    @Test
    fun `no codecs at all is null, which is not the same as zero percent`() {
        assertNull(losslessShare(listOf(FormatPlayCount(null, 0, 0, 5))))
    }
}
