package com.engabd.sendpin.audio

import com.engabd.sendpin.util.DurableFile
import java.io.File

/**
 * Tracks the analyser has given up on, remembered across restarts.
 *
 * In memory alone, a file whose decode always fails — a codec this phone lacks, a
 * server that always cuts the transfer — was fetched and decoded again on the first
 * play of every session, which on a streamed library is a download each time for a
 * result already known. Kept for [RETRY_AFTER_MS] rather than for ever, because the
 * reasons a decode fails (a server update, a re-encoded file, a new phone) do change.
 *
 * Filed by the scan's file name, not the key itself: keys can be URLs or titles, and
 * the hash is already what names the scan, so neither ever needs escaping.
 */
class ScanFailureLog(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var entries: MutableMap<String, Long>? = null

    /** Whether [key] was given up on less than [RETRY_AFTER_MS] ago. */
    @Synchronized
    fun givenUp(key: String): Boolean {
        val at = loaded()[id(key)] ?: return false
        if (now() - at < RETRY_AFTER_MS) return true
        forget(key)
        return false
    }

    @Synchronized
    fun record(key: String) {
        loaded()[id(key)] = now()
        save()
    }

    @Synchronized
    fun forget(key: String) {
        if (loaded().remove(id(key)) != null) save()
    }

    @Synchronized
    fun forgetAll() {
        loaded().clear()
        file.delete()
    }

    private fun loaded(): MutableMap<String, Long> = entries ?: read().also { entries = it }

    /** The log as stored, less what has expired — written back when that is anything. */
    private fun read(): MutableMap<String, Long> {
        val stored = DurableFile.read(file) { text ->
            text.lineSequence().mapNotNull { line ->
                val (name, at) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
                at.toLongOrNull()?.let { name to it }
            }.toMap()
        }.orEmpty()
        val cutoff = now() - RETRY_AFTER_MS
        val live = HashMap(stored.filterValues { it > cutoff })
        if (live.size != stored.size) {
            entries = live
            save()
        }
        return live
    }

    // Best-effort: a log that cannot be written costs a retry next session, which is
    // exactly what happened before it existed.
    private fun save() {
        runCatching {
            DurableFile.write(file, loaded().entries.joinToString("\n") { "${it.key} ${it.value}" })
        }
    }

    private fun id(key: String) = TrackScanStore.fileName(key)

    companion object {
        /** How long a given-up track is left alone before it gets another go. */
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
