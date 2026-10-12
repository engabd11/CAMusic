package com.engabd.sendpin.mpd

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Cover art for MPD, as a URL the image loader can be taught to fetch.
 *
 * MPD does serve artwork — `readpicture` for the picture embedded in a file's own
 * tags, `albumart` for a cover sitting beside it in the directory — but only down
 * the protocol socket, in binary chunks. There is no HTTP URL to hand an image
 * loader, which is why the first cut of this provider returned null for every
 * cover and the whole library browsed as grey placeholder squares.
 *
 * So the item carries a URL in a scheme of its own, `mpd-art://cover/<id>`, and
 * `MpdArtFetcher` teaches Coil what to do with it: ask the connected MPD for the
 * bytes. Everything in between — the grid, the detail screens, the player, the
 * cache — goes on treating art as a string, because that is all it ever was.
 *
 * Deliberately built without `android.net.Uri`: this is called from the parser,
 * which is where the unit tests live, and `Uri` is a stub that throws outside an
 * instrumented run.
 */
object MpdArt {

    /** The scheme `MpdArtFetcher` claims. */
    const val SCHEME = "mpd-art"

    private const val PREFIX = "$SCHEME://cover/"

    /**
     * The art URL for a library item — a track's file path, or an album's
     * NUL-separated album-and-artist id — or null when there is no id to ask
     * about.
     *
     * The id is form-encoded, which is what makes an arbitrary file path survive
     * being a URL: paths contain spaces, `#`, `?` and, in the album case, a NUL.
     */
    fun url(id: String?): String? {
        val raw = id?.takeIf { it.isNotBlank() } ?: return null
        return PREFIX + encode(raw)
    }

    /** The id back out of a [url], or null when this isn't one of ours. */
    fun idFrom(url: String?): String? {
        val raw = url?.takeIf { it.startsWith(PREFIX) } ?: return null
        return decode(raw.removePrefix(PREFIX)).takeIf { it.isNotBlank() }
    }

    /**
     * Whether [id] names an album rather than a file.
     *
     * The browse screens build an album's id as name, NUL, album artist — see
     * `MpdClient.buildAlbumId` — and no file path holds a NUL, so the separator
     * is the test. An album has to be resolved to one of its songs before MPD
     * will say anything about its cover; see [MpdClient.anySongIn].
     */
    fun isAlbumId(id: String): Boolean = id.contains(Char(0))

    /**
     * The art URL for an artist, by name.
     *
     * MPD has no artist pictures — its metadata is file tags, and tags hold covers,
     * not portraits — so every MPD artist tile was a blank initial. The artist's own
     * album cover is the honest stand-in: this names the artist, and the fetcher
     * resolves it to one of their songs and reads that song's cover.
     */
    fun artistUrl(name: String?): String? {
        val raw = name?.takeIf { it.isNotBlank() } ?: return null
        return url(ARTIST_MARK + raw)
    }

    /** The artist's name, when [id] is one [artistUrl] made; null otherwise. */
    fun artistFrom(id: String): String? =
        id.takeIf { it.startsWith(ARTIST_MARK) }?.removePrefix(ARTIST_MARK)?.takeIf { it.isNotBlank() }

    /**
     * Marks an artist id. A control character, so it can never begin a real file
     * path or an album id (which starts with the album's name).
     */
    private const val ARTIST_MARK = "\u0001artist\u0001"

    private fun encode(s: String): String = try {
        URLEncoder.encode(s, Charsets.UTF_8.name())
    } catch (_: UnsupportedEncodingException) {
        s
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, Charsets.UTF_8.name())
    } catch (_: Exception) {
        // A malformed escape is a cover that won't load, not a crash on a grid.
        ""
    }
}
