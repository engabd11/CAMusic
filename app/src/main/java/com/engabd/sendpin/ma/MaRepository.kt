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

    /** Recently added tracks — "New to your library". */
    suspend fun recentlyAdded(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/recently_added_tracks", buildJsonObject { put("limit", limit) }), serverUrl)

    /** Personalised recommendations. */
    suspend fun recommendations(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/recommendations", buildJsonObject { put("limit", limit) }), serverUrl)

    /** Audiobooks and podcasts in progress. */
    suspend fun inProgress(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/in_progress_items", buildJsonObject { put("limit", limit) }), serverUrl)

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

    // --- queue management --------------------------------------------------

    /** Full queue items for a player. */
    suspend fun queueItems(queueId: String) =
        MaParse.queueItems(api.sendCommand("player_queues/items", buildJsonObject { put("queue_id", queueId) }), serverUrl)

    /** Remove an item from the queue by index or item id. */
    suspend fun deleteQueueItem(queueId: String, itemIdOrIndex: String) =
        api.sendCommand("player_queues/delete_item", buildJsonObject {
            put("queue_id", queueId); put("item_id_or_index", itemIdOrIndex)
        })

    /** Clear the entire queue. */
    suspend fun clearQueue(queueId: String) =
        api.sendCommand("player_queues/clear", buildJsonObject { put("queue_id", queueId) })

    /** Move a queue item up or down. */
    suspend fun moveQueueItem(queueId: String, itemId: String, newIndex: Int) =
        api.sendCommand("player_queues/move_item", buildJsonObject {
            put("queue_id", queueId); put("queue_item_id", itemId); put("new_index", newIndex)
        })

    /** Save the current queue as a playlist. */
    suspend fun saveQueueAsPlaylist(queueId: String, name: String) =
        api.sendCommand("player_queues/save_as_playlist", buildJsonObject {
            put("queue_id", queueId); put("name", name)
        })

    /** Play a specific item at an index in the queue. */
    suspend fun playIndex(queueId: String, index: Int) =
        api.sendCommand("player_queues/play_index", buildJsonObject {
            put("queue_id", queueId); put("index", index)
        })

    // --- favorites ---------------------------------------------------------

    /** Add an item to favorites. */
    suspend fun addFavorite(itemId: String, provider: String, mediaType: String) =
        api.sendCommand("music/favorites/add_item", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
            put("media_type", mediaType)
        })

    /** Remove an item from favorites. */
    suspend fun removeFavorite(itemId: String, provider: String, mediaType: String) =
        api.sendCommand("music/favorites/remove_item", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
            put("media_type", mediaType)
        })

    // --- sonic similarity --------------------------------------------------

    /** Find acoustically similar tracks. */
    suspend fun similarTracks(itemId: String, provider: String, limit: Int = 12) =
        MaParse.similarTracks(api.sendCommand("music/tracks/similar_tracks", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
            put("limit", limit)
        }), serverUrl)

    /** Natural-language music search via CLAP embeddings. */
    suspend fun sonicTextSearch(query: String, limit: Int = 12) =
        MaParse.similarTracks(api.sendCommand("sonic_similarity/text_search", buildJsonObject {
            put("query", query); put("limit", limit)
        }), serverUrl)

    // --- player power & options --------------------------------------------

    /** Turn a player on or off. */
    suspend fun setPower(playerId: String, powered: Boolean) =
        api.sendCommand("players/cmd/power", buildJsonObject {
            put("player_id", playerId); put("powered", powered)
        })

    // --- playback speed ----------------------------------------------------

    /** Set playback speed (1.0 = normal, 1.5 = 1.5×, etc.). */
    suspend fun setPlaybackSpeed(queueId: String, speed: Float) =
        api.sendCommand("player_queues/set_playback_speed", buildJsonObject {
            put("queue_id", queueId); put("speed", speed.coerceIn(0.5f, 3.0f))
        })

    // --- don't stop the music ----------------------------------------------

    /** Auto-populate the queue when it runs low. */
    suspend fun setDontStopTheMusic(queueId: String, enabled: Boolean) =
        api.sendCommand("player_queues/dont_stop_the_music", buildJsonObject {
            put("queue_id", queueId); put("enabled", enabled)
        })

    // --- lyrics ------------------------------------------------------------

    /** Get lyrics for a track. */
    suspend fun getLyrics(itemId: String, provider: String) =
        MaParse.lyrics(api.sendCommand("metadata/get_track_lyrics", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
        }))

    // --- track preview -----------------------------------------------------

    /** Get a short preview URL for a track. */
    suspend fun trackPreview(itemId: String, provider: String): String? {
        val res = api.sendCommand("music/tracks/preview", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
        })?.jsonObject
        return res?.get("url")?.jsonPrimitive?.contentOrNull
    }

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
