package com.engabd.sendpin.download

import android.content.Context
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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
    /** The album this came from, so a downloaded album can be grouped and played whole. */
    val albumId: String? = null,
) {
    /** Cover art that works with the server gone: the local copy if we cached one. */
    val artUri: String? get() = coverPath?.takeIf { File(it).exists() }?.let { "file://$it" } ?: image

    fun toLocalTrack(streamUrl: String? = null) = LocalTrack(
        id = id, title = title, artist = artist, album = album,
        durationMs = durationMs, artUrl = artUri, streamUrl = streamUrl, localPath = filePath,
    )
}

/** A download in flight (or one that failed), for the library's progress rows. */
data class DownloadJob(
    val id: String,
    val title: String,
    val artist: String?,
    val fraction: Float,
    val failed: Boolean = false,
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
    context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(DownloadedTrack.serializer())
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val coverDir = File(dir, "covers").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    private val _downloads = MutableStateFlow(loadIndex())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Downloads currently running or failed, so the library can show progress. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs

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
            streamUrl = streamUrl,
            localPath = dl?.filePath ?: localPathFallback,
            sourceQuality = item.audioFormat?.let {
                StreamQuality(it.codec, it.sampleRate, it.bitDepth)
            },
        )
    }

    /** Total bytes on disk, for the Downloads header. */
    fun bytesUsed(): Long = _downloads.value.sumOf { runCatching { File(it.filePath).length() }.getOrDefault(0L) }

    /**
     * Fetch [url] (the original file) to local storage and index [item].
     * [coverUrl] is cached beside it when given, so offline playback keeps its art.
     */
    suspend fun download(item: MaItem, url: String, coverUrl: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded(item.itemId)) return@withContext true
        putJob(DownloadJob(item.itemId, item.name, item.subtitle, 0f))
        try {
            val file = File(dir, "${item.itemId.hashCode()}.audio")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext fail(item)
                val body = resp.body ?: return@withContext fail(item)
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
                                    putJob(DownloadJob(item.itemId, item.name, item.subtitle, f))
                                }
                            }
                        }
                    }
                }
            }
            val cover = cacheCover(item, coverUrl ?: item.image)
            val list = _downloads.value + DownloadedTrack(
                id = item.itemId, title = item.name, artist = item.subtitle,
                filePath = file.absolutePath, image = item.image,
                album = item.album, coverPath = cover?.absolutePath,
                durationMs = (item.duration ?: 0).toLong() * 1000,
                trackNumber = item.trackNumber, albumId = item.parentId,
            )
            _downloads.value = list
            saveIndex(list)
            clearJob(item.itemId)
            true
        } catch (_: Exception) {
            fail(item)
        }
    }

    /**
     * Download several tracks one after another. Sequential on purpose: a phone on
     * house wifi pulling twenty FLACs at once just makes every one of them slow, and
     * the progress rows become unreadable.
     *
     * Returns how many landed. Already-downloaded tracks count as successes.
     */
    suspend fun downloadAll(
        items: List<MaItem>,
        urlFor: (MaItem) -> String,
        coverFor: (MaItem) -> String? = { it.image },
    ): Int {
        var ok = 0
        for (item in items) {
            if (item.mediaType != "track") continue
            if (download(item, urlFor(item), coverFor(item))) ok++
        }
        return ok
    }

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

    private fun fail(item: MaItem): Boolean {
        putJob(DownloadJob(item.itemId, item.name, item.subtitle, 0f, failed = true))
        return false
    }

    private fun putJob(job: DownloadJob) {
        _jobs.value = _jobs.value.filterNot { it.id == job.id } + job
    }

    private fun clearJob(id: String) {
        _jobs.value = _jobs.value.filterNot { it.id == id }
    }

    /** Drop a failed entry (e.g. once the user has retried it). */
    fun dismissJob(id: String) = clearJob(id)

    fun delete(id: String) {
        val entry = _downloads.value.firstOrNull { it.id == id } ?: return
        runCatching { File(entry.filePath).delete() }
        val list = _downloads.value.filterNot { it.id == id }
        // The cover is shared across an album — only bin it once the last track goes.
        entry.coverPath?.let { path ->
            if (list.none { it.coverPath == path }) runCatching { File(path).delete() }
        }
        _downloads.value = list
        saveIndex(list)
    }

    fun deleteAll() {
        _downloads.value.forEach { runCatching { File(it.filePath).delete() } }
        runCatching { coverDir.listFiles()?.forEach { it.delete() } }
        _downloads.value = emptyList()
        saveIndex(emptyList())
    }

    private fun loadIndex(): List<DownloadedTrack> = try {
        if (indexFile.exists()) json.decodeFromString(serializer, indexFile.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveIndex(list: List<DownloadedTrack>) {
        runCatching { indexFile.writeText(json.encodeToString(serializer, list)) }
    }
}
