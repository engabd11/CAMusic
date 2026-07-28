package com.engabd.sendpin.download

import android.content.Context
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
    val image: String? = null,
)

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
 */
class DownloadManager(
    context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val serializer = ListSerializer(DownloadedTrack.serializer())
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    private val _downloads = MutableStateFlow(loadIndex())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Downloads currently running or failed, so the library can show progress. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs

    fun isDownloaded(id: String): Boolean = _downloads.value.any { it.id == id }
    fun localPath(id: String): String? = _downloads.value.firstOrNull { it.id == id }?.filePath

    /** Fetch [url] (the original file) to local storage and index [item]. */
    suspend fun download(item: MaItem, url: String): Boolean = withContext(Dispatchers.IO) {
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
            val list = _downloads.value + DownloadedTrack(
                id = item.itemId, title = item.name, artist = item.subtitle,
                filePath = file.absolutePath, image = item.image,
            )
            _downloads.value = list
            saveIndex(list)
            clearJob(item.itemId)
            true
        } catch (_: Exception) {
            fail(item)
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
        _downloads.value = list
        saveIndex(list)
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
