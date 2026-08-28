package com.engabd.sendpin.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Manages the offline speed-limit SQLite database on the device.
 *
 * The database is produced by the `build_speed_db.py` pipeline from Transport
 * Victoria's open Speed Zones GeoJSON, and contains:
 *  - `speed_zones` — one row per road segment with speed_limit, direction, etc.
 *  - `speed_zones_rtree` — an R-tree virtual table for fast bounding-box lookups
 *  - `segment_coords` — the actual polyline coordinates for nearest-segment matching
 *  - `meta` — version, source, generation date
 *
 * The database is a downloaded asset, not bundled in the APK — it's ~40-80MB
 * after processing (from 454MB GeoJSON). It lives in the app's files directory
 * and is checked for existence + version on first use.
 *
 * Download logic is in [SpeedLimitDownloadManager]; this class only handles
 * opening and querying an already-present database.
 */
class SpeedLimitDatabase(context: Context) {

    private val dbFile: File = File(context.filesDir, DB_FILENAME)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var db: SQLiteDatabase? = null

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

        // R-tree query: find all zones whose bounding box intersects our search box
        val candidates = try {
            database.rawQuery(
                """
                SELECT sz.id, sz.speed_limit
                FROM speed_zones_rtree r
                JOIN speed_zones sz ON sz.id = r.id
                WHERE r.min_lon <= ? AND r.max_lon >= ?
                  AND r.min_lat <= ? AND r.max_lat >= ?
                """,
                arrayOf(
                    (lon + lonDeg).toString(),
                    (lon - lonDeg).toString(),
                    (lat + latDeg).toString(),
                    (lat - latDeg).toString(),
                ),
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
        val candidateIds = candidates.map { it.first }
        val placeholders = candidateIds.joinToString(",") { "?" }
        val allSegmentsByZone: Map<Int, List<List<Pair<Double, Double>>>> = try {
            database.rawQuery(
                "SELECT zone_id, coords FROM segment_coords WHERE zone_id IN ($placeholders) ORDER BY zone_id, segment_idx",
                candidateIds.map { it.toString() }.toTypedArray(),
            ).use { cursor ->
                val segsByZone = mutableMapOf<Int, MutableList<List<Pair<Double, Double>>>>()
                while (cursor.moveToNext()) {
                    val zoneId = cursor.getInt(0)
                    val coordsJson = cursor.getString(1)
                    segsByZone.getOrPut(zoneId) { mutableListOf() }
                        .add(parseCoords(coordsJson))
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

    /**
     * Parse a JSON array of [lon, lat] pairs into a list of (lon, lat) pairs.
     * The coordinates are stored as [[lon, lat], [lon, lat], ...] in the database.
     */
    private fun parseCoords(json: String): List<Pair<Double, Double>> {
        val arr = JSONArray(json)
        val result = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until arr.length()) {
            val point = arr.getJSONArray(i)
            result.add(point.getDouble(0) to point.getDouble(1)) // (lon, lat)
        }
        return result
    }

    companion object {
        private const val DB_FILENAME = "speed_zones.sqlite3"
    }
}

/**
 * Downloads the speed-limit SQLite database from a configurable URL.
 *
 * The URL points to a pre-built database file (produced by `build_speed_db.py`
 * and hosted — for now, this can be a GitHub release asset, a Tailscale-served
 * file, or any HTTPS URL). The download streams to a temp file and atomically
 * renames on completion.
 *
 * This is a simple file download, not a complex sync protocol: the database
 * is a static file that's regenerated monthly when Transport Victoria publishes
 * new data. Version checking is by comparing the `meta.version` in the local
 * DB against the remote version string (fetched separately).
 */
class SpeedLimitDownloadManager(
    private val context: Context,
    private val database: SpeedLimitDatabase,
) {

    /**
     * Download the database file from [url] to the app's files directory.
     * Streams to a .tmp file and renames atomically on success.
     *
     * @param url  HTTPS URL to the pre-built SQLite database
     * @param onProgress  callback with bytes downloaded and total (-1 if unknown)
     * @return true if the file was downloaded and is a valid SQLite database
     */
    suspend fun download(
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(context.filesDir, "speed_zones.sqlite3")
        val tmpFile = File(context.filesDir, "speed_zones.sqlite3.tmp")
        val backupFile = File(context.filesDir, "speed_zones.sqlite3.bak")

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            // Use response.use { } to guarantee the response (and its connection)
            // is closed on all paths — success, failure, and exception.
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext false

                val body = resp.body ?: return@withContext false
                val totalBytes = body.contentLength()

                tmpFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            ensureActive() // cancel the download if the coroutine is cancelled
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
            }

            // Safe rename: back up the existing file, then move the new one into
            // place. If the rename fails, restore the backup — so a failed
            // download never leaves the user without their existing database.
            if (targetFile.exists()) {
                if (backupFile.exists()) backupFile.delete()
                targetFile.renameTo(backupFile)
            }
            if (!tmpFile.renameTo(targetFile)) {
                // Restore the previous database
                if (backupFile.exists()) backupFile.renameTo(targetFile)
                return@withContext false
            }
            backupFile.delete()

            // Verify the file is a valid SQLite database
            database.close()
            database.open()
        } catch (e: Exception) {
            tmpFile.delete()
            false
        }
    }

    fun databaseFile(): File = File(context.filesDir, "speed_zones.sqlite3")
}