package com.engabd.sendpin.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Small JSON state files that survive a crash, a power cut and a bad write.
 *
 * Three stores (the saved queue, the scrobble backlog, the download queue) each did
 * the same two things, and both had a hole:
 *
 * - **Reading**: a file that failed to parse was read as empty, and the next change
 *   wrote the empty list over it. One truncated write and the backlog of listens
 *   made offline, or the queue of downloads, was gone with nothing kept to recover.
 *   Now the unreadable file is moved aside to `<name>.bad` first, and the store
 *   starts empty beside it.
 * - **Writing**: a temp file renamed over the real one, but never flushed to disk
 *   first. A rename can reach the disk before the data it points at does, so a
 *   power cut at the wrong moment left a file of zeros — which the read above then
 *   wiped. The temp file is now synced before the rename.
 */
object DurableFile {

    /** [file]'s contents through [parse]; null when there is no file or it could not be read. */
    fun <T> read(file: File, parse: (String) -> T): T? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return null
        }
        return try {
            parse(text)
        } catch (e: Exception) {
            quarantine(file)
            null
        }
    }

    /** Keep an unreadable [file] as `<name>.bad`, so nothing writes over what it held. */
    fun quarantine(file: File) {
        val bad = File(file.parentFile, file.name + ".bad")
        bad.delete()
        if (!file.renameTo(bad)) file.delete()
    }

    /** Replace [file] with [text]: written in full and synced to disk before it takes the name. */
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        }
    }
}
