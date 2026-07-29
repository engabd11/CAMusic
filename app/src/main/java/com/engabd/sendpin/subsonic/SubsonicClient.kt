package com.engabd.sendpin.subsonic

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Direct OpenSubsonic / Navidrome client — browse/search/stream straight from the
 * server, no Music Assistant in the path (the standalone / offline-download source
 * when MA is unavailable). A Kotlin port of the Home Assistant integration's
 * `audio/subsonic.py` + `library/subsonic_backend.py`.
 *
 * Token auth (`t = md5(password + salt)`) so the password never rides on a URL.
 * Items are returned as [MaItem] (provider = "subsonic") so one Library UI renders
 * both backends; a track's `uri` is its Subsonic id (stream/download URLs are built
 * on demand).
 */
class SubsonicClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private companion object {
        const val CLIENT = "sendpin"
        const val API_VERSION = "1.16.1"
        const val HEX = "0123456789abcdef"
    }

    // --- URL building -----------------------------------------------------

    private fun base(): String {
        var b = baseUrl.trim().trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        return b
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
    /**
     * One URL for both list thumbnails and the full-bleed Now Playing cover:
     * Coil samples it down per use and caches a single copy. 300px was sharp
     * enough for a 44dp row and nothing else — a phone cover is ~1100px.
     */
    private fun coverUrl(id: String?): String? =
        id?.let { restUrl("getCoverArt", mapOf("id" to it, "size" to "1200"), jsonFmt = false) }

    // --- requests ---------------------------------------------------------

    private suspend fun get(endpoint: String, params: Map<String, String> = emptyMap()): JsonObject? =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(restUrl(endpoint, params, jsonFmt = true)).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val body = resp.body?.string() ?: return@withContext null
                        val root = json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
                            ?: return@withContext null
                        if (root["status"]?.jsonPrimitive?.contentOrNull == "failed") null else root
                    }
            } catch (_: Exception) {
                null
            }
        }

    /** Reachability + credentials probe. */
    suspend fun ping(): Boolean = get("ping") != null

    // --- browse -----------------------------------------------------------

    suspend fun artists(): List<MaItem> {
        val index = get("getArtists")?.get("artists")?.jsonObject?.get("index")?.jsonArray ?: return emptyList()
        return index.flatMap { grp ->
            (grp.jsonObject["artist"]?.jsonArray ?: JsonArray(emptyList())).map { artistItem(it.jsonObject) }
        }
    }

    suspend fun artistAlbums(id: String): List<MaItem> {
        val albums = get("getArtist", mapOf("id" to id))?.get("artist")?.jsonObject?.get("album")?.jsonArray
            ?: return emptyList()
        return albums.map { albumItem(it.jsonObject) }
    }

    suspend fun albumTracks(id: String): List<MaItem> {
        val songs = get("getAlbum", mapOf("id" to id))?.get("album")?.jsonObject?.get("song")?.jsonArray
            ?: return emptyList()
        return songs.map { songItem(it.jsonObject) }
    }

    suspend fun playlists(): List<MaItem> {
        val pls = get("getPlaylists")?.get("playlists")?.jsonObject?.get("playlist")?.jsonArray ?: return emptyList()
        return pls.map { playlistItem(it.jsonObject) }
    }

    suspend fun playlistTracks(id: String): List<MaItem> {
        val entries = get("getPlaylist", mapOf("id" to id))?.get("playlist")?.jsonObject?.get("entry")?.jsonArray
            ?: return emptyList()
        return entries.map { songItem(it.jsonObject) }
    }

    suspend fun albumList(type: String = "newest", size: Int = 100): List<MaItem> {
        val albums = get("getAlbumList2", mapOf("type" to type, "size" to size.toString()))
            ?.get("albumList2")?.jsonObject?.get("album")?.jsonArray ?: return emptyList()
        return albums.map { albumItem(it.jsonObject) }
    }

    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val res = get("search3", mapOf(
            "query" to query, "artistCount" to limit.toString(),
            "albumCount" to limit.toString(), "songCount" to limit.toString(),
        ))?.get("searchResult3")?.jsonObject
        return MaSearchResults(
            artists = res?.get("artist")?.jsonArray?.map { artistItem(it.jsonObject) } ?: emptyList(),
            albums = res?.get("album")?.jsonArray?.map { albumItem(it.jsonObject) } ?: emptyList(),
            tracks = res?.get("song")?.jsonArray?.map { songItem(it.jsonObject) } ?: emptyList(),
            playlists = emptyList(),
        )
    }

    suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistAlbums(item.itemId)
        "album" -> albumTracks(item.itemId)
        "playlist" -> playlistTracks(item.itemId)
        else -> emptyList()
    }

    // --- item parsing -----------------------------------------------------

    private fun artistItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = "subsonic", name = o.str("name") ?: "Unknown artist",
        uri = o.str("id"), mediaType = "artist",
        subtitle = o.int("albumCount")?.let { "$it albums" },
        image = coverUrl(o.str("coverArt") ?: o.str("id")), duration = null,
    )

    private fun albumItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = "subsonic",
        name = o.str("name") ?: o.str("album") ?: "Unknown album",
        uri = o.str("id"), mediaType = "album", subtitle = o.str("artist"),
        image = coverUrl(o.str("coverArt") ?: o.str("id")), duration = null,
    )

    private fun songItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = "subsonic", name = o.str("title") ?: "Unknown title",
        uri = o.str("id"), mediaType = "track", subtitle = o.str("artist"),
        image = coverUrl(o.str("coverArt") ?: o.str("albumId") ?: o.str("id")), duration = o.int("duration"),
    )

    private fun playlistItem(o: JsonObject) = MaItem(
        itemId = o.str("id") ?: "", provider = "subsonic", name = o.str("name") ?: "Playlist",
        uri = o.str("id"), mediaType = "playlist",
        subtitle = o.int("songCount")?.let { "$it songs" }, image = coverUrl(o.str("coverArt")), duration = null,
    )

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
