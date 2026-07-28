package com.engabd.sendpin.ma

import com.engabd.sendpin.audio.StreamQuality
import kotlinx.serialization.json.*
import java.net.URLEncoder

/** A Music Assistant media item (artist / album / track / playlist / radio). */
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

/** A player queue, reduced to the part the UI cares about: what's streaming. */
data class MaQueue(
    val queueId: String,
    val quality: StreamQuality?,
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",   // off | one | all
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
)

/** Lyrics for a track. */
data class MaLyrics(
    val text: String,
    val synced: Boolean = false,
)

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
        )
    }

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
                groupChilds = strList(o["group_childs"]),
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

    fun queues(result: JsonElement?): List<MaQueue> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["queue_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaQueue(
                queueId = id,
                quality = quality(o["current_item"]),
                shuffleEnabled = o["shuffle_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                repeatMode = o["repeat_mode"]?.jsonPrimitive?.contentOrNull ?: "off",
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

    private fun imageUrl(o: JsonObject, serverUrl: String?): String? {
        val images = ((o["metadata"] as? JsonObject)?.get("images") as? JsonArray)
            ?: (o["images"] as? JsonArray) ?: return null
        val first = images.firstOrNull() as? JsonObject ?: return null
        val path = first["path"]?.jsonPrimitive?.contentOrNull ?: return null
        if (path.startsWith("http", ignoreCase = true)) return path
        val base = serverUrl?.trimEnd('/') ?: return null
        val provider = first["provider"]?.jsonPrimitive?.contentOrNull ?: "builtin"
        val enc = URLEncoder.encode(path, "UTF-8")
        return "$base/imageproxy?path=$enc&provider=$provider"
    }

    // --- queue items --------------------------------------------------------

    fun queueItems(result: JsonElement?, serverUrl: String?): List<MaQueueItem> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["queue_item_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaQueueItem(
                queueItemId = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                duration = o["duration"]?.jsonPrimitive?.intOrNull,
                sortIndex = o["sort_index"]?.jsonPrimitive?.intOrNull ?: 0,
                mediaItem = item(o["media_item"], serverUrl),
                streamDetails = quality(o),
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
