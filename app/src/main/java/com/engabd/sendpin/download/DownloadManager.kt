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

    fun isDownloaded(id: String): Boolean = _downloads.value.any { it.id == id }
    fun localPath(id: String): String? = _downloads.value.firstOrNull { it.id == id }?.filePath

    /** Fetch [url] (the original file) to local storage and index [item]. */
    suspend fun download(item: MaItem, url: String): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded(item.itemId)) return@withContext true
        try {
            val file = File(dir, "${item.itemId.hashCode()}.audio")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val stream = resp.body?.byteStream() ?: return@withContext false
                stream.use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val list = _downloads.value + DownloadedTrack(
                id = item.itemId, title = item.name, artist = item.subtitle,
                filePath = file.absolutePath, image = item.image,
            )
            _downloads.value = list
            saveIndex(list)
            true
        } catch (_: Exception) {
            false
        }
    }

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
