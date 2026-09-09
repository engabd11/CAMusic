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
 * [getSpeedLimit] is called on every GPS reading — one a second, since
 * [SpeedMonitor] tightened its update interval — so it should be fast, under 10ms
 * for the local database path. The geohash cache in [OfflineSpeedLimitProvider]
 * makes repeat lookups on the same stretch of road effectively free.
 * [SpeedMonitor] never lets two run at once and never waits on one before judging a
 * reading, so a slow lookup delays the *limit* updating and not the alert.
 */
interface SpeedLimitProvider {

    /**
     * @param lat  GPS latitude (WGS84 decimal degrees).
     * @param lon  GPS longitude (WGS84 decimal degrees).
     * @param accuracyMeters  the fix's own reported horizontal accuracy, or 0 when
     *        it did not report one. A lookup matches the point against road geometry
     *        within a radius, and a fix that is honestly ±25 m needs that radius
     *        widened by 25 m or it lands beside every road it is actually on — which
     *        reads to the driver as "auto-detect does not work down my street".
     * @return the posted speed limit in km/h at the given location, or null
     *         if no data is available (outside dataset coverage, database not
     *         downloaded yet, etc.). Returning null tells [SpeedMonitor] to
     *         fall back to the manually-set limit, so this method should be
     *         honest about what it doesn't know rather than guessing.
     */
    suspend fun getSpeedLimit(lat: Double, lon: Double, accuracyMeters: Float = 0f): Int?

    /**
     * Whether the provider has data ready — for the offline provider this
     * means the SQLite database has been downloaded and opened. Exposed as
     * a flow so the UI can show "downloading…" vs "ready" status.
     */
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean>

    /**
     * Why the data is unavailable, or null when there is nothing wrong.
     *
     * [ready] alone cannot distinguish "still unpacking" from "will never unpack",
     * and the settings card said the former for both — so an unpack that failed for
     * any reason (no room on the device, a truncated read, a build with no asset in
     * it) reported perpetual progress on a step that was never going to finish.
     * This is the second half of that answer, and it is a flow so the card updates
     * the moment a failure is known rather than waiting on a [ready] that never comes.
     */
    val failure: kotlinx.coroutines.flow.StateFlow<String?>

    /**
     * Human-readable description of the data source for the UI — e.g.
     * "Victoria speed zones (July 2026)" or why there is none.
     *
     * Re-read whenever [ready] or [failure] changes.
     */
    fun statusDescription(): String

    /**
     * Release any resources (database handles, etc.). Called by [SpeedMonitor]
     * when location updates stop. Safe to call multiple times.
     */
    fun close()
}