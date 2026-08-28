package com.engabd.sendpin.service

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Offline speed-limit provider backed by a local SQLite R-tree database.
 *
 * Wraps [SpeedLimitDatabase] with a geohash-keyed LRU cache so that repeat
 * lookups within the same ~150m cell are instant. The cache is small (64
 * entries) and stores both hits and misses (a miss is cached as null so we
 * don't re-query the database for a point we've already determined has no
 * data — e.g. a rural area outside the dataset).
 *
 * Lifecycle:
 * - The database file is a downloaded asset, not bundled in the APK.
 * - [ready] is true when the database is present and opened.
 * - If the database is not present, [getSpeedLimit] returns null immediately,
 *   and the UI shows a "Download speed-limit data" prompt.
 * - [SpeedMonitor] falls back to the manual limit when this provider returns
 *   null, so the alert still works without the database.
 *
 * Thread safety: [getSpeedLimit] is called from a single coroutine (the
 * SpeedMonitor's location-update handler), so no locking is needed beyond
 * the cache's own internal synchronization.
 */
class OfflineSpeedLimitProvider(
    context: Context,
) : SpeedLimitProvider {

    private val database = SpeedLimitDatabase(context)
    private val cache = SpeedLimitCache()

    override val ready: StateFlow<Boolean> = database.ready

    init {
        // Open the database if already downloaded from a previous session.
        // SQLiteDatabase.openDatabase on an existing file is fast (~1ms), and
        // this runs once when SpeedMonitor first starts listening — not on
        // every GPS reading. If the file doesn't exist, it returns immediately.
        database.open()
    }

    override suspend fun getSpeedLimit(lat: Double, lon: Double): Int? {
        // If the database isn't open, don't even check the cache — just return null.
        // The cache might have stale entries from a previous session, and without
        // the database we can't verify or refresh them.
        if (!ready.value) return null

        val geohashKey = Geohash.encode(lat, lon, precision = GEOHASH_PRECISION)

        // Check cache first
        when (val cached = cache.get(geohashKey)) {
            is SpeedLimitCache.Result.NOT_CACHED -> { /* fall through to database query */ }
            is SpeedLimitCache.Result.Cached -> return cached.value
        }

        // Query the database
        val result = database.querySpeedLimit(lat, lon)

        // Cache both hits and misses
        cache.put(geohashKey, result)

        return result
    }

    override fun statusDescription(): String {
        if (!ready.value) {
            return if (database.isDatabasePresent()) "Speed-limit data (needs reopen)"
            else "Speed-limit data not downloaded"
        }
        val version = database.databaseVersion() ?: "unknown"
        val sizeMB = database.databaseSizeBytes() / (1024.0 * 1024.0)
        return "Victoria speed zones ($version, ${"%.1f".format(sizeMB)} MB)"
    }

    /**
     * Clear the geohash cache. Called when the database is updated so stale
     * cached entries don't mask new data.
     */
    fun invalidateCache() {
        cache.clear()
    }

    fun getDatabase(): SpeedLimitDatabase = database

    companion object {
        /**
         * Geohash precision for cache keys.
         * 7 chars ≈ 150m × 150m cells. This is small enough that all readings
         * within a single city block share a cache entry, but large enough
         * that a 64-entry cache covers a ~1.2km × 1.2km area of driving
         * without eviction — more than enough for a commute through a city.
         */
        private const val GEOHASH_PRECISION = 7
    }
}