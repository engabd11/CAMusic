package com.engabd.sendpin.ma

import com.engabd.sendpin.audio.StreamQuality
import kotlinx.serialization.json.*
import java.net.URLEncoder

/** A Music Assistant media item (artist / album / track / playlist / radio). */
/**
 * The source file's own format, off `provider_mappings[].audio_format`. Used to
 * decide whether Music Assistant would have to convert a track to stream it — see
 * [com.engabd.sendpin.audio.FormatNegotiator].
 */
data class MaAudioFormat(
    val codec: String,
    val sampleRate: Int,
    val bitDepth: Int,
)

data class MaItem(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String?,
    val mediaType: String,
    val subtitle: String?,   // artist(s) / owner
    val image: String?,      // best-effort image URL
    val duration: Int?,
    val favorite: Boolean = false,
    val audioFormat: MaAudioFormat? = null,
    /**
     * The provider domains that can actually supply this item, off
     * `provider_mappings[].provider_domain`. A library item's own `provider` is
     * always "library" — it says where Music Assistant filed the track, not where
     * the bytes come from, which is what the source badge is asking about.
     */
    val providerDomains: List<String> = emptyList(),
    /** Album release year (from album metadata). */
    val year: Int? = null,
    /** Genres associated with this item. */
    val genres: List<String> = emptyList(),
    /** Track number within an album (for tracks). */
    val trackNumber: Int? = null,
    /** Disc number for multi-disc albums (for tracks). */
    val discNumber: Int? = null,
) {
    val browsable get() = mediaType in BROWSABLE
    val playable get() = uri != null && mediaType in PLAYABLE

    companion object {
        private val BROWSABLE = setOf("artist", "album", "playlist")
        private val PLAYABLE = setOf("track", "album", "playlist", "radio")
    }
}

/** What a player is currently playing (from a player's `current_media`). */
data class MaNowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long?,
    val elapsedMs: Long?,
)

/** A Music Assistant player (a possible playback target / group). */
data class MaPlayer(
    val playerId: String,
    val name: String,
    val available: Boolean,
    val powered: Boolean,
    val type: String = "player",             // player | group | ...
    val state: String = "idle",              // playback_state: playing | paused | idle
    val volumeLevel: Int = 0,                // 0..100
    val groupVolume: Int? = null,            // 0..100 when this is a group/sync leader
    val syncedTo: String? = null,            // the leader this player is synced to (member)
    val groupChilds: List<String> = emptyList(), // members when this player is the leader
    val canGroupWith: List<String> = emptyList(), // player_ids this can be grouped with
    val supportedFeatures: List<String> = emptyList(),
    val icon: String? = null,
    val nowPlaying: MaNowPlaying? = null,
) {
    val isPlaying get() = state == "playing"
    /** This player leads an (ad-hoc sync or static) group. */
    val isLeader get() = groupChilds.isNotEmpty()

    /** This player follows another player's group. */
    val isMember get() = syncedTo != null

    /** The server lets us add/remove members with this player as target. */
    val canSetMembers get() = "set_members" in supportedFeatures
}

/** A player's Sendspin sync-delay config value + the (variable) key it lives under. */
data class SyncDelay(val key: String, val ms: Int)

/** A player queue: what's streaming, and how the queue itself is set up. */
data class MaQueue(
    val queueId: String,
    val quality: StreamQuality?,
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",   // off | one | all
    val currentIndex: Int? = null,
    val itemCount: Int = 0,
    /** The library item behind `current_item` — the handle for favourite/lyrics/similar. */
    val currentItem: MaItem? = null,
    val currentQueueItemId: String? = null,
    val dontStopTheMusic: Boolean = false,
    val playbackSpeed: Float = 1f,
    /**
     * The provider actually streaming the current item, off
     * `current_item.streamdetails.provider`. This is the honest answer to "where is
     * this coming from" — the media item itself is filed under "library" no matter
     * which backend holds the file.
     */
    val streamProvider: String? = null,
)

