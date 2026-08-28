package com.engabd.sendpin.service

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geohash encoding/decoding for spatial caching.
 *
 * A geohash is a string encoding of a lat/lon pair where shared prefixes
 * indicate spatial proximity. A 7-character geohash covers roughly 150m ×
 * 150m — fine-grained enough that all GPS readings within a single city
 * block hash to the same key, but coarse enough that one cache entry
 * serves dozens of consecutive readings while a car drives through it.
 *
 * This is not a general-purpose geohash library — it does exactly what the
 * speed-limit cache needs: encode a lat/lon to a fixed-precision string
 * that can be used as a hash map key.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Encode a coordinate to a geohash string of [precision] characters.
     * 7 characters ≈ 150m × 150m cells — the right granularity for
     * speed-limit caching (a single zone segment is usually 100-500m long).
     */
    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        val sb = StringBuilder()
        var even = true
        var bit = 0
        var ch = 0
        while (sb.length < precision) {
            val mid = if (even) (minLon + maxLon) / 2 else (minLat + maxLat) / 2
            val value = if (even) lon else lat
            if (value >= mid) {
                ch = (ch shl 1) or 1
                if (even) minLon = mid else minLat = mid
            } else {
                ch = ch shl 1
                if (even) maxLon = mid else maxLat = mid
            }
            even = !even
            if (++bit == 5) {
                sb.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }
}

/**
 * LRU cache for speed-limit lookups, keyed by geohash.
 *
 * The cache is intentionally small (64 entries). Both [get] and [put] are
 * `synchronized` on the cache instance, which is correct but very cheap —
 * the cache is accessed from a single coroutine (SpeedMonitor's location
 * handler) so contention is effectively zero, and the synchronized block
 * protects against the adaptive-volume path reading concurrently.
 */
class SpeedLimitCache(private val maxSize: Int = 64) {

    private data class Entry(val key: String, val value: Int?)

    private val entries = ArrayDeque<Entry>()

    /**
     * Look up a cached speed limit by geohash key.
     * @return the cached limit (Int? — null means "cached miss: no data at
     *         this location"), or a sentinel indicating the key is not in
     *         the cache at all.
     */
    fun get(key: String): Result = synchronized(this) {
        val idx = entries.indexOfFirst { it.key == key }
        if (idx < 0) return Result.NOT_CACHED
        // Move to end (most-recently used)
        val entry = entries.removeAt(idx)
        entries.addLast(entry)
        Result.Cached(entry.value)
    }

    fun put(key: String, value: Int?) = synchronized(this) {
        // Remove if already present
        entries.removeAll { it.key == key }
        entries.addLast(Entry(key, value))
        while (entries.size > maxSize) entries.removeFirst()
    }

    fun clear() = synchronized(this) {
        entries.clear()
    }

    sealed class Result {
        /** Key not in cache — caller should query the database. */
        data object NOT_CACHED : Result()
        /** Key is cached. [value] is the speed limit, or null for "queried but no data". */
        data class Cached(val value: Int?) : Result()
    }
}

/**
 * Distance from a point to a line segment, in metres.
 *
 * The point and segment endpoints are in lat/lon. For short distances
 * (tens of metres) we approximate the spherical geometry with an equirectangular
 * projection — accurate to well under a metre at city scale, which is more
 * than enough for picking the nearest road segment.
 */
fun pointToSegmentMeters(
    pointLat: Double, pointLon: Double,
    segLat1: Double, segLon1: Double,
    segLat2: Double, segLon2: Double,
): Double {
    // Equirectangular approximation: convert lon delta to metres using cos(lat)
    val lat0 = Math.toRadians((pointLat + segLat1 + segLat2) / 3)
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * cos(lat0)

    val px = pointLon * metersPerDegLon
    val py = pointLat * metersPerDegLat
    val x1 = segLon1 * metersPerDegLon
    val y1 = segLat1 * metersPerDegLat
    val x2 = segLon2 * metersPerDegLon
    val y2 = segLat2 * metersPerDegLat

    val dx = x2 - x1
    val dy = y2 - y1
    val lenSq = dx * dx + dy * dy

    if (lenSq < 1e-6) {
        // Segment is effectively a point
        return hypot(px - x1, py - y1)
    }

    // Project point onto segment, clamp t to [0, 1]
    val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
    val tClamped = t.coerceIn(0.0, 1.0)
    val projX = x1 + tClamped * dx
    val projY = y1 + tClamped * dy
    return hypot(px - projX, py - projY)
}