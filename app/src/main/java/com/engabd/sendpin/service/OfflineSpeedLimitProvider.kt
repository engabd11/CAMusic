package com.engabd.sendpin.service

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Offline speed-limit provider backed by a local SQLite R-tree database.
 *
 * Wraps [SpeedLimitDatabase] with a geohash-keyed LRU cache so that repeat
 * lookups on the same stretch of road are instant.
 *
 * ## What the cache may and may not remember
 *
 * **Hits only.** It used to cache misses too, under a 7-character geohash — a cell
 * roughly 150 m on a side. That is a whole block of driving answered from one
 * lookup, and the lookup that answered it could easily be the bad one: a fix that
 * landed 35 m off the road, at a set of lights, under a bridge, beside a tall
 * building. One such fix wrote "no data here" over the entire cell, and every
 * reading that followed through it — however good — was answered from that note
 * without the database ever being asked again. The driver's report of this reads
 * exactly as it did: auto-detect works on most roads and silently does not on some.
 *
 * A miss is now simply not remembered. Roads outside the dataset cost one R-tree
 * bounding-box query per fix, which is a few milliseconds on IO and is the honest
 * price of not being wrong; [SpeedMonitor] runs one lookup at a time and never waits
 * on it, so the cost is not on any path the driver can feel.
 *
 * The key is also finer now — 8 characters, ~38 m — because a 150 m cell can span a
 * limit change, and answering the second half of it with the first half's limit is
 * the same failure in the other direction.
 *
 * Lifecycle:
 * - The database ships gzipped in the APK and is expanded on first use.
 * - [ready] is true once that has happened and the file is open. The expand is
 *   tens of megabytes, so it runs on IO and this provider is usable - answering
 *   null - for the second or two it takes.
 * - [SpeedMonitor] falls back to the manual limit when this provider returns
 *   null, so the alert works throughout, and everywhere outside Victoria — which
 *   is why the manual fallback is worth setting even with auto-detect on.
 *
 * Thread safety: [getSpeedLimit] is called from a single coroutine (the
 * SpeedMonitor's location-update handler), so no locking is needed beyond
 * the cache's own internal synchronization.
 */
class OfflineSpeedLimitProvider(
    context: Context,
) : SpeedLimitProvider {

    private val database = SpeedLimitDatabase(context)
    // Bigger than the 64 it was, because the key is finer: the same stretch of a
    // commute now takes several times as many entries to hold.
    private val cache = SpeedLimitCache(maxSize = 256)

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

    override suspend fun getSpeedLimit(lat: Double, lon: Double, accuracyMeters: Float): Int? {
        // If the database isn't open, don't even check the cache — just return null.
        // The cache might have stale entries from a previous session, and without
        // the database we can't verify or refresh them.
        if (!ready.value) return null

        val geohashKey = Geohash.encode(lat, lon, precision = GEOHASH_PRECISION)

        // Check cache first. Only hits are ever in here — see the class doc.
        when (val cached = cache.get(geohashKey)) {
            is SpeedLimitCache.Result.NOT_CACHED -> { /* fall through to database query */ }
            is SpeedLimitCache.Result.Cached -> return cached.value
        }

        // The fix's own error, added to the base radius rather than assumed away.
        // Capped, because past a certain width the nearest segment stops being the
        // road the car is on and starts being the one beside it.
        val radius = (BASE_MATCH_METERS + accuracyMeters.coerceAtLeast(0f))
            .toDouble()
            .coerceAtMost(MAX_MATCH_METERS)
        val result = database.querySpeedLimit(lat, lon, maxDistanceMeters = radius)

        // Hits only. A miss is not evidence about the next fix from this cell.
        if (result != null) cache.put(geohashKey, result)

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
         *
         * 8 chars ≈ 38 m × 19 m — about the length of a car and a half of road.
         * Fine enough that a cached limit cannot outlive the zone it was read in,
         * which a 150 m cell (7 chars, what this used to be) comfortably could.
         */
        private const val GEOHASH_PRECISION = 8

        /**
         * How far from a road segment a fix can be and still be counted as on it,
         * before the fix's own reported accuracy is added on top.
         *
         * 30 m covers the width of most roads plus the few metres a good fix is
         * out by. It does not cover a *bad* fix, which is the whole reason
         * [getSpeedLimit] takes an accuracy at all.
         */
        private const val BASE_MATCH_METERS = 30f

        /** Past this, the nearest segment is as likely to be the next road over. */
        private const val MAX_MATCH_METERS = 70.0
    }
}