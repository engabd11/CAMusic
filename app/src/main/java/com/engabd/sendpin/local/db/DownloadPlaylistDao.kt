package com.engabd.sendpin.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A playlist with how many of its tracks are actually on the phone. */
data class PlaylistWithCount(
    val id: String,
    val name: String,
    val sourceProvider: String?,
    val sourceId: String?,
    val image: String?,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
)

/**
 * Room access for downloaded playlists and their ordering.
 *
 * Every read joins onto `downloads`, and that join is the point rather than an
 * optimisation: membership is not a foreign key (see
 * [DownloadedPlaylistTrackEntity]), so a row can outlive the file it names. Joining
 * means a playlist always reports what is genuinely playable offline, and a track
 * deleted to free space quietly leaves the playlist shorter instead of producing a
 * row that plays silence.
 */
@Dao
interface DownloadPlaylistDao {

    /**
     * Every playlist, newest first, with its *playable* track count.
     *
     * `LEFT JOIN` so a playlist whose files have all been deleted still appears, at
     * zero — it can then be removed deliberately rather than vanishing on its own.
     */
    @Query(
        """
        SELECT p.id, p.name, p.sourceProvider, p.sourceId, p.image, p.coverPath,
               p.createdAt, p.updatedAt,
               COUNT(d.id) AS trackCount
        FROM downloaded_playlists p
        LEFT JOIN downloaded_playlist_tracks t ON t.playlistId = p.id
        LEFT JOIN downloads d ON d.id = t.trackId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC, p.name ASC
        """,
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query(
        """
        SELECT p.id, p.name, p.sourceProvider, p.sourceId, p.image, p.coverPath,
               p.createdAt, p.updatedAt,
               COUNT(d.id) AS trackCount
        FROM downloaded_playlists p
        LEFT JOIN downloaded_playlist_tracks t ON t.playlistId = p.id
        LEFT JOIN downloads d ON d.id = t.trackId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC, p.name ASC
        """,
    )
    suspend fun all(): List<PlaylistWithCount>

    @Query("SELECT * FROM downloaded_playlists WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadedPlaylistEntity?

    /**
     * A playlist's downloaded tracks, in the order the playlist put them.
     *
     * `INNER JOIN`, so a membership row whose file is gone drops out rather than
     * becoming an unplayable entry.
     */
    @Query(
        """
        SELECT d.* FROM downloads d
        INNER JOIN downloaded_playlist_tracks t ON t.trackId = d.id
        WHERE t.playlistId = :playlistId
        ORDER BY t.position ASC
        """,
    )
    suspend fun tracksOf(playlistId: String): List<DownloadedTrackEntity>

    /** The track ids already filed under this playlist, for an incremental update. */
    @Query("SELECT trackId FROM downloaded_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun trackIdsOf(playlistId: String): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM downloaded_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: DownloadedPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMembers(members: List<DownloadedPlaylistTrackEntity>)

    @Query("DELETE FROM downloaded_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearMembers(playlistId: String)

    @Query("DELETE FROM downloaded_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    /** Drop one track from every playlist holding it — used when its file is deleted. */
    @Query("DELETE FROM downloaded_playlist_tracks WHERE trackId = :trackId")
    suspend fun forgetTrack(trackId: String)

    @Query("DELETE FROM downloaded_playlist_tracks")
    suspend fun clearAllMembers()

    @Query("DELETE FROM downloaded_playlists")
    suspend fun deleteAllPlaylists()

    /** Remove a playlist and its membership together, so neither can be orphaned. */
    @Transaction
    suspend fun remove(id: String) {
        clearMembers(id)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun removeAll() {
        clearAllMembers()
        deleteAllPlaylists()
    }
}
