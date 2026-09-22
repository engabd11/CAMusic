package com.engabd.sendpin.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.toEntity
import com.engabd.sendpin.local.toModel
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.subsonic.SubsonicClient
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.engabd.sendpin.data.Http
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class DownloadedTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val filePath: String,
    /** The server's cover URL — useless offline, kept only to re-fetch on demand. */
    val image: String? = null,
    val album: String? = null,
    /** Absolute path to the cover cached beside the audio, so art survives offline. */
    val coverPath: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int? = null,
    /**
     * Disc number, so a downloaded box set plays in the order it was pressed in.
     *
     * Defaulted, so an index written before this field existed still loads — those
     * rows simply sort by track number alone, as they did before.
     */
    val discNumber: Int? = null,
    /** The album this came from, so a downloaded album can be grouped and played whole. */
    val albumId: String? = null,
    /**
     * The stored file's own format, recorded at download time.
     *
     * A download is the original file, so the library's reading of it stays true for
     * as long as the file exists — and offline is exactly when there is no server left
     * to ask. Without it, playing from the Downloads shelf fell back to describing the
     * phone's *output* ("PCM 48/16") rather than the FLAC actually on disk.
     *
     * Defaulted, so an index written before this field existed still loads; those
     * entries simply have nothing to say until they are downloaded again.
     */
    val format: MaAudioFormat? = null,
    /**
     * The library this file came from, as a `MusicSource.providerId`.
     *
     * Downloads used to be Subsonic-only, so "which server does this id belong to" had
     * one answer and nobody had to ask. With a second library that can serve them, an
     * id alone is ambiguous — and reporting a Navidrome play to Jellyfin is a play
     * neither of them records. Defaulted for indexes written before this existed;
     * those entries fall back to Navidrome, which is what they were.
     */
    val sourceProvider: String? = null,
) {
    /** Cover art that works with the server gone: the local copy if we cached one. */
    val artUri: String? get() = coverPath?.takeIf { File(it).exists() }?.let { "file://$it" } ?: image

    fun toLocalTrack(streamUrl: String? = null) = LocalTrack(
        id = id, title = title, artist = artist, album = album,
        durationMs = durationMs, artUrl = artUri, streamUrl = streamUrl, localPath = filePath,
        sourceQuality = format?.quality,
    )
}

/** A download in flight (or one that failed), for the library's progress rows. */
data class DownloadJob(
    val id: String,
    val title: String,
    val artist: String?,
    val fraction: Float,
    val failed: Boolean = false,
    /**
     * The library this download was for, so a failed one can be retried against the
     * server that has the file. Without it a retry is a guess, and with two libraries
     * configured a guess is wrong half the time.
     */
    val provider: String? = null,
    /** Kept so a retry can rebuild the request without the library screen's help. */
    val album: String? = null,
    val image: String? = null,
    /** Why it failed, in a few words, for the row. Null while running or when it is not known. */
    val reason: String? = null,
)

/**
 * Downloads original audio files for offline playback and tracks them in a small
 * JSON index (no Room/annotation-processing, so the build stays simple). The
 * original file (not a transcode) is fetched so offline stays bit-perfect-ready.
 *
 * Album art is cached alongside the audio. Without it every downloaded track lost
 * its cover the moment the server went away — which is exactly when the downloads
 * are being used.
 *
 * Process-scoped (see `SendpinApp.downloads`) so the index is one list wherever it
 * is read from.
 */
