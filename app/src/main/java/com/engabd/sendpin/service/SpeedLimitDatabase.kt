package com.engabd.sendpin.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the offline speed-limit SQLite database on the device.
 *
 * The database is produced by the `build_speed_db.py` pipeline from Transport
 * Victoria's open Speed Zones GeoJSON, and contains:
 *  - `speed_zones` — one row per road segment with speed_limit, direction, etc.
 *  - `speed_zones_rtree` — an R-tree virtual table for fast bounding-box lookups
 *  - `segment_coords` — one row per zone, holding all of its polylines packed
 *    as little-endian int32 microdegree (lon, lat) pairs
 *  - `meta` — version, source, generation date
 *
 * The database **ships with the app**. `app/src/main/assets/speed_zones.sqlite3.gz`
 * is what is committed, but the asset this code opens is called
 * `speed_zones.sqlite3`, without the suffix, and arrives already expanded:
 * **AGP's asset merger gunzips any `.gz` under `assets/` at build time.** So the
 * gzip is purely a way to keep 78 MB out of the git history - AAPT then deflates
 * it back down to ~39 MB inside the APK on its own, and neither end needs a
 * `GZIPInputStream`. Do not "fix" the missing suffix here; the file genuinely is
 * not there at runtime.
 *
 * It is copied into the app's files directory once, the first time driving mode
 * actually needs it - not at install time, and never on the main thread. SQLite
 * cannot open a database inside an APK, which is what the copy is for.
 *
 * It used to be described as a downloaded asset, and SpeedLimitDownloadManager was
 * written to fetch it. Nothing ever instantiated that class and no URL was ever
 * configured, so `ready` was false on every device that has run this code, and
 * auto-detect silently fell back to the manually-typed limit while the settings
 * page promised a monthly-updated download. Bundling is what makes the claim
 * true; the downloader is gone.
 */
class SpeedLimitDatabase(context: Context) {

    private val appContext = context.applicationContext
    private val dbFile: File = File(context.filesDir, DB_FILENAME)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var db: SQLiteDatabase? = null

