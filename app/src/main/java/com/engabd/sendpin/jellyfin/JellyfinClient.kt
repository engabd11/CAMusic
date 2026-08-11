package com.engabd.sendpin.jellyfin

import com.engabd.sendpin.data.LanOnlyCleartext
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JellyfinException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** A rejected login is worth re-prompting for; an unreachable host is not. */
    val isAuth: Boolean get() = httpCode == 401 || httpCode == 403
}

/**
 * A Jellyfin music library, browsed and streamed directly.
 *
 * Built to the same shape as `SubsonicClient` — items come back as [MaItem] with
 * `provider = `[PROVIDER], stream and cover URLs are built from ids on demand, and
 * every failure is a [JellyfinException] carrying something a user can read. That is
 * deliberate: the two are interchangeable behind
 * [com.engabd.sendpin.library.MusicSource], and a second client that answered
 * differently would push its differences up into the library.
 *
 * ## Auth
 *
 * `POST /Users/AuthenticateByName` with an `Authorization: MediaBrowser …` header
 * returns an access token and the user's id. Both are needed afterwards — the token
 * on every request, the user id *in the path* of half of them — which is why
 * [ServerConfig.OPT_USER_ID][com.engabd.sendpin.library.ServerConfig.OPT_USER_ID]
 * exists rather than the token alone being stored.
 *
 * ## Why the item mapping is the interesting part
 *
 * Jellyfin returns one `BaseItemDto` shape for artists, albums and tracks,
 * distinguished by `Type`. Its audio metadata lives two levels down in
 * `MediaSources[].MediaStreams[]` — and it is *complete*: codec, sample rate, bit
 * depth, bit rate and channel count, which is everything the quality badge wants. So
 * Jellyfin gets a full `FLAC • 96/24 • 3 Mb/s` badge on day one, where a plain
 * Subsonic server can only name the codec.
 */
class JellyfinClient(
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
        const val PROVIDER = "jellyfin"

        private const val CLIENT = "CAMusic"
        private const val DEVICE = "Android"
        private const val VERSION = "1.0"

        /** Matches the Subsonic client's single shared pool, for the same reasons. */
        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(LanOnlyCleartext)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** One cover size for every use, sampled down by Coil. As Subsonic does. */
        private const val COVER_PX = 1000

        /** The types worth listing from a music library. */
        private const val MUSIC_TYPES = "MusicArtist,MusicAlbum,Audio"
    }

    val serverUrl: String get() = base()

    /**
     * Jellyfin's `container` parameter, or "raw" for the stored file.
     *
     * Same contract as `SubsonicClient.streamFormat` so the two are interchangeable,
     * but a different wire spelling: Jellyfin takes `static=true` for the original
     * file and a `container=` for anything else.
     */
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
     * The header Jellyfin authenticates every request with.
     *
     * It is `Authorization`, not a bearer token: the value is a comma-separated set
     * of fields naming the client, and the token rides inside it. Sending a plain
     * `Bearer` is the most common way to get a 401 from a server that is working.
     */
    private fun authHeader(): String = buildString {
        append("MediaBrowser Client=\"$CLIENT\", Device=\"$DEVICE\", DeviceId=\"$deviceId\", Version=\"$VERSION\"")
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
     * A playable URL. `static=true` asks for the stored file byte-for-byte; anything
     * else asks the server to remux or transcode into [format]'s container.
     *
     * The token goes on the query string here rather than in a header, because this
     * URL is handed to ExoPlayer, which opens it without our interceptors.
     */
    fun streamUrl(id: String, format: String = streamFormat): String {
        val container = format.substringBefore('-').ifBlank { "raw" }
        val bitrate = format.substringAfter('-', "").toIntOrNull()
        val params = buildMap {
            put("api_key", token)
            put("deviceId", deviceId)
            // The user id is required for permission checks even when api_key is present.
            // Without it, Jellyfin may return 401 on the stream endpoint or redirect to
            // a transcoding URL that loses the token on the redirect.
            if (userId.isNotBlank()) put("userId", userId)
            if (container == "raw") {
                put("static", "true")
            } else {
                put("container", container)
                // Jellyfin wants bits per second where the token carries kbps.
                bitrate?.let { put("audioBitRate", (it * 1000).toString()) }
            }
        }
        return url("/Audio/$id/universal", params)
    }

    /** `/Download` is defined to return the original file, so it takes no format hint. */
    fun downloadUrl(id: String): String =
        url("/Items/$id/Download", mapOf("api_key" to token))

    fun coverUrl(id: String?, size: Int = COVER_PX): String? =
        id?.takeIf { it.isNotBlank() }?.let {
            url("/Items/$it/Images/Primary", mapOf("maxWidth" to size.toString()))
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
            http.newCall(builder.header("Authorization", authHeader()).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw JellyfinException(explain(resp.code), httpCode = resp.code)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: JellyfinException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
        }
        // Several endpoints (favourites, playback reporting) answer 204 with no body.
        // That is a success, not a parse failure.
        if (body.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw JellyfinException("That address didn't answer like a Jellyfin server")
        } catch (e: JellyfinException) {
            throw e
        } catch (_: Exception) {
            throw JellyfinException("That address didn't answer like a Jellyfin server")
        }
    }

    private fun explain(code: Int): String = when (code) {
        401 -> "Jellyfin rejected the username or password"
        403 -> "That account isn't allowed to browse this library"
        404 -> "Not found on the server — it may have been removed"
        else -> "Jellyfin returned HTTP $code"
    }

    // ── Auth ──────────────────────────────────────────────────────────────

    /**
     * Sign in, keeping the token and user id for later calls.
     *
     * Returns them too, so the caller can persist them and skip this next time — a
     * Jellyfin token does not expire on its own, and re-authenticating on every launch
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
            ?: throw JellyfinException("Jellyfin didn't return an access token", httpCode = 401)
        val user = (res["User"] as? JsonObject)?.str("Id")
            ?: throw JellyfinException("Jellyfin didn't say which user that was", httpCode = 401)
        token = accessToken
        userId = user
        return accessToken to user
    }

    /** Null when the server answered and the token works, else why not. */
    suspend fun pingError(): String? = pingResult()?.message

    /**
     * As [pingError], but keeping the exception so the caller can tell a rejected
     * login from an unreachable host — see
     * [com.engabd.sendpin.library.SourceError].
     */
    suspend fun pingResult(): JellyfinException? = try {
        if (token.isBlank()) JellyfinException("Not signed in to Jellyfin", httpCode = 401)
        else { get("/System/Info"); null }
    } catch (e: JellyfinException) {
        e
    }

    /**
     * The server's libraries, so the user can pick which one holds their music.
     *
     * A Jellyfin server usually has films and television alongside it, and browsing
     * the lot as one flat list of "items" is not a music library.
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

    /**
     * The one query behind almost every browse call.
     *
     * Jellyfin's `/Items` is a single filtered, sorted, paged endpoint rather than an
     * endpoint per shelf, so the shelves differ only by their sort and filter. Fields
     * are requested explicitly: `MediaSources` is what carries the audio format, and
     * without asking for it every track would come back formatless and the quality
     * badge would have nothing to show.
     */
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
            // `AlbumArtists` is requested explicitly: without it an album comes back
            // with no artist id at all, and "more by this artist" has nothing to ask.
            put("Fields", "MediaSources,Genres,DateCreated,ChildCount,ProductionYear,AlbumId,ParentId,AlbumArtists,ArtistItems")
            put("ImageTypeLimit", "1")
            // Blank is not a parent. Sending `ParentId=` asks Jellyfin to look under a
            // folder with no id, which is not the same request as omitting it.
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

    /**
     * Playlists live outside the music library's own tree, so this is the one browse
     * that must *not* inherit [libraryId] — scoping it to the music folder returns
     * nothing at all.
     */
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
                    // The *name* is the key everything else takes, the same way it is
                    // on Subsonic — so it is the id here too.
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
        return MaSearchResults(
            artists = all.filter { it.mediaType == "artist" },
            albums = all.filter { it.mediaType == "album" },
            tracks = all.filter { it.mediaType == "track" },
            playlists = emptyList(),
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

    /**
     * Playback reporting, which is how Jellyfin's play counts and "continue
     * listening" stay honest — its equivalent of a Subsonic scrobble.
     */
    suspend fun reportPlayback(id: String, completed: Boolean, positionMs: Long = 0) {
        val ticks = positionMs * 10_000  // Jellyfin counts in 100-nanosecond ticks
        val body = buildJsonObject {
            put("ItemId", id)
            put("PositionTicks", ticks)
        }
        if (completed) post("/Sessions/Playing/Stopped", body) else post("/Sessions/Playing", body)
    }

    suspend fun lyrics(songId: String): MaLyrics? = try {
        val res = get("/Audio/$songId/Lyrics")
        val lines = res["Lyrics"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        when {
            lines.isEmpty() -> null
            // Jellyfin gives each line a `Start` in ticks when the file carried an
            // LRC. Rebuilt into LRC text so `MaLyrics` — which already parses stamps
            // for the Subsonic path — renders both sources identically.
            lines.any { it["Start"] != null } -> MaLyrics(
                text = lines.joinToString("\n") { l ->
                    val ms = (l.long("Start") ?: 0L) / 10_000
                    "[%02d:%02d.%02d]%s".format(
                        ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10, l.str("Text").orEmpty(),
                    )
                },
                synced = true,
            )
            else -> MaLyrics(text = lines.joinToString("\n") { it.str("Text").orEmpty() }, synced = false)
        }
    } catch (_: JellyfinException) {
        null
    }

    // ── Item mapping ──────────────────────────────────────────────────────

    /**
     * One `BaseItemDto` as an [MaItem].
     *
     * Jellyfin returns artists, albums and tracks through the same shape, keyed by
     * `Type` — so this is the only place the difference has to be handled, and every
     * caller above gets the same model the Subsonic client produces.
     */
    private fun item(o: JsonObject): MaItem? {
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
        // Ticks again — Jellyfin's RunTimeTicks is 100-nanosecond units.
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
            // Album and track art usually lives on the album, not the track, so the
            // fallback keeps a track row from showing a blank tile.
            image = coverUrl(
                if (o["ImageTags"]?.jsonObject?.get("Primary") != null) id
                else o.str("AlbumId") ?: id
            ),
            duration = seconds,
            favorite = (o["UserData"] as? JsonObject)?.bool("IsFavorite") ?: false,
            audioFormat = if (mediaType == "track") audioFormat(o) else null,
            trackNumber = o.int("IndexNumber"),
            discNumber = o.int("ParentIndexNumber"),
            // An album's parent is its *artist*, which is what the "more by this
            // artist" shelf looks it up by. Falling back to `ParentId` handed that
            // shelf the id of the library folder, which resolves to no artist at all —
            // so an unknown artist is left null rather than filled with a wrong answer.
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

    /**
     * The file's own format, from the first audio stream of the first media source.
     *
     * This is where Jellyfin is *better* than plain Subsonic: `MediaStreams` carries
     * codec, sample rate, bit depth, bit rate and channel count for every track, so
     * the quality badge can read `FLAC • 96/24 • 3 Mb/s` with no derivation. The
     * bitrate arrives in bits per second and is converted, for the same reason MA's
     * parser does it — an unconverted one renders as "3011000k".
     */
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
            // 0 rather than a guessed 2, matching the Subsonic client: "the server
            // didn't say" and "the server said stereo" are different answers.
            channels = stream?.int("Channels") ?: 0,
            sizeBytes = source?.long("Size") ?: 0L,
        )
    }

    // ── JSON helpers, matching SubsonicClient's ───────────────────────────

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
