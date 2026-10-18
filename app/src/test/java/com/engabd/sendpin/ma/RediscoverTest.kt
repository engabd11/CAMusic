package com.engabd.sendpin.ma

import com.engabd.sendpin.local.db.PlayRow
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Rediscover shelves' rules: date windows, thresholds and tiers, all against a fixed clock. */
class RediscoverTest {

    private val zone = ZoneId.of("UTC")
    private val day = Rediscover.DAY_MS

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun row(ts: Long, title: String = "Song", artist: String = "Band", album: String = "Album") =
        PlayRow(ts, title, artist, album)

    private fun album(id: String, name: String = id, artist: String? = "Band", added: Long? = null) =
        MaItem(id, "p", name, id, "album", artist, null, null, addedAtMs = added)

    private fun track(id: String, name: String = id) =
        MaItem(id, "p", name, "uri:$id", "track", "Band", null, null)

    // On this day ----------------------------------------------------------

    @Test
    fun `on this day finds plays a year ago and names that year`() {
        val result = Rediscover.onThisDay(listOf(row(at(2025, 6, 15))), at(2026, 6, 15), zone)!!
        assertEquals(2025, result.year)
        assertEquals("On this day in 2025", Rediscover.onThisDayTitle(result.year))
    }

    @Test
    fun `window reaches one day either side but not two`() {
        val now = at(2026, 6, 15)
        assertTrue(Rediscover.onThisDay(listOf(row(at(2025, 6, 14))), now, zone) != null)
        assertTrue(Rediscover.onThisDay(listOf(row(at(2025, 6, 16))), now, zone) != null)
        assertNull(Rediscover.onThisDay(listOf(row(at(2025, 6, 13))), now, zone))
        assertNull(Rediscover.onThisDay(listOf(row(at(2025, 6, 17))), now, zone))
    }

    @Test
    fun `this year is never searched`() {
        assertNull(Rediscover.onThisDay(listOf(row(at(2026, 6, 15, 8))), at(2026, 6, 15), zone))
    }

    @Test
    fun `most recent earlier year with plays wins`() {
        val rows = listOf(row(at(2023, 6, 15), "Old"), row(at(2024, 6, 15), "Newer"))
        val result = Rediscover.onThisDay(rows, at(2026, 6, 15), zone)!!
        assertEquals(2024, result.year)
        assertEquals(listOf("Newer"), result.picks.map { it.title })
    }

    @Test
    fun `leap day today falls back to the 28th in a common year`() {
        // 2028-02-29 today; in 2027 (no leap day) the anchor is Feb 28, so Mar 1 is in reach.
        val result = Rediscover.onThisDay(listOf(row(at(2027, 3, 1))), at(2028, 2, 29), zone)!!
        assertEquals(2027, result.year)
        // And in a leap year the leap day itself is the anchor.
        assertEquals(2024, Rediscover.onThisDay(listOf(row(at(2024, 2, 29))), at(2028, 2, 29), zone)!!.year)
    }

    @Test
    fun `the 1st of March reaches back to a leap day`() {
        assertEquals(2024, Rediscover.onThisDay(listOf(row(at(2024, 2, 29))), at(2026, 3, 1), zone)!!.year)
        // 2025 has no Feb 29: Feb 27 is two days from the anchor's Feb 28 start, out of reach.
        assertNull(Rediscover.onThisDay(listOf(row(at(2025, 2, 27))), at(2026, 3, 1), zone))
    }

    @Test
    fun `31 December reaches into the following January`() {
        // Window for 2025 is 30 Dec 2025 to 1 Jan 2026.
        val result = Rediscover.onThisDay(listOf(row(at(2026, 1, 1))), at(2026, 12, 31), zone)!!
        assertEquals(2025, result.year)
    }

    @Test
    fun `1 January reaches back into the previous December`() {
        // Window for 2025 is 31 Dec 2024 to 2 Jan 2025.
        val result = Rediscover.onThisDay(listOf(row(at(2024, 12, 31))), at(2026, 1, 1), zone)!!
        assertEquals(2025, result.year)
    }

    @Test
    fun `on this day with no history is nothing`() {
        assertNull(Rediscover.onThisDay(emptyList(), at(2026, 6, 15), zone))
    }

    @Test
    fun `on this day ranks by plays and merges the same song`() {
        val rows = listOf(
            row(at(2025, 6, 15), "A", "X"), row(at(2025, 6, 14), "b", "x"), row(at(2025, 6, 15), "B", "X"),
        )
        val picks = Rediscover.onThisDay(rows, at(2026, 6, 15), zone)!!.picks
        assertEquals(listOf("B", "A"), picks.map { it.title })
        assertEquals(2, picks.first().plays)
    }

    // Forgotten favourites -------------------------------------------------

    private fun plays(title: String, n: Int, lastDaysAgo: Int, now: Long) =
        (0 until n).map { row(now - (lastDaysAgo + it * 3L) * day, title) }

    @Test
    fun `five plays and quiet for over 90 days is a forgotten favourite`() {
        val now = at(2026, 6, 15)
        assertEquals(listOf("Fave"), Rediscover.forgottenFavourites(plays("Fave", 5, 91, now), now).map { it.title })
    }

    @Test
    fun `a play inside 90 days keeps a song off the shelf`() {
        val now = at(2026, 6, 15)
        assertTrue(Rediscover.forgottenFavourites(plays("Fave", 6, 89, now), now).isEmpty())
        // Exactly 90 days ago still counts as recent.
        assertTrue(Rediscover.forgottenFavourites(plays("Fave", 6, 90, now), now).isEmpty())
    }