    /** Guards [prepare] so overlapping calls expand the asset once, not twice. */
    private val expanding = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Open the database read-only if it exists. Safe to call repeatedly.
     * @return true if the database is open and ready for queries.
     */
    fun open(): Boolean {
        if (db != null && db!!.isOpen) {
            _ready.value = true
            return true
        }
        if (!dbFile.exists()) {
            _ready.value = false
            return false
        }
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            _ready.value = true
            true
        } catch (e: Exception) {
            _ready.value = false
            false
        }
    }

    /**
     * Expand the bundled asset if it is not on disk yet, then open it.
     *
     * Returns immediately and does the work on IO: the caller is
     * [OfflineSpeedLimitProvider], constructed on the main thread the moment
     * driving mode starts listening for locations, and unpacking tens of
     * megabytes there would drop frames on the one screen where that matters.
     * Until it finishes [ready] stays false and the alert uses the manual limit,
     * which is exactly the documented fallback.
     *
     * Idempotent, and safe to call when the file is already present.
     */
    fun prepare() {
        if (_ready.value || !expanding.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!dbFile.exists()) expandAsset()
                open()
            } finally {
                expanding.set(false)
            }
        }
    }

    /**
     * Copy the shipped asset out to [dbFile], where SQLite can open it.
     *
     * A plain stream copy: the asset is stored deflated in the APK and
     * `AssetManager.open` already inflates it (see the class doc on the `.gz`
     * suffix disappearing at build time).
     *
     * Written to a temp file and renamed, so being killed mid-copy leaves no
     * half-written database for [open] to find and fail on.
     */
    private fun expandAsset() {
        val tmp = File(dbFile.parentFile, DB_FILENAME + ".tmp")
        try {
            tmp.parentFile?.mkdirs()
            appContext.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dbFile)) tmp.delete()
        } catch (e: Exception) {
            // No asset in this build, no room on the device, a truncated stream:
            // all of them mean the same thing to the caller, which is that
            // auto-detect has no data and the manual limit stands.
            runCatching { tmp.delete() }
        }
    }

    fun close() {
        db?.close()
        db = null
        _ready.value = false
    }

    fun isDatabasePresent(): Boolean = dbFile.exists()

    fun databaseVersion(): String? {
        if (!open()) return null
        return try {
            db?.rawQuery("SELECT value FROM meta WHERE key = 'version'", null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    fun databaseSizeBytes(): Long = if (dbFile.exists()) dbFile.length() else 0L

    /**
     * Query the speed limit at a geographic point.
     *
     * Strategy:
     * 1. R-tree bounding-box query to find candidate zones within ~50m
     * 2. For each candidate, load its segment coordinates and compute
     *    point-to-segment distance
     * 3. Return the speed_limit of the nearest segment within the threshold
     *
     * @param lat  GPS latitude
     * @param lon  GPS longitude
     * @param maxDistanceMeters  Maximum distance to accept a zone (default 30m —
     *        a GPS reading is typically 3-10m off, and 30m covers the width of
     *        most roads plus GPS error without matching a parallel road)
     * @return speed limit in km/h, or null if no zone found within range
     */
    suspend fun querySpeedLimit(
        lat: Double,
        lon: Double,
        maxDistanceMeters: Double = 30.0,
    ): Int? = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext null
        if (!database.isOpen) return@withContext null

        // Convert the search radius to a degree-based bounding box.
        // 1 degree lat ≈ 111.32 km; scale lon by cos(lat).
        val latDeg = maxDistanceMeters / 111_320.0
        val cosLat = cos(Math.toRadians(lat))
        val lonDeg = if (cosLat < 1e-6) 0.0
            else maxDistanceMeters / (111_320.0 * cosLat)

        // R-tree query: find all zones whose bounding box intersects our search box.
        //
        // The bounds are formatted into the SQL rather than bound as parameters.
        // Android's rawQuery only takes Array<String>, and an R-tree's columns are
        // REAL - so every bound was arriving as text against a virtual table whose
        // whole job is numeric range comparison. These four values are our own
        // arithmetic on a GPS fix, never user input, so there is nothing here to
        // inject.
        val minLon = lon - lonDeg
        val maxLon = lon + lonDeg
        val minLat = lat - latDeg
        val maxLat = lat + latDeg
        val candidates = try {
            database.rawQuery(
                """
                SELECT sz.id, sz.speed_limit
                FROM speed_zones_rtree r
                JOIN speed_zones sz ON sz.id = r.id
                WHERE r.min_lon <= $maxLon AND r.max_lon >= $minLon
                  AND r.min_lat <= $maxLat AND r.max_lat >= $minLat
                """,
                null,
            ).use { cursor ->
                val results = mutableListOf<Pair<Int, Int>>()
                while (cursor.moveToNext()) {
                    results.add(cursor.getInt(0) to cursor.getInt(1))
                    // (id, speed_limit) — we'll need the id to fetch segments
                }
                results
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext null

        if (candidates.isEmpty()) return@withContext null

        // Batch-fetch all segment coordinates for all candidate zones in one query,
        // rather than N+1 individual queries. The IN clause is built from the
        // candidate IDs — safe because they come from our own R-tree, not user input.
        val idList = candidates.joinToString(",") { it.first.toString() }
        val allSegmentsByZone: Map<Int, List<List<Pair<Double, Double>>>> = try {
            database.rawQuery(
                "SELECT zone_id, coords FROM segment_coords WHERE zone_id IN ($idList)",
                null,
            ).use { cursor ->
                val segsByZone = mutableMapOf<Int, List<List<Pair<Double, Double>>>>()
                while (cursor.moveToNext()) {
                    segsByZone[cursor.getInt(0)] = parseSegments(cursor.getBlob(1))
                }
                segsByZone
            }
        } catch (e: Exception) {
            return@withContext null
        }

        // For each candidate zone, find the minimum distance from the GPS point
        // to any of its segments, then pick the zone with the overall minimum.
        var bestSpeedLimit: Int? = null
        var bestDistance = Double.MAX_VALUE

        for (candidate in candidates) {
            val zoneId = candidate.first
            val speedLimit = candidate.second
            val segments = allSegmentsByZone[zoneId] ?: continue

            // Compute min distance from point to any segment in this zone
            for (segment in segments) {
                for (i in 0 until segment.size - 1) {
                    val dist = pointToSegmentMeters(
                        lat, lon,
                        segment[i].second, segment[i].first,       // lat1, lon1
                        segment[i + 1].second, segment[i + 1].first, // lat2, lon2
                    )
                    if (dist < bestDistance) {
                        bestDistance = dist
                        bestSpeedLimit = speedLimit
                    }
                }
            }
        }

        // Only return if the nearest zone is within the distance threshold
        if (bestDistance <= maxDistanceMeters) bestSpeedLimit else null
    }

    companion object {
        private const val DB_FILENAME = "speed_zones.sqlite3"

        /**
         * Unpack one zone's polylines.
         *
         * Per segment: a uint16 point count, then that many (lon, lat) pairs of
         * little-endian int32 microdegrees. Written by `tools/build_speed_db.py`'s
         * `pack()`, and the two have to change together.
         *
         * It was JSON text in a row per polyline, which cost twenty-three bytes a
         * point and put the gzipped asset at 56 MB — a lot of APK for a
         * Victoria-only driving feature. Microdegrees are ~0.11 m, far finer than
         * the 30 m this is then matched against.
         *
         * A truncated blob stops at the last whole segment rather than throwing.
         * The caller treats a zone with no usable segments as no match, which is
         * the right answer for a corrupt row too.
         */
        internal fun parseSegments(blob: ByteArray): List<List<Pair<Double, Double>>> {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val segments = mutableListOf<List<Pair<Double, Double>>>()
            while (buf.remaining() >= 2) {
                val count = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < count * 8) break
                val points = ArrayList<Pair<Double, Double>>(count)
                repeat(count) {
                    // Named rather than `buf.int to buf.int`: that reads correctly only
                    // if you know the evaluation order, and getting it backwards would
                    // silently put the driver in the Bass Strait.
                    val lon = buf.int / 1e6
                    val lat = buf.int / 1e6
                    points.add(lon to lat)
                }
                if (points.size >= 2) segments.add(points)
            }
            return segments
        }

        /**
         * The database asset as it exists at runtime - no `.gz`, because AGP
         * expanded it at build time. See the class doc.
         */
        const val ASSET_NAME = "speed_zones.sqlite3"
    }
}
