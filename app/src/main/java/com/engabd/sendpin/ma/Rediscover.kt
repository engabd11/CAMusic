package com.engabd.sendpin.ma

import androidx.compose.runtime.Immutable
import com.engabd.sendpin.local.db.PlayRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The four "Rediscover" shelves on the library home, as pure rules over the play
 * history.
 *
 * Everything here is a function of rows, a clock and a zone, so the date edges (leap
 * day, New Year) and the thresholds (90 days, 30 days, the play-count tier) can be
 * checked by a JVM test rather than by waiting for the right day. Turning a chosen
 * title and artist into something playable in the active library is the view model's
 * job — it needs a network for that — see [LibraryViewModel].
 */

/** One shelf of the rediscover set: its heading and what is on it. */
@Immutable
data class RediscoverShelf(val key: String, val title: String, val items: List<MaItem>)

/** A song the rules picked, before it is looked up in the library. */
data class SongPick(val title: String, val artist: String, val plays: Int)

/** "On this day": the year the plays were from, and what was played. */
data class OnThisDay(val year: Int, val picks: List<SongPick>)

object Rediscover {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** A favourite has gone quiet after this long. */
    const val FORGOTTEN_AFTER_DAYS = 90

    /** An album counts as left unplayed once it has sat this long. */
    const val NEVER_PLAYED_AFTER_DAYS = 30

    /** Plays that make a song a favourite outright. */
    const val FAVOURITE_PLAYS = 5

    /** Below this a song is never a favourite, even at the top of a quiet history. */
    const val MIN_TIER_PLAYS = 3

    /** Most songs a shelf asks the library to look up. */
    const val SHELF_LIMIT = 12

    /** A song played this often or less counts as a deep cut. */
    const val RARE_PLAYS = 1

    /** Most listened to artists whose back catalogue is searched. */
    const val DEEP_CUT_ARTISTS = 3

    /** An artist needs this many plays to count as one you listen to. */
    const val MIN_ARTIST_PLAYS = 5

    /** Per artist, so one prolific artist cannot fill the shelf. */
    const val DEEP_CUTS_PER_ARTIST = 4

