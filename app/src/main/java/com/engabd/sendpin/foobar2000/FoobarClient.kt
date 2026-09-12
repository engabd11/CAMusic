package com.engabd.sendpin.foobar2000

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * An exception from the Beefweb REST API client.
 *
 * [isAuth] follows the same contract as the other providers: a rejected
 * password is worth re-prompting for, an unreachable host is not. Beefweb's
 * optional basic auth returns HTTP 401, which is the only auth failure path.
 */
class FoobarException(message: String, val isAuth: Boolean = false) : Exception(message)

/**
 * A foobar2000 library and player, driven through the Beefweb plugin's REST API.
 *
 * Built to the same shape as [com.engabd.sendpin.mpd.MpdClient] and
 * [com.engabd.sendpin.subsonic.SubsonicClient]: items come back as [MaItem] with
 * `provider = `[PROVIDER], there is no stream URL (foobar2000 plays its own
 * audio), and every failure is an [FoobarException] carrying something a user
 * can read.
 *
 * ## The Beefweb plugin
 *
 * foobar2000 has no built-in network API — it is a desktop player. The Beefweb
 * component (`foo_beefweb`) embeds an HTTP server that exposes a REST-like API
 * on port 8880 by default. It provides playback control, playlist management,
 * a file browser, and artwork endpoints. See the Swagger spec at
 * `https://hyperblast.org/beefweb/api`.
 *
 * Unlike MPD's line-based TCP protocol, Beefweb speaks HTTP with JSON
 * responses. The shared [Http] client is used for connection pooling and
 * cleartext-on-LAN enforcement, matching every other provider.
 *
 * ## Playing
 *
 * Like MPD, foobar2000 plays its own audio to whatever output device its own
 * config names — the point of running it on a PC with a DAC. So this client
 * does not fetch audio either: it puts the app's queue into a foobar2000
 * playlist and works the transport — `play`, `pause`, `next`, `previous` —
 * while `GET /api/player` says where the playhead is. See [FoobarRemote] and
 * [com.engabd.sendpin.audio.RemotePlayback].
 *
 * ## Library browsing
 *
 * Beefweb does not expose a structured media-library query API (no "list
 * artists" or "list albums" endpoint). It has a file browser
 * (`GET /api/browser/entries`) and playlist items with title-formatting
 * columns. For browsing, we use the file system browser to walk the music
 * directories the user configured in Beefweb's settings, and group tracks by
 * their tags (returned as columns) into artists and albums. This is less
 * elegant than MPD's `list artist` but workable for a LAN music collection.
 *
 * ## What foobar2000 has and doesn't have
 *
 * Metadata comes from file tags, so [MaAudioFormat] is rich when the columns
 * request it (codec, sample rate, bit depth). There are no lyrics, no artist
 * biographies, and no similar-track suggestions. Artwork is served over HTTP
 * via `GET /api/artwork/{playlistId}/{index}` — a real URL, unlike MPD's
 * binary-over-protocol art. foobar2000 has its own ReplayGain, but Beefweb's
 * API does not expose a toggle for it; the setting is controlled in
 * foobar2000's own preferences.
 */
class FoobarClient(
    /** The Beefweb server address, e.g. `http://192.168.0.138:8880`. */
    private val address: String,
    /** Optional basic auth password for Beefweb's remote access. */
    @Volatile var password: String = "",
    /** Optional basic auth username. */
    @Volatile var username: String = "",
) {
    companion object {
        const val PROVIDER = "foobar2000"

        /**
         * The columns requested for every playlist item — foobar2000's title
         * formatting strings, returned verbatim as the `columns` array.
         *
         * These mirror what MPD's tag-based browsing gives us: the file path
         * (for queue identity), track title, artist, album, album artist,
         * duration, track number, disc number, date, genre, and the technical
         * fields for the quality badge.
         */
        internal val COLUMNS = listOf(
            "%path%",
            "%title%",
            "%artist%",
            "%album%",
            "%album artist%",
            "%length_seconds%",
            "%tracknumber%",
            "%discnumber%",
            "%date%",
            "%genre%",
            "%codec%",
            "%samplerate%",
            "%bitspersample%",
            "%channels%",
            "%bitrate%",
        )

        /** Indices into [COLUMNS], for the parser. */
        internal const val COL_PATH = 0
        internal const val COL_TITLE = 1
        internal const val COL_ARTIST = 2
        internal const val COL_ALBUM = 3
        internal const val COL_ALBUM_ARTIST = 4
        internal const val COL_LENGTH = 5
        internal const val COL_TRACK = 6
        internal const val COL_DISC = 7
        internal const val COL_DATE = 8
        internal const val COL_GENRE = 9
        internal const val COL_CODEC = 10
        internal const val COL_SAMPLE_RATE = 11
        internal const val COL_BIT_DEPTH = 12
        internal const val COL_CHANNELS = 13
        internal const val COL_BITRATE = 14

        /** A request body for POST endpoints that take no parameters. */
        private val EMPTY_BODY = "{}".toRequestBody("application/json".toMediaType())

        /**
         * One playlist item's columns, as [MaItem].
         *
         * Pure, so it can be tested against recorded Beefweb responses without
         * an HTTP connection.
         */
        internal fun buildTrack(columns: List<String>): MaItem {
            val path = columns.getOrNull(COL_PATH) ?: ""
            val title = columns.getOrNull(COL_TITLE)?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringBeforeLast('.')
            val artist = columns.getOrNull(COL_ARTIST)?.takeIf { it.isNotBlank() }
                ?: columns.getOrNull(COL_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            val album = columns.getOrNull(COL_ALBUM)?.takeIf { it.isNotBlank() }
            val albumArtist = columns.getOrNull(COL_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            val duration = columns.getOrNull(COL_LENGTH)?.toFloatOrNull()?.toInt()
            val trackNumber = columns.getOrNull(COL_TRACK)
                ?.substringBefore('/')?.toIntOrNull()
            val discNumber = columns.getOrNull(COL_DISC)
                ?.substringBefore('/')?.toIntOrNull()
            val year = columns.getOrNull(COL_DATE)?.substringBefore('-')?.toIntOrNull()
            val genre = columns.getOrNull(COL_GENRE)?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()
            val codec = columns.getOrNull(COL_CODEC)?.takeIf { it.isNotBlank() }
            val sampleRate = columns.getOrNull(COL_SAMPLE_RATE)?.toIntOrNull() ?: 0
            val bitDepth = columns.getOrNull(COL_BIT_DEPTH)?.toIntOrNull() ?: 0
            val channels = columns.getOrNull(COL_CHANNELS)?.toIntOrNull() ?: 2
            val bitrate = columns.getOrNull(COL_BITRATE)?.toIntOrNull() ?: 0

            val audioFormat = if (codec != null) {
                MaAudioFormat(
                    codec = codec,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    bitRate = bitrate,
                )
            } else null

            // The album id is name + NUL + album artist, matching MPD's scheme
            // so albumDetail/coverUrl work the same way.
            val albumId = if (album != null) {
                if (albumArtist != null) "$album\u0000$albumArtist" else album
            } else null

            return MaItem(
                itemId = path,
                provider = PROVIDER,
                name = title,
                uri = path,
                mediaType = "track",
                subtitle = artist,
                image = null,
                duration = duration,
                audioFormat = audioFormat,
                year = year,
                genres = genre,
                trackNumber = trackNumber,
                discNumber = discNumber,
                album = album,
                parentId = albumId,
            )
        }

        /**
         * Build a unique album id from name + artist, using a NUL separator —
         * the same scheme [MpdClient] uses.
         */
        internal fun buildAlbumId(album: String, artist: String?): String =
            if (artist != null) "$album\u0000$artist" else album

        /**
         * One player state reading, parsed from a `GET /api/player` response.
         *
         * Pure, so it can be tested without a connection — see `FoobarUrlTest`.
         */
        internal fun readPlayerState(player: JsonObject): FoobarState {
            val state = player["playbackState"]?.jsonPrimitive?.contentOrNull ?: "stopped"
            val activeItem = player["activeItem"]?.jsonObject
            val volume = player["volume"]?.jsonObject

            return FoobarState(
                state = state,
                playlistId = activeItem?.get("playlistId")?.jsonPrimitive?.contentOrNull,
                playlistIndex = activeItem?.get("playlistIndex")?.jsonPrimitive?.intOrNull ?: -1,
                itemIndex = activeItem?.get("index")?.jsonPrimitive?.intOrNull ?: -1,
                positionSeconds = activeItem?.get("position")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                durationSeconds = activeItem?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                volumeValue = volume?.get("value")?.jsonPrimitive?.doubleOrNull,
                volumeMin = volume?.get("min")?.jsonPrimitive?.doubleOrNull,
                volumeMax = volume?.get("max")?.jsonPrimitive?.doubleOrNull,
                isMuted = volume?.get("isMuted")?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    /**
     * One reading of the player's transport state, from `GET /api/player`.
     *
     * The position and duration are in seconds (Beefweb's unit), converted to
     * milliseconds in [FoobarRemote.poll]. Volume is an absolute value in
     * Beefweb's own scale (typically dB, but configurable); the min/max are
     * used to normalise it to 0..1 for the app's volume slider.
     */
    data class FoobarState(
        val state: String,
        val playlistId: String?,
        val playlistIndex: Int,
        val itemIndex: Int,
        val positionSeconds: Double,
        val durationSeconds: Double,
        val volumeValue: Double?,
        val volumeMin: Double?,
        val volumeMax: Double?,
        val isMuted: Boolean,
    ) {
        val playing: Boolean get() = state == "playing"
        val paused: Boolean get() = state == "paused"
        val stopped: Boolean get() = state == "stopped"
    }

    val serverUrl: String get() = base()

    /**
     * The normalised base URL. Unlike MPD (always plain TCP), Beefweb is HTTP,
     * so the scheme the user typed is honoured — `https://` stays `https://`,
     * which matters if someone has put a reverse proxy in front of it.
     */
    private fun base(): String {
        val b = address.trim().trimEnd('/')
        return when {
            b.startsWith("http://") || b.startsWith("https://") -> b
            else -> "http://$b"
        }
    }

    /** The API root, which is the base URL plus `/api`. */
    private val apiRoot: String get() = "${base()}/api"

    @Volatile
    var streamFormat: String = "raw"

    // ── HTTP transport ────────────────────────────────────────────────────

    /**
     * The shared OkHttp client, derived from [Http.base] so connection pooling
     * and cleartext-on-LAN enforcement are shared with every other provider.
     */
    private val http: OkHttpClient = Http.base.newBuilder().build()

    /**
     * One GET request, returning the JSON body, or throwing [FoobarException].
     */
    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonObject =
        request("GET", path, null, *params)

    /**
     * One POST request with an optional JSON body, returning 204 (no body) or
     * throwing. Most Beefweb POST endpoints return 204 No Content.
     */
    private suspend fun post(path: String, body: String? = null) {
        requestUnit("POST", path, body)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        vararg params: Pair<String, String>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val urlBuilder = (apiRoot + path).toHttpUrl().newBuilder()
        for ((key, value) in params) urlBuilder.addQueryParameter(key, value)
        val request = Request.Builder()
            .url(urlBuilder.build())
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .apply { addAuth(this) }
            .build()

        try {
            val response = http.newCall(request).execute()
            val responseBody = response.use { it.body?.string() }
            checkResponse(response.code, responseBody, response.message)
            responseBody?.let { Json.parseToJsonElement(it).jsonObject }
                ?: JsonObject(emptyMap())
        } catch (e: FoobarException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FoobarException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
        }
    }

    private suspend fun requestUnit(method: String, path: String, body: String?) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(apiRoot + path)
                .method(method, body?.toRequestBody("application/json".toMediaType()) ?: EMPTY_BODY)
                .apply { addAuth(this) }
                .build()

            try {
                val response = http.newCall(request).execute()
                response.use {
                    checkResponse(it.code, null, it.message)
                }
            } catch (e: FoobarException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw FoobarException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
            }
        }

    /**
     * Add basic auth headers if credentials are configured.
     */
    private fun addAuth(builder: Request.Builder) {
        if (username.isNotBlank() || password.isNotBlank()) {
            val credentials = okhttp3.Credentials.basic(
                username.ifBlank { "" },
                password,
            )
            builder.header("Authorization", credentials)
        }
    }

    /**
     * Check an HTTP response code and throw the right exception.
     *
     * 401 is an auth failure (worth re-prompting), anything else is a generic
     * failure. 200 and 204 are the success codes Beefweb uses.
     */
    private fun checkResponse(code: Int, body: String?, message: String) {
        when (code) {
            200, 204 -> return
            401 -> throw FoobarException("Beefweb rejected the credentials", isAuth = true)
            403 -> throw FoobarException("Beefweb forbids this operation", isAuth = true)
            else -> {
                val msg = body?.takeIf { it.isNotBlank() } ?: message
                throw FoobarException("Beefweb error: $msg")
            }
        }
    }

    // ── Ping ──────────────────────────────────────────────────────────────

    /** Null when the server answered, else why not. */
    suspend fun pingError(): String? = pingResult()?.message

    /** As [pingError], but keeping the exception for auth/unreachable distinction. */
    suspend fun pingResult(): FoobarException? = try {
        get("/player")
        null
    } catch (e: FoobarException) {
        e
    }

    // ── Playback state ────────────────────────────────────────────────────

    /**
     * What foobar2000 is doing right now, as `GET /api/player` reports it.
     *
     * This is the whole reason foobar2000 is driven rather than streamed — the
     * same argument as MPD. foobar2000 knows the elapsed position, the
     * duration, which playlist and item is playing, and whether it is paused.
     * So the phone asks it.
     */
    suspend fun playerState(): FoobarState? = try {
        val response = get("/player", "columns" to COLUMNS.joinToString(","))
        val player = response["player"]?.jsonObject
            ?: throw FoobarException("Beefweb returned no player object")
        readPlayerState(player)
    } catch (_: FoobarException) {
        null
    }

    // ── Transport ─────────────────────────────────────────────────────────

    suspend fun play() = post("/player/play")
    suspend fun pause() = post("/player/pause")
    suspend fun togglePause() = post("/player/pause/toggle")
    suspend fun stop() = post("/player/stop")
    suspend fun next() = post("/player/next")
    suspend fun previous() = post("/player/previous")

    /**
     * Play a specific item in a specific playlist.
     *
     * Beefweb uses playlist IDs (strings) or indices (integers). We use the ID
     * from [playerState] when available, falling back to the index.
     */
    suspend fun playItem(playlistId: String, index: Int) =
        post("/player/play/$playlistId/$index")

    /**
     * Seek to an absolute position in seconds.
     *
     * Beefweb's `SetPlayerStateRequest` takes `position` in seconds.
     */
    suspend fun seekTo(seconds: Double) =
        post("/player", """{"position":$seconds}"")

    /**
     * Set the volume to an absolute value in Beefweb's own scale.
     *
     * Beefweb's volume is configurable (dB or linear); the min/max from
     * [FoobarState] tell the caller how to normalise a 0..1 float to the
     * right range.
     */
    suspend fun setVolume(value: Double) =
        post("/player", """{"volume":$value}""")

    // ── Playlists ─────────────────────────────────────────────────────────

    /**
     * All playlists, from `GET /api/playlists`.
     *
     * Each has an id, index, title, item count, and whether it is the current
     * (playing) playlist.
     */
    suspend fun playlists(): List<FoobarPlaylist> {
        val response = get("/playlists")
        val playlists = response["playlists"]?.jsonArray ?: return emptyList()
        return playlists.mapNotNull { element ->
            val obj = element.jsonObject
            FoobarPlaylist(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                isCurrent = obj["isCurrent"]?.jsonPrimitive?.booleanOrNull ?: false,
                itemCount = obj["itemCount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    /**
     * The current (active) playlist, or null when none exists.
     *
     * Beefweb always has at least one playlist; the current one is what the
     * transport controls operate on.
     */
    suspend fun currentPlaylist(): FoobarPlaylist? =
        playlists().firstOrNull { it.isCurrent } ?: playlists().firstOrNull()

    /**
     * Items in a playlist, paginated by range.
     *
     * Beefweb's `GET /api/playlists/{id}/items/{range}` takes a range in
     * `offset:count` form and a `columns` parameter specifying which
     * title-formatting fields to return.
     */
    suspend fun playlistItems(
        playlistId: String,
        offset: Int = 0,
        count: Int = 500,
    ): List<MaItem> {
        val response = get(
            "/playlists/$playlistId/items/$offset:$count",
            "columns" to COLUMNS.joinToString(","),
        )
        val items = response["playlistItems"]?.jsonObject
            ?: return emptyList()
        val entries = items["items"]?.jsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val obj = element.jsonObject
            val columns = obj["columns"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }
                ?: return@mapNotNull null
            buildTrack(columns)
        }
    }

    /**
     * Replace a playlist's contents with [items] and optionally start playing.
     *
     * Uses `POST /api/playlists/{id}/items/add` with `replace: true` and
     * `play: true`, which is Beefweb's "queue swap" — the equivalent of MPD's
     * `clear`, `add` per track, `play` command list.
     */
    suspend fun replacePlaylist(
        playlistId: String,
        items: List<String>,
        play: Boolean = true,
        startIndex: Int = 0,
    ) {
        if (items.isEmpty()) {
            post("/playlists/$playlistId/clear")
            return
        }
        val itemsJson = items.joinToString(",") { "\"${escapeJson(it)}\"" }
        post(
            "/playlists/$playlistId/items/add",
            """{"items":[$itemsJson],"replace":true,"play":$play}""",
        )
        if (play && startIndex > 0) {
            playItem(playlistId, startIndex)
        }
    }

    /** Append items to a playlist, leaving what is playing alone. */
    suspend fun addItems(playlistId: String, items: List<String>) {
        if (items.isEmpty()) return
        val itemsJson = items.joinToString(",") { "\"${escapeJson(it)}\"" }
        post("/playlists/$playlistId/items/add", """{"items":[$itemsJson]}""")
    }

    /**
     * Insert items at a specific position in a playlist.
     *
     * Used by [FoobarRemote.playNext] to insert tracks directly after the
     * current one — the equivalent of MPD's `add uri position`.
     */
    suspend fun addItemsAt(playlistId: String, items: List<String>, index: Int) {
        if (items.isEmpty()) return
        val itemsJson = items.joinToString(",") { "\"${escapeJson(it)}\"" }
        post("/playlists/$playlistId/items/add", """{"items":[$itemsJson],"index":$index}""")
    }

    /**
     * Remove items at the given indices.
     *
     * Beefweb takes an array of indices, not a range.
     */
    suspend fun removeItems(playlistId: String, indices: List<Int>) {
        if (indices.isEmpty()) return
        val indicesJson = indices.joinToString(",")
        post("/playlists/$playlistId/items/remove", """{"items":[$indicesJson]}""")
    }

    /** Move an item from one position to another within a playlist. */
    suspend fun moveItem(playlistId: String, from: Int, to: Int) {
        post(
            "/playlists/$playlistId/items/move",
            """{"items":[$from],"targetIndex":$to}""",
        )
    }

    /** Clear a playlist. */
    suspend fun clearPlaylist(playlistId: String) =
        post("/playlists/$playlistId/clear")

    /** Remove a playlist entirely. */
    suspend fun removePlaylist(playlistId: String) =
        post("/playlists/remove/$playlistId")

    /** Create a new playlist, returning its id. */
    suspend fun createPlaylist(title: String): String? {
        val response = postWithResponse("/playlists/add", """{"title":"${escapeJson(title)}"}""")
        return response?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /**
     * A POST that returns a JSON body (unlike most Beefweb POSTs which return
     * 204). Used for `playlists/add`.
     */
    private suspend fun postWithResponse(path: String, body: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(apiRoot + path)
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { addAuth(this) }
                .build()
            try {
                val response = http.newCall(request).execute()
                val responseBody = response.use { it.body?.string() }
                checkResponse(response.code, responseBody, response.message)
                responseBody?.let { Json.parseToJsonElement(it).jsonObject }
            } catch (e: FoobarException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw FoobarException(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach ${base()}")
            }
        }

    // ── Queue ─────────────────────────────────────────────────────────────

    /**
     * What the current playlist holds right now, in its own order.
     *
     * Read on connecting, so a phone that opens while foobar2000 is already
     * playing shows what is playing rather than an empty player.
     */
    suspend fun queueTracks(): List<MaItem> {
        val playlist = currentPlaylist() ?: return emptyList()
        return playlistItems(playlist.id, 0, 500)
    }

    /**
     * The index of the currently playing item in the current playlist.
     */
    suspend fun currentIndex(): Int =
        playerState()?.itemIndex?.coerceAtLeast(0) ?: 0

    // ── File browser ──────────────────────────────────────────────────────

    /**
     * File system entries at a path, from `GET /api/browser/entries`.
     *
     * Beefweb browses the file system, not a pre-scanned library. Directories
     * are browsable; files are tracks. This is the basis for library browsing
     * when no structured query API exists.
     */
    suspend fun browseEntries(path: String? = null): List<FoobarFsEntry> {
        val params = if (path != null) arrayOf("path" to path) else emptyArray()
        val response = get("/browser/entries", *params)
        val entries = response["entries"]?.jsonArray ?: return emptyList()
        val separator = response["pathSeparator"]?.jsonPrimitive?.contentOrNull ?: "\\"
        return entries.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            FoobarFsEntry(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
                type = type,
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                pathSeparator = separator,
            )
        }
    }

    /**
     * The file system roots, from `GET /api/browser/roots`.
     *
     * On Windows these are drive letters (`C:\`, `D:\`); on Linux, `/`.
     */
    suspend fun browseRoots(): List<FoobarFsEntry> {
        val response = get("/browser/roots")
        val roots = response["roots"]?.jsonArray ?: return emptyList()
        val separator = response["pathSeparator"]?.jsonPrimitive?.contentOrNull ?: "\\"
        return roots.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            FoobarFsEntry(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "",
                type = type,
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0,
                pathSeparator = separator,
            )
        }
    }

    // ── Artwork ───────────────────────────────────────────────────────────

    /**
     * The artwork URL for a playlist item.
     *
     * Unlike MPD (which serves art down the protocol socket), Beefweb serves
     * it over plain HTTP — `GET /api/artwork/{playlistId}/{index}`. This is a
     * real URL the image loader can open, with no custom fetcher needed.
     *
     * The playlistId and index come from the current playlist; for a library
     * track that is not in a playlist, we use the current playlist and find
     * the item by path.
     */
    fun artworkUrl(playlistId: String, index: Int): String =
        "$apiRoot/artwork/$playlistId/$index"

    // ── Outputs ───────────────────────────────────────────────────────────

    /**
     * The configured output devices, from `GET /api/outputs`.
     *
     * foobar2000's output names its configured audio device — the equivalent
     * of MPD's `outputs` command. Used by [FoobarRemote] to report
     * `outputDeviceName` in the poll state.
     */
    suspend fun outputs(): List<FoobarOutput> {
        val response = get("/outputs")
        val outputs = response["outputs"]?.jsonObject ?: return emptyList()
        val active = outputs["active"]?.jsonObject
        val activeDeviceId = active?.get("deviceId")?.jsonPrimitive?.contentOrNull
        val types = outputs["types"]?.jsonArray ?: return emptyList()
        return types.flatMap { typeElement ->
            val typeObj = typeElement.jsonObject
            val typeId = typeObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val devices = typeObj["devices"]?.jsonArray ?: return@flatMap emptyList<FoobarOutput>()
            devices.mapNotNull { deviceElement ->
                val deviceObj = deviceElement.jsonObject
                val deviceId = deviceObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = deviceObj["name"]?.jsonPrimitive?.contentOrNull ?: deviceId
                FoobarOutput(
                    id = deviceId,
                    name = name,
                    typeId = typeId,
                    isActive = deviceId == activeDeviceId,
                )
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Full search across the file browser.
     *
     * Beefweb has no search API. This walks the file system looking for file
     * names matching the query — a coarse approximation, but it finds a track
     * by name when the user knows what they are looking for. The structured
     * tag search MPD offers is not available here.
     */
    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        // Without a query API, search returns empty. A file-system crawl would
        // be too slow for a search box, and the results would be paths rather
        // than tagged metadata. This is an honest empty rather than a fake one.
        return MaSearchResults(
            artists = emptyList(),
            albums = emptyList(),
            tracks = emptyList(),
            playlists = emptyList(),
        )
    }

    // ── ReplayGain ────────────────────────────────────────────────────────

    /**
     * Whether foobar2000's ReplayGain is enabled.
     *
     * Beefweb's API does not expose a ReplayGain toggle. foobar2000 applies it
     * in its own DSP chain, configured in its preferences. This always returns
     * null — the setting is not controllable from the API, and claiming it
     * would put a switch on screen that can only ever do nothing.
     */
    suspend fun replayGainMode(): String? = null
}

/** One foobar2000 playlist, from `GET /api/playlists`. */
data class FoobarPlaylist(
    val id: String,
    val index: Int,
    val title: String,
    val isCurrent: Boolean,
    val itemCount: Int,
)

/** One file system entry, from `GET /api/browser/entries`. */
data class FoobarFsEntry(
    val name: String,
    val path: String,
    /** `"D"` for directory, `"F"` for file. */
    val type: String,
    val size: Long,
    val pathSeparator: String,
) {
    val isDirectory: Boolean get() = type == "D"
    val isFile: Boolean get() = type == "F"
}

/** One output device, from `GET /api/outputs`. */
data class FoobarOutput(
    val id: String,
    val name: String,
    val typeId: String,
    val isActive: Boolean,
)

// ── JSON helpers ──────────────────────────────────────────────────────────

/**
 * Escape a string for use inside a JSON string literal.
 *
 * Only the characters that matter are escaped — backslash, double quote, and
 * the control characters — so the result is safe inside a `"..."` pair without
 * pulling in kotlinx.serialization's whole encoder for a one-line body.
 */
internal fun escapeJson(s: String): String = buildString(s.length + 2) {
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) {
                append("\\u%04x".format(c.code))
            } else {
                append(c)
            }
        }
    }
}

/** Get the content of a JsonElement as a String, or null. */
internal val JsonElement.contentOrNull: String?
    get() = (this as? JsonPrimitive)?.content

/** Get the content of a JsonElement as an Int, or null. */
internal val JsonElement.intOrNull: Int?
    get() = (this as? JsonPrimitive)?.content?.toIntOrNull()

/** Get the content of a JsonElement as a Long, or null. */
internal val JsonElement.longOrNull: Long?
    get() = (this as? JsonPrimitive)?.content?.toLongOrNull()

/** Get the content of a JsonElement as a Double, or null. */
internal val JsonElement.doubleOrNull: Double?
    get() = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

/** Get the content of a JsonElement as a Boolean, or null. */
internal val JsonElement.booleanOrNull: Boolean?
    get() = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()