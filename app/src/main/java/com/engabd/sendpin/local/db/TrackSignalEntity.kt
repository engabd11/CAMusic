package com.engabd.sendpin.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * What the radio has learned about one track: how often the listener skipped it, and
 * when it was last heard — served by the radio, or played through to the point a play
 * counts. Read by [com.engabd.sendpin.audio.RadioMemory].
 *
 * Keyed like a scan (`TrackScanRepository.keyFor`, "<provider>:<id>"), so two
 * libraries that both number their songs from 1 do not share one song's skips.
 * Zero in either time column means "never".
 */
@Entity(tableName = "track_signal")
data class TrackSignalEntity(
    @PrimaryKey val trackKey: String,
    val skips: Int = 0,
    val lastSkippedAt: Long = 0,
    val lastServedAt: Long = 0,
)

@Dao
interface TrackSignalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<TrackSignalEntity>)

    @Query("UPDATE track_signal SET skips = skips + 1, lastSkippedAt = :at WHERE trackKey = :key")
    suspend fun bumpSkip(key: String, at: Long)

    @Query("UPDATE track_signal SET lastServedAt = :at WHERE trackKey IN (:keys)")
    suspend fun touchServed(keys: List<String>, at: Long)

    /** One more skip of [key], at [at]. */
    @Transaction
    suspend fun recordSkip(key: String, at: Long) {
        insertIgnore(listOf(TrackSignalEntity(key)))
        bumpSkip(key, at)
    }

    /** [keys] were just queued by the radio. Also what a counted play records, under [lastServedAt]. */
    @Transaction
    suspend fun recordServed(keys: List<String>, at: Long) {
        if (keys.isEmpty()) return
        insertIgnore(keys.map { TrackSignalEntity(it) })
        touchServed(keys, at)
    }

    @Query("SELECT * FROM track_signal WHERE lastServedAt > 0 ORDER BY lastServedAt DESC LIMIT :limit")
    suspend fun recentlyServed(limit: Int): List<TrackSignalEntity>

    @Query("SELECT * FROM track_signal WHERE skips > 0 AND lastSkippedAt >= :since")
    suspend fun skippedSince(since: Long): List<TrackSignalEntity>

    /**
     * Rows with nothing left to say: a skip that has fully decayed and a serve long
     * out of the no-repeat window. Keeps the table at the size of what is recent.
     */
    @Query("DELETE FROM track_signal WHERE lastSkippedAt < :before AND lastServedAt < :before")
    suspend fun trim(before: Long)
}
