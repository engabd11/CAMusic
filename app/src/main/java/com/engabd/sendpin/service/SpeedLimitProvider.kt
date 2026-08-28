package com.engabd.sendpin.service

/**
 * Provides speed-limit lookups for the driving-mode alert.
 *
 * The alert system has always needed a speed limit to compare against. The
 * original implementation required the driver to type one in; this interface
 * abstracts over *where* the limit comes from so that a dynamic, location-
 * aware source can supply it instead — without the alert logic knowing or
 * caring whether the number was typed, looked up from a local database, or
 * fell back to a default.
 *
 * Implementations must be safe to call from a background coroutine scope.
 * [getSpeedLimit] is called on every GPS reading (throttled to one every 5s
 * by [SpeedMonitor]'s update interval), so it should be fast — under 10ms
 * for the local database path. The geohash cache in [OfflineSpeedLimitProvider]
 * makes repeat lookups in the same area effectively free.
 */
interface SpeedLimitProvider {

    /**
     * @param lat  GPS latitude (WGS84 decimal degrees).
     * @param lon  GPS longitude (WGS84 decimal degrees).
     * @return the posted speed limit in km/h at the given location, or null
     *         if no data is available (outside dataset coverage, database not
     *         downloaded yet, etc.). Returning null tells [SpeedMonitor] to
     *         fall back to the manually-set limit, so this method should be
     *         honest about what it doesn't know rather than guessing.
     */
    suspend fun getSpeedLimit(lat: Double, lon: Double): Int?

    /**
     * Whether the provider has data ready — for the offline provider this
     * means the SQLite database has been downloaded and opened. Exposed as
     * a flow so the UI can show "downloading…" vs "ready" status.
     */
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean>

    /**
     * Human-readable description of the data source for the UI — e.g.
     * "Victoria speed zones (July 2026)" or "Not downloaded yet".
     */
    fun statusDescription(): String

    /**
     * Release any resources (database handles, etc.). Called by [SpeedMonitor]
     * when location updates stop. Safe to call multiple times.
     */
    fun close()
}