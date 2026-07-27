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

/** A Music Assistant player (a possible playback target / group). */
data class MaPlayer(
    val playerId: String,
    val name: String,
    val available: Boolean,
    val powered: Boolean,
)

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
            )
        }
    }

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
