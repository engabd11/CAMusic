package com.engabd.sendpin.subsonic

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
class SubsonicException(message: String) : IOException(message)

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
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        /** The provider tag every item from this client carries. */
        const val PROVIDER = "subsonic"

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

    private fun restUrl(endpoint: String, params: Map<String, String>, jsonFmt: Boolean): String {
        val sb = StringBuilder("${base()}/rest/$endpoint.view?${authQuery()}")
        for ((k, v) in params) sb.append("&${enc(k)}=${enc(v)}")
        if (jsonFmt) sb.append("&f=json")
        return sb.toString()
    }

    /**
     * `format=raw` tells Navidrome to hand back the stored file untouched. Without it
     * the server applies whatever transcoding profile it has for this client, which
     * would silently turn a FLAC into 192k MP3 — so it is not optional if playback is
     * meant to be bit-perfect. `estimateContentLength` is off for the same reason: it
     * only means anything for transcoded output.
     */
    fun streamUrl(id: String): String =
        restUrl("stream", mapOf("id" to id, "format" to "raw"), jsonFmt = false)

    /** `/download` is defined to return the original file, so it needs no format hint. */
    fun downloadUrl(id: String): String = restUrl("download", mapOf("id" to id), jsonFmt = false)

    fun coverUrl(id: String?, size: Int = COVER_PX): String? =
        id?.takeIf { it.isNotBlank() }
            ?.let { restUrl("getCoverArt", mapOf("id" to it, "size" to size.toString()), jsonFmt = false) }

    // --- requests ---------------------------------------------------------

    private suspend fun get(endpoint: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val body = try {
                http.newCall(Request.Builder().url(restUrl(endpoint, params, jsonFmt = true)).build())
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
                throw SubsonicException(
                    err?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "The server refused the request (code ${err?.get("code")?.jsonPrimitive?.contentOrNull ?: "?"})"
                )
            }
            root
        }

    /** Reachability + credentials probe. Returns null when fine, else why not. */
    suspend fun pingError(): String? = try {
        get("ping")
        null
    } catch (e: SubsonicException) {
        e.message
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
     * Best-effort: a server that refuses it must not break playback.
     */
    suspend fun scrobble(id: String, submission: Boolean = true) {
        try {
            get("scrobble", mapOf("id" to id, "submission" to submission.toString()))
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
    )

    private fun playlistItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = PROVIDER, name = o.str("name") ?: "Playlist",
        uri = o.str("id"), mediaType = "playlist",
        subtitle = o.int("songCount")?.let { if (it == 1) "1 song" else "$it songs" },
        image = coverUrl(o.str("coverArt")), duration = o.int("duration"),
    )

    /**
     * The stored file's own format, for the quality badge. `samplingRate` and
     * `bitDepth` are OpenSubsonic fields; a plain Subsonic server sends neither, so
     * an unknown rate is left at 0 rather than guessed at 44.1.
     */
    private fun audioFormat(o: JsonObject): MaAudioFormat? {
        val codec = o.str("suffix") ?: o.str("contentType")?.substringAfterLast('/') ?: return null
        return MaAudioFormat(
            codec = codec,
            sampleRate = o.int("samplingRate") ?: 0,
            bitDepth = o.int("bitDepth") ?: 16,
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}

private fun JsonArray?.orEmptyArray(): List<JsonElement> = this ?: emptyList()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
