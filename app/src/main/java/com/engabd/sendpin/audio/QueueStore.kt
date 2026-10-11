package com.engabd.sendpin.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.engabd.sendpin.util.DurableFile
import java.io.File

/**
 * The last local queue, written down so it outlives the process.
 *
 * The queue lived in memory only. After the process ended — swiped away, reclaimed
 * overnight, a reboot — there was nothing to resume: a car's resume tile had nothing
 * to point at, a headset's play button after a reboot did nothing, and Android's own
 * media controls had no "pick up where you were" card to offer.
 *
 * Kept in `noBackupFilesDir`: a stream URL can carry a server's credentials in its
 * query string, and this file has no business in a cloud backup.
 */
@Serializable
data class SavedQueue(
    val tracks: List<SavedTrack>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    /** "off" | "one" | "all", as [LocalPlayer.setRepeatMode] takes it. */
    val repeat: String = "off",
    val savedAtMs: Long = 0,
) {
    val current: SavedTrack? get() = tracks.getOrNull(index)
}

/** The parts of a [LocalTrack] needed to play it again. */
@Serializable
data class SavedTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val artUrl: String? = null,
    val genre: String? = null,
    val streamUrl: String? = null,
    val localPath: String? = null,
    val scrobbleId: String? = null,
    val scrobbleProvider: String? = null,
    val composer: String? = null,
) {
    fun toLocalTrack() = LocalTrack(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs,
        artUrl = artUrl, genre = genre, streamUrl = streamUrl, localPath = localPath,
        scrobbleId = scrobbleId, scrobbleProvider = scrobbleProvider, composer = composer,
    )

    companion object {
        fun of(t: LocalTrack) = SavedTrack(
            id = t.id, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
            artUrl = t.artUrl, genre = t.genre, streamUrl = t.streamUrl, localPath = t.localPath,
            scrobbleId = t.scrobbleId, scrobbleProvider = t.scrobbleProvider, composer = t.composer,
        )
    }
}

class QueueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): SavedQueue? = synchronized(lock) {
        DurableFile.read(file) { json.decodeFromString(SavedQueue.serializer(), it) }
            ?.takeIf { it.tracks.isNotEmpty() && it.index in it.tracks.indices }
    }

    fun save(queue: SavedQueue) = synchronized(lock) {
        runCatching { DurableFile.write(file, json.encodeToString(SavedQueue.serializer(), queue)) }
    }

    fun clear() = synchronized(lock) { file.delete() }
}
