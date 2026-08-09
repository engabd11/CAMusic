package com.engabd.sendpin.subsonic

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import com.engabd.sendpin.data.LanOnlyCleartext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A Subsonic call the server refused, or that never reached it.
 *
 * Failures used to be swallowed and returned as an empty list, which is why a
 * wrong password, an unreachable host and a genuinely empty library all looked
 * identical — a blank shelf and no explanation. Everything here throws instead,
 * and the ViewModel turns that into an error the user can act on.
 */
/**
 * A play queue another client left on the server, for cross-device resume.
 *
 * @param changedBy the client name that saved it — worth showing, because "resume
 *   from your laptop" is a different offer from "resume from this phone".
 */
data class SavedQueue(
    val tracks: List<MaItem>,
    val index: Int,
    val positionMs: Long,
    val changedBy: String?,
)

class SubsonicException(
    message: String,
    /**
     * The Subsonic error code, when the server got far enough to give one.
     * [SubsonicError] has the meanings; null means the request never reached a
     * Subsonic server at all.
     */
    val code: Int? = null,
) : IOException(message)

/**
 * The Subsonic error codes, and what to tell someone about them.
 *
 * These were being interpolated into a string and never looked at, so a wrong
 * password and a missing album produced the same shrug — and the connect screen
 * reported bad credentials and an unreachable host identically, which is the one
 * place the difference actually decides what the user should do next.
 */
object SubsonicError {
    const val GENERIC = 0
    const val MISSING_PARAM = 10
    const val CLIENT_TOO_OLD = 20
    const val SERVER_TOO_OLD = 30
    const val BAD_CREDENTIALS = 40
    const val TOKEN_AUTH_UNSUPPORTED = 41
    const val NOT_AUTHORIZED = 50
    const val TRIAL_EXPIRED = 60
    const val NOT_FOUND = 70

    /** True when re-entering the password is the fix. */
    fun isAuth(code: Int?) = code == BAD_CREDENTIALS || code == TOKEN_AUTH_UNSUPPORTED

    fun explain(code: Int?, serverMessage: String?): String = when (code) {
        BAD_CREDENTIALS -> "That username or password was rejected"
        // Navidrome only hits this for LDAP users; the fix is a real one, so say it.
        TOKEN_AUTH_UNSUPPORTED ->
            "This account can't use token authentication - it needs a password-auth client"
        NOT_AUTHORIZED -> "That account isn't allowed to do this"
        NOT_FOUND -> "The server doesn't have that"
        SERVER_TOO_OLD -> "The server is too old for this app"
        CLIENT_TOO_OLD -> "The server wants a newer client than this"
        TRIAL_EXPIRED -> "The server's trial period has expired"
        else -> serverMessage?.takeIf { it.isNotBlank() }
            ?: "The server refused the request${code?.let { " (code $it)" }.orEmpty()}"
    }
}

/**
 * Direct OpenSubsonic / Navidrome client — browse/search/stream straight from the
 * server, no Music Assistant in the path. This is the standalone library: it is
 * what plays when MA (or Home Assistant) is down, and what backs offline
 * downloads.
 *
 * Token auth (`t = md5(password + salt)`) so the password never rides on a URL.
 * Items are returned as [MaItem] (provider = [PROVIDER]) so one Library UI
 * renders both backends; a track's `uri` is its Subsonic id, and stream/download
 * URLs are built from it on demand.
 */
class SubsonicClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = shared,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        /** The provider tag every item from this client carries. */
        const val PROVIDER = "subsonic"

        /**
         * One client for every Subsonic call in the app.
         *
         * Each view model used to build its own, so six connection pools and six
         * dispatcher thread pools existed against a single server, none of them
         * reusing each other's sockets. OkHttp is designed to be shared — the whole
         * point of the pool is that it outlives any one caller.
         */
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
        .addInterceptor(LanOnlyCleartext)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private const val CLIENT = "sendpin"
        private const val API_VERSION = "1.16.1"
        private const val HEX = "0123456789abcdef"

        /**
         * One cover URL serves both the 44dp list thumbnail and the full-bleed Now
         * Playing cover: Coil samples it down per use and keeps a single copy on
         * disk. Sizing it per call would multiply every cover across the cache.
         */
        private const val COVER_PX = 1000
    }

    /** The normalised server root, e.g. `http://192.168.0.10:4533`. */
    val serverUrl: String get() = base()

    /**
     * What [streamUrl] asks the server for, as the token described there
     * (`"raw"`, `"flac"`, `"mp3-320"`, …).
     *
     * A mutable property rather than a constructor argument because the client is
     * long-lived and shared, and the setting can change under it — rebuilding the
     * client on a format change would drop the connection state with it.
     */
    @Volatile
    var streamFormat: String = "raw"

    // --- URL building -----------------------------------------------------

    /**
     * A typed-in address usually has no scheme. A LAN box (`192.168.0.10:4533`,
     * `nas.local`) is almost never on TLS and defaulting it to https just fails to
     * connect; a public hostname almost always is, and defaulting *that* to http
     * would send the credentials in the clear. So the guess follows the address.
     */
    private fun base(): String {
        val b = baseUrl.trim().trimEnd('/')
        if (b.startsWith("http://") || b.startsWith("https://")) return b
        val host = b.substringBefore('/').substringBefore(':')
        val local = host == "localhost" ||
            host.endsWith(".local", ignoreCase = true) ||
            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return (if (local) "http://" else "https://") + b
    }

    private fun authQuery(): String {
        val salt = (1..8).map { HEX.random() }.joinToString("")
        val token = md5(password + salt)
        return "u=${enc(username)}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT"
    }

    /**
     * @param repeated parameters Subsonic defines as repeatable — `songId`,
     *   `songIdToAdd`, `id` on star/unstar. They go on the query string once per
     *   value: `songId=a&songId=b`. Comma-joining them, which is what this used to
     *   do, hands the server a single id with commas in it, and the call silently
     *   does nothing.
     */
    private fun restUrl(
        endpoint: String,
        params: Map<String, String>,
        jsonFmt: Boolean,
        repeated: List<Pair<String, String>> = emptyList(),
    ): String {
        val sb = StringBuilder("${base()}/rest/$endpoint.view?${authQuery()}")
        for ((k, v) in params) sb.append("&${enc(k)}=${enc(v)}")
        for ((k, v) in repeated) sb.append("&${enc(k)}=${enc(v)}")
        if (jsonFmt) sb.append("&f=json")
        return sb.toString()
    }

    /**
     * A stream URL for [id] in [format].
     *
     * `format` is always sent explicitly. Omitting it lets the server apply whatever
     * transcoding profile it has for this client, which would silently turn a FLAC
     * into 192k MP3 — so `"raw"` (the default, meaning the stored file untouched) is
     * not optional if playback is meant to be bit-perfect.
     *
     * The lossy options carry the bitrate in the same token (`"mp3-320"`), because
     * Subsonic wants it as a separate `maxBitRate` parameter and keeping the pair
     * together stops the two drifting apart. `maxBitRate` is meaningless for lossless
     * output and is left off there.
     */
    fun streamUrl(id: String, format: String = streamFormat): String {
        val codec = format.substringBefore('-').ifBlank { "raw" }
        val bitrate = format.substringAfter('-', "").toIntOrNull()
        val params = buildMap {
            put("id", id)
            put("format", codec)
            bitrate?.let { put("maxBitRate", it.toString()) }
            // A transcode is streamed chunked with no Content-Length, and MediaPlayer
            // probes such a source with byte `Range` requests that Navidrome then
            // resolves into a *time* offset — which is how a track opened two or
            // three seconds in. Asking the server to estimate the length makes the
            // response seekable by byte, so a range means what the player thinks it
            // means. Meaningless for `raw`, which is served from the stored file.
            if (codec != "raw") put("estimateContentLength", "true")
        }
        return restUrl("stream", params, jsonFmt = false)
    }

    /** `/download` is defined to return the original file, so it needs no format hint. */
    fun downloadUrl(id: String): String = restUrl("download", mapOf("id" to id), jsonFmt = false)

    fun coverUrl(id: String?, size: Int = COVER_PX): String? =
        id?.takeIf { it.isNotBlank() }
            ?.let { restUrl("getCoverArt", mapOf("id" to it, "size" to size.toString()), jsonFmt = false) }

    // --- requests ---------------------------------------------------------

    private suspend fun get(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        repeated: List<Pair<String, String>> = emptyList(),
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val body = try {
                http.newCall(Request.Builder().url(restUrl(endpoint, params, jsonFmt = true, repeated)).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw SubsonicException("Server returned HTTP ${resp.code}")
                        }
                        resp.body?.string() ?: throw SubsonicException("Empty response from the server")
                    }
            } catch (e: SubsonicException) {
                throw e
            } catch (e: Exception) {
                // Wrong host, wrong port, no route, TLS refusal — all indistinguishable
                // to the user, so say what we can and name the address we tried.
                throw SubsonicException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
            }

            val root = try {
                json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
            } catch (_: Exception) {
                null
            } ?: throw SubsonicException("That address didn't answer like a Subsonic server")

            if (root["status"]?.jsonPrimitive?.contentOrNull == "failed") {
                val err = root["error"]?.jsonObject
                val code = err?.get("code")?.jsonPrimitive?.intOrNull
                throw SubsonicException(
                    SubsonicError.explain(code, err?.get("message")?.jsonPrimitive?.contentOrNull),
                    code = code,
                )
            }
            root
        }

    /**
     * Reachability + credentials probe. Returns null when fine, else why not.
     *
     * The distinction matters to the caller, not just to the message: a rejected
     * password is worth re-prompting for, an unreachable host is worth falling back
     * to downloads for. See [pingResult].
     */
    suspend fun pingError(): String? = pingResult()?.message

    /** As [pingError], but keeping the code so the caller can tell the cases apart. */
    suspend fun pingResult(): SubsonicException? = try {
        get("ping")
        null
    } catch (e: SubsonicException) {
        e
    }

    // --- browse -----------------------------------------------------------

    suspend fun artists(): List<MaItem> =
        get("getArtists")["artists"]?.jsonObject?.get("index")?.jsonArray.orEmptyArray()
            .flatMap { grp -> grp.jsonObject["artist"]?.jsonArray.orEmptyArray() }
            .map { artistItem(it.jsonObject) }

    /** Artist metadata + their albums. */
    suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val artistObj = get("getArtist", mapOf("id" to id))?.get("artist")?.jsonObject
            ?: return null to emptyList()
        val artist = MaItem(
            itemId = artistObj.str("id") ?: id, provider = "subsonic",
            name = artistObj.str("name") ?: "Unknown artist",
            uri = artistObj.str("id"), mediaType = "artist",
            subtitle = artistObj.int("albumCount")?.let { "$it albums" },
            image = coverUrl(artistObj.str("coverArt") ?: artistObj.str("id")),
            duration = null,
        )
        val albums = artistObj["album"]?.jsonArray ?: emptyList()
        return artist to albums.map { albumItem(it.jsonObject) }
    }

    suspend fun artistAlbums(id: String): List<MaItem> = artistDetail(id).second

    /**
     * Album metadata (year, artist, cover, duration) plus all of its tracks, in the
     * one `getAlbum` round-trip that already carries both — what the album detail
     * screen needs to draw a header without a second call.
     */
    suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val albumObj = get("getAlbum", mapOf("id" to id))["album"]?.jsonObject
            ?: return null to emptyList()
        val songs = albumObj["song"]?.jsonArray.orEmptyArray()
        return albumItem(albumObj) to songs.map { songItem(it.jsonObject) }
    }

    suspend fun albumTracks(id: String): List<MaItem> = albumDetail(id).second

    suspend fun playlists(): List<MaItem> =
        get("getPlaylists")["playlists"]?.jsonObject?.get("playlist")?.jsonArray.orEmptyArray()
            .map { playlistItem(it.jsonObject) }

    suspend fun playlistTracks(id: String): List<MaItem> =
        get("getPlaylist", mapOf("id" to id))["playlist"]?.jsonObject?.get("entry")?.jsonArray.orEmptyArray()
            .map { songItem(it.jsonObject) }

    /**
     * [type] is Subsonic's album-list flavour: `newest`, `recent` (recently played),
     * `frequent`, `starred`, `random`, `alphabeticalByName`, `alphabeticalByArtist`.
     */
    suspend fun albumList(type: String = "newest", size: Int = 200, offset: Int = 0): List<MaItem> =
        get(
            "getAlbumList2",
            mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
        )["albumList2"]?.jsonObject?.get("album")?.jsonArray.orEmptyArray()
            .map { albumItem(it.jsonObject) }

    suspend fun genres(): List<MaItem> =
        get("getGenres")["genres"]?.jsonObject?.get("genre")?.jsonArray.orEmptyArray()
            .mapNotNull { el ->
                val o = el.jsonObject
                // A genre's name is the element's *value*, not an "id" field, and it
                // is also the key `getSongsByGenre` takes — so it is both here.
                val name = o.str("value") ?: o.str("name") ?: return@mapNotNull null
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = null, mediaType = "genre",
                    subtitle = o.int("songCount")?.let { "$it songs" }, image = null, duration = null,
                )
            }
            .sortedBy { it.name.lowercase() }

    suspend fun songsByGenre(genre: String, count: Int = 300, offset: Int = 0): List<MaItem> =
        get(
            "getSongsByGenre",
            mapOf("genre" to genre, "count" to count.toString(), "offset" to offset.toString()),
        )["songsByGenre"]?.jsonObject?.get("song")?.jsonArray.orEmptyArray()
            .map { songItem(it.jsonObject) }

    suspend fun randomSongs(size: Int = 100): List<MaItem> =
        get("getRandomSongs", mapOf("size" to size.toString()))["randomSongs"]
            ?.jsonObject?.get("song")?.jsonArray.orEmptyArray()
            .map { songItem(it.jsonObject) }

    // --- playlist CRUD ----------------------------------------------------

    /**
     * Create a new, empty playlist. OpenSubsonic's `createPlaylist` takes a name
     * and an optional list of song ids to seed it with; an empty playlist is
     * valid and is what the library's "Create playlist" dialog asks for.
     *
     * Returns the new playlist's id (from the response) so the caller can open
     * it directly, or null when the server doesn't report one.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? {
        // `songId` is a repeated parameter, not a comma-separated list.
        val res = get(
            "createPlaylist",
            mapOf("name" to name),
            repeated = songIds.map { "songId" to it },
        )["playlist"]?.jsonObject
        return res?.str("id")
    }

    /**
     * Rename a playlist, and/or add and remove tracks.
     *
     * `updatePlaylist` is a *delta*, which is the part that had been got wrong: it
     * takes `songIdToAdd` (repeated) and `songIndexToRemove` (repeated, and an
     * **index into the playlist**, not a song id). It has no `songId` parameter at
     * all, so the previous shape was accepted and quietly ignored.
     */
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        addSongIds: List<String> = emptyList(),
        removeIndices: List<Int> = emptyList(),
    ) {
        val params = buildMap {
            put("playlistId", id)
            name?.let { put("name", it) }
        }
        get(
            "updatePlaylist",
            params,
            repeated = addSongIds.map { "songIdToAdd" to it } +
                removeIndices.map { "songIndexToRemove" to it.toString() },
        )
    }

    /**
     * A 1–5 star rating, or 0 to clear it.
     *
     * Distinct from starring: `star` is a boolean favourite, `setRating` is the
     * five-point scale Navidrome keeps separately and exposes in its own UI.
     */
    suspend fun setRating(id: String, rating: Int) {
        get("setRating", mapOf("id" to id, "rating" to rating.coerceIn(0, 5).toString()))
    }

    // --- play queue (cross-device resume) ----------------------------------

    /**
     * The play queue this user left on another client, if any.
     *
     * Navidrome returns `current` as a **string song id**, not the integer index the
     * Subsonic schema describes — their docs call that out explicitly — so it is read
     * as an id and matched against the entries rather than used as a position.
     */
    suspend fun playQueue(): SavedQueue? {
        val q = get("getPlayQueue")["playQueue"]?.jsonObject ?: return null
        val songs = q["entry"]?.jsonArray.orEmptyArray().map { songItem(it.jsonObject) }
        if (songs.isEmpty()) return null
        val currentId = q.str("current")
        return SavedQueue(
            tracks = songs,
            index = songs.indexOfFirst { it.itemId == currentId }.coerceAtLeast(0),
            positionMs = q.long("position") ?: 0L,
            changedBy = q.str("changedBy"),
        )
    }

    /** Hand the current queue to the server so another client can pick it up. */
    suspend fun savePlayQueue(songIds: List<String>, currentId: String?, positionMs: Long) {
        if (songIds.isEmpty()) return
        val params = buildMap {
            currentId?.let { put("current", it) }
            put("position", positionMs.coerceAtLeast(0).toString())
        }
        get("savePlayQueue", params, repeated = songIds.map { "id" to it })
    }

    /**
     * Which OpenSubsonic extensions this server implements.
     *
     * The app probes-and-falls-back everywhere else (see [getLyrics]), which costs a
     * failed round trip per call on a server that doesn't have the endpoint. Asking
     * once is cheaper and, more usefully, lets the UI stop offering things the
     * server cannot do. Returns an empty map on a server that has never heard of the
     * endpoint — which is itself the answer.
     */
    suspend fun openSubsonicExtensions(): Map<String, List<Int>> = try {
        get("getOpenSubsonicExtensions")["openSubsonicExtensions"]?.jsonArray.orEmptyArray()
            .mapNotNull { el ->
                val o = el.jsonObject
                val name = o.str("name") ?: return@mapNotNull null
                name to o["versions"]?.jsonArray.orEmptyArray().mapNotNull { it.jsonPrimitive.intOrNull }
            }.toMap()
    } catch (_: SubsonicException) {
        emptyMap()
    }

    /** Delete a playlist by id. The server removes it and its track associations. */
    suspend fun deletePlaylist(id: String) {
        get("deletePlaylist", mapOf("id" to id))
    }

    // --- lyrics ------------------------------------------------------------

    /**
     * Lyrics for a song, as a [MaLyrics] so the existing [LyricsPane] renders
     * them unchanged.
     *
     * OpenSubsonic's `getLyricsBySongId` is tried first — it is the structured
     * endpoint and returns lyrics as a list of `structuredLyrics` entries with
     * a `lang` and `synced` flag. A server that doesn't implement it returns an
     * error, which is caught and falls through to the legacy `getLyrics`
     * endpoint (artist + title based, plain text).
     *
     * The structured response carries either timed LRC lines (`synced = true`)
     * or plain text. Both are folded into [MaLyrics], which already knows how to
     * split LRC stamps from plain lines — so the UI does not need to know which
     * Subsonic endpoint the text came from.
     */
    suspend fun getLyrics(songId: String): MaLyrics? {
        // OpenSubsonic structured lyrics — the preferred path.
        try {
            val root = get("getLyricsBySongId", mapOf("id" to songId))
            val structured = root["lyricsList"]?.jsonObject?.get("structuredLyrics")?.jsonArray
            StructuredLyrics.parse(structured)?.let { return it }
        } catch (_: SubsonicException) {
            // Server doesn't support getLyricsBySongId — fall through to legacy.
        }

        // Legacy getLyrics — plain text, and the only way to ask is by *name*, not by
        // id. That is the whole problem with it: the server matches however it likes,
        // and a miss does not come back empty, it comes back as some other song's
        // words. Which is why tracks with no lyrics were showing something random.
        //
        // The response says which song it actually found (`artist`/`title` on the
        // lyrics element, per the Subsonic schema), so the answer is checkable — and
        // anything that doesn't match what was asked for is thrown away.
        return try {
            val song = get("getSong", mapOf("id" to songId))["song"]?.jsonObject
            val artist = song?.str("artist") ?: return null
            val title = song.str("title") ?: return null
            val el = get("getLyrics", mapOf("artist" to artist, "title" to title))["lyrics"]
            val obj = el as? JsonObject
            val text = obj?.str("value")
                ?: obj?.str("content")
                ?: (el as? JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank()) return null
            // Only trust it when the server names the same song back. A server that
            // reports nothing is given the benefit of the doubt — it asked for this
            // artist and title and answered, which is as much as the endpoint offers.
            val gotArtist = obj?.str("artist")
            val gotTitle = obj?.str("title")
            val matches = (gotArtist == null || gotArtist.equals(artist, ignoreCase = true)) &&
                (gotTitle == null || gotTitle.equals(title, ignoreCase = true))
            if (!matches) null else MaLyrics(text, synced = false)
        } catch (_: SubsonicException) {
            null
        }
    }

    // --- artist / album info -----------------------------------------------

    /**
     * Extended artist metadata: biography, music-brainz id, last.fm URL, etc.
     *
     * `getArtistInfo2` is the OpenSubsonic endpoint for this; `getArtistInfo`
     * (v1) is the older equivalent. We try `2` first and fall back silently —
     * a plain Subsonic server may not implement the `2` variant.
     *
     * Returns the biography text, or null when the server has nothing.
     */
    suspend fun getArtistInfo2(id: String): String? {
        // Try the OpenSubsonic `2` variant first.
        try {
            val res = get("getArtistInfo2", mapOf("id" to id, "count" to "0", "includeNotPresent" to "true"))
            val info = res["artistInfo2"]?.jsonObject
            return info?.str("biography")?.takeIf { it.isNotBlank() }
        } catch (_: SubsonicException) {
            // Fall through to the legacy endpoint.
        }
        return try {
            val res = get("getArtistInfo", mapOf("id" to id, "count" to "0", "includeNotPresent" to "true"))
            res["artistInfo"]?.jsonObject?.str("biography")?.takeIf { it.isNotBlank() }
        } catch (_: SubsonicException) {
            null
        }
    }

    /**
     * Extended album metadata: liner notes, description, etc.
     *
     * `getAlbumInfo2` is the OpenSubsonic endpoint; `getAlbumInfo` is the
     * legacy equivalent. Same fallback pattern as [getArtistInfo2].
     */
    suspend fun getAlbumInfo2(id: String): String? {
        try {
            val res = get("getAlbumInfo2", mapOf("id" to id))
            val info = res["albumInfo2"]?.jsonObject
            return info?.str("notes")?.takeIf { it.isNotBlank() }
        } catch (_: SubsonicException) {
        }
        return try {
            val res = get("getAlbumInfo", mapOf("id" to id))
            res["albumInfo"]?.jsonObject?.str("notes")?.takeIf { it.isNotBlank() }
        } catch (_: SubsonicException) {
            null
        }
    }

    /** Everything the user has starred, grouped the way search results are. */
    suspend fun starred(): MaSearchResults {
        val res = get("getStarred2")["starred2"]?.jsonObject
        return MaSearchResults(
            artists = res?.get("artist")?.jsonArray.orEmptyArray().map { artistItem(it.jsonObject) },
            albums = res?.get("album")?.jsonArray.orEmptyArray().map { albumItem(it.jsonObject) },
            tracks = res?.get("song")?.jsonArray.orEmptyArray().map { songItem(it.jsonObject) },
            playlists = emptyList(),
        )
    }

    /**
     * `search3` covers artists, albums and songs but has no notion of a playlist, so
     * playlists are matched by name against the (small, already-cached-cheap) list
     * the server holds — otherwise searching for a playlist by name finds nothing.
     */
    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val res = get(
            "search3",
            mapOf(
                "query" to query, "artistCount" to limit.toString(),
                "albumCount" to limit.toString(), "songCount" to limit.toString(),
            ),
        )["searchResult3"]?.jsonObject
        val matchingPlaylists = try {
            playlists().filter { it.name.contains(query, ignoreCase = true) }
        } catch (_: SubsonicException) {
            emptyList()
        }
        return MaSearchResults(
            artists = res?.get("artist")?.jsonArray.orEmptyArray().map { artistItem(it.jsonObject) },
            albums = res?.get("album")?.jsonArray.orEmptyArray().map { albumItem(it.jsonObject) },
            tracks = res?.get("song")?.jsonArray.orEmptyArray().map { songItem(it.jsonObject) },
            playlists = matchingPlaylists,
        )
    }

    suspend fun song(id: String): MaItem? =
        (get("getSong", mapOf("id" to id))["song"] as? JsonObject)?.let { songItem(it) }

    suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistAlbums(item.itemId)
        "album" -> albumTracks(item.itemId)
        "playlist" -> playlistTracks(item.itemId)
        "genre" -> songsByGenre(item.itemId)
        else -> emptyList()
    }

    /**
     * Every track under an item, in play order — what "play this album" and
     * "download this playlist" both need. A track resolves to itself, so callers
     * can hand any item straight through.
     */
    suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album", "playlist", "genre" -> children(item)
        "artist" -> artistAlbums(item.itemId).flatMap { albumTracks(it.itemId) }
        else -> emptyList()
    }

    // --- write -------------------------------------------------------------

    /** Star/unstar. Subsonic wants the id under a type-specific parameter. */
    suspend fun setStarred(item: MaItem, starred: Boolean) {
        val param = when (item.mediaType) {
            "album" -> "albumId"
            "artist" -> "artistId"
            else -> "id"
        }
        get(if (starred) "star" else "unstar", mapOf(param to item.itemId))
    }

    /**
     * Report a play so Navidrome's play counts, "recently played" and any connected
     * scrobbler stay honest — without this the Recently played shelf never moves.
     *
     * The spec gives `submission` a precise meaning that the app was getting backwards:
     * `false` is a *now playing* notification, sent when a track starts, and `true` is
     * a **completed** play. This used to send `submission=true` the instant a track
     * started, so every track skipped after a second still counted as a full play and
     * Navidrome's counts drifted upwards. There is no default any more — the caller
     * has to say which one it means.
     *
     * [timeMs] is when the play *started*, in Unix epoch milliseconds, which is what
     * lets a submission arriving minutes later be filed at the right moment.
     *
     * Best-effort: a server that refuses it must not break playback.
     */
    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long? = null) {
        try {
            get(
                "scrobble",
                buildMap {
                    put("id", id)
                    put("submission", submission.toString())
                    timeMs?.let { put("time", it.toString()) }
                },
            )
        } catch (_: SubsonicException) {
        }
    }

    // --- item parsing -----------------------------------------------------

    private fun artistItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = PROVIDER, name = o.str("name") ?: "Unknown artist",
        uri = o.str("id"), mediaType = "artist",
        subtitle = o.int("albumCount")?.let { if (it == 1) "1 album" else "$it albums" },
        image = coverUrl(o.str("coverArt") ?: o.str("id")), duration = null,
        favorite = o.str("starred") != null,
    )

    private fun albumItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = PROVIDER,
        name = o.str("name") ?: o.str("album") ?: "Unknown album",
        uri = o.str("id"), mediaType = "album", subtitle = o.str("artist"),
        image = coverUrl(o.str("coverArt") ?: o.str("id")),
        duration = o.int("duration"),
        favorite = o.str("starred") != null,
        year = o.int("year"),
        parentId = o.str("artistId"),
    )

    private fun songItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = PROVIDER, name = o.str("title") ?: "Unknown title",
        uri = o.str("id"), mediaType = "track", subtitle = o.str("artist"),
        image = coverUrl(o.str("coverArt") ?: o.str("albumId") ?: o.str("id")),
        duration = o.int("duration"),
        favorite = o.str("starred") != null,
        audioFormat = audioFormat(o),
        trackNumber = o.int("track"),
        discNumber = o.int("discNumber"),
        parentId = o.str("albumId"),
        album = o.str("album"),
        composer = o.str("composer"),
    )

    private fun playlistItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = PROVIDER, name = o.str("name") ?: "Playlist",
        uri = o.str("id"), mediaType = "playlist",
        subtitle = o.int("songCount")?.let { if (it == 1) "1 song" else "$it songs" },
        image = coverUrl(o.str("coverArt")), duration = o.int("duration"),
    )

    /**
     * The stored file's own format, for the quality badge. `samplingRate`,
     * `bitDepth`, `bitRate` and `channelCount` are OpenSubsonic fields; a plain
     * Subsonic server sends none of them, so what it doesn't say is left at 0 rather
     * than guessed. The depth used to default to 16, which made every track on a
     * plain Subsonic server claim CD depth it had never reported.
     */
    private fun audioFormat(o: JsonObject): MaAudioFormat? {
        val codec = o.str("suffix") ?: o.str("contentType")?.substringAfterLast('/') ?: return null
        // OpenSubsonic's `replayGain` object: {trackGain, albumGain, trackPeak,
        // albumPeak, baseGain}, all optional and all in dB. Navidrome sends it when
        // the file carries the tags; plain Subsonic servers omit the object
        // entirely, which is why absent stays null rather than becoming 0 dB —
        // "no measurement" and "measured at unity" are different answers.
        val rg = o["replayGain"] as? JsonObject
        // `bitRate` is optional even on OpenSubsonic, but `size` and `duration` are
        // plain Subsonic fields every server sends — and a file's average bitrate is
        // exactly its size over its length. Derived only as a fallback: a server that
        // states its bitrate knows better than this arithmetic does.
        val stated = o.int("bitRate") ?: 0
        val size = o.long("size") ?: 0L
        val seconds = o.int("duration") ?: 0
        val derived = if (stated <= 0 && size > 0 && seconds > 0) {
            (size * 8 / seconds / 1000).toInt()
        } else 0
        return MaAudioFormat(
            codec = codec,
            sampleRate = o.int("samplingRate") ?: 0,
            bitDepth = o.int("bitDepth") ?: 0,
            bitRate = if (stated > 0) stated else derived,
            // 0, not 2: a plain Subsonic server saying nothing about channels is not
            // the same as it saying stereo, and the badge can leave it out.
            channels = o.int("channelCount") ?: 0,
            replayGainTrack = rg?.float("trackGain"),
            replayGainAlbum = rg?.float("albumGain"),
            sizeBytes = size,
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.float(k: String) = this[k]?.jsonPrimitive?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}

private fun JsonArray?.orEmptyArray(): List<JsonElement> = this ?: emptyList()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
