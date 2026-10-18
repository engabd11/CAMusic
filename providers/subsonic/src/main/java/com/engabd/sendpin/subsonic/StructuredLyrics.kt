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

        // OpenSubsonic carries a per-set `offset`, "the offset to apply to all
        // lyrics, in milliseconds" — the same idea as LRC's `[offset:]` tag, and how
        // a tagger corrects a set that runs early or late against the recording.
        // Ignoring it, which is what this did, leaves every line off by a constant
        // for exactly the files someone bothered to correct.
        val offset = entry.longOf("offset") ?: 0L

        val text = buildString {
            for (el in lines) {
                val line = el as? JsonObject ?: continue
                // A negative offset can pull the opening line before zero; clamp
                // rather than drop it, or the correction costs the first lyric.
                val start = (line.startMs() + offset).coerceAtLeast(0L)
                val value = line["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // `>= 0`, not `> 0`. A line starting at exactly 0 ms is ordinary —
                // plenty of songs open on a lyric. Stamping only positive starts left
                // that line bare, and MaLyrics drops unstamped lines out of a synced
                // set, so the first line silently disappeared.
                if (synced && start >= 0) append(lrcStamp(start))
                // Word cues, where the server sends them (OpenSubsonic `cue` / `cueLine`
                // entries with their own start and end), become enhanced-LRC word stamps.
                val cued = if (synced) cuedBody(line, value, offset) else null
                append(cued ?: value)
                append('\n')
            }
        }
        return MaLyrics(text.trimEnd(), synced = synced)
    }

    /**
     * The line's body with `<m:ss.mmm>` stamps before each cue, or null if it has no
     * usable cues. A cue's text is its `value`, or else the slice of the line named by
     * `byteStart`/`byteEnd` (UTF-8 byte offsets). Cues without a start are ignored, and
     * anything odd falls back to the plain line rather than failing the whole set.
     */
    private fun cuedBody(line: JsonObject, value: String, offset: Long): String? {
        val cues = ((line["cue"] ?: line["cueLine"]) as? JsonArray)?.mapNotNull { it as? JsonObject }
        if (cues.isNullOrEmpty()) return null
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var lastEnd = -1L
        for (cue in cues) {
            val start = cue.longOf("start")?.plus(offset)?.coerceAtLeast(0L) ?: continue
            val text = cue["value"]?.jsonPrimitive?.contentOrNull
                ?: run {
                    val b0 = cue.longOf("byteStart")?.toInt() ?: return@run null
                    val b1 = cue.longOf("byteEnd")?.toInt() ?: return@run null
                    if (b0 < 0 || b1 > bytes.size || b0 >= b1) null else String(bytes, b0, b1 - b0, Charsets.UTF_8)
                }
                ?: continue
            sb.append(wordStamp(start)).append(text)
            lastEnd = cue.longOf("end")?.plus(offset) ?: -1L
        }
        if (sb.isEmpty()) return null
        if (lastEnd >= 0) sb.append(wordStamp(lastEnd.coerceAtLeast(0L)))
        return sb.toString()
    }

    private fun wordStamp(ms: Long): String =
        "<${ms / 60_000}:${((ms % 60_000) / 1_000).toString().padStart(2, '0')}.${(ms % 1_000).toString().padStart(3, '0')}>"

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

    private fun JsonObject.startMs(): Long = longOf("start") ?: 0L

    private fun JsonObject.longOf(key: String): Long? {
        val p = this[key]?.jsonPrimitive ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }
}