/** Grouped search hits. */
data class MaSearchResults(
    val artists: List<MaItem>,
    val albums: List<MaItem>,
    val tracks: List<MaItem>,
    val playlists: List<MaItem>,
)

/** A single queue item with its stream details. */
data class MaQueueItem(
    val queueItemId: String,
    val name: String,
    val duration: Int?,
    val sortIndex: Int,
    val mediaItem: MaItem?,
    val streamDetails: StreamQuality?,
    /** Position in the queue — what `player_queues/play_index` takes. */
    val index: Int = 0,
    val artist: String? = null,
    val image: String? = null,
    val available: Boolean = true,
)

/** One timed line of an LRC lyric. */
data class LyricLine(val atMs: Long, val text: String)

/** Lyrics for a track, plain or LRC-timed. */
data class MaLyrics(
    val text: String,
    val synced: Boolean = false,
) {
    /**
     * The lyric as timed lines. Plain lyrics come back as one line per row with a
     * timestamp of 0, so callers can render either kind the same way and simply
     * not highlight when [synced] is false.
     */
    val lines: List<LyricLine> by lazy {
        if (!synced) return@lazy text.lines().map { LyricLine(0, it.trim()) }
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val stamps = LRC_STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                // The fraction is 2 or 3 digits (centi- or milliseconds).
                val fracMs = when (frac.length) {
                    0 -> 0L
                    2 -> frac.toLong() * 10
                    else -> frac.padEnd(3, '0').take(3).toLong()
                }
                out += LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1_000 + fracMs, body)
            }
        }
        out.sortedBy { it.atMs }
    }

    private companion object {
        val LRC_STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?]""")
    }
}

/** A track similar to the seed (from sonic_similarity or music/tracks/similar_tracks). */
data class MaSimilarTrack(
    val itemId: String,
    val name: String,
    val artist: String?,
    val image: String?,
    val uri: String?,
    val provider: String,
)

object MaParse {

    fun items(result: JsonElement?, serverUrl: String?): List<MaItem> = when (result) {
        is JsonArray -> result.mapNotNull { item(it, serverUrl) }
        is JsonObject -> (result["items"] as? JsonArray)?.mapNotNull { item(it, serverUrl) } ?: emptyList()
        else -> emptyList()
    }

    fun item(el: JsonElement?, serverUrl: String?): MaItem? {
        val o = el as? JsonObject ?: return null
        val itemId = o["item_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = itemId,
            provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "library",
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
            uri = o["uri"]?.jsonPrimitive?.contentOrNull,
            mediaType = o["media_type"]?.jsonPrimitive?.contentOrNull ?: "track",
            subtitle = artistString(o) ?: o["owner"]?.jsonPrimitive?.contentOrNull,
            image = imageUrl(o, serverUrl),
            duration = o["duration"]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() },
            favorite = o["favorite"]?.jsonPrimitive?.booleanOrNull ?: false,
            audioFormat = audioFormat(o),
            providerDomains = providerDomains(o),
            year = o["year"]?.jsonPrimitive?.intOrNull,
            genres = genreList(o),
            trackNumber = o["track_number"]?.jsonPrimitive?.intOrNull,
            discNumber = o["disc_number"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** Which backends can supply this item, in the order the server listed them. */
    private fun providerDomains(o: JsonObject): List<String> =
        (o["provider_mappings"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject) }
            ?.mapNotNull {
                it["provider_domain"]?.jsonPrimitive?.contentOrNull
                    ?: it["provider_instance"]?.jsonPrimitive?.contentOrNull
            }
            ?.distinct()
            ?: emptyList()

    /** The best (highest-rate) format any provider can supply this item in. */
    private fun audioFormat(o: JsonObject): MaAudioFormat? =
        (o["provider_mappings"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("audio_format") as? JsonObject }
            ?.mapNotNull { f ->
                val rate = f["sample_rate"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                MaAudioFormat(
                    codec = f["content_type"]?.jsonPrimitive?.contentOrNull
                        ?: f["codec_type"]?.jsonPrimitive?.contentOrNull ?: "?",
                    sampleRate = rate,
                    bitDepth = f["bit_depth"]?.jsonPrimitive?.intOrNull ?: 16,
                )
            }
            ?.maxByOrNull { it.sampleRate.toLong() * 100 + it.bitDepth }

    fun players(result: JsonElement?): List<MaPlayer> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["player_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaPlayer(
                playerId = id,
                name = o["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                available = o["available"]?.jsonPrimitive?.booleanOrNull ?: true,
                powered = o["powered"]?.jsonPrimitive?.booleanOrNull ?: true,
                type = o["type"]?.jsonPrimitive?.contentOrNull ?: "player",
                state = o["playback_state"]?.jsonPrimitive?.contentOrNull ?: "idle",
                volumeLevel = o["volume_level"]?.jsonPrimitive?.intOrNull ?: 0,
                groupVolume = o["group_volume"]?.jsonPrimitive?.intOrNull,
                syncedTo = o["synced_to"]?.jsonPrimitive?.contentOrNull,
                // MA renamed this to `group_members`; older servers still send
                // `group_childs`, so accept whichever one turns up.
                groupChilds = strList(o["group_members"] ?: o["group_childs"]),
                canGroupWith = strList(o["can_group_with"]),
                supportedFeatures = strList(o["supported_features"]),
                icon = o["icon"]?.jsonPrimitive?.contentOrNull,
                nowPlaying = nowPlaying(o["current_media"], o["elapsed_time"]),
            )
        }
    }

    private fun nowPlaying(el: JsonElement?, elapsed: JsonElement?): MaNowPlaying? {
        val m = el as? JsonObject ?: return null
        val title = m["title"]?.jsonPrimitive?.contentOrNull ?: return null
        fun ms(e: JsonElement?): Long? = (e as? JsonPrimitive)?.let {
            it.doubleOrNull?.let { d -> (d * 1000).toLong() } ?: it.longOrNull
        }
        return MaNowPlaying(
            title = title,
            artist = m["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = m["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            imageUrl = m["image_url"]?.jsonPrimitive?.contentOrNull
                ?: ((m["image"] as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull),
            durationMs = ms(m["duration"]),
            elapsedMs = ms(m["elapsed_time"] ?: elapsed),
        )
    }

    fun queues(result: JsonElement?, serverUrl: String? = null): List<MaQueue> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["queue_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val current = o["current_item"] as? JsonObject
            MaQueue(
                queueId = id,
                quality = quality(current),
                shuffleEnabled = o["shuffle_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                repeatMode = o["repeat_mode"]?.jsonPrimitive?.contentOrNull ?: "off",
                currentIndex = o["current_index"]?.jsonPrimitive?.intOrNull,
                itemCount = o["items"]?.jsonPrimitive?.intOrNull ?: 0,
                currentItem = item(current?.get("media_item"), serverUrl),
                currentQueueItemId = current?.get("queue_item_id")?.jsonPrimitive?.contentOrNull,
                dontStopTheMusic = o["dont_stop_the_music_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                playbackSpeed = o["playback_speed"]?.jsonPrimitive?.floatOrNull ?: 1f,
                streamProvider = (current?.get("streamdetails") as? JsonObject)
                    ?.get("provider")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /**
     * Pull codec/rate/depth out of a queue item's `streamdetails`. MA has moved
     * these between the stream details root and a nested `audio_format` across
     * versions, so both shapes are accepted — and the *output* format is
     * preferred over the source, since that's what the speaker actually receives.
     */
    private fun quality(currentItem: JsonElement?): StreamQuality? {
        val sd = (currentItem as? JsonObject)?.get("streamdetails") as? JsonObject ?: return null
        val fmt = (sd["audio_format"] as? JsonObject) ?: sd
        val codec = fmt["content_type"]?.jsonPrimitive?.contentOrNull
            ?: fmt["codec"]?.jsonPrimitive?.contentOrNull
            ?: return null
        if (codec.isBlank() || codec.equals("unknown", ignoreCase = true)) return null
        return StreamQuality(
            codec = codec,
            sampleRateHz = fmt["sample_rate"]?.jsonPrimitive?.intOrNull ?: 0,
            bitDepth = fmt["bit_depth"]?.jsonPrimitive?.intOrNull ?: 0,
            bitrateKbps = fmt["bit_rate"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun strList(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun artistString(o: JsonObject): String? {
        val arr = o["artists"] as? JsonArray ?: return null
        val names = arr.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        return names.joinToString(", ").ifBlank { null }
    }

    /** Genres can arrive as a JSON array of strings, a comma-separated string, or a list of objects. */
    private fun genreList(o: JsonObject): List<String> = when (val g = o["genres"]) {
        is JsonArray -> g.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: it.jsonPrimitive.contentOrNull }
        is JsonPrimitive -> g.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        else -> emptyList()
    }

    /**
     * Resolve a media item's artwork.
     *
     * The server sends images in more than one shape and only the first was being
     * handled, which is why most of the library came back blank:
     *  - full items (Album/Artist/Track) carry `metadata.images[]`, but that field
     *    is nullable and empty until a metadata scan has run;
     *  - an `ItemMapping` — what search results and nested artist/album refs are —
     *    carries a *single* `image` object instead, with no `metadata` at all;
     *  - a track usually has no art of its own and inherits its album's.
     *
     * `remotely_accessible` marks a path that is already a public URL; everything
     * else has to go through the server's `/imageproxy`.
     */
    private fun imageUrl(o: JsonObject, serverUrl: String?): String? =
        imageFrom(o, serverUrl)
            ?: imageFrom(o["album"] as? JsonObject, serverUrl)
            ?: (o["artists"] as? JsonArray)?.firstNotNullOfOrNull {
                imageFrom(it as? JsonObject, serverUrl)
            }

    /** Artwork carried directly by [o], ignoring anything it merely references. */
    private fun imageFrom(o: JsonObject?, serverUrl: String?): String? {
        if (o == null) return null
        val candidates: List<JsonObject> = buildList {
            ((o["metadata"] as? JsonObject)?.get("images") as? JsonArray)
                ?.forEach { (it as? JsonObject)?.let(::add) }
            (o["images"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::add) }
            (o["image"] as? JsonObject)?.let(::add)
        }
        if (candidates.isEmpty()) return null
        // Prefer a square thumb; the rest (fanart, banner, logo) crop badly in a tile.
        val img = candidates.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "thumb" }
            ?: candidates.first()
        val path = img["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val remote = img["remotely_accessible"]?.jsonPrimitive?.booleanOrNull ?: false
        if (remote || path.startsWith("http", ignoreCase = true)) return path
        val base = serverUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val provider = img["provider"]?.jsonPrimitive?.contentOrNull ?: "builtin"
        return imageProxyUrl(base, provider, path)
    }

    /**
     * Music Assistant's own image-proxy URL shape, reproduced exactly:
     *
     * ```
     * {base}/imageproxy?provider={provider}&size=0&fmt={fmt}&path={quote_plus(quote_plus(path))}
     * ```
     *
     * The **double** encoding is not a mistake — the server encodes the path twice
     * on the way out and unquotes it twice on the way in, so a singly-encoded path
     * comes back mangled for anything containing a `%`, `+` or a nested URL. Sending
     * it singly-encoded is what left every non-remote cover blank.
     *
     * [base] is the *API* base here; MA actually serves the proxy from its stream
     * server, which usually sits on a different port. Rather than guess the port,
     * the app's image loader ([com.engabd.sendpin.SendpinApp]) probes the handful of
     * shapes MA has used and remembers whichever one the server answers.
     */
    fun imageProxyUrl(base: String, provider: String, path: String, size: Int = 0): String {
        val encoded = URLEncoder.encode(URLEncoder.encode(path, "UTF-8"), "UTF-8")
        return "${base.trimEnd('/')}/imageproxy?provider=${URLEncoder.encode(provider, "UTF-8")}" +
            "&size=$size&fmt=${imageFormat(path)}&path=$encoded"
    }

    /** MA's `_detect_image_format`: read off the extension, PNG when in doubt. */
    private fun imageFormat(path: String): String =
        when (path.substringAfterLast('.', "").substringBefore('?').lowercase()) {
            "jpg", "jpeg" -> "jpeg"
            "gif" -> "gif"
            "webp" -> "webp"
            "svg" -> "svg"
            else -> "png"
        }

    // --- queue items --------------------------------------------------------

    fun queueItems(result: JsonElement?, serverUrl: String?): List<MaQueueItem> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o["queue_item_id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val media = item(o["media_item"], serverUrl)
            MaQueueItem(
                queueItemId = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: media?.name ?: "?",
                duration = o["duration"]?.jsonPrimitive?.intOrNull,
                sortIndex = o["sort_index"]?.jsonPrimitive?.intOrNull ?: 0,
                mediaItem = media,
                streamDetails = quality(o),
                // `index` is the queue position play_index takes; fall back to the
                // array order, which is the same thing for a full page-0 fetch.
                index = o["index"]?.jsonPrimitive?.intOrNull ?: i,
                artist = media?.subtitle,
                image = media?.image
                    ?: ((o["image"] as? JsonObject)?.let { imageUrl(JsonObject(mapOf("images" to JsonArray(listOf(it)))), serverUrl) }),
                available = o["available"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }

    // --- lyrics ------------------------------------------------------------

    fun lyrics(result: JsonElement?): MaLyrics? {
        // MA returns a (lyrics, lrc_lyrics) tuple — a 2-element array where [0] is
        // plain text and [1] is LRC-synced text (or null). It can also come back as
        // a bare string for older endpoints.
        when (result) {
            is JsonArray -> {
                if (result.size < 1) return null
                val lrc = result.getOrNull(1)?.jsonPrimitive?.contentOrNull
                val plain = result.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val text = lrc ?: plain ?: return null
                return MaLyrics(text = text, synced = lrc != null)
            }
            is JsonObject -> {
                val text = result["lyrics"]?.jsonPrimitive?.contentOrNull
                    ?: result["text"]?.jsonPrimitive?.contentOrNull ?: return null
                return MaLyrics(
                    text = text,
                    synced = result["synced"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            is JsonPrimitive -> {
                val text = result.contentOrNull ?: return null
                return MaLyrics(text = text, synced = false)
            }
            else -> return null
        }
    }

    // --- similar tracks ----------------------------------------------------

    fun similarTracks(result: JsonElement?, serverUrl: String?): List<MaSimilarTrack> {
        // music/tracks/similar_tracks → Array of Track; sonic_similarity/text_search
        // → object with string keys and Any values (a dict of matches). Both carry
        // item_id / name / artists at the leaf level.
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> {
                // text_search returns a dict; try common result-wrapper keys, else treat values.
                (result["matches"] as? JsonArray)
                    ?: (result["result"] as? JsonArray)
                    ?: JsonArray(result.values.filter { it is JsonObject || it is JsonArray })
            }
            else -> return emptyList()
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["item_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaSimilarTrack(
                itemId = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                artist = artistString(o),
                image = imageUrl(o, serverUrl),
                uri = o["uri"]?.jsonPrimitive?.contentOrNull,
                provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "library",
            )
        }
    }
}
