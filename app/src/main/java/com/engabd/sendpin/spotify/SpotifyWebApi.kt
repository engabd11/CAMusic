package com.engabd.sendpin.spotify

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class SpotifyApiException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** 401 is a stale token (retried once with a fresh one); 403 is a real refusal. */
    val isAuth: Boolean get() = httpCode == 403
}

/**
 * Spotify's Web API, authenticated with the **user's own session token**.
 *
 * This is not the dev-mode app path (its 5-user allowlist makes it useless for an
 * open-source app): librespot's `TokenProvider` mints an access token from the
 * *session it already opened with the user's credentials*, and that token works
 * against the read-only Web API endpoints exactly like Music Assistant uses its own.
 * The app therefore never registers a Spotify client id at all.
 *
 * Parsers are pure and fixture-tested (`SpotifyApiParseTest`); the HTTP layer is a
 * thin shell, per the house pattern.
 */
class SpotifyWebApi(
    /** Mints fresh tokens from the live session; wired by [SpotifySource]. */
    private val tokenProvider: () -> String,
    private val http: OkHttpClient = Http.base,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val BASE = "https://api.spotify.com/v1"
        const val PROVIDER = "spotify"
    }

    // ── Library ───────────────────────────────────────────────────────────

    /** The user's saved tracks, one page. */
    suspend fun savedTracks(limit: Int = 50, offset: Int = 0): List<MaItem> =
        paging("me/tracks?limit=$limit&offset=$offset").mapNotNull { parseTrack(it) }

    /** The user's saved albums, one page. */
    suspend fun savedAlbums(limit: Int = 50, offset: Int = 0): List<MaItem> =
        paging("me/albums?limit=$limit&offset=$offset").mapNotNull { parseAlbum(it) }

    /** The user's playlists (owned + followed), one page. */
    suspend fun playlists(limit: Int = 50, offset: Int = 0): List<MaItem> =
        get("me/playlists?limit=$limit&offset=$offset")["items"].items()
            .mapNotNull { parsePlaylist(it) }

    /** One playlist's tracks, one page. */
    suspend fun playlistTracks(id: String, limit: Int = 100, offset: Int = 0): List<MaItem> =
        paging("playlists/$id/tracks?limit=$limit&offset=$offset").mapNotNull { parseTrack(it) }

    /** One album's tracks, one page (short `track` objects — album context filled in). */
    suspend fun albumTracks(id: String, album: MaItem): List<MaItem> =
        paging("albums/$id/tracks?limit=50").mapNotNull { parseTrack(it, albumContext = album) }

    /** An artist's top tracks. */
    suspend fun artistTopTracks(id: String): List<MaItem> =
        get("artists/$id/top-tracks?market=from_token")["tracks"].items()
            .mapNotNull { parseTrack(it) }

    /** An artist's albums, one page. */
    suspend fun artistAlbums(id: String, limit: Int = 50, offset: Int = 0): List<MaItem> =
        paging("artists/$id/albums?limit=$limit&offset=$offset").mapNotNull { parseAlbum(it) }

    /** One track. */
    suspend fun track(id: String): MaItem? = parseTrack(get("tracks/$id"))

    /** One album. */
    suspend fun album(id: String): MaItem? = parseAlbum(get("albums/$id"))

    // ── Search ────────────────────────────────────────────────────────────

    suspend fun search(query: String, limit: Int = 20): MaSearchResults {
        val body = get(
            "search?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&type=track,album,artist,playlist&limit=$limit",
        )
        return MaSearchResults(
            artists = body["artists"]?.obj()?.get("items").items().mapNotNull { parseArtist(it) },
            albums = body["albums"]?.obj()?.get("items").items().mapNotNull { parseAlbum(it) },
            tracks = body["tracks"]?.obj()?.get("items").items().mapNotNull { parseTrack(it) },
            playlists = body["playlists"]?.obj()?.get("items").items().mapNotNull { parsePlaylist(it) },
        )
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private suspend fun get(pathAndQuery: String): JsonObject = withContext(Dispatchers.IO) {
        request(pathAndQuery, tokenProvider())
    }

    /** Bare-endpoint fetch, for source paths that need one (artist metadata). */
    suspend fun artist(id: String): MaItem? = parseArtist(get("artists/$id"))

    /** A `paging` object's items, descending into the Spotify page envelope. */
    private suspend fun paging(pathAndQuery: String): List<JsonObject> {
        val body = get(pathAndQuery)
        return body["items"].items()
    }

    private suspend fun request(pathAndQuery: String, token: String): JsonObject {
        val request = Request.Builder()
            .url("$BASE/$pathAndQuery")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (response.code == 401) throw SpotifyApiException("Spotify token rejected", httpCode = 401)
            if (response.code == 403) throw SpotifyApiException("Spotify refused this request", httpCode = 403)
            if (response.code == 429) throw SpotifyApiException("Spotify is rate-limiting this app — try again shortly", httpCode = 429)
            if (!response.isSuccessful) throw SpotifyApiException("Spotify returned ${response.code}", httpCode = response.code)
            return json.parseToJsonElement(bodyText).jsonObject
        }
    }

    // ── Parsers (pure; fixture-tested) ────────────────────────────────────

    fun parseTrack(obj: JsonObject, albumContext: MaItem? = null): MaItem? {
        val track = obj["track"]?.obj() ?: obj
        val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val album = track["album"]?.obj()
        val artists = track["artists"].items()
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = track["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://open.spotify.com/track/$id",
            mediaType = "track",
            subtitle = artists.firstOrNull()?.get("name")?.jsonPrimitive?.contentOrNull,
            image = album?.let { parseImage(it) } ?: albumContext?.image,
            duration = track["duration_ms"]?.jsonPrimitive?.longOrNull?.div(1000)?.toInt(),
            trackNumber = track["track_number"]?.jsonPrimitive?.intOrNull,
            discNumber = track["disc_number"]?.jsonPrimitive?.intOrNull,
            album = album?.get("name")?.jsonPrimitive?.contentOrNull ?: albumContext?.name,
            parentId = album?.get("id")?.jsonPrimitive?.contentOrNull ?: albumContext?.itemId,
        )
    }

    fun parseAlbum(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val artists = obj["artists"].items()
        val year = obj["release_date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://open.spotify.com/album/$id",
            mediaType = "album",
            subtitle = artists.firstOrNull()?.get("name")?.jsonPrimitive?.contentOrNull,
            image = parseImage(obj),
            duration = null,
            year = year,
            parentId = artists.firstOrNull()?.get("id")?.jsonPrimitive?.contentOrNull,
        )
    }

    fun parseArtist(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://open.spotify.com/artist/$id",
            mediaType = "artist",
            subtitle = null,
            image = parseImage(obj),
            duration = null,
        )
    }

    fun parsePlaylist(obj: JsonObject): MaItem? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            uri = "https://open.spotify.com/playlist/$id",
            mediaType = "playlist",
            subtitle = obj["owner"]?.obj()?.get("display_name")?.jsonPrimitive?.contentOrNull,
            image = obj["images"]?.takeIf { it is JsonArray }
                ?.let { (it as JsonArray).firstOrNull() as? JsonObject }
                ?.get("url")?.jsonPrimitive?.contentOrNull,
            duration = null,
        )
    }

    /** Spotify art always arrives as an `images: [{url, width, height}]` array. */
    fun parseImage(obj: JsonObject): String? =
        obj["images"]?.takeIf { it is JsonArray }
            ?.let { (it as JsonArray).firstOrNull() as? JsonObject }
            ?.get("url")?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun kotlinx.serialization.json.JsonElement?.items(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