class DownloadManager(
    private val context: Context,
    // Was built with no timeouts at all, so a server that accepted the connection and
    // then stopped sending held the job open for ever. [Http.transfer] keeps the read
    // timeout and drops only the overall call deadline, which a large file needs.
    private val http: OkHttpClient = Http.transfer(),
) {
    /**
     * The runs themselves live here, not in whichever ViewModel asked. An artist's
     * discography launched from its screen used to die with that screen's
     * `viewModelScope`: back out to the library while it was running and the file in
     * flight was marked failed, the rest never started. [downloadAll] runs on this
     * and the caller only *awaits* it, so leaving the screen drops the summary toast
     * and nothing else.
     */
    private val runs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(DownloadedTrack.serializer())
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val coverDir = File(dir, "covers").apply { mkdirs() }
    /** Legacy JSON index, imported into Room on first access then removed. */
    private val indexFile = File(dir, "index.json")

    private val dao = com.engabd.sendpin.local.db.LocalMediaDatabase.get(context).downloadDao()

    /**
     * Playlist membership, so deleting a file cannot leave a playlist pointing at it.
     *
     * The DAO directly rather than `SendpinApp.downloadedPlaylists`: this class takes
     * a plain `Context` and is constructed before the app's lazies are touched.
     */
    private val playlistDao =
        com.engabd.sendpin.local.db.LocalMediaDatabase.get(context).downloadPlaylistDao()

    private companion object {
        const val TAG = "DownloadManager"
        /** Goes at one file before it is called failed. */
        const val ATTEMPTS = 3
        /** Between goes, times the attempt number — a server saying "not now" wants a moment. */
        const val RETRY_BACKOFF_MS = 1500L
    }

    private val _downloads = MutableStateFlow(emptyList<DownloadedTrack>())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads

    private val _jobs = MutableStateFlow(emptyList<DownloadJob>())
    /** Downloads currently running or failed, so the library can show progress. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    /** One-shot migration + flow collection. */
    private val initJob: kotlinx.coroutines.Job

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        initJob = scope.launch {
            migrateLegacyIndex()
            dao.observeAll().collect { entities ->
                _downloads.value = entities.map { it.toModel() }
            }
        }
    }

    /**
     * Suspending entry point for tests: import the legacy JSON index once, then
     * collect the Room flow until the first emission.
     */
    suspend fun awaitInitialization() {
        initJob.join()
    }

    /**
     * Import the old JSON index into Room once, then delete the file.
     *
     * Existing installs have a JSON index; new installs have no file and simply
     * start with an empty Room table. The migration is idempotent because inserts
     * use REPLACE.
     */
    private suspend fun migrateLegacyIndex() {
        if (!indexFile.exists()) return
        val legacy = try {
            json.decodeFromString(serializer, indexFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
        if (legacy.isNotEmpty()) {
            dao.insertAll(legacy.map { it.toEntity() })
        }
        runCatching { indexFile.delete() }
    }

    /** Whether the device is currently on Wi-Fi. */
    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true  // if we can't check, don't block
        val info = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return info.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Enforce the storage cap: delete the oldest downloaded tracks until the total
     * is under the cap (in MB). A cap of 0 means unlimited. Called after every
     * successful download and on app start.
     *
     * The MB-to-bytes conversion is [AppSettings.storageCapBytes] and not a local
     * multiply: this used to evict at MiB while the other half of the same feature
     * refused new downloads at MB.
     */
    private suspend fun enforceStorageCap(capMb: Int) {
        val capBytes = AppSettings.storageCapBytes(capMb) ?: return
        // Never the track being listened to. Deleting the file out from under the
        // player is the one eviction a user would experience as the app breaking, and
        // it is also what the Downloads settings page promises does not happen.
        val playing = protectedId
        while (bytesUsed() > capBytes) {
            val oldest = _downloads.value
                .filterNot { it.id == playing }
                .minByOrNull { runCatching { File(it.filePath).lastModified() }.getOrDefault(Long.MAX_VALUE) }
                ?: return   // nothing left that may be evicted
            delete(oldest.id)
        }
    }

    /**
     * A download the cap may not evict — the one currently playing.
     *
     * Published by whoever owns playback rather than read from it, so this class keeps
     * no reference to the player.
     */
    @Volatile
    var protectedId: String? = null

    fun isDownloaded(id: String): Boolean = _downloads.value.any { it.id == id }
    fun localPath(id: String): String? = _downloads.value.firstOrNull { it.id == id }?.filePath
    fun get(id: String): DownloadedTrack? = _downloads.value.firstOrNull { it.id == id }

    /**
     * A library item as something [com.engabd.sendpin.audio.LocalPlayer] can open.
     *
     * This lives here because the offline copy is the interesting half: a
     * downloaded track is bit-identical, costs no bandwidth and keeps playing when
     * the server doesn't answer, so it wins over [streamUrl] wherever one exists.
     * [localPathFallback] covers an item that already *is* a download and carries
     * its own path.
     */
    fun toLocalTrack(item: MaItem, streamUrl: String? = null, localPathFallback: String? = null): LocalTrack {
        val dl = get(item.itemId)
        return LocalTrack(
            id = item.itemId,
            title = item.name,
            artist = item.subtitle,
            album = item.album ?: dl?.album,
            durationMs = (item.duration ?: 0).toLong() * 1000,
            artUrl = dl?.artUri ?: item.image,
            // First tag only. Servers hand back anything from one word to a
            // semicolon-separated list, the rules match loosely in both
            // directions anyway, and a track's first genre is the one that
            // describes it.
            genre = item.genres.firstOrNull(),
            streamUrl = streamUrl,
            localPath = dl?.filePath ?: localPathFallback,
            // `MaAudioFormat.quality` rather than a hand-rolled copy: this used to
            // list the fields positionally and stop after `bitDepth`, so the bitrate,
            // the channel count and the file size were dropped on the floor for every
            // Navidrome and offline track — which is the whole reason the badge read
            // "FLAC • 96/24" for a file the server had already told us was 3 Mb/s.
            //
            // The index entry is the fallback, not the first choice: the library's
            // live reading is the fresher one, and a download recorded before this
            // field existed has nothing stored at all.
            sourceQuality = (item.audioFormat ?: dl?.format)?.quality,
            composer = item.composer,
            // The library id and the library it belongs to, filled in here rather than
            // by one caller. `LibraryViewModel` set them and the three detail screens
            // did not, so a track started from an album page carried no library at
            // all — which silently took its scrobble and its download chip with it.
            scrobbleId = item.itemId.takeIf { MusicSources.isLocalProvider(item.provider) },
            scrobbleProvider = when {
                item.provider == MusicSources.DOWNLOAD_PROVIDER ->
                    dl?.sourceProvider ?: SubsonicClient.PROVIDER
                MusicSources.isLocalProvider(item.provider) -> item.provider
                else -> null
            },
        )
    }

    /** Total bytes on disk, for the Downloads header. */
    fun bytesUsed(): Long = _downloads.value.sumOf { runCatching { File(it.filePath).length() }.getOrDefault(0L) }

    /**
     * Fetch [url] (the original file) to local storage and index [item].
     * [coverUrl] is cached beside it when given, so offline playback keeps its art.
     *
     * [wifiOnly] and [storageCapMb] are enforced here rather than at the call site:
     * a Wi-Fi check has to happen at the moment the download starts (not when it
     * was queued), and the storage cap has to be checked after each file lands.
     */
    suspend fun download(
        item: MaItem,
        url: String,
        coverUrl: String? = null,
        wifiOnly: Boolean = false,
        storageCapMb: Int = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded(item.itemId)) return@withContext true
        if (wifiOnly && !isOnWifi(context)) {
            // Mark as failed so the UI shows why, rather than silently skipping.
            fail(item, "Not on Wi-Fi")
            return@withContext false
        }
        putJob(job(item, 0f))
        val file = File(dir, "${item.itemId.hashCode()}.audio")
        try {
            // A whole album is twenty requests back to back to one server, and one
            // of them dropping — a reset connection, a read that stalls, a 503 from
            // a server busy with the previous file — used to fail that track for
            // good while its neighbours landed, so taking an artist offline meant
            // pressing Download until the failed rows stopped appearing. Each file
            // now gets [ATTEMPTS] goes at the things another go can fix: transport
            // errors and the server saying "not now". A 404 or a 401 is the same
            // answer every time and is not retried.
            var attempt = 0
            while (true) {
                attempt++
                val outcome = try {
                    fetch(item, url, file)
                } catch (e: IOException) {
                    Log.w(TAG, "download ${item.name}: attempt $attempt failed: $e")
                    Outcome.Retry(e.message ?: e.javaClass.simpleName)
                }
                when (outcome) {
                    Outcome.Done -> break
                    is Outcome.Refused -> return@withContext fail(item, outcome.reason, file)
                    is Outcome.Retry -> {
                        if (attempt >= ATTEMPTS) return@withContext fail(item, outcome.reason, file)
                        putJob(job(item, 0f))
                        delay(RETRY_BACKOFF_MS * attempt)
                    }
                }
            }
            val cover = cacheCover(item, coverUrl ?: item.image)
            val entity = DownloadedTrack(
                id = item.itemId, title = item.name, artist = item.subtitle,
                filePath = file.absolutePath, image = item.image,
                album = item.album, coverPath = cover?.absolutePath,
                durationMs = (item.duration ?: 0).toLong() * 1000,
                trackNumber = item.trackNumber, discNumber = item.discNumber,
                albumId = item.parentId,
                format = item.audioFormat,
                sourceProvider = item.provider,
            ).toEntity()
            dao.insert(entity)
            clearJob(item.itemId)
            enforceStorageCap(storageCapMb)
            true
        } catch (e: CancellationException) {
            // The run was cancelled, not the file refused: leave no half-written
            // file and no red row behind for it.
            runCatching { file.delete() }
            clearJob(item.itemId)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download ${item.name}: $e")
            fail(item, e.message ?: e.javaClass.simpleName, file)
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome
        /** Worth another go: the transport failed, or the server said "not now". */
        data class Retry(val reason: String) : Outcome
        /** The server answered, and asking again gets the same answer. */
        data class Refused(val reason: String) : Outcome
    }

    /** One attempt at [url] into [file], publishing progress as it goes. Throws [IOException] on the wire. */
    private fun fetch(item: MaItem, url: String, file: File): Outcome {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 5xx and 429 are the server's "not now"; 408 is a request timeout.
                if (resp.code >= 500 || resp.code == 429 || resp.code == 408) return Outcome.Retry("Server said ${resp.code}")
                return Outcome.Refused(
                    when (resp.code) {
                        // Jellyfin answers a download the user is not allowed with 401
                        // even on a valid token (its GetDownload returns Unauthorized
                        // when the user lacks "Allow media downloading"); Emby and
                        // Navidrome say 403. Either way it is the account, not the link.
                        401, 403 -> "Not allowed for this user (${resp.code}) — check the account's download permission on the server"
                        404 -> "Not on the server any more (404)"
                        else -> "Server said ${resp.code}"
                    },
                )
            }
            val body = resp.body ?: return Outcome.Retry("Empty response")
            val total = body.contentLength()
            var read = 0L
            var lastPublished = 0f
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val f = (read.toFloat() / total).coerceIn(0f, 1f)
                            // Only republish on whole-percent moves — a 64 KiB
                            // buffer would otherwise recompose the row hundreds
                            // of times per file.
                            if (f - lastPublished >= 0.01f) {
                                lastPublished = f
                                putJob(job(item, f))
                            }
                        }
                    }
                }
            }
            // A body that ended early is a dropped connection the stream did not
            // report as one — the file is short, and playing it would cut off.
            if (total > 0 && read < total) return Outcome.Retry("Connection dropped at ${read * 100 / total}%")
        }
        return Outcome.Done
    }

    /**
     * Download several tracks one after another. Sequential on purpose: a phone on
     * house wifi pulling twenty FLACs at once just makes every one of them slow, and
     * the progress rows become unreadable.
     *
     * [wifiOnly] and [storageCapMb] are passed through to each [download] call so
     * the check happens at the moment each file starts, not just the first.
     *
     * Returns how many landed. Already-downloaded tracks count as successes.
     */
    suspend fun downloadAll(
        items: List<MaItem>,
        urlFor: (MaItem) -> String,
        coverFor: (MaItem) -> String? = { it.image },
        wifiOnly: Boolean = false,
        storageCapMb: Int = 0,
    ): Int = runs.async {
        var ok = 0
        for (item in items) {
            if (item.mediaType != "track") continue
            if (download(item, urlFor(item), coverFor(item), wifiOnly, storageCapMb)) ok++
        }
        ok
    }.await()

    /**
     * Covers are shared by every track on an album, so they are keyed by album id
     * where there is one — a 12-track album caches one JPEG, not twelve.
     */
    private fun cacheCover(item: MaItem, url: String?): File? {
        if (url.isNullOrBlank()) return null
        val key = (item.parentId ?: item.itemId).hashCode()
        val file = File(coverDir, "$key.img")
        if (file.exists() && file.length() > 0) return file
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) return null
                body.byteStream().use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            }
            file.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun fail(item: MaItem, reason: String? = null, partial: File? = null): Boolean {
        // A half-written file is not a download, and the next attempt overwrites it
        // anyway; leaving it made the storage total lie by the size of every failure.
        partial?.let { runCatching { it.delete() } }
        putJob(job(item, 0f, failed = true, reason = reason))
        return false
    }

    /** A progress row for [item], carrying enough to retry it unaided. */
    private fun job(item: MaItem, fraction: Float, failed: Boolean = false, reason: String? = null) = DownloadJob(
        id = item.itemId,
        title = item.name,
        artist = item.subtitle,
        fraction = fraction,
        failed = failed,
        reason = reason,
        provider = item.provider,
        album = item.album,
        image = item.image,
    )

    private fun putJob(job: DownloadJob) {
        _jobs.value = _jobs.value.filterNot { it.id == job.id } + job
    }

    private fun clearJob(id: String) {
        _jobs.value = _jobs.value.filterNot { it.id == id }
    }

    /** Drop a failed entry (e.g. once the user has retried it). */
    fun dismissJob(id: String) = clearJob(id)

    /**
     * Suspending, and on [Dispatchers.IO], because both halves of a delete are disk:
     * unlinking the file (and its cover) and writing the row out of Room. These used
     * to be plain functions that did the unlink inline and wrapped the Room write in
     * `runBlocking`, and every caller outside the eviction loop is a tap handler —
     * so a delete ran the whole thing on the main thread. One track was survivable;
     * "Delete all downloads" over a filled cap is thousands of `unlink` calls and a
     * full table wipe with the UI thread blocked behind them, which is an ANR.
     */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        val entry = _downloads.value.firstOrNull { it.id == id } ?: return@withContext
        runCatching { File(entry.filePath).delete() }
        // The cover is shared across an album — only bin it once the last track goes.
        entry.coverPath?.let { path ->
            if (_downloads.value.none { it.id != id && it.coverPath == path }) runCatching { File(path).delete() }
        }
        dao.delete(id)
        // Hygiene rather than correctness: a playlist read joins onto `downloads`, so
        // a membership row whose file is gone is already invisible. Left alone it
        // would sit in the table for the life of the install, and the storage-cap
        // eviction loop deletes enough tracks over time for that to add up.
        runCatching { playlistDao.forgetTrack(id) }
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        _downloads.value.forEach { runCatching { File(it.filePath).delete() } }
        runCatching { coverDir.listFiles()?.forEach { it.delete() } }
        dao.deleteAll()
        // The playlists go with the files. Keeping them would leave a Downloads
        // library full of empty playlists after "delete everything", which reads as
        // the delete having failed.
        runCatching { playlistDao.removeAll() }
    }
}
