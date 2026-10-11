package com.engabd.sendpin.download

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.util.DurableFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A download asked for and not yet finished, written down so it survives the process.
 *
 * Downloads ran in an in-process scope and nowhere else: backgrounding the app for
 * long enough, the system reclaiming it, or a reboot, and the rest of an album simply
 * never arrived — with nothing on screen afterwards to say it had been asked for.
 * Each entry carries what [DownloadManager.download] needs to run unaided, including
 * the URL, so a restarted process can pick the queue up without a library screen
 * open.
 */
@Serializable
data class QueuedDownload(
    val itemId: String,
    val provider: String,
    val name: String,
    val subtitle: String? = null,
    val album: String? = null,
    val image: String? = null,
    val duration: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val parentId: String? = null,
    val audioFormat: MaAudioFormat? = null,
    val url: String,
    val coverUrl: String? = null,
    val wifiOnly: Boolean = false,
    val storageCapMb: Int = 0,
) {
    fun toItem(): MaItem = MaItem(
        itemId = itemId, provider = provider, name = name, uri = null, mediaType = "track",
        subtitle = subtitle, image = image, duration = duration, audioFormat = audioFormat,
        trackNumber = trackNumber, discNumber = discNumber, parentId = parentId, album = album,
    )

    companion object {
        fun of(item: MaItem, url: String, coverUrl: String?, wifiOnly: Boolean, storageCapMb: Int) = QueuedDownload(
            itemId = item.itemId, provider = item.provider, name = item.name, subtitle = item.subtitle,
            album = item.album, image = item.image, duration = item.duration,
            trackNumber = item.trackNumber, discNumber = item.discNumber, parentId = item.parentId,
            audioFormat = item.audioFormat, url = url, coverUrl = coverUrl,
            wifiOnly = wifiOnly, storageCapMb = storageCapMb,
        )
    }
}

/** The queue on disk: a small JSON file, replaced atomically on every change. */
class DownloadQueue(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(QueuedDownload.serializer())
    private val lock = Any()

    fun load(): List<QueuedDownload> = synchronized(lock) {
        DurableFile.read(file) { json.decodeFromString(serializer, it) } ?: emptyList()
    }

    fun add(entries: List<QueuedDownload>) = synchronized(lock) {
        if (entries.isEmpty()) return
        val current = load()
        // `add` returns false for a key already seen, so this also drops a repeat
        // within the batch itself, not only one already on disk.
        val keys = current.map { it.provider to it.itemId }.toHashSet()
        write(current + entries.filter { keys.add(it.provider to it.itemId) })
    }

    fun remove(provider: String, itemId: String) = synchronized(lock) {
        val current = load()
        val next = current.filterNot { it.provider == provider && it.itemId == itemId }
        if (next.size != current.size) write(next)
    }

    private fun write(entries: List<QueuedDownload>) {
        if (entries.isEmpty()) { file.delete(); return }
        DurableFile.write(file, json.encodeToString(serializer, entries))
    }
}
