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
 * - The database ships gzipped in the APK and is expanded on first use.
 * - [ready] is true once that has happened and the file is open. The expand is
 *   tens of megabytes, so it runs on IO and this provider is usable - answering
 *   null - for the second or two it takes.
 * - [SpeedMonitor] falls back to the manual limit when this provider returns
 *   null, so the alert works throughout, and everywhere outside Victoria.
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
        // Open the file if a previous session already expanded it.
        // SQLiteDatabase.openDatabase on an existing file is fast (~1ms), and
        // this runs once when SpeedMonitor first starts listening — not on
        // every GPS reading. If it isn't there yet, `prepare` unpacks the
        // bundled asset on IO and flips `ready` when it lands; until then every
        // lookup answers null and the manual limit stands.
        if (!database.open()) database.prepare()
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
            return if (database.isDatabasePresent()) "Speed-limit data, opening…"
            else "Unpacking speed-limit data…"
        }
        val version = database.databaseVersion() ?: "unknown"
        val sizeMB = database.databaseSizeBytes() / (1024.0 * 1024.0)
        return "Victoria speed zones ($version, ${"%.0f".format(sizeMB)} MB on this phone)"
    }

    /**
     * Clear the geohash cache. Called when the database is updated so stale
     * cached entries don't mask new data.
     */
    fun invalidateCache() {
        cache.clear()
    }

    override fun close() {
        database.close()
        cache.clear()
    }

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