package com.engabd.sendpin.plex

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class PlexException(message: String, val httpCode: Int? = null) : Exception(message) {
    /** A rejected or expired token is worth re-prompting for; an unreachable host is not. */
    val isAuth: Boolean get() = httpCode == 401 || httpCode == 403
}

/**
 * A Plex music library, browsed and streamed directly.
 *
 * Built to the same shape as `SubsonicClient` and `JellyfinClient` — items come back
 * as [MaItem] tagged `provider = `[PROVIDER], and every failure is a [PlexException]
 * a user can read. Signing in is the one thing this client does *not* do: Plex's
 * account lives at plex.tv rather than on this server, so the token it needs is
 * minted by [PlexAuth]'s PIN flow and handed in already made.
 *
 * ## Two shapes, one helper
 *
 * A Plex `MediaContainer` puts folder-like results (a library section) under
 * `Directory` and media results (an artist, an album, a track, a playlist) under
 * `Metadata` — and which one a given endpoint uses is not obvious from the outside,
 * nor perfectly consistent across server versions. [entries] reads whichever key is
 * present rather than betting on one, since the two are never populated at once for
 * the same query and getting this wrong would mean a browse that quietly returns
 * nothing.
 *
 * ## Streaming needs the track first
 *
 * Every other backend can build a stream URL from an id alone, by formula.
 * Plex can't: the byte path lives two levels down, at
 * `Media[0].Part[0].key` on the *track's own metadata*, not on its id. So [item]
 * caches it — and the resized cover path alongside it — the moment a track is
 * parsed, and [streamUrl] and [coverUrl] read the cache rather than the id. A track
 * that has never been fetched has nothing to read, which is why both fall back to
 * the universal transcoder (for streaming) or null (for a cover) rather than
 * guessing a path.
 */
class PlexClient(
    private val baseUrl: String,
    /** The plex.tv-issued access token from [PlexAuth], or "" before signing in. */
    @Volatile var token: String = "",
    /** The music library section's key. Blank means no section has been picked yet. */
    @Volatile var librarySectionKey: String = "",
    private val clientIdentifier: String = "camusic",
    private val http: OkHttpClient = shared,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val PROVIDER = "plex"

        /** The app-wide client: one pool, one cache, one User-Agent. See [Http]. */
        private val shared: OkHttpClient get() = Http.base

        private const val COVER_PX = 1000

        // Plex's own numeric type codes for a music library's three kinds of thing.
        private const val TYPE_ARTIST = "8"
        private const val TYPE_ALBUM = "9"
        private const val TYPE_TRACK = "10"
    }

    val serverUrl: String get() = base()

    /** "raw" for the stored file; otherwise a `codec-bitrateKbps` token, as elsewhere. */
    @Volatile
    var streamFormat: String = "raw"

    /** Track id → the `Part.key` path this server actually stores the bytes at. */
    private val partKeys = ConcurrentHashMap<String, String>()

    /** Item id → its own `thumb` path (or its album's / artist's, as a fallback). */
    private val thumbs = ConcurrentHashMap<String, String>()

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

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base()).append(path)
        if (params.isNotEmpty()) {
            sb.append('?')
            sb.append(params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })
        }
        return sb.toString()
    }

    /**
     * A playable URL for [id]. The stored file byte-for-byte when its `Part.key` is
     * known, otherwise the universal transcoder asked to direct-play — see the class
     * doc for why a track has to be fetched before it can be streamed.
     */
    fun streamUrl(id: String, format: String = streamFormat): String {
        val container = format.substringBefore('-').ifBlank { "raw" }
        if (container == "raw") {
            partKeys[id]?.let { return url(it, mapOf("X-Plex-Token" to token)) }
            return transcodeUrl(id, container = "mp3", bitrateKbps = null, directPlay = true)
        }
        val bitrate = format.substringAfter('-', "").toIntOrNull()
        return transcodeUrl(id, container = if (container == "opus") "opus" else "mp3", bitrateKbps = bitrate, directPlay = false)
    }

    /**
     * Plex's Universal Transcoder, asked either to hand back the original ([directPlay])
     * or to transcode to [container] at [bitrateKbps]. Needs only the id — unlike the
     * direct file path, it resolves the track itself server-side.
     */
    private fun transcodeUrl(id: String, container: String, bitrateKbps: Int?, directPlay: Boolean): String =
        url(
            "/music/:/transcode/universal/start.$container",
            buildMap {
                put("path", "/library/metadata/$id")
                put("protocol", "http")
                put("directPlay", if (directPlay) "1" else "0")
                put("directStream", if (directPlay) "1" else "0")
                put("fastSeek", "1")
                put("mediaIndex", "0")
                put("partIndex", "0")
                bitrateKbps?.let { put("audioBitrate", it.toString()) }
                put("X-Plex-Client-Identifier", clientIdentifier)
                put("X-Plex-Token", token)
            },
        )

    /** The original file, same path [streamUrl] uses for "raw" when the track is known. */
    fun downloadUrl(id: String): String =
        partKeys[id]?.let { url(it, mapOf("X-Plex-Token" to token)) }
            ?: transcodeUrl(id, container = "mp3", bitrateKbps = null, directPlay = true)

    /**
     * Cover art, resized through `/photo/:/transcode`. Null when [id]'s thumb path
     * hasn't been seen yet — the same "nothing to read" case [streamUrl] falls back
     * from, except a guessed cover is not worth showing where a guessed stream is at
     * least playable.
     */
    fun coverUrl(id: String?, size: Int = COVER_PX): String? {
        val path = id?.takeIf { it.isNotBlank() }?.let { thumbs[it] } ?: return null
        return url(
            "/photo/:/transcode",
            mapOf(
                "width" to size.toString(),
                "height" to size.toString(),
                "minSize" to "1",
                "upscale" to "1",
                "url" to path,
                "X-Plex-Token" to token,
            ),
        )
    }

    // ── Transport ─────────────────────────────────────────────────────────

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) { request(Request.Builder().url(url(path, params)).get()) }

    /** Every Plex response wraps its payload in one `MediaContainer` object. */
    private suspend fun container(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        get(path, params)["MediaContainer"] as? JsonObject ?: JsonObject(emptyMap())

    private fun request(builder: Request.Builder): JsonObject {
        val req = builder
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", clientIdentifier)
            .header("X-Plex-Product", "CAMusic")
            .header("X-Plex-Device", "Android")
            .header("X-Plex-Platform", "Android")
            .apply { if (token.isNotBlank()) header("X-Plex-Token", token) }
            .build()
        val body = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw PlexException(explain(resp.code), httpCode = resp.code)
                resp.body?.string().orEmpty()
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: Exception) {
            throw PlexException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
        }
        if (body.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(body) as? JsonObject
                ?: throw PlexException("That address didn't answer like a Plex server")
        } catch (e: PlexException) {
            throw e
        } catch (_: Exception) {
            throw PlexException("That address didn't answer like a Plex server")
        }
    }

    private fun explain(code: Int): String = when (code) {
        401 -> "Plex rejected that sign-in"
        403 -> "That account isn't allowed to browse this library"
        404 -> "Not found on the server, it may have been removed"
        else -> "Plex returned HTTP $code"
    }

    /**
     * [Metadata] and [Directory] combined — see the class doc for why both are read
     * rather than one assumed.
     */
    private fun entries(mediaContainer: JsonObject): List<JsonObject> =
        (mediaContainer["Metadata"]?.jsonArray.orEmpty() + mediaContainer["Directory"]?.jsonArray.orEmpty())
            .mapNotNull { it as? JsonObject }

    // ── Reachability & setup ─────────────────────────────────────────────

    /**
     * As [pingError], but keeping the exception so the caller can tell a refused
     * token from an unreachable host — see
     * [com.engabd.sendpin.library.SourceError].
     */
    suspend fun pingResult(): PlexException? = try {
        if (token.isBlank()) PlexException("Not signed in to Plex", httpCode = 401)
        else { container("/library/sections"); null }
    } catch (e: PlexException) {
        e
    }

    /**
     * This server's music libraries — a Plex server just as often has films and
     * shows alongside, so this is what setup picks the first of, the same way
     * Jellyfin picks a music view.
     */
    suspend fun musicSections(): List<MaItem> =
        container("/library/sections")["Directory"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "artist" }
            .map {
                val key = it.str("key") ?: ""
                MaItem(
                    itemId = key, provider = PROVIDER, name = it.str("title") ?: "Music",
                    uri = key, mediaType = "library", subtitle = null, image = null, duration = null,
                )
            }

    // ── Browse ────────────────────────────────────────────────────────────

    suspend fun artists(limit: Int = 500): List<MaItem> =
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf("type" to TYPE_ARTIST, "sort" to "titleSort", "X-Plex-Container-Size" to limit.toString()),
            ),
        ).mapNotNull(::item)

    suspend fun albums(offset: Int = 0, limit: Int = 200): List<MaItem> =
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf(
                    "type" to TYPE_ALBUM, "sort" to "titleSort",
                    "X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to limit.toString(),
                ),
            ),
        ).mapNotNull(::item)

    suspend fun recentlyAdded(limit: Int = 200): List<MaItem> =
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf("type" to TYPE_ALBUM, "sort" to "addedAt:desc", "X-Plex-Container-Size" to limit.toString()),
            ),
        ).mapNotNull(::item)

    suspend fun recentlyPlayed(limit: Int = 200): List<MaItem> = runCatching {
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf("type" to TYPE_ALBUM, "sort" to "lastViewedAt:desc", "X-Plex-Container-Size" to limit.toString()),
            ),
        ).mapNotNull(::item)
    }.getOrDefault(emptyList())

    suspend fun mostPlayed(limit: Int = 200): List<MaItem> = runCatching {
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf("type" to TYPE_ALBUM, "sort" to "viewCount:desc", "X-Plex-Container-Size" to limit.toString()),
            ),
        ).mapNotNull(::item)
    }.getOrDefault(emptyList())

    /**
     * Plex has no server-side shuffle, unlike Jellyfin's `SortBy=Random` — there is
     * no `random` sort field to ask for. So this fetches a page sorted normally and
     * shuffles it here; wide enough that repeatedly asking for "something else"
     * doesn't just show the same dozen albums back in a different order.
     */
    suspend fun randomAlbums(limit: Int = 12): List<MaItem> = albums(limit = 200).shuffled().take(limit)

    suspend fun randomSongs(size: Int = 100): List<MaItem> =
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf("type" to TYPE_TRACK, "X-Plex-Container-Size" to (size * 3).coerceAtMost(1000).toString()),
            ),
        ).mapNotNull(::item).shuffled().take(size)

    suspend fun artistAlbums(artistId: String): List<MaItem> =
        entries(container("/library/metadata/$artistId/children")).mapNotNull(::item)

    suspend fun albumTracks(albumId: String): List<MaItem> =
        entries(container("/library/metadata/$albumId/children")).mapNotNull(::item)

    suspend fun item(id: String): MaItem? =
        entries(container("/library/metadata/$id")).firstOrNull()?.let(::item)

    suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> = item(id) to albumTracks(id)

    suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> = item(id) to artistAlbums(id)

    suspend fun playlists(): List<MaItem> =
        entries(container("/playlists", mapOf("playlistType" to "audio"))).mapNotNull(::item)

    suspend fun playlistTracks(id: String): List<MaItem> =
        entries(container("/playlists/$id/items")).mapNotNull(::item)

    suspend fun genres(): List<MaItem> = runCatching {
        container("/library/sections/$librarySectionKey/genre", mapOf("type" to TYPE_ARTIST))["Directory"]
            ?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val name = o.str("title") ?: return@mapNotNull null
                MaItem(
                    itemId = name, provider = PROVIDER, name = name, uri = o.str("key"),
                    mediaType = "genre", subtitle = null, image = null, duration = null,
                )
            }
    }.getOrDefault(emptyList())

    suspend fun songsByGenre(genre: String, count: Int = 300, offset: Int = 0): List<MaItem> = runCatching {
        entries(
            container(
                "/library/sections/$librarySectionKey/all",
                mapOf(
                    "type" to TYPE_TRACK, "genre" to genre,
                    "X-Plex-Container-Start" to offset.toString(), "X-Plex-Container-Size" to count.toString(),
                ),
            ),
        ).mapNotNull(::item)
    }.getOrDefault(emptyList())

    suspend fun search(query: String, limit: Int = 30): MaSearchResults = runCatching {
        val hubs = container("/hubs/search", mapOf("query" to query, "limit" to (limit * 4).toString()))["Hub"]
            ?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        fun hub(type: String) = hubs.firstOrNull { it.str("type") == type }
            ?.let(::entries).orEmpty().mapNotNull(::item)
        MaSearchResults(
            artists = hub("artist").take(limit),
            albums = hub("album").take(limit),
            tracks = hub("track").take(limit),
            playlists = emptyList(),
        )
    }.getOrDefault(MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList()))

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

    // ── Playback reporting ────────────────────────────────────────────────

    /**
     * Plex's timeline, the same call its own apps send every few seconds during
     * playback. Best-effort: a malformed report should not interrupt playback, so
     * failures are swallowed rather than surfaced.
     */
    suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) {
        runCatching {
            get(
                "/:/timeline",
                mapOf(
                    "ratingKey" to id,
                    "key" to "/library/metadata/$id",
                    "state" to if (paused) "paused" else "playing",
                    "time" to positionMs.toString(),
                    "identifier" to "com.plexapp.plugins.library",
                ),
            )
        }
    }

    /**
     * A finished play increments Plex's own play count via `/:/scrobble`; a start
     * is just a timeline ping, the same one [reportProgress] sends mid-playback.
     */
    suspend fun reportPlayback(id: String, completed: Boolean, positionMs: Long = 0) {
        if (completed) {
            runCatching { get("/:/scrobble", mapOf("key" to id, "identifier" to "com.plexapp.plugins.library")) }
        } else {
            reportProgress(id, positionMs, paused = false)
        }
    }

    // ── Item mapping ──────────────────────────────────────────────────────

    /**
     * One `Metadata`/`Directory` object as an [MaItem]. Also where [partKeys] and
     * [thumbs] are filled in — see the class doc for why streaming and covers read
     * those caches instead of building a URL from the id alone.
     */
    internal fun item(o: JsonObject): MaItem? {
        val id = o.str("ratingKey") ?: return null
        val mediaType = when (o.str("type")) {
            "artist" -> "artist"
            "album" -> "album"
            "track" -> "track"
            "playlist" -> "playlist"
            else -> return null
        }

        val thumbPath = o.str("thumb") ?: o.str("parentThumb") ?: o.str("grandparentThumb")
        thumbPath?.let { thumbs[id] = it }

        val media = o["Media"]?.jsonArray.orEmpty().firstOrNull() as? JsonObject
        val part = media?.get("Part")?.jsonArray.orEmpty().firstOrNull() as? JsonObject
        part?.str("key")?.let { partKeys[id] = it }

        val seconds = (o.long("duration") ?: part?.long("duration"))?.let { (it / 1000).toInt() }?.takeIf { it > 0 }

        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = o.str("title") ?: "Unknown",
            uri = id,
            mediaType = mediaType,
            subtitle = when (mediaType) {
                "artist" -> o.int("childCount")?.let { if (it == 1) "1 album" else "$it albums" }
                "playlist" -> o.int("leafCount")?.let { if (it == 1) "1 song" else "$it songs" }
                "album" -> o.str("parentTitle")
                else -> o.str("grandparentTitle")
            },
            image = coverUrl(id),
            duration = seconds,
            audioFormat = if (mediaType == "track") audioFormat(media, part) else null,
            trackNumber = o.int("index"),
            discNumber = o.int("parentIndex")?.takeIf { it >= 0 },
            // A track's parent is its album, an album's parent is its artist — both
            // are `parentRatingKey` in Plex's hierarchy.
            parentId = when (mediaType) {
                "album", "track" -> o.str("parentRatingKey")
                else -> null
            },
            album = o.str("parentTitle").takeIf { mediaType == "track" },
            year = o.int("year"),
            genres = o["Genre"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.str("tag") },
        )
    }

    /**
     * Codec, bitrate and channel count off the track's first `Media` entry. Sample
     * rate and bit depth are left at 0 — "the server didn't say" — rather than
     * guessed, since Plex's basic `Media` element doesn't carry either reliably.
     * Unlike Jellyfin's bits-per-second, Plex already reports `bitrate` in kbps.
     */
    private fun audioFormat(media: JsonObject?, part: JsonObject?): MaAudioFormat? {
        val codec = media?.str("audioCodec") ?: part?.str("container") ?: return null
        return MaAudioFormat(
            codec = codec,
            sampleRate = 0,
            bitDepth = 0,
            bitRate = media?.int("bitrate") ?: 0,
            channels = media?.int("audioChannels") ?: 0,
            sizeBytes = part?.long("size") ?: 0L,
        )
    }

    // ── JSON helpers, matching SubsonicClient's and JellyfinClient's ─────────

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
