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

    suspend fun setVolume(playerId: String, level: Int) =
        api.sendCommand("players/cmd/volume_set", buildJsonObject {
            put("player_id", playerId); put("volume_level", level.coerceIn(0, 100))
        })

    private suspend fun cmd(command: String, playerId: String) =
        api.sendCommand("players/cmd/$command", buildJsonObject { put("player_id", playerId) })

    // --- args -------------------------------------------------------------

    private fun libraryArgs(offset: Int, limit: Int) = buildJsonObject {
        put("limit", limit); put("offset", offset)
    }

    private fun itemRef(item: MaItem) = buildJsonObject {
        put("item_id", item.itemId)
        put("provider_instance_id_or_domain", item.provider)
    }
}
