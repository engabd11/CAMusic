package com.engabd.sendpin.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Refuses a stream response that is not audio, before anything reads it as audio or
 * writes it into [MediaCache].
 *
 * A Subsonic server answers a failed `stream` request with HTTP 200 and a small XML or
 * JSON error document, not an error status. Navidrome does exactly that for a file it
 * cannot find, which is what every track under a renamed folder is until its next scan.
 * The cache stored that document as the track, under a key with no credentials in it,
 * so it outlived the rescan: Navidrome kept the track's id and played it again, and
 * CAMusic kept reading the 250-byte error from its own cache, failing with "none of the
 * available extractors could read the stream" however often the library was refreshed.
 *
 * Here such a response fails at open, so nothing is cached, and the failure carries the
 * server's own message ("data not found") rather than an extractor list.
 */
@OptIn(UnstableApi::class)
class NotAudioGuard(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = NotAudioGuard(upstream.createDataSource())
    }

    /** The server answered with a document instead of audio; [serverMessage] is its error text, if it had one. */
    class NotAudioException(
        dataSpec: DataSpec,
        val contentType: String,
        val serverMessage: String?,
    ) : HttpDataSource.HttpDataSourceException(
        "The server sent $contentType instead of audio" + (serverMessage?.let { ": $it" } ?: ""),
        dataSpec,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
    )

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val type = contentType(upstream.responseHeaders) ?: return length
        if (!isDocumentType(type)) return length
        val head = ByteArray(BODY_PEEK)
        var got = 0
        runCatching {
            while (got < head.size) {
                val n = upstream.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
        }
        runCatching { upstream.close() }
        throw NotAudioException(dataSpec, type, errorMessage(String(head, 0, got, Charsets.UTF_8)))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        private const val BODY_PEEK = 2048

        fun contentType(headers: Map<String, List<String>>): String? =
            headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

        /**
         * A content type that is a document and never audio: JSON, XML, HTML, plain
         * text. Everything else passes — servers label audio every which way
         * (`application/octet-stream`, `video/mp4` for an m4a, nothing at all), so
         * this lists what to refuse rather than what to allow. Playlist types
         * (`audio/mpegurl`, `application/vnd.apple.mpegurl`) are not matched.
         */
        fun isDocumentType(type: String): Boolean {
            val t = type.lowercase()
            return t.startsWith("text/") || t == "application/json" || t == "application/xml" ||
                t.endsWith("+json") || t.endsWith("+xml") || t == "application/xhtml+xml"
        }

        /**
         * Whether [head], the first bytes of a stored stream, are a document rather than
         * audio. No audio container starts with `<` or `{` (FLAC is `fLaC`, MP3 `ID3`
         * or a frame sync, Ogg `OggS`, MP4 a box size, WAV `RIFF`), and every server
         * error document does.
         */
        fun looksLikeDocument(head: ByteArray, length: Int = head.size): Boolean {
            var i = 0
            // A UTF-8 byte-order mark, then whitespace.
            if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < length && head[i].toInt().toChar() in " \t\r\n") i++
            if (i >= length) return false
            val c = head[i].toInt().toChar()
            return c == '<' || c == '{'
        }

        private val XML_MESSAGE = Regex("""<error\b[^>]*\bmessage\s*=\s*"([^"]*)"""")
        private val JSON_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        /** The error text in a Subsonic (XML or JSON) error document, or null. */
        fun errorMessage(body: String): String? {
            val raw = XML_MESSAGE.find(body)?.groupValues?.get(1)
                ?: JSON_MESSAGE.find(body)?.groupValues?.get(1)
                ?: return null
            return raw.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&").replace("\\\"", "\"")
                .trim().ifEmpty { null }
        }
    }
}
