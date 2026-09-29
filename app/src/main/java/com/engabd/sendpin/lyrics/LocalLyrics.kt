package com.engabd.sendpin.lyrics

import android.content.Context
import android.net.Uri
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.ma.MaLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics that are already on the phone: a sidecar `.lrc` beside a downloaded file,
 * or the tags inside the file itself.
 *
 * Asked first for a track that plays from the phone, before any server — it is
 * instant, it works offline, and it is what the person who tagged the file chose.
 */
object LocalLyrics {

    /** Where a download's lyrics are kept: next to the audio, as `<file>.lrc`. */
    fun sidecarFor(audio: File): File = File(audio.path + ".lrc")

    suspend fun read(context: Context, track: LocalTrack): MaLyrics? = withContext(Dispatchers.IO) {
        track.localPath?.let { path ->
            val audio = File(path)
            sidecarFor(audio).takeIf { it.isFile }
                ?.let { runCatching { EmbeddedLyrics.toLyrics(it.readText()) }.getOrNull() }
                ?.let { return@withContext it }
            if (audio.isFile) {
                runCatching { audio.inputStream().use(EmbeddedLyrics::read) }.getOrNull()?.let { return@withContext it }
            }
        }
        // "This device" plays straight from MediaStore; its bytes are readable with
        // the audio permission the library already holds.
        val url = track.streamUrl
        if (url != null && url.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(url))?.use(EmbeddedLyrics::read)
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    /** Keep [lyrics] beside a downloaded [audio] file, so they come with it offline. */
    fun save(audio: File, lyrics: MaLyrics) {
        runCatching { sidecarFor(audio).writeText(lyrics.text) }
    }
}