    /** Lower-cased, trimmed, collapsed: the key that matches the same song across libraries. */
    fun norm(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    fun songKey(title: String, artist: String): String = norm(title) + "|" + norm(artist)

    /**
     * Songs played within a day either side of today's date in the most recent earlier
     * year that has any.
     *
     * The window is anchored on the same calendar date in each past year: 29 February
     * falls back to the 28th in a year without one, and 1 March in a year with one
     * reaches back to the leap day. The window is plain date arithmetic, so 31 December
     * reaches into 1 January of the next year and 1 January into the 31st of the one
     * before. The current year is never searched; this is a look back.
     */
    fun onThisDay(
        rows: List<PlayRow>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = SHELF_LIMIT,
    ): OnThisDay? {
        if (rows.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dated = rows.map { it to Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay() }
        // One year further back than the oldest row: a 31 December window reaches into the next January.
        val earliestYear = dated.minOf { LocalDate.ofEpochDay(it.second).year } - 1
        for (year in today.year - 1 downTo earliestYear) {
            val anchor = today.withYear(year)
            val from = anchor.minusDays(1).toEpochDay()
            val to = anchor.plusDays(1).toEpochDay()
            val hits = dated.filter { it.second in from..to }.map { it.first }
            if (hits.isEmpty()) continue
            val picks = rank(hits).take(limit)
            if (picks.isNotEmpty()) return OnThisDay(year, picks)
        }
        return null
    }

    /** Songs by play count, then by who was played most recently. Blank titles are dropped. */
    private fun rank(rows: List<PlayRow>): List<SongPick> =
        rows.filter { it.title.isNotBlank() }
            .groupBy { songKey(it.title, it.artist) }
            .values
            .sortedWith(
                compareByDescending<List<PlayRow>> { it.size }
                    .thenByDescending { g -> g.maxOf { it.timestamp } },
            )
            .map { g ->
                val newest = g.maxBy { it.timestamp }
                SongPick(newest.title.trim(), newest.artist.trim(), g.size)
            }

    /**
     * The play count that puts a song in this listener's top tier: the 90th percentile
     * of per-song counts, held between [MIN_TIER_PLAYS] and [FAVOURITE_PLAYS]. A heavy
     * listener's tier is five plays; a light one's can be as low as three.
     */
    fun favouriteTier(counts: Collection<Int>): Int {
        if (counts.isEmpty()) return FAVOURITE_PLAYS
        val sorted = counts.sorted()
        val p90 = sorted[((sorted.size - 1) * 0.9).toInt()]
        return p90.coerceIn(MIN_TIER_PLAYS, FAVOURITE_PLAYS)
    }

    /**
     * Songs played often and not lately: at or above the [favouriteTier], with the last
     * play more than [FORGOTTEN_AFTER_DAYS] days ago. A play exactly that long ago still
     * counts as recent. Most played first.
     */
    fun forgottenFavourites(rows: List<PlayRow>, now: Long, limit: Int = SHELF_LIMIT): List<SongPick> {
        val groups = rows.filter { it.title.isNotBlank() }.groupBy { songKey(it.title, it.artist) }
        if (groups.isEmpty()) return emptyList()
        val tier = favouriteTier(groups.values.map { it.size })
        val cutoff = now - FORGOTTEN_AFTER_DAYS * DAY_MS
        return groups.values
            .filter { it.size >= tier && it.maxOf { r -> r.timestamp } < cutoff }
            .sortedWith(
                compareByDescending<List<PlayRow>> { it.size }
                    .thenByDescending { g -> g.maxOf { it.timestamp } },
            )
            .take(limit)
            .map { g ->
                val newest = g.maxBy { it.timestamp }
                SongPick(newest.title.trim(), newest.artist.trim(), g.size)
            }
    }

    /**
     * Albums the library has had for more than [NEVER_PLAYED_AFTER_DAYS] days and the
     * history has never mentioned. An album with no added date is skipped, since there
     * is no telling how long it has been there. Matched on album name, and on artist too
     * when both sides name one, so two different "Greatest Hits" are not one album.
     * The order is shuffled by [seed] (the day, so the shelf is stable within one).
     */
    fun neverPlayed(
        albums: List<MaItem>,
        rows: List<PlayRow>,
        now: Long,
        seed: Long,
        limit: Int = SHELF_LIMIT,
    ): List<MaItem> {
        val cutoff = now - NEVER_PLAYED_AFTER_DAYS * DAY_MS
        val played = HashMap<String, MutableList<String>>()
        rows.forEach { if (it.album.isNotBlank()) played.getOrPut(norm(it.album)) { mutableListOf() }.add(norm(it.artist)) }
        val unplayed = albums.filter { a ->
            val added = a.addedAtMs ?: return@filter false
            if (added >= cutoff) return@filter false
            val artists = played[norm(a.name)] ?: return@filter true
            val artist = norm(a.subtitle.orEmpty())
            // Played if any row of that album name is by the same artist, or by no one in particular.
            artists.none { it.isEmpty() || artist.isEmpty() || it.contains(artist) || artist.contains(it) }
        }
        return unplayed.distinctBy { it.itemId }.shuffled(kotlin.random.Random(seed)).take(limit)
    }

    /** Artists the listener plays most, who have enough plays to be worth digging into. */
    fun topArtists(artistCounts: List<Pair<String, Int>>): List<String> =
        artistCounts.filter { it.second >= MIN_ARTIST_PLAYS && it.first.isNotBlank() }
            .sortedByDescending { it.second }
            .take(DEEP_CUT_ARTISTS)
            .map { it.first }

    /**
     * Tracks from [catalogue] (an artist's songs, from the source) that the history has
     * never or barely touched: [RARE_PLAYS] plays or fewer. Never played ones come first.
     * [rows] should be this artist's history; plays are counted by title, because the
     * catalogue is one artist's and a credit like "A feat. B" should not hide a play.
     */
    fun deepCuts(
        catalogue: List<MaItem>,
        rows: List<PlayRow>,
        seed: Long,
        limit: Int = DEEP_CUTS_PER_ARTIST,
    ): List<MaItem> {
        val plays = rows.groupingBy { norm(it.title) }.eachCount()
        return catalogue
            .filter { it.mediaType == "track" && it.playable }
            .distinctBy { norm(it.name) }
            .filter { (plays[norm(it.name)] ?: 0) <= RARE_PLAYS }
            .shuffled(kotlin.random.Random(seed))
            .sortedBy { plays[norm(it.name)] ?: 0 }
            .take(limit)
    }

    /** "On this day in 2025". */
    fun onThisDayTitle(year: Int) = "On this day in $year"
}
