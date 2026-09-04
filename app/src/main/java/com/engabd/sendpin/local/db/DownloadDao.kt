package com.engabd.sendpin.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the offline download index.
 *
 * The public [com.engabd.sendpin.download.DownloadManager] keeps the same StateFlow
 * API; this DAO is the backing store.
 */
@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY id")
    fun observeAll(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloads ORDER BY id")
    suspend fun getAll(): List<DownloadedTrackEntity>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadedTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DownloadedTrackEntity>)

    @Update
    suspend fun update(entity: DownloadedTrackEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /**
     * Resilient offline SQLite fast search index across title, artist, and album.
     */
    @Query("SELECT * FROM downloads WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title ASC")
    suspend fun searchTracks(query: String): List<DownloadedTrackEntity>

    @Query("SELECT * FROM downloads WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title ASC")
    fun observeSearchTracks(query: String): Flow<List<DownloadedTrackEntity>>
}
