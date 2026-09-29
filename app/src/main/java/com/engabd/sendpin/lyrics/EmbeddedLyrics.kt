package com.engabd.sendpin.lyrics

import com.engabd.sendpin.ma.MaLyrics
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Lyrics stored inside an audio file's own tags.
 *
 * Two containers cover almost every file that carries them:
 *  - **FLAC** — a `VORBIS_COMMENT` block, under `LYRICS`, `UNSYNCEDLYRICS` or
 *    `SYNCEDLYRICS` (what beets, MusicBrainz Picard and foobar2000 write).
 *  - **MP3** — an ID3v2 `USLT` frame.
 *
 * Only the tag area at the front of the file is read; a picture block ahead of the
 * comments is skipped, not loaded. The text is whatever the tagger wrote: LRC
 * timestamps in it make it synced, and plain text stays plain.
 *
 * Android's `MediaMetadataRetriever` exposes neither, which is why this exists.
 */
object EmbeddedLyrics {

    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private val FLAC_KEYS = listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")

    /** The lyrics in [input]'s tags, or null. Never throws on a malformed file. */
    fun read(input: InputStream): MaLyrics? = runCatching {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        data.readFully(magic, 0, 3)
        when {
            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() ->
                id3(data)
            magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() && magic[2] == 'a'.code.toByte() -> {
                magic[3] = data.readByte()
                if (magic[3] == 'C'.code.toByte()) flac(data) else null
            }
            else -> null
        }
    }.getOrNull()?.let(::toLyrics)

    /** Plain or LRC text as [MaLyrics], deciding which by whether it has timestamps. */
    fun toLyrics(text: String): MaLyrics? {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isEmpty()) return null
        return MaLyrics(t, synced = LRC_LINE.containsMatchIn(t))
    }

    private val LRC_LINE = Regex("""(?m)^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

    // ── FLAC ──────────────────────────────────────────────────────────────

    private fun flac(data: DataInputStream): String? {
        var read = 0
        while (read < MAX_TAG_BYTES) {
            val header = data.readUnsignedByte()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            read += 4 + length
            if (type == 4) {
                val block = ByteArray(length)
                data.readFully(block)
                return vorbisComments(block)
            }
            skipFully(data, length.toLong())
            if (last) return null
        }
        return null
    }

    /** A Vorbis comment block: little-endian lengths, `KEY=value` entries. */
    internal fun vorbisComments(block: ByteArray): String? {
        var p = 0
        fun le32(): Int {
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendor = le32()
        p += vendor
        val count = le32()
        val found = HashMap<String, String>()
        repeat(count) {
            if (p + 4 > block.size) return@repeat
            val len = le32()
            if (len < 0 || p + len > block.size) return@repeat
            val entry = String(block, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key in FLAC_KEYS && key !in found) found[key] = entry.substring(eq + 1)
            }
        }
        return FLAC_KEYS.firstNotNullOfOrNull { k -> found[k]?.takeIf { it.isNotBlank() } }
    }

    // ── ID3v2 ─────────────────────────────────────────────────────────────

    private fun id3(data: DataInputStream): String? {
        val major = data.readUnsignedByte()
        data.readUnsignedByte() // revision
        val flags = data.readUnsignedByte()
        val size = syncsafe(data.readInt())
        if (size <= 0 || size > MAX_TAG_BYTES) return null
        val tag = ByteArray(size)
        data.readFully(tag)
        var p = 0
        // An extended header is rare and skippable.
        if (flags and 0x40 != 0 && major >= 3) {
            val ext = be32(tag, 0).let { if (major == 4) syncsafe(it) else it + 4 }
            p += ext
        }
        val idLen = if (major == 2) 3 else 4
        while (p + idLen + (if (major == 2) 3 else 6) <= tag.size) {
            val id = String(tag, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize: Int
            val headerLen: Int
            if (major == 2) {
                frameSize = ((tag[p + 3].toInt() and 0xFF) shl 16) or ((tag[p + 4].toInt() and 0xFF) shl 8) or
                    (tag[p + 5].toInt() and 0xFF)
                headerLen = 6
            } else {
                val raw = be32(tag, p + 4)
                frameSize = if (major == 4) syncsafe(raw) else raw
                headerLen = 10
            }
            if (frameSize <= 0 || p + headerLen + frameSize > tag.size) break
            if (id == "USLT" || id == "ULT") {
                return uslt(tag.copyOfRange(p + headerLen, p + headerLen + frameSize))
            }
            p += headerLen + frameSize
        }
        return null
    }

    /** `USLT`: encoding, language, a terminated descriptor, then the text. */
    internal fun uslt(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val enc = frame[0].toInt()
        var p = 4 // encoding + 3-byte language
        val wide = enc == 1 || enc == 2
        // Skip the content descriptor up to its terminator.
        if (wide) {
            while (p + 1 < frame.size && !(frame[p].toInt() == 0 && frame[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < frame.size && frame[p].toInt() != 0) p++
            p += 1
        }
        if (p > frame.size) return null
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(frame, p, frame.size - p, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun syncsafe(v: Int): Int =
        ((v shr 24) and 0x7F shl 21) or ((v shr 16) and 0x7F shl 14) or ((v shr 8) and 0x7F shl 7) or (v and 0x7F)

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else {
                left -= s
            }
        }
    }
}
