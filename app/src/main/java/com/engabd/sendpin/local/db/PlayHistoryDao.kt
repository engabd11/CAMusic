package com.engabd.sendpin.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** One artist's play count within a window. */
data class ArtistPlayCount(val artist: String, val plays: Int)

/** One track's play count within a window — for "most played this week". */
data class TrackPlayCount(val trackId: String, val title: String, val artist: String, val plays: Int)

/** One codec/rate/depth combination's play count — the format-breakdown pie. */
data class FormatPlayCount(val codec: String?, val sampleRate: Int, val bitDepth: Int, val plays: Int)

/** One provider's play count — the server-breakdown bar. */
data class ProviderPlayCount(val provider: String, val plays: Int)

/** One (hour-of-day, day-of-week) cell, for the listening clock. */
data class HourCell(val dow: Int, val hour: Int, val plays: Int)

/** One day's totals, for the streak and the week-on-week series. */
data class DayTotals(val day: String, val plays: Int, val listenedMs: Long, val artists: Int)

/** A track and how much of it was actually heard — for skips and completion. */
data class Completion(val title: String, val artist: String, val playedMs: Long, val durationMs: Long)

/** One track's tempo and energy — the scatter's point. */
data class TempoEnergyPoint(val bpm: Float, val energy: Float, val keyMode: String?)

