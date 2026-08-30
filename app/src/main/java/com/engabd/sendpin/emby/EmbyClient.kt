package com.engabd.sendpin.emby

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class EmbyException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** A rejected login is worth re-prompting for; an unreachable host is not. */
    val isAuth: Boolean get() = httpCode == 401 || httpCode == 403
}

/**
 * An Emby music library, browsed and streamed directly.
 *
 * Emby is Jellyfin's ancestor — the two forked from one codebase and their `/Items`
 * DTOs are still close enough to share field names — but they have drifted where it
 * matters here: the auth header is `X-Emby-Authorization` rather than `Authorization`,
 * and Emby never grew Jellyfin's `/universal` negotiating endpoint, so a transcode
 * asks `/Audio/{id}/stream.{container}` directly with the codec and bitrate as query
 * parameters instead. Written as its own client rather than a `JellyfinClient`
 * subclass for the same reason `JellyfinClient` is not a `SubsonicClient` subclass —
 * the two owe nothing to each other, only to the same shape of API.
 */
class EmbyClient(
    private val baseUrl: String,
    /** The access token from [authenticate], or "" before signing in. */
    @Volatile var token: String = "",
    /** The authenticated user's id — needed in the path of most endpoints. */
    @Volatile var userId: String = "",
    /** Which library to browse. Blank means the whole server. */
    @Volatile var libraryId: String = "",
    private val deviceId: String = "camusic",
    private val http: OkHttpClient = shared,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val PROVIDER = "emby"

        private const val CLIENT = "CAMusic"
        private const val DEVICE = "Android"
        private const val VERSION = "1.0"

        /** The app-wide client: one pool, one cache, one User-Agent. See [Http]. */
        private val shared: OkHttpClient get() = Http.base

        /** One cover size for every use, sampled down by Coil. As Subsonic/Jellyfin do. */
        private const val COVER_PX = 1000

        /** The types worth listing from a music library. */
        private const val MUSIC_TYPES = "MusicArtist,MusicAlbum,Audio"

        private const val BASE_FIELDS =
            "Genres,DateCreated,ChildCount,ProductionYear,AlbumId,ParentId,AlbumArtists," +
                "ArtistItems,ParentIndexNumber,IndexNumber"

        /** The codec to transcode *to* for a given container, matching Jellyfin's map. */
        private fun codecFor(container: String): String = when (container) {
            "m4a" -> "aac"
            "ogg" -> "opus"
            else -> container
        }
    }

    val serverUrl: String get() = base()

    /** Emby's `container` for `/stream.{container}`, or "raw" for the stored file. */
    @Volatile
    var streamFormat: String = "raw"

    // ── URLs ──────────────────────────────────────────────────────────────

    /** Scheme guessing follows the address, exactly as `SubsonicClient.base` does. */
    private fun base(): String {
        val b = baseUrl.trim().trimEnd('/')
        if (b.startsWith("http://") || b.startsWith("https://")) return b
        val host = b.substringBefore('/').substringBefore(':')
        val local = host == "localhost" ||
            host.endsWith(".local", ignoreCase = true) ||
            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return (if (local) "http://" else "https://") + b
    }

    /**
     * The header Emby authenticates every request with.
     *
     * `X-Emby-Authorization`, not `Authorization` — the one header that would fail
     * silently if this were copied from `JellyfinClient` unchanged, since Emby ignores
     * the header it doesn't recognise rather than rejecting the request, and every
     * call would look like it works right up until one that actually needs the user
     * id it never received.
     */
    private fun authHeader(): String = buildString {
        append("Emby Client=\"$CLIENT\", Device=\"$DEVICE\", DeviceId=\"$deviceId\", Version=\"$VERSION\"")
        if (token.isNotBlank()) append(", Token=\"$token\"")
    }

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base()).append(path)
        if (params.isNotEmpty()) {
            sb.append('?')
            sb.append(params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })
        }
        return sb.toString()
    }

    /**
     * A playable URL: the stored file byte-for-byte, or a transcode.
     *
     * Emby has no `/universal` negotiator, so a transcode names its container in the
     * path — `/Audio/{id}/stream.mp3` rather than Jellyfin's single `/universal` with
     * a profile — and the codec/bitrate ride as plain query parameters Emby has
     * supported since long before the fork.
     */
    fun streamUrl(id: String, format: String = streamFormat): String {
        val container = format.substringBefore('-').ifBlank { "raw" }
        val bitrate = format.substringAfter('-', "").toIntOrNull()
        val common = buildMap {
            put("api_key", token)
            put("deviceId", deviceId)
            if (userId.isNotBlank()) put("userId", userId)
        }
        if (container == "raw") {
            return url("/Audio/$id/stream", common + mapOf("static" to "true"))
        }
        return url(
            "/Audio/$id/stream.$container",
            common + buildMap {
                put("audioCodec", codecFor(container))
                // Emby wants bits per second where the token carries kbps.
                bitrate?.let { put("audioBitRate", (it * 1000).toString()) }
            },
        )
    }

    /** `/Items/{id}/Download` returns the original file, so it takes no format hint. */
    fun downloadUrl(id: String): String =
        url("/Items/$id/Download", mapOf("api_key" to token))

    /**
     * Cover art. Carries the token because Coil opens this URL directly, without the
     * `X-Emby-Authorization` header the rest of the client sends.
     */
    fun coverUrl(id: String?, size: Int = COVER_PX): String? =
        id?.takeIf { it.isNotBlank() }?.let {
            url(
                "/Items/$it/Images/Primary",
                buildMap {
                    put("maxWidth", size.toString())
                    if (token.isNotBlank()) put("api_key", token)
                },
            )
        }

    // ── Transport ─────────────────────────────────────────────────────────

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) { request(Request.Builder().url(url(path, params)).get()) }

    private suspend fun post(path: String, body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val payload = (body ?: JsonObject(emptyMap())).toString()
                .toRequestBody("application/json".toMediaType())
            request(Request.Builder().url(url(path)).post(payload))
        }

    private suspend fun delete(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) { request(Request.Builder().url(url(path, params)).delete()) }

    private fun request(builder: Request.Builder): JsonObject {
        val body = try {
            http.newCall(builder.header("X-Emby-Authorization", authHeader()).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw EmbyException(explain(resp.code), httpCode = resp.code)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: EmbyException) {
            throw e
        } catch (e: Exception) {
            throw EmbyException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
        }
        // Several endpoints (favourites, playback reporting) answer 204 with no body.
        if (body.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw EmbyException("That address didn't answer like an Emby server")
        } catch (e: EmbyException) {
            throw e
        } catch (_: Exception) {
            throw EmbyException("That address didn't answer like an Emby server")
        }
    }

    private fun explain(code: Int): String = when (code) {
        401 -> "Emby rejected the username or password"
        403 -> "That account isn't allowed to browse this library"
        404 -> "Not found on the server, it may have been removed"
        else -> "Emby returned HTTP $code"
    }

    // ── Auth ──────────────────────────────────────────────────────────────

    /**
     * Sign in, keeping the token and user id for later calls.
     *
     * Returns them too, so the caller can persist them and skip this next time — an
     * Emby token does not expire on its own, and re-authenticating on every launch
     * would create a new device session in the server's dashboard each time.
     */
    suspend fun authenticate(username: String, password: String): Pair<String, String> {
        val res = post(
            "/Users/AuthenticateByName",
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
            },
        )
        val accessToken = res.str("AccessToken")
            ?: throw EmbyException("Emby didn't return an access token", httpCode = 401)
        val user = (res["User"] as? JsonObject)?.str("Id")
            ?: throw EmbyException("Emby didn't say which user that was", httpCode = 401)
        token = accessToken
        userId = user
        return accessToken to user
    }

    /**
     * As [pingError], but keeping the exception so the caller can tell a rejected
     * login from an unreachable host — see [com.engabd.sendpin.library.SourceError].
     */
    suspend fun pingResult(): EmbyException? = try {
        if (token.isBlank()) EmbyException("Not signed in to Emby", httpCode = 401)
        else { get("/System/Info"); null }
    } catch (e: EmbyException) {
        e
    }

    /**
     * The server's libraries, so the user can pick which one holds their music.
     *
     * An Emby server usually has films and television alongside it, and browsing the
     * lot as one flat list of "items" is not a music library.
     */
    suspend fun musicLibraries(): List<MaItem> =
        get("/Users/$userId/Views")["Items"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("CollectionType").equals("music", ignoreCase = true) }
            .map {
                MaItem(
                    itemId = it.str("Id") ?: "", provider = PROVIDER,
                    name = it.str("Name") ?: "Music", uri = it.str("Id"),
                    mediaType = "library", subtitle = null,
                    image = coverUrl(it.str("Id")), duration = null,
                )
            }

    // ── Browse ────────────────────────────────────────────────────────────

    private suspend fun items(
        types: String = MUSIC_TYPES,
        parentId: String? = null,
        sortBy: String? = null,
        sortOrder: String = "Ascending",
        filters: String? = null,
        searchTerm: String? = null,
        artistIds: String? = null,
        limit: Int = 200,
        offset: Int = 0,
        recursive: Boolean = true,
        /** Search the whole server rather than the music library — see [playlists]. */
        ignoreLibrary: Boolean = false,
    ): List<MaItem> {
        val params = buildMap {
            put("IncludeItemTypes", types)
            put("Recursive", recursive.toString())
            put("Limit", limit.toString())
            put("StartIndex", offset.toString())
            put(
                "Fields",
                if (types.contains("Audio")) BASE_FIELDS + ",MediaSources" else BASE_FIELDS,
            )
            put("ImageTypeLimit", "1")
            val parent = parentId?.takeIf { it.isNotBlank() }
                ?: libraryId.takeIf { !ignoreLibrary && it.isNotBlank() }
            parent?.let { put("ParentId", it) }
            sortBy?.let { put("SortBy", it); put("SortOrder", sortOrder) }
            filters?.let { put("Filters", it) }
            searchTerm?.let { put("SearchTerm", it) }
            artistIds?.let { put("ArtistIds", it) }
        }
        return get("/Users/$userId/Items", params)["Items"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::item) }
    }

    suspend fun artists(limit: Int = 500): List<MaItem> =
        items(types = "MusicArtist", sortBy = "SortName", limit = limit)

    suspend fun albums(offset: Int = 0, limit: Int = 200): List<MaItem> =
        items(types = "MusicAlbum", sortBy = "SortName", limit = limit, offset = offset)

    suspend fun recentlyAdded(limit: Int = 200): List<MaItem> =
        items(types = "MusicAlbum", sortBy = "DateCreated", sortOrder = "Descending", limit = limit)

    suspend fun recentlyPlayed(limit: Int = 200): List<MaItem> =
        items(types = "MusicAlbum", sortBy = "DatePlayed", sortOrder = "Descending", filters = "IsPlayed", limit = limit)

    suspend fun mostPlayed(limit: Int = 200): List<MaItem> =
        items(types = "MusicAlbum", sortBy = "PlayCount", sortOrder = "Descending", filters = "IsPlayed", limit = limit)

    suspend fun randomSongs(size: Int = 100): List<MaItem> =
        items(types = "Audio", sortBy = "Random", limit = size)

    suspend fun randomAlbums(limit: Int = 12): List<MaItem> =
        items(types = "MusicAlbum", sortBy = "Random", limit = limit)

    suspend fun albumTracks(albumId: String): List<MaItem> =
        items(types = "Audio", parentId = albumId, sortBy = "ParentIndexNumber,IndexNumber", recursive = false)

    suspend fun artistAlbums(artistId: String): List<MaItem> =
        items(types = "MusicAlbum", artistIds = artistId, sortBy = "ProductionYear,SortName")

    suspend fun item(id: String): MaItem? =
        (get("/Users/$userId/Items/$id") as JsonObject?)?.takeIf { it.isNotEmpty() }?.let(::item)

    suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> = item(id) to albumTracks(id)

    suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> = item(id) to artistAlbums(id)

    /** Playlists live outside the music library's own tree, so this must not inherit [libraryId]. */
    suspend fun playlists(): List<MaItem> =
        items(types = "Playlist", sortBy = "SortName", ignoreLibrary = true)

    suspend fun playlistTracks(id: String): List<MaItem> =
        items(types = "Audio", parentId = id, recursive = false)

    suspend fun genres(): List<MaItem> =
        get(
            "/MusicGenres",
            buildMap {
                put("Limit", "500")
                put("SortBy", "SortName")
                libraryId.takeIf { it.isNotBlank() }?.let { put("ParentId", it) }
            },
        )["Items"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val name = o.str("Name") ?: return@mapNotNull null
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = o.str("Id"),
                    mediaType = "genre", subtitle = null, image = null, duration = null,
                )
            }

    suspend fun songsByGenre(genre: String, count: Int = 300, offset: Int = 0): List<MaItem> {
        val params = buildMap {
            put("IncludeItemTypes", "Audio")
            put("Recursive", "true")
            put("Genres", genre)
            put("Limit", count.toString())
            put("StartIndex", offset.toString())
            put("Fields", "MediaSources,Genres,AlbumId")
            libraryId.takeIf { it.isNotBlank() }?.let { put("ParentId", it) }
        }
        return get("/Users/$userId/Items", params)["Items"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::item) }
    }

    suspend fun favorites(): MaSearchResults {
        val all = items(filters = "IsFavorite", limit = 500)
        val favouritePlaylists = runCatching {
            items(types = "Playlist", filters = "IsFavorite", ignoreLibrary = true)
        }.getOrDefault(emptyList())
        return MaSearchResults(
            artists = all.filter { it.mediaType == "artist" },
            albums = all.filter { it.mediaType == "album" },
            tracks = all.filter { it.mediaType == "track" },
            playlists = favouritePlaylists,
        )
    }

    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val hits = items(searchTerm = query, limit = limit * 4)
        return MaSearchResults(
            artists = hits.filter { it.mediaType == "artist" }.take(limit),
            albums = hits.filter { it.mediaType == "album" }.take(limit),
            tracks = hits.filter { it.mediaType == "track" }.take(limit),
            playlists = emptyList(),
        )
    }

    suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistAlbums(item.itemId)
        "album" -> albumTracks(item.itemId)
        "playlist" -> playlistTracks(item.itemId)
        "genre" -> songsByGenre(item.itemId)
        else -> emptyList()
    }

    suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album", "playlist", "genre" -> children(item)
        "artist" -> artistAlbums(item.itemId).flatMap { albumTracks(it.itemId) }
        else -> emptyList()
    }

    // ── Write ─────────────────────────────────────────────────────────────

    suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) post("/Users/$userId/FavoriteItems/$id")
        else delete("/Users/$userId/FavoriteItems/$id")
    }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? =
        post(
            "/Playlists",
            buildJsonObject {
                put("Name", name)
                put("UserId", userId)
                put("MediaType", "Audio")
                put("Ids", JsonArray(songIds.map { JsonPrimitive(it) }))
            },
        ).str("Id")

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        if (songIds.isEmpty()) return
        postQuery("/Playlists/$playlistId/Items", mapOf("Ids" to songIds.joinToString(","), "UserId" to userId))
    }

    suspend fun deleteItem(id: String) { delete("/Items/$id") }

    /** Playback reporting, Emby's equivalent of a Subsonic scrobble. */
    suspend fun reportPlayback(id: String, completed: Boolean, positionMs: Long = 0) {
        val body = sessionBody(id, positionMs)
        if (completed) post("/Sessions/Playing/Stopped", body)
        else post("/Sessions/Playing", body)
    }

    suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) {
        post("/Sessions/Playing/Progress", sessionBody(id, positionMs, paused))
    }

    private fun sessionBody(id: String, positionMs: Long, paused: Boolean = false): JsonObject =
        buildJsonObject {
            put("ItemId", id)
            put("MediaSourceId", id)
            put("PositionTicks", positionMs * 10_000)
            put("IsPaused", paused)
            put("CanSeek", true)
            put("PlayMethod", if (streamFormat == "raw") "DirectPlay" else "Transcode")
        }

    // ── Item mapping ──────────────────────────────────────────────────────

    /**
     * One `BaseItemDto` as an [MaItem] — the same shape Jellyfin returns, since the
     * two forked from a shared model. See `JellyfinClient.item` for the field-by-field
     * reasoning; kept identical here rather than shared, because the day these two
     * diverge on a field is the day sharing this function becomes a bug in one of
     * them.
     */
    internal fun item(o: JsonObject): MaItem? {
        val id = o.str("Id") ?: return null
        val type = o.str("Type").orEmpty()
        val mediaType = when (type) {
            "MusicArtist", "Artist" -> "artist"
            "MusicAlbum" -> "album"
            "Audio" -> "track"
            "Playlist" -> "playlist"
            "MusicGenre" -> "genre"
            else -> return null
        }
        val seconds = o.long("RunTimeTicks")?.let { (it / 10_000_000).toInt() }?.takeIf { it > 0 }
        val artistName = o.str("AlbumArtist")
            ?: o["Artists"]?.jsonArray.orEmpty().firstOrNull()?.jsonPrimitive?.contentOrNull
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = o.str("Name") ?: "Unknown",
            uri = id,
            mediaType = mediaType,
            subtitle = when (mediaType) {
                "artist" -> o.int("ChildCount")?.let { if (it == 1) "1 album" else "$it albums" }
                "playlist" -> o.int("ChildCount")?.let { if (it == 1) "1 song" else "$it songs" }
                else -> artistName
            },
            image = coverUrl(
                if (o["ImageTags"]?.jsonObject?.get("Primary") != null) id
                else o.str("AlbumId") ?: id
            ),
            duration = seconds,
            favorite = (o["UserData"] as? JsonObject)?.bool("IsFavorite") ?: false,
            audioFormat = if (mediaType == "track") audioFormat(o) else null,
            trackNumber = o.int("IndexNumber"),
            discNumber = o.int("ParentIndexNumber")?.takeIf { it >= 0 },
            parentId = when (mediaType) {
                "album" -> o.str("AlbumArtistId")
                    ?: (o["AlbumArtists"]?.jsonArray.orEmpty().firstOrNull() as? JsonObject)?.str("Id")
                    ?: (o["ArtistItems"]?.jsonArray.orEmpty().firstOrNull() as? JsonObject)?.str("Id")
                else -> o.str("AlbumId")
            },
            album = o.str("Album"),
            year = o.int("ProductionYear"),
            genres = o["Genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }

    private fun audioFormat(o: JsonObject): MaAudioFormat? {
        val source = o["MediaSources"]?.jsonArray.orEmpty().firstOrNull() as? JsonObject
        val stream = source?.get("MediaStreams")?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("Type").equals("Audio", ignoreCase = true) }
        val codec = stream?.str("Codec") ?: source?.str("Container") ?: return null
        val bps = stream?.int("BitRate") ?: source?.int("Bitrate") ?: 0
        return MaAudioFormat(
            codec = codec,
            sampleRate = stream?.int("SampleRate") ?: 0,
            bitDepth = stream?.int("BitDepth") ?: 0,
            bitRate = if (bps > 10_000) bps / 1000 else bps,
            channels = stream?.int("Channels") ?: 0,
            sizeBytes = source?.long("Size") ?: 0L,
        )
    }

    // ── JSON helpers, matching SubsonicClient's and JellyfinClient's ─────────

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull

    /** A POST whose arguments go on the query string — `/Playlists/{id}/Items` does. */
    private suspend fun postQuery(path: String, params: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            request(
                Request.Builder()
                    .url(url(path, params))
                    .post("".toRequestBody("application/json".toMediaType())),
            )
        }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
