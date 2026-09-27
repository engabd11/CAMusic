package com.engabd.sendpin.scrobble

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A listen waiting to be delivered.
 *
 * [target] is a [ScrobbleService.id], or [SERVER] for the library the track came from
 * — in which case [provider] and [trackId] say which library and which track, and the
 * delivery goes back through that library's own `scrobble`.
 */
@Serializable
data class PendingScrobble(
    val target: String,
    val play: Play,
    val provider: String? = null,
    val trackId: String? = null,
    val attempts: Int = 0,
) {
    companion object {
        const val SERVER = "server"
    }
}

/**
 * Listens that could not be delivered when they happened, kept until they can.
 *
 * Scrobbles used to be fire-and-forget: one attempt, and a failure was dropped. A
 * listen made on a plane, from downloads, was lost for good, and so was every listen
 * during a server restart. They are written here instead and sent when a network
 * comes back. Oldest first, capped at [MAX] — a phone offline for months should not
 * grow this without bound — and a listen is given up on after [MAX_ATTEMPTS] tries.
 */
class ScrobbleQueue(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingScrobble.serializer())
    private val lock = Any()

    fun load(): List<PendingScrobble> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }

    fun add(entry: PendingScrobble) = synchronized(lock) {
        write((load() + entry).takeLast(MAX))
    }

    /** Replace the whole queue — what a flush leaves behind. */
    fun replace(entries: List<PendingScrobble>) = synchronized(lock) { write(entries) }

    private fun write(entries: List<PendingScrobble>) {
        file.parentFile?.mkdirs()
        if (entries.isEmpty()) { file.delete(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, entries))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val MAX = 2_000
        const val MAX_ATTEMPTS = 20
    }
}