/** One key's play count within a window — Listening DNA's dominant-keys bar. */
data class KeyPlayCount(val keyTonic: Int, val keyMode: String, val plays: Int)

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(entity: PlayHistoryEntity)

    @Query(
        """
        SELECT artist, COUNT(*) as plays FROM play_history
        WHERE timestamp >= :since AND artist != ''
        GROUP BY artist ORDER BY plays DESC LIMIT :limit
        """,
    )
    suspend fun topArtists(since: Long, limit: Int = 10): List<ArtistPlayCount>

    @Query("SELECT COALESCE(SUM(durationPlayedMs), 0) FROM play_history WHERE timestamp >= :since")
    suspend fun totalListeningMs(since: Long): Long

    @Query(
        """
        SELECT trackId, title, artist, COUNT(*) as plays FROM play_history
        WHERE timestamp >= :since
        GROUP BY trackId ORDER BY plays DESC LIMIT 1
        """,
    )
    suspend fun mostPlayedTrack(since: Long): TrackPlayCount?

    @Query(
        """
        SELECT codec, sampleRate, bitDepth, COUNT(*) as plays FROM play_history
        WHERE timestamp >= :since
        GROUP BY codec, sampleRate, bitDepth ORDER BY plays DESC
        """,
    )
    suspend fun formatBreakdown(since: Long): List<FormatPlayCount>

    @Query(
        """
        SELECT provider, COUNT(*) as plays FROM play_history
        WHERE timestamp >= :since
        GROUP BY provider ORDER BY plays DESC
        """,
    )
    suspend fun providerBreakdown(since: Long): List<ProviderPlayCount>

    @Query(
        """
        SELECT keyTonic, keyMode, COUNT(*) as plays FROM play_history
        WHERE timestamp >= :since AND keyTonic IS NOT NULL AND keyMode IS NOT NULL
        GROUP BY keyTonic, keyMode ORDER BY plays DESC
        """,
    )
    suspend fun keyDistribution(since: Long): List<KeyPlayCount>

    /** Every non-null bpm in the window — bucketed into bins in Kotlin, not SQL. */
    @Query("SELECT bpm FROM play_history WHERE timestamp >= :since AND bpm IS NOT NULL")
    suspend fun bpmSamples(since: Long): List<Float>

    /**
     * Where the bytes came from, preferring [PlayHistoryEntity.streamProvider].
     *
     * `provider` is the backend this app was talking to, and for everything Music
     * Assistant played that is the one literal "MA" — so this used to be a single bar
     * whatever the library was actually made of. `streamProvider` is what MA reported
     * streaming from, and where it exists it is the more useful answer.
     */
    @Query(
        """
        SELECT COALESCE(NULLIF(streamProvider, ''), provider) as provider, COUNT(*) as plays
        FROM play_history WHERE timestamp >= :since
        GROUP BY COALESCE(NULLIF(streamProvider, ''), provider) ORDER BY plays DESC
        """,
    )
    suspend fun sourceBreakdown(since: Long): List<ProviderPlayCount>

    /**
     * Plays per (day-of-week, hour), for the listening clock.
     *
     * `timestamp` has been stored since the table existed and never once been read.
     * SQLite's `%w` is 0 = Sunday, `%H` is 00-23; both are computed in the local zone
     * because the question is "when do *I* listen", not what UTC thought at the time.
     */
    @Query(
        """
        SELECT CAST(strftime('%w', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) as dow,
               CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) as hour,
               COUNT(*) as plays
        FROM play_history WHERE timestamp >= :since
        GROUP BY dow, hour
        """,
    )
    suspend fun listeningClock(since: Long): List<HourCell>

    /**
     * One row per calendar day: plays, time, and how many distinct artists.
     *
     * The artist count is what makes a listening-diversity figure possible without
     * pulling every row into memory.
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') as day,
               COUNT(*) as plays,
               COALESCE(SUM(durationPlayedMs), 0) as listenedMs,
               COUNT(DISTINCT artist) as artists
        FROM play_history WHERE timestamp >= :since
        GROUP BY day ORDER BY day ASC
        """,
    )
    suspend fun dailyTotals(since: Long): List<DayTotals>

    /** Distinct artists in the window — the denominator for a diversity figure. */
    @Query("SELECT COUNT(DISTINCT artist) FROM play_history WHERE timestamp >= :since AND artist != ''")
    suspend fun distinctArtists(since: Long): Int

    /** Total plays in the window. */
    @Query("SELECT COUNT(*) FROM play_history WHERE timestamp >= :since")
    suspend fun playCount(since: Long): Int

    /**
     * Artists heard in the window that were never heard before it — the "new to you"
     * figure, answered in SQL rather than by loading two lists and subtracting.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT artist) FROM play_history
        WHERE timestamp >= :since AND artist != '' AND artist NOT IN (
            SELECT DISTINCT artist FROM play_history WHERE timestamp < :since
        )
        """,
    )
    suspend fun newArtists(since: Long): Int

    /** Every (bpm, energy) pair — the tempo/energy scatter. Nulls excluded. */
    @Query(
        """
        SELECT bpm, energy, keyMode FROM play_history
        WHERE timestamp >= :since AND bpm IS NOT NULL AND energy IS NOT NULL
        """,
    )
    suspend fun tempoEnergy(since: Long): List<TempoEnergyPoint>

    /**
     * How much of each track was heard, for the least-finished list.
     *
     * Only rows that recorded a duration, which is everything written since the v5
     * migration — older rows have `durationMs = 0` and nothing to be a fraction of.
     */
    @Query(
        """
        SELECT title, artist, durationPlayedMs as playedMs, durationMs FROM play_history
        WHERE timestamp >= :since AND durationMs > 0 AND durationPlayedMs > 0
        """,
    )
    suspend fun completions(since: Long): List<Completion>

    /** Listening time by source, not just play count — a long album is not one play. */
    @Query(
        """
        SELECT COALESCE(NULLIF(streamProvider, ''), provider) as provider,
               COALESCE(SUM(durationPlayedMs), 0) as plays
        FROM play_history WHERE timestamp >= :since
        GROUP BY COALESCE(NULLIF(streamProvider, ''), provider) ORDER BY plays DESC
        """,
    )
    suspend fun timeBySource(since: Long): List<ProviderPlayCount>

    /** The oldest row's timestamp, so "all time" can say how far back it goes. */
    @Query("SELECT MIN(timestamp) FROM play_history")
    suspend fun earliest(): Long?

    /** Storage cap: this table only ever grows otherwise. Called after every insert. */
    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int = 5_000)
}
