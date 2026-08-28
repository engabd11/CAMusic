package com.engabd.sendpin.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GeohashTest {

    @Test
    fun `encodes Melbourne CBD to 7-char geohash`() {
        // Flinders Street Station: ~144.9631, -37.8182
        val hash = Geohash.encode(-37.8182, 144.9631, precision = 7)
        assertEquals(7, hash.length)
        // Melbourne CBD should encode to r1r0n... (approximate)
        // The exact value depends on the encoding, but it should be deterministic
        val hash2 = Geohash.encode(-37.8182, 144.9631, precision = 7)
        assertEquals(hash, hash2)
    }

    @Test
    fun `nearby points share a geohash prefix`() {
        val hash1 = Geohash.encode(-37.8182, 144.9631, precision = 7)
        val hash2 = Geohash.encode(-37.8183, 144.9632, precision = 7)
        // Points ~10m apart should share at least 6 of 7 characters
        assertTrue(
            hash1.take(6) == hash2.take(6),
            "Points 10m apart should share 6+ geohash chars: $hash1 vs $hash2",
        )
    }

    @Test
    fun `distant points have different geohash prefixes`() {
        val melbourne = Geohash.encode(-37.81, 144.96, precision = 7)
        val sydney = Geohash.encode(-33.87, 151.21, precision = 7)
        assertTrue(melbourne != sydney)
        // Melbourne and Sydney are ~700km apart — they share the first geohash
        // character (both in the 'r' cell covering SE Australia) but diverge
        // by the third character.
        assertTrue(
            melbourne.take(3) != sydney.take(3),
            "Cities 700km apart should differ by 3rd geohash char: $melbourne vs $sydney",
        )
    }
}

class SpeedLimitCacheTest {

    @Test
    fun `returns NOT_CACHED for unknown key`() {
        val cache = SpeedLimitCache()
        val result = cache.get("unknown")
        assertTrue(result is SpeedLimitCache.Result.NOT_CACHED)
    }

    @Test
    fun `stores and retrieves a value`() {
        val cache = SpeedLimitCache()
        cache.put("abc123", 60)
        val result = cache.get("abc123")
        assertTrue(result is SpeedLimitCache.Result.Cached)
        assertEquals(60, (result as SpeedLimitCache.Result.Cached).value)
    }

    @Test
    fun `caches null as a valid result (miss is cached)`() {
        val cache = SpeedLimitCache()
        cache.put("rural_area", null)
        val result = cache.get("rural_area")
        assertTrue(result is SpeedLimitCache.Result.Cached)
        assertNull((result as SpeedLimitCache.Result.Cached).value)
    }

    @Test
    fun `evicts oldest entries when capacity exceeded`() {
        val cache = SpeedLimitCache(maxSize = 3)
        cache.put("a", 40)
        cache.put("b", 50)
        cache.put("c", 60)
        cache.put("d", 70) // should evict "a"
        assertTrue(cache.get("a") is SpeedLimitCache.Result.NOT_CACHED)
        assertTrue(cache.get("d") is SpeedLimitCache.Result.Cached)
    }

    @Test
    fun `accessing an entry moves it to most-recently-used`() {
        val cache = SpeedLimitCache(maxSize = 3)
        cache.put("a", 40)
        cache.put("b", 50)
        cache.put("c", 60)
        // Access "a" — it should now be most-recently-used
        cache.get("a")
        // Add "d" — should evict "b" (least-recently-used), not "a"
        cache.put("d", 70)
        assertTrue(cache.get("a") is SpeedLimitCache.Result.Cached)
        assertTrue(cache.get("b") is SpeedLimitCache.Result.NOT_CACHED)
    }

    @Test
    fun `clear empties the cache`() {
        val cache = SpeedLimitCache()
        cache.put("key", 100)
        cache.clear()
        assertTrue(cache.get("key") is SpeedLimitCache.Result.NOT_CACHED)
    }
}

class PointToSegmentTest {

    @Test
    fun `point on segment has near-zero distance`() {
        // Point between two segment endpoints
        val dist = pointToSegmentMeters(
            pointLat = -37.8180, pointLon = 144.9630,
            segLat1 = -37.8180, segLon1 = 144.9620,
            segLat2 = -37.8180, segLon2 = 144.9640,
        )
        assertTrue(dist < 1.0, "Point on segment should have <1m distance, got $dist m")
    }

    @Test
    fun `point far from segment has large distance`() {
        val dist = pointToSegmentMeters(
            pointLat = -37.8180, pointLon = 144.9700,
            segLat1 = -37.8180, segLon1 = 144.9620,
            segLat2 = -37.8180, segLon2 = 144.9640,
        )
        // ~600m away (0.006 degrees lon at this latitude)
        assertTrue(dist > 500.0, "Point 600m away should have >500m distance, got $dist m")
    }

    @Test
    fun `point projects onto segment endpoint when beyond it`() {
        // Point beyond the end of the segment — distance should be to the endpoint
        val dist = pointToSegmentMeters(
            pointLat = -37.8180, pointLon = 144.9610,
            segLat1 = -37.8180, segLon1 = 144.9620,
            segLat2 = -37.8180, segLon2 = 144.9640,
        )
        // Should be ~80m (0.001 degrees lon)
        assertTrue(dist < 120.0 && dist > 60.0, "Point beyond segment should be ~80m, got $dist m")
    }
}