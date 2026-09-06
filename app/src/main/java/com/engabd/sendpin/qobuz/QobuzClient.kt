package com.engabd.sendpin.qobuz

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class QobuzException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** A refused login or an expired session is worth re-prompting for; a dead host is not. */
    val isAuth: Boolean get() = httpCode == 401 || httpCode == 403
}

/**
 * The Qobuz catalog and a user's library, over the web app's own API.
 *
 * Qobuz offers **no** official third-party API, and none is needed: the same
 * `api.json/0.2` endpoints the web player calls answer plainly, hand out catalog
 * metadata, favourites and playlists, and — for a signed request — a straight HTTPS
 * url to the actual FLAC bytes. Music Assistant has driven exactly this wire for
 * years, and its provider was the reference for every convention here: the MD5
 * request signature (sorted `keyvalue` pairs + timestamp + app secret, MD5'd), the
 * `format_id` fallback chain on [streamJob], and the placeholder-art skip in
 * [parseImage] (Last.fm's shared "no art" pixel pollutes every result otherwise).
 *
 * The client is deliberately split: [parseArtist]/[parseAlbum]/[parseTrack]/
 * [parsePlaylist]/[parseImage] and [signedParams] are **pure** and unit-tested
 * against fixture JSON, while the HTTP layer is the thin, compilation-verified
 * shell around them — the house pattern (see `PlexClient` for its nearest cousin).
 *
 * App credentials: Qobuz's API wants an app id + secret pair that belongs to whoever
 * calls it. Music Assistant's pair is registered to their project and explicitly not
 * for reuse, so these come in from [com.engabd.sendpin.library.ServerConfig] options —
 * see `QobuzSource` for the defaults story.
 */
class QobuzClient(
    /** The Qobuz account email. */
    @Volatile var username: String = "",
    /** The Qobuz account password. */
    @Volatile var password: String = "",
    /** The app id registered to this application. */
    @Volatile var appId: String = "",
    /** The app secret paired with [appId]. */
    @Volatile var appSecret: String = "",
    private val http: OkHttpClient = shared,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val PROVIDER = "qobuz"
        private const val BASE = "https://www.qobuz.com/api.json/0.2"

        /** The app-wide client: one pool, one cache, one User-Agent. See [Http]. */
        private val shared: OkHttpClient get() = Http.base

        /**
         * Qobuz `format_id`s, highest first. 27 is hi-res FLAC (24 bit, ≥96 kHz),
         * 7 is CD FLAC, 6/5 are MP3 tiers. [streamJob] walks down this chain because
         * — per Music Assistant's comment on the same loop — requesting only the
         * highest sometimes answers an empty url even for streamable tracks.
         */
        val QUALITY_ORDER = listOf(27, 7, 6, 5)
    }

    @Volatile
    private var authToken: String? = null

    /** Cache of [QobuzUser] from the last login, for badges and the settings row. */
    @Volatile
    var user: JsonObject? = null
        private set

    /**
     * The bundle of endpoint name + parameters whose signature [signedParams] built —
     * internal so the signing function stays a pure, testable mapping.
     */
    internal data class SignedRequest(val endpoint: String, val params: Map<String, String>)

    /**
     * Build the signing bundle for [endpoint]: the endpoint with its slashes stripped,
     * then every parameter as `keyvalue` in sorted order, then the unix timestamp and
     * the app secret; the whole thing MD5-hexed. Qobuz mandates MD5 here (it is their
     * wire format, not a security choice), and the timestamp travels alongside the
     * signature so the server can recompute it.
     */
    internal fun signedParams(
        endpoint: String,
        params: Map<String, String>,
        timestampSeconds: Long,
    ): Map<String, String> {
        val signingData = buildString {
            append(endpoint.replace("/", ""))
            for (key in params.keys.sorted()) {
                append(key)
                append(params[key])
            }
            append(timestampSeconds)
            append(appSecret)
        }
        val md5 = MessageDigest.getInstance("MD5")
            .digest(signingData.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return params + mapOf(
            "request_ts" to timestampSeconds.toString(),
            "request_sig" to md5,
            "app_id" to appId,
            "user_auth_token" to (authToken ?: ""),
        )
    }

    /**
     * The signed request [signedParams] would build for [endpoint] — the seam tests
     * pin the wire format through, and [get] builds its query from exactly this.
     */
    internal fun signedRequest(
        endpoint: String,
        params: Map<String, String>,
        timestampSeconds: Long,
    ): SignedRequest = SignedRequest(endpoint, signedParams(endpoint, params, timestampSeconds))

    // ── Account ───────────────────────────────────────────────────────────

    /**
     * Log in, caching the session token and user blob. Qobuz sessions are long-lived
     * (weeks), so this runs once per process unless a 401 clears [authToken].
     */
    suspend fun login() {
        withContext(Dispatchers.IO) {
            val body = get(
                "user/login",
                mapOf(
                    "username" to username,
                    "password" to password,
                    "device_manufacturer_id" to "camusic",
                ),
                authless = true,
            )
            val token = body["user_auth_token"]?.jsonPrimitive?.content
                ?: throw QobuzException("Qobuz accepted the login but sent no session token")
            authToken = token
            user = body["user"]?.jsonObject
        }
    }

    /** Forget the session; the next call logs in again. */
    fun logout() {
        authToken = null
        user = null
    }

    // ── Catalog & library ─────────────────────────────────────────────────

    /** Search everything; Qobuz falls back to all types when `type` is absent. */
    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val body = get("catalog/search", mapOf("query" to query, "limit" to limit.toString()))
        return MaSearchResults(
            artists = body["artists"]?.jsonObject?.items().orEmpty().mapNotNull { parseArtist(it) },
            albums = body["albums"]?.jsonObject?.items().orEmpty().mapNotNull { parseAlbum(it) },
            tracks = body["tracks"]?.jsonObject?.items().orEmpty().mapNotNull { parseTrack(it) },
            playlists = body["playlists"]?.jsonObject?.items().orEmpty().mapNotNull { parsePlaylist(it) },
        )
    }

    /** The user's favourited artists — Qobuz's "library" for that type. */
    suspend fun favoriteArtists(limit: Int = 500, offset: Int = 0): List<MaItem> =
        favorites("artists", limit, offset).mapNotNull { parseArtist(it) }

    /** The user's favourited albums. */
    suspend fun favoriteAlbums(limit: Int = 500, offset: Int = 0): List<MaItem> =
        favorites("albums", limit, offset).mapNotNull { parseAlbum(it) }

    /** The user's favourited tracks. */
    suspend fun favoriteTracks(limit: Int = 500, offset: Int = 0): List<MaItem> =
        favorites("tracks", limit, offset).mapNotNull { parseTrack(it) }

    private suspend fun favorites(type: String, limit: Int, offset: Int): List<JsonObject> =
        get(
            "favorite/getUserFavorites",
            mapOf("type" to type, "limit" to limit.toString(), "offset" to offset.toString()),
        ).get(type)?.jsonObject?.items().orEmpty()

    /** The user's playlists (owned + subscribed). */
    suspend fun playlists(limit: Int = 500, offset: Int = 0): List<MaItem> =
        get(
            "playlist/getUserPlaylists",
            mapOf("limit" to limit.toString(), "offset" to offset.toString()),
        )?.get("playlists")?.jsonObject?.items().orEmpty().mapNotNull { parsePlaylist(it) }

    /** One album's full metadata and its tracks. */
    suspend fun album(id: String): JsonObject =
        get("album/get", mapOf("album_id" to id, "extra" to "tracks"))

    /** One artist's full metadata, optionally with their albums alongside. */
    suspend fun artist(id: String, withAlbums: Boolean = false): JsonObject =
        if (withAlbums) {
            get("artist/get", mapOf("artist_id" to id, "extra" to "albums", "limit" to "100"))
        } else {
            get("artist/get", mapOf("artist_id" to id))
        }

    /** One playlist's metadata; [withTracks] adds the track list. */
    suspend fun playlist(id: String, withTracks: Boolean = false): JsonObject =
        if (withTracks) {
            get(
                "playlist/get",
                mapOf(
                    "playlist_id" to id,
                    "extra" to "tracks",
                    "limit" to "500",
                    "offset" to "0",
                ),
            )
        } else {
            get("playlist/get", mapOf("playlist_id" to id))
        }

    // ── Streaming ─────────────────────────────────────────────────────────

    /**
     * One quality attempt of `track/getFileUrl` — the signed call that answers with
     * the real bytes. Returns the response body or null when Qobuz answered without
     * a url (which [streamJob] treats as "try the next quality down").
     */
    suspend fun fileUrl(trackId: String, formatId: Int): JsonObject? =
        get(
            "track/getFileUrl",
            mapOf(
                "format_id" to formatId.toString(),
                "intent" to "stream",
                "track_id" to trackId,
            ),
            sign = true,
        ).takeIf { it["url"]?.jsonPrimitive?.contentOrNull != null }

    /**
     * The url to hand the player for [trackId], walking the quality chain from the
     * top until one answers. This is what [StreamSchemes]' `qobuz` handler calls at
     * player-open time — the answer expires within minutes, so it is fetched fresh
     * per track and never cached.
     */
    suspend fun streamUrl(trackId: String): String {
        for (formatId in QUALITY_ORDER) {
            val body = fileUrl(trackId, formatId)
            val url = body?.get("url")?.jsonPrimitive?.contentOrNull
            if (!url.isNullOrBlank()) return url
        }
        throw QobuzException("Qobuz has no streamable file for track $trackId")
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /**
     * GET one endpoint, logged in unless [authless]. 401 clears the session and
     * raises [QobuzException.isAuth]; 429 and 5xx raise with the code so the source
     * can distinguish "Qobuz is busy" from "Qobuz refused you".
     */
    private suspend fun get(
        endpoint: String,
        params: Map<String, String>,
        sign: Boolean = false,
        authless: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        val effective = if (sign) {
            signedParams(endpoint, params, System.currentTimeMillis() / 1000)
        } else {
            params + mapOf("app_id" to appId) +
                (if (authless) emptyMap() else mapOf("user_auth_token" to (authToken ?: "")))
        }
        val url = buildString {
            append(BASE).append('/').append(endpoint)
            append('?')
            append(effective.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            })
        }
        val request = Request.Builder()
            .url(url)
            .header("X-App-Id", appId)
            .apply { if (!authless) authToken?.let { header("X-User-Auth-Token", it) } }
            .build()
        val response = http.newCall(request).execute()
        response.use {
            when {
                it.code == 401 && authless ->
                    throw QobuzException("Qobuz refused that login", httpCode = 401)
                it.code == 401 -> {
                    authToken = null
                    throw QobuzException("Qobuz session expired — sign in again", httpCode = 401)
                }
                it.code == 429 ->
                    throw QobuzException("Qobuz is rate-limiting this app — try again shortly", httpCode = 429)
                it.code in 500..599 ->
                    throw QobuzException("Qobuz had a server error (${it.code})", httpCode = it.code)
                !it.isSuccessful ->
                    throw QobuzException("Qobuz returned ${it.code} for $endpoint", httpCode = it.code)
            }
            json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
        }
    }

    private fun JsonObject.items(): List<JsonObject> =
        (this["items"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    // ── Parsers (pure; fixture-tested) ────────────────────────────────────

    /**
     * The best image url on a Qobuz object, or null.
     *
     * Qobuz nests images as `{large: …, small: …}`; playlists instead carry an
     * `images300` array. The hardcoded hash is Last.fm's shared "no artwork"
     * placeholder which Qobuz faithfully forwards — Music Assistant skips it and
     * so does this: a grid of identical blank covers is worse than a fallback.
     */
    fun parseImage(obj: JsonObject): String? {
        (obj["image"] as? JsonObject)?.let { image ->
            for (key in listOf("extralarge", "large", "medium", "small")) {
                val url = image[key]?.jsonPrimitive?.contentOrNull
                if (url != null && PLACEHOLDER !in url) return url
            }
        }
        (obj["images300"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let {
            if (PLACEHOLDER !in it) return it
        }
        return null
    }

    /** An artist, or null when the object is not really one (search noise). */
    fun parseArtist(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://open.qobuz.com/artist/$id",
            mediaType = "artist",
            subtitle = null,
            image = parseImage(obj),
            duration = null,
        )
    }

    /**
     * An album. `title` + `version` are joined the way the app displays releases
     * ("Random Access Memories" / "10th Anniversary Edition"), the artist's name
     * rides in the subtitle, and hi-res capability lands in [MaItem.audioFormat]
     * so the UI can badge 24/192 where it exists.
     */
    fun parseAlbum(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = obj["version"]?.jsonPrimitive?.contentOrNull
        val artist = obj["artist"]?.jsonObject
        val maxRate = (obj["maximum_sampling_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()
        val maxDepth = obj["maximum_bit_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = listOfNotNull(title, version).joinToString(" (").let { if (version != null) "$it)" else it },
            uri = "https://open.qobuz.com/album/$id",
            mediaType = "album",
            subtitle = artist?.get("name")?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj),
            duration = null,
            audioFormat = if (maxRate > 0 || maxDepth > 0) {
                MaAudioFormat(codec = "flac", sampleRate = maxRate, bitDepth = maxDepth)
            } else {
                null
            },
            year = obj["released_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.let { java.time.Instant.ofEpochSecond(it) }?.atZone(java.time.ZoneOffset.UTC)?.year,
            genres = listOfNotNull(obj["genre"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull),
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            parentId = artist?.get("id")?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * A track. Its album (embedded on every Qobuz track object) fills [MaItem.album]
     * and [parentId], the performing artist wins the subtitle over the album artist,
     * and `media_number` is the disc side of the number pair.
     */
    fun parseTrack(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = obj["version"]?.jsonPrimitive?.contentOrNull
        val performer = obj["performer"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        val album = obj["album"]?.jsonObject
        val albumArtist = album?.get("artist")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        val maxRate = (obj["maximum_sampling_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt()
        val maxDepth = obj["maximum_bit_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = listOfNotNull(title, version).joinToString(" (").let { if (version != null) "$it)" else it },
            uri = "https://open.qobuz.com/track/$id",
            mediaType = "track",
            subtitle = performer ?: albumArtist,
            image = parseImage(obj) ?: album?.let { parseImage(it) },
            duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            audioFormat = if (maxRate > 0 || maxDepth > 0) {
                MaAudioFormat(codec = "flac", sampleRate = maxRate, bitDepth = maxDepth)
            } else {
                null
            },
            trackNumber = obj["track_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            discNumber = obj["media_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            album = album?.get("title")?.jsonPrimitive?.contentOrNull,
            parentId = album?.get("id")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** A playlist; its owner's name rides in the subtitle, per the app's playlist rows. */
    fun parsePlaylist(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = name,
            uri = "https://open.qobuz.com/playlist/$id",
            mediaType = "playlist",
            subtitle = obj["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj),
            duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }
}

/**
 * The Last.fm "no artwork" placeholder Qobuz forwards on empty covers — see
 * [QobuzClient.parseImage].
 */
private const val PLACEHOLDER = "2a96cbd8b46e442fc41c2b86b821562f"