    @Test
    fun `four plays is not a favourite when the tier is five`() {
        val now = at(2026, 6, 15)
        val rows = plays("Fave", 5, 120, now) + plays("Meh", 4, 120, now) +
            (1..20).flatMap { plays("Other$it", 5, 120, now) }
        val titles = Rediscover.forgottenFavourites(rows, now, limit = 100).map { it.title }
        assertTrue("Meh" !in titles)
        assertTrue("Fave" in titles)
    }

    @Test
    fun `tier drops for a light listener but never below three`() {
        assertEquals(5, Rediscover.favouriteTier(listOf(9, 8, 7, 1, 1)))
        assertEquals(3, Rediscover.favouriteTier(listOf(1, 1, 2, 3)))
        assertEquals(3, Rediscover.favouriteTier(listOf(1, 1, 1)))
        assertEquals(5, Rediscover.favouriteTier(emptyList()))
        val now = at(2026, 6, 15)
        val light = plays("Top", 3, 100, now) + plays("Two", 2, 100, now) + plays("One", 1, 100, now)
        assertEquals(listOf("Top"), Rediscover.forgottenFavourites(light, now).map { it.title })
    }

    @Test
    fun `forgotten favourites with no history is empty`() {
        assertTrue(Rediscover.forgottenFavourites(emptyList(), at(2026, 6, 15)).isEmpty())
    }

    // Never played ---------------------------------------------------------

    @Test
    fun `only unplayed albums added more than 30 days ago qualify`() {
        val now = at(2026, 6, 15)
        val albums = listOf(
            album("old", added = now - 31 * day),
            album("fresh", added = now - 29 * day),
            album("boundary", added = now - 30 * day),
            album("undated", added = null),
        )
        assertEquals(listOf("old"), Rediscover.neverPlayed(albums, emptyList(), now, seed = 1).map { it.itemId })
    }

    @Test
    fun `an album in the history is excluded`() {
        val now = at(2026, 6, 15)
        val a = album("a", "Heard", "Band", now - 40 * day)
        val b = album("b", "Unheard", "Band", now - 40 * day)
        val out = Rediscover.neverPlayed(listOf(a, b), listOf(row(now - day, album = "heard")), now, seed = 1)
        assertEquals(listOf("b"), out.map { it.itemId })
    }

    @Test
    fun `same album name by another artist is not a match`() {
        val now = at(2026, 6, 15)
        val a = album("a", "Greatest Hits", "Other", now - 40 * day)
        val played = listOf(row(now - day, artist = "Band", album = "Greatest Hits"))
        assertEquals(1, Rediscover.neverPlayed(listOf(a), played, now, seed = 1).size)
    }

    @Test
    fun `never played is stable for a seed, capped, and empty without albums`() {
        val now = at(2026, 6, 15)
        val albums = (1..30).map { album("a$it", added = now - 60 * day) }
        val one = Rediscover.neverPlayed(albums, emptyList(), now, seed = 7)
        assertEquals(one, Rediscover.neverPlayed(albums, emptyList(), now, seed = 7))
        assertEquals(Rediscover.SHELF_LIMIT, one.size)
        assertTrue(Rediscover.neverPlayed(emptyList(), emptyList(), now, seed = 7).isEmpty())
    }

    // Deep cuts ------------------------------------------------------------

    @Test
    fun `top artists need enough plays and are capped at three`() {
        val out = Rediscover.topArtists(listOf("A" to 40, "B" to 30, "C" to 20, "D" to 10, "E" to 4, "" to 90))
        assertEquals(listOf("A", "B", "C"), out)
        assertTrue(Rediscover.topArtists(listOf("E" to 4)).isEmpty())
    }

    @Test
    fun `deep cuts are tracks played once or never, unplayed first`() {
        val now = at(2026, 6, 15)
        val catalogue = listOf(track("1", "Hit"), track("2", "Once"), track("3", "Never"), track("4", "Twice"))
        val rows = (1..9).map { row(now - it * day, "Hit") } + row(now, "Once") +
            row(now, "Twice") + row(now - day, "Twice")
        assertEquals(listOf("Never", "Once"), Rediscover.deepCuts(catalogue, rows, seed = 3).map { it.name })
    }

    @Test
    fun `deep cuts of an empty catalogue is empty`() {
        assertTrue(Rediscover.deepCuts(emptyList(), emptyList(), seed = 1).isEmpty())
    }

    // Added dates ----------------------------------------------------------

    @Test
    fun `added dates parse the shapes servers send`() {
        assertEquals(at(2024, 3, 1, 10), AddedDates.parseIso("2024-03-01T10:00:00Z"))
        assertEquals(at(2024, 3, 1, 10), AddedDates.parseIso("2024-03-01T11:00:00+01:00"))
        assertEquals(at(2024, 3, 1, 10), AddedDates.parseIso("2024-03-01T10:00:00"))
        assertEquals(123L, AddedDates.parseIso("2024-03-01T10:00:00.1234567Z")!! - at(2024, 3, 1, 10))
        assertNull(AddedDates.parseIso("yesterday"))
        assertNull(AddedDates.parseIso(null))
        assertEquals(1_700_000_000_000, AddedDates.fromEpochSeconds(1_700_000_000))
        assertNull(AddedDates.fromEpochSeconds(0))
    }
}
