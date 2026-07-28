package com.engabd.sendpin.ma

import kotlinx.serialization.json.*

/**
 * High-level Music Assistant library + control, on top of [MaApiClient]. Command
 * names and argument shapes follow the MA WS API (see the massdroid MaCommands
 * contracts, MIT). A player's queue id equals its player id, so playing to *this*
 * phone means `playMedia(ourClientId, …)`.
 */
class MaRepository(val api: MaApiClient) {

    private val serverUrl: String? get() = api.serverUrl

    // --- library browse ---------------------------------------------------

    suspend fun artists(offset: Int = 0, limit: Int = 500) =
        MaParse.items(api.sendCommand("music/artists/library_items", libraryArgs(offset, limit)), serverUrl)

    suspend fun albums(offset: Int = 0, limit: Int = 500) =
        MaParse.items(api.sendCommand("music/albums/library_items", libraryArgs(offset, limit)), serverUrl)

    suspend fun tracks(offset: Int = 0, limit: Int = 500) =
        MaParse.items(api.sendCommand("music/tracks/library_items", libraryArgs(offset, limit)), serverUrl)

    suspend fun playlists(offset: Int = 0, limit: Int = 500) =
        MaParse.items(api.sendCommand("music/playlists/library_items", libraryArgs(offset, limit)), serverUrl)

    /** Feeds the library's "Recently played" shelf. */
    suspend fun recentlyPlayed(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/recently_played_items", buildJsonObject { put("limit", limit) }), serverUrl)

    suspend fun artistAlbums(item: MaItem) =
        MaParse.items(api.sendCommand("music/artists/artist_albums", itemRef(item)), serverUrl)

    suspend fun albumTracks(item: MaItem) =
        MaParse.items(api.sendCommand("music/albums/album_tracks", itemRef(item)), serverUrl)

    suspend fun playlistTracks(item: MaItem) =
        MaParse.items(api.sendCommand("music/playlists/playlist_tracks", itemRef(item)), serverUrl)

    suspend fun search(query: String, limit: Int = 30): MaSearchResults {
        val res = api.sendCommand("music/search", buildJsonObject {
            put("search_query", query); put("limit", limit)
        })?.jsonObject
        return MaSearchResults(
            artists = MaParse.items(res?.get("artists"), serverUrl),
            albums = MaParse.items(res?.get("albums"), serverUrl),
            tracks = MaParse.items(res?.get("tracks"), serverUrl),
            playlists = MaParse.items(res?.get("playlists"), serverUrl),
        )
    }

    /** Browse into a container item (artist → albums, album/playlist → tracks). */
    suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistAlbums(item)
        "album" -> albumTracks(item)
        "playlist" -> playlistTracks(item)
        else -> emptyList()
    }

    // --- players + playback ----------------------------------------------

    suspend fun players() = MaParse.players(api.sendCommand("players/all"))

    /** All player queues — carries the stream details behind the quality badge. */
    suspend fun queues() = MaParse.queues(api.sendCommand("player_queues/all"))

    /** Play items to a player (queue_id == player_id). [option]: play|replace|next|add. */
    suspend fun playMedia(playerId: String, uris: List<String>, option: String = "replace") {
        api.sendCommand("player_queues/play_media", buildJsonObject {
            put("queue_id", playerId)
            put("media", JsonArray(uris.map { JsonPrimitive(it) }))
            put("option", option)
        })
    }

    suspend fun play(playerId: String) = cmd("play", playerId)
    suspend fun pause(playerId: String) = cmd("pause", playerId)
    suspend fun next(playerId: String) = cmd("next", playerId)
    suspend fun previous(playerId: String) = cmd("previous", playerId)

    suspend fun seek(playerId: String, positionSec: Int) =
        api.sendCommand("players/cmd/seek", buildJsonObject {
            put("player_id", playerId); put("position", positionSec.coerceAtLeast(0))
        })

    suspend fun setVolume(playerId: String, level: Int) =
        api.sendCommand("players/cmd/volume_set", buildJsonObject {
            put("player_id", playerId); put("volume_level", level.coerceIn(0, 100))
        })

    suspend fun setShuffle(queueId: String, enabled: Boolean) =
        api.sendCommand("player_queues/shuffle", buildJsonObject {
            put("queue_id", queueId); put("shuffle_enabled", enabled)
        })

    /** [mode] is `off`, `one` or `all`. */
    suspend fun setRepeat(queueId: String, mode: String) =
        api.sendCommand("player_queues/repeat", buildJsonObject {
            put("queue_id", queueId); put("repeat_mode", mode)
        })

    private suspend fun cmd(command: String, playerId: String) =
        api.sendCommand("players/cmd/$command", buildJsonObject { put("player_id", playerId) })

    // --- grouping ---------------------------------------------------------

    /**
     * Add/remove sync members with [targetPlayer] as the group leader. Joining a
     * player to *this phone* = `setMembers(myPlayerId, add=listOf(other))`.
     */
    suspend fun setMembers(
        targetPlayer: String,
        add: List<String> = emptyList(),
        remove: List<String> = emptyList(),
    ) = api.sendCommand("players/cmd/set_members", buildJsonObject {
        put("target_player", targetPlayer)
        if (add.isNotEmpty()) put("player_ids_to_add", JsonArray(add.map { JsonPrimitive(it) }))
        if (remove.isNotEmpty()) put("player_ids_to_remove", JsonArray(remove.map { JsonPrimitive(it) }))
    })

    /** Remove a single player from whatever group it is synced into. */
    suspend fun ungroup(playerId: String) =
        api.sendCommand("players/cmd/ungroup", buildJsonObject { put("player_id", playerId) })

    /** Group volume (fans out to members preserving ratios). [leaderId] = the group/leader. */
    suspend fun setGroupVolume(leaderId: String, level: Int) =
        api.sendCommand("players/cmd/group_volume", buildJsonObject {
            put("player_id", leaderId); put("volume_level", level.coerceIn(0, 100))
        })

    // --- per-player Sendspin sync-delay (player config) -------------------

    /**
     * The per-player Sendspin sync-delay lives in the player config. The key
     * varies (plain `sendspin_sync_delay` or a protocol-wrapped
     * `<sub>||protocol||sendspin_sync_delay`), so match by suffix and carry the
     * exact key back for the save.
     */
    suspend fun getSyncDelay(playerId: String): SyncDelay? {
        val res = api.sendCommand("config/players/get", buildJsonObject { put("player_id", playerId) })
            ?.jsonObject ?: return null
        val values = res["values"]?.jsonObject ?: return null
        val key = values.keys.firstOrNull { it.endsWith("sendspin_sync_delay") } ?: return null
        val ms = values[key].configInt() ?: 0
        return SyncDelay(key, ms)
    }

    suspend fun setSyncDelay(playerId: String, key: String, ms: Int) =
        api.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject { put(key, ms) })
        })

    private fun JsonElement?.configInt(): Int? = when (this) {
        is JsonPrimitive -> intOrNull
        is JsonObject -> this["value"]?.jsonPrimitive?.intOrNull
        else -> null
    }

    // --- args -------------------------------------------------------------

    private fun libraryArgs(offset: Int, limit: Int) = buildJsonObject {
        put("limit", limit); put("offset", offset)
    }

    private fun itemRef(item: MaItem) = buildJsonObject {
        put("item_id", item.itemId)
        put("provider_instance_id_or_domain", item.provider)
    }
}
