package com.engabd.sendpin.lyrics

import android.content.Context
import java.io.File

/**
 * Where finished lyric translations are kept, shared by both translators.
 *
 * They live in the app's own data (`filesDir`), not the cache: a translation can cost a
 * long wait or a paid AI call, and Android may empty the cache whenever storage is short.
 * Translations used to be written to `cacheDir`, so the first use moves any that are
 * still there.
 *
 * The folder is capped so it can never grow without bound: past [MAX_BYTES] the files
 * least recently used are dropped until it is back under [TRIM_TO_BYTES]. A read touches
 * the file, so the songs you keep playing are the ones that survive.
 */
object LyricTranslationStore {
    private const val FOLDER = "lyric-translations"
    const val MAX_BYTES = 20L * 1024 * 1024
    const val TRIM_TO_BYTES = 15L * 1024 * 1024

    /** How many songs are saved, and how much space they take. */
    data class Summary(val count: Int, val bytes: Long)

    @Volatile private var migrated = false

    /** The folder translations are read from and written to; moves any old ones first. */
    fun dir(context: Context): File {
        val target = File(context.filesDir, FOLDER)
        if (!migrated) {
            synchronized(this) {
                if (!migrated) {
                    migrate(File(context.cacheDir, FOLDER), target)
                    migrated = true
                }
            }
        }
        return target
    }

    /** Marks a file as just used, so [prune] keeps it. */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    fun summary(context: Context): Summary = summary(dir(context))

    fun clear(context: Context): Int = clear(dir(context))

    internal fun summary(dir: File): Summary {
        val files = files(dir)
        return Summary(files.size, files.sumOf { it.length() })
    }

    /** Deletes just these saved translations (one song's); returns how many went. */
    fun delete(files: List<File>): Int = files.count { it.isFile && it.delete() }

    /** Deletes every saved translation; returns how many went. */
    internal fun clear(dir: File): Int = files(dir).count { it.delete() }

    /**
     * Moves every file from [from] into [to], then removes [from]. A rename is tried
     * first, and a copy followed by a delete when that is refused. A file already in
     * [to] wins, since it is the newer.
     */
    internal fun migrate(from: File, to: File) {
        if (!from.isDirectory) return
        runCatching {
            to.mkdirs()
            from.listFiles()?.filter { it.isFile }?.forEach { f ->
                val dest = File(to, f.name)
                if (dest.exists()) {
                    f.delete()
                } else if (!f.renameTo(dest)) {
                    runCatching { f.copyTo(dest); f.delete() }
                }
            }
            from.deleteRecursively()
        }
    }

    /** Past [maxBytes], deletes the least recently used files until at most [trimTo] remain. */
    internal fun prune(dir: File, maxBytes: Long = MAX_BYTES, trimTo: Long = TRIM_TO_BYTES) {
        val all = files(dir)
        var total = all.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= trimTo) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private fun files(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
}
