package com.engabd.sendpin.subsonic

import com.engabd.sendpin.ma.MaLyrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OpenSubsonic `getLyricsBySongId` → [MaLyrics].
 *
 * Split out of [SubsonicClient] because it is the only part of that call with any
 * decisions in it, and the rest of the client is welded to a live HTTP round-trip.
 *
 * The output is LRC text rather than a structured list because [MaLyrics] already
 * knows how to split LRC stamps from plain lines, and the Music Assistant backend
 * delivers lyrics that way too — so the pane never has to know which server the
 * words came from.
 */
internal object StructuredLyrics {

    fun parse(structured: JsonArray?): MaLyrics? {
        if (structured.isNullOrEmpty()) return null
        val entries = structured.mapNotNull { it as? JsonObject }

        // A server may return several sets for one song — commonly a plain set
        // alongside a synced one, sometimes several languages. Taking the first
        // handed back the plain set whenever it happened to be listed first,
        // throwing away the timing the pane exists to show.
        val entry = entries.firstOrNull { it.isSynced() } ?: entries.firstOrNull() ?: return null
        val synced = entry.isSynced()
        val lines = entry["line"] as? JsonArray ?: return null

        val text = buildString {
            for (el in lines) {
                val line = el as? JsonObject ?: continue
                val start = line.startMs()
                val value = line["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // `>= 0`, not `> 0`. A line starting at exactly 0 ms is ordinary —
                // plenty of songs open on a lyric. Stamping only positive starts left
                // that line bare, and MaLyrics drops unstamped lines out of a synced
                // set, so the first line silently disappeared.
                if (synced && start >= 0) append(lrcStamp(start))
                append(value)
                append('\n')
            }
        }
        return MaLyrics(text.trimEnd(), synced = synced)
    }

    /** `[m:ss.cc]`, the shape MaLyrics' own parser accepts. */
    private fun lrcStamp(ms: Long): String {
        val mm = ms / 60_000
        val ss = (ms % 60_000) / 1_000
        val cs = (ms % 1_000) / 10
        return "[$mm:${ss.toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}]"
    }

    /**
     * `synced` is a JSON boolean in the spec, but comparing the primitive's content
     * accepts both that and the string `"true"` some servers send.
     */
    private fun JsonObject.isSynced(): Boolean =
        this["synced"]?.jsonPrimitive?.contentOrNull == "true"

    private fun JsonObject.startMs(): Long {
        val p = this["start"]?.jsonPrimitive ?: return 0L
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: 0L
    }
}
