package com.engabd.sendpin.ma

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

/** Grouped search hits. */
data class MaSearchResults(
    val artists: List<MaItem>,
    val albums: List<MaItem>,
    val tracks: List<MaItem>,
    val playlists: List<MaItem>,
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
}
