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

    /** Storage cap: this table only ever grows otherwise. Called after every insert. */
    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int = 5_000)
}
