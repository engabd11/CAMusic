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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

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
 * is what is committed (in git-lfs), but the asset this code opens is called
 * `speed_zones.sqlite3`, without the suffix, and arrives already expanded:
 * **AGP's asset merger gunzips any `.gz` under `assets/` at build time.** The gzip
 * is purely a way to keep 78 MB out of the git history — AAPT then deflates it back
 * down to ~39 MB inside the APK on its own, and neither end needs a
 * `GZIPInputStream`. The proof that the merge step is real is in `.github/workflows`:
 * a checkout without `lfs: true` fails at `mergeAssets` with "Not in GZIP format",
 * because the merger tried to gunzip a 130-byte pointer file.
 *
 * So the missing suffix is correct and must not be "fixed". [expandAsset] does now
 * accept either name and gunzips or does not according to the bytes rather than the
 * suffix — see [ASSET_NAMES] and [GZIP_MAGIC] — but that is belt-and-braces against
 * a build-tool behaviour this code cannot see and does not control, not a fix for
 * anything that was failing.
 *
 * It is copied into the app's files directory once, the first time driving mode
 * actually needs it - not at install time, and never on the main thread. SQLite
 * cannot open a database inside an APK, which is what the copy is for.
 *
 * It used to be described as a downloaded asset, and SpeedLimitDownloadManager was
 * written to fetch it. Nothing ever instantiated that class and no URL was ever
 * configured, so the settings page promised a monthly-updated download that no code
 * path could perform. Bundling is what makes the claim true; the downloader is gone.
 */
class SpeedLimitDatabase(context: Context) {

    private val appContext = context.applicationContext
    private val dbFile: File = File(context.filesDir, DB_FILENAME)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Why the data is not available, or null while it is fine or still arriving.
     *
     * The settings card could previously only ever say "Unpacking speed-limit
     * data…", and said it forever when the unpack could not succeed — which is
     * precisely the state every device was in. A failure that the driver can read
     * is the difference between a bug report and a shrug.
     */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

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
                // A file that is present but not a database is worse than no file:
                // `dbFile.exists()` is the only thing standing between a bad expand
                // and another attempt, so a half-written or truncated one has to be
                // cleared here or auto-detect stays broken until the app is
                // reinstalled. [looksLikeSqlite] is what tells the two apart.
                if (dbFile.exists() && !looksLikeSqlite(dbFile)) dbFile.delete()
                if (!dbFile.exists()) expandAsset()
                if (!open()) {
                    // Expanded but unopenable. Drop it so the next start retries
                    // rather than inheriting the same unusable file forever.
                    if (dbFile.exists()) {
                        dbFile.delete()
                        if (_failure.value == null) _failure.value = "Speed-limit data could not be opened."
                    }
                }
            } finally {
                expanding.set(false)
            }
        }
    }

    /**
     * Copy the shipped asset out to [dbFile], where SQLite can open it.
     *
     * ## Both names, and the bytes decide
     *
     * The asset is looked for under [ASSET_NAMES] in order, and whether to gunzip is
     * decided by *sniffing its first two bytes* rather than by its suffix. Neither
     * is load-bearing today — a normal build hands us the plain, already-expanded
     * name — but the alternative is a runtime that silently produces no speed limits
     * if the merger's gunzip behaviour ever changes, which is not a failure anyone
     * would connect back to a build tool. Both questions are cheap and both are
     * answered from the file itself.
     *
     * Written to a temp file and renamed, so being killed mid-copy leaves no
     * half-written database for [open] to find and fail on — and verified before the
     * rename, so a truncated stream never becomes a permanently broken install.
     * That last part is the one that was genuinely missing: [prepare] only re-expands
     * when the file is *absent*, so any bad expand used to be permanent.
     */
    private fun expandAsset() {
        val tmp = File(dbFile.parentFile, DB_FILENAME + ".tmp")
        try {
            tmp.parentFile?.mkdirs()
            val opened = ASSET_NAMES.firstNotNullOfOrNull { name ->
                runCatching { appContext.assets.open(name) }.getOrNull()
            }
            if (opened == null) {
                _failure.value = "No speed-limit data in this build of the app."
                return
            }
            opened.use { raw ->
                decompressIfGzipped(raw).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
            }
            if (!looksLikeSqlite(tmp)) {
                _failure.value = "Speed-limit data is damaged; auto-detect is using your manual limit."
                tmp.delete()
                return
            }
            if (tmp.renameTo(dbFile)) {
                _failure.value = null
            } else {
                _failure.value = "Speed-limit data could not be saved to this phone."
                tmp.delete()
            }
        } catch (e: Exception) {
            // No room on the device, a truncated stream, a read error: all of them
            // mean the same thing to the caller, which is that auto-detect has no
            // data and the manual limit stands. The message is for the driver.
            _failure.value = "Speed-limit data could not be unpacked (${e.javaClass.simpleName})."
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
         * Where the bundled database might be, most-likely first.
         *
         * The plain name is what a normal build produces, because AGP's asset merger
         * has already gunzipped the committed `.gz` — see the class doc. The `.gz`
         * name is a fallback for a build that did not, and costs one failed
         * `assets.open` when it is not needed.
         */
        val ASSET_NAMES = listOf("speed_zones.sqlite3", "speed_zones.sqlite3.gz")

        /**
         * The stream, gunzipped if it is gzip.
         *
         * Sniffed rather than assumed. `AssetManager.open` already undoes the APK's own
         * deflate, so what arrives here is whatever was committed — and that is the
         * thing worth checking, because the committed file's name and the committed
         * file's format have disagreed once already.
         */
        internal fun decompressIfGzipped(raw: InputStream): InputStream {
            val buffered = BufferedInputStream(raw)
            buffered.mark(GZIP_MAGIC.size)
            val head = readExactly(buffered, GZIP_MAGIC.size)
            buffered.reset()
            return if (head != null && head.contentEquals(GZIP_MAGIC)) GZIPInputStream(buffered) else buffered
        }

        /**
         * Whether [file] starts with SQLite's own header.
         *
         * The cheapest possible answer to "did the expand actually produce a database",
         * and the guard that keeps one bad unpack from being permanent: [prepare] only
         * ever re-expands when the file is missing, so a 40-byte truncation would
         * otherwise sit there failing to open for the life of the install.
         */
        internal fun looksLikeSqlite(file: File): Boolean = runCatching {
            file.inputStream().use { stream -> readExactly(stream, SQLITE_MAGIC.size)?.contentEquals(SQLITE_MAGIC) == true }
        }.getOrDefault(false)

        /**
         * [count] bytes from [stream], or null if it ends first.
         *
         * A loop rather than one `read`, because a single `read(ByteArray)` is allowed
         * to return fewer bytes than asked for and both callers here are comparing the
         * result against a fixed magic number — a short read would quietly read as "not
         * gzip" or "not a database" and take the wrong branch on a perfectly good file.
         */
        private fun readExactly(stream: InputStream, count: Int): ByteArray? {
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = stream.read(buffer, filled, count - filled)
                if (read <= 0) return null
                filled += read
            }
            return buffer
        }

        /** gzip's two-byte header. See [decompressIfGzipped]. */
        private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())

        /** The first six bytes of `SQLite format 3\u0000`. See [looksLikeSqlite]. */
        private val SQLITE_MAGIC = "SQLite".toByteArray(Charsets.US_ASCII)
    }
}
