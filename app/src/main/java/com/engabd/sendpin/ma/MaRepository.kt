package com.engabd.sendpin.ma

import kotlinx.serialization.json.*

/**
 * High-level Music Assistant library + control, on top of [MaApiClient]. Command
 * names and argument shapes follow the MA WS API (see the massdroid MaCommands
 * contracts, MIT). Queue commands address a **queue**, not a player: use
 * [activeQueueId] (or [playOn], which does it for you) rather than assuming the two
 * ids are the same — for a synced player they are not.
 */
class MaRepository(val api: MaApiClient) {

    private val serverUrl: String? get() = api.serverUrl

    // --- library browse ---------------------------------------------------

    companion object {
        /**
         * How many library items to ask for at once.
         *
         * The whole library used to be one request for 5000 items against a 30-second
         * deadline, on a socket shared with everything else the app is doing — a big
         * collection on a modest server simply ran out of time and the user saw
         * "Request timed out" with nothing loaded. Pages are individually cheap, and a
         * slow page costs one retry rather than the whole library.
         */
        const val PAGE_SIZE = 500

        /**
         * A generous deadline for library reads specifically.
         *
         * The default 30s is sized for a control command, where a slow answer means
         * something is wrong. A library page on a cold server that is still building
         * its response is just slow, and failing it turns a wait into an error.
         */
        private const val LIBRARY_TIMEOUT_MS = 60_000L
    }

    suspend fun artists(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/artists/library_items", offset, limit)

    suspend fun albums(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/albums/library_items", offset, limit)

    suspend fun tracks(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/tracks/library_items", offset, limit)

    suspend fun playlists(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/playlists/library_items", offset, limit)

    private suspend fun libraryPage(command: String, offset: Int, limit: Int) =
        MaParse.items(
            api.sendCommand(command, libraryArgs(offset, limit), timeoutMs = LIBRARY_TIMEOUT_MS, retries = 2),
            serverUrl,
        )

    /**
     * Read a whole library category, a page at a time.
     *
     * [onPage] is called with the running total after each page, so the grid fills as
     * the pages land instead of staying empty until the last one. Stops when a short
     * page comes back — the server has no more — or at [cap], which is a backstop
     * against a server that ignores `offset` and hands back the same page forever.
     */
    suspend fun allLibraryItems(
        page: suspend (offset: Int, limit: Int) -> List<MaItem>,
        cap: Int = 20_000,
        onPage: (List<MaItem>) -> Unit = {},
    ): List<MaItem> {
        val all = mutableListOf<MaItem>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (offset < cap) {
            val batch = page(offset, PAGE_SIZE)
            if (batch.isEmpty()) break
            val fresh = batch.filter { seen.add(it.itemId + "|" + it.provider) }
            if (fresh.isEmpty()) break          // the server is repeating itself
            all += fresh
            onPage(all.toList())
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return all
    }

    /** Feeds the library's "Favourite albums" shelf. */
    suspend fun favoriteAlbums(limit: Int = 12, orderBy: String? = null) =
        MaParse.items(
            api.sendCommand(
                "music/albums/library_items",
                libraryArgs(0, limit, favorite = true, orderBy = orderBy),
                timeoutMs = LIBRARY_TIMEOUT_MS,
            ),
            serverUrl,
        )

    /**
     * Feeds the library's "Favourite artists" shelf.
     *
     * `album_artists_only` keeps one-off featured credits out of a shelf that is
     * meant to read as "the artists you follow".
     */
    suspend fun favoriteArtists(limit: Int = 12, orderBy: String? = null, albumArtistsOnly: Boolean = true) =
        MaParse.items(
            api.sendCommand(
                "music/artists/library_items",
                buildJsonObject {
                    libraryArgs(0, limit, favorite = true, orderBy = orderBy).forEach { (k, v) -> put(k, v) }
                    put("album_artists_only", albumArtistsOnly)
                },
                timeoutMs = LIBRARY_TIMEOUT_MS,
            ),
            serverUrl,
        )

    /** Feeds the library's "Recently played" shelf. */
    suspend fun recentlyPlayed(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/recently_played_items", buildJsonObject { put("limit", limit) }), serverUrl)

    /** Recently added tracks — "New to your library". */
    suspend fun recentlyAdded(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/recently_added_tracks", buildJsonObject { put("limit", limit) }), serverUrl)

    /** Personalised recommendations — returns RecommendationFolder[] with nested items. */
    suspend fun recommendations(): List<MaItem> {
        val folders = api.sendCommand("music/recommendations") as? JsonArray ?: return emptyList()
        return folders.flatMap { f ->
            val o = f as? JsonObject ?: return@flatMap emptyList()
            val items = o["items"] as? JsonArray ?: return@flatMap emptyList()
            MaParse.items(items, serverUrl)
        }
    }

    /** Audiobooks and podcasts in progress. */
    suspend fun inProgress(limit: Int = 12) =
        MaParse.items(api.sendCommand("music/in_progress_items", buildJsonObject { put("limit", limit) }), serverUrl)

    suspend fun artistAlbums(item: MaItem) =
        MaParse.items(api.sendCommand("music/artists/artist_albums", itemRef(item)), serverUrl)

    /** Full artist metadata (biography, genres, image) from `music/artists/get`. */
    suspend fun getArtist(item: MaItem): MaItem? =
        MaParse.item(api.sendCommand("music/artists/get", itemRef(item)), serverUrl)

    /** All tracks by an artist. */
    suspend fun artistTracks(item: MaItem) =
        MaParse.items(api.sendCommand("music/artists/artist_tracks", itemRef(item)), serverUrl)

    /** Full album metadata (year, genre, artists, etc.) from `music/albums/get`. */
    suspend fun getAlbum(item: MaItem): MaItem? =
        MaParse.item(api.sendCommand("music/albums/get", itemRef(item)), serverUrl)

    suspend fun albumTracks(item: MaItem) =
        MaParse.items(api.sendCommand("music/albums/album_tracks", itemRef(item)), serverUrl)

    suspend fun playlistTracks(item: MaItem) =
        MaParse.items(api.sendCommand("music/playlists/playlist_tracks", itemRef(item)), serverUrl)

    // --- playlist CRUD (MA) ------------------------------------------------

    /**
     * Create a new playlist. MA's `music/playlists/create` takes a name and
     * returns the new playlist's library item. The caller can then open it or
     * add tracks to it via [playOn].
     */
    suspend fun createPlaylist(name: String): MaItem? {
        val res = api.sendCommand("music/playlists/create", buildJsonObject {
            put("name", name)
        })
        return MaParse.item(res, serverUrl)
    }

    /**
     * Delete a playlist. MA identifies it by `item_id` + `provider`, the same
     * pair [itemRef] builds for every other library command.
     */
    suspend fun deletePlaylist(item: MaItem) {
        api.sendCommand("music/playlists/delete", itemRef(item))
    }

    /**
     * Rename a playlist — the only edit the library UI exposes. MA's
     * `music/playlists/edit` takes the item ref and the new name.
     */
    suspend fun editPlaylist(item: MaItem, newName: String) {
        api.sendCommand("music/playlists/edit", buildJsonObject {
            put("item_id", item.itemId)
            put("provider_instance_id_or_domain", item.provider)
            put("name", newName)
        })
    }

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
    suspend fun queues() = MaParse.queues(api.sendCommand("player_queues/all"), serverUrl)

    /**
     * The queue a player is actually playing from.
     *
     * **Not** the same thing as the player id, which is what every queue command in
     * this app used to be given. The `player_queues` commands address a *queue*, and a
     * player that is synced to another plays the leader's queue — so a queue command
     * aimed at the member's own id lands on a queue nobody is listening to. That is
     * why "add to queue" appeared to do nothing unless the queue happened to be empty:
     * the items really were added, to the wrong queue, and only became audible when
     * playing it made that queue the active one.
     *
     * `player_queues/get_active_queue` is Music Assistant's own answer to the question,
     * so it is used in preference to guessing from `synced_to`.
     */
    suspend fun activeQueueId(playerId: String): String {
        val res = runCatching {
            api.sendCommand("player_queues/get_active_queue", buildJsonObject { put("player_id", playerId) })
        }.getOrNull()?.jsonObject
        return res?.get("queue_id")?.jsonPrimitive?.contentOrNull ?: playerId
    }

    /** A single player's state, or null when the server doesn't know it. */
    suspend fun getPlayer(playerId: String): MaPlayer? {
        val res = runCatching {
            api.sendCommand("players/get", buildJsonObject {
                put("player_id", playerId); put("raise_unavailable", false)
            })
        }.getOrNull() ?: return null
        return MaParse.players(JsonArray(listOf(res))).firstOrNull()
    }

    /**
     * Append [uris] to a player's queue, addressed by its **active** queue.
     *
     * Kept as a distinct entry point from [playMedia] so the enqueue path can't drift
     * from what the reference client sends. Counting the queue either side of the call
     * to "verify" it was a mistake: Music Assistant tops the queue up on its own (radio
     * mode, "don't stop the music"), so the delta reported items nobody added and said
     * nothing useful about whether the request itself landed.
     */
    suspend fun enqueue(playerId: String, uris: List<String>, option: String) =
        playOn(playerId, uris, option)

    /**
     * Play [uris] on whatever queue [playerId] is actually using.
     *
     * The entry point every screen should use. [playMedia] takes a raw `queue_id` and
     * is left exposed only for callers that already hold one.
     */
    suspend fun playOn(
        playerId: String,
        uris: List<String>,
        option: String = "replace",
        radioMode: Boolean = false,
    ) = playMedia(activeQueueId(playerId), uris, option, radioMode)

    /**
     * `player_queues/play_media`, argument-for-argument as the official Music
     * Assistant app sends it (`api/Request.kt: Player.play`).
     *
     * `radio_mode` is sent **explicitly**. Leaving it out is what broke enqueueing:
     * with the flag absent Music Assistant fell back to the queue's own radio setting,
     * and on a queue that already had items the added tracks were discarded in favour
     * of its generated ones — which is exactly the reported behaviour, that "add" only
     * worked when the queue was empty. It is a defaulted parameter server-side, so
     * omitting it looked harmless; it isn't.
     */
    suspend fun playMedia(
        playerId: String,
        uris: List<String>,
        option: String = "replace",
        radioMode: Boolean = false,
    ) {
        api.sendCommand("player_queues/play_media", buildJsonObject {
            put("media", JsonArray(uris.map { JsonPrimitive(it) }))
            put("option", option)
            put("radio_mode", radioMode)
            put("queue_id", playerId)
        })
    }

    suspend fun play(playerId: String) = cmd("play", playerId)
    suspend fun pause(playerId: String) = cmd("pause", playerId)
    suspend fun stop(playerId: String) = cmd("stop", playerId)
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

    /**
     * One page of a queue's items.
     *
     * `limit`/`offset` are sent explicitly: the command defaults to `limit=500`, so a
     * longer queue was silently truncated to its first 500 rows.
     */
    suspend fun queueItems(queueId: String, offset: Int = 0, limit: Int = PAGE_SIZE) =
        MaParse.queueItems(
            api.sendCommand("player_queues/items", buildJsonObject {
                put("queue_id", queueId); put("limit", limit); put("offset", offset)
            }, timeoutMs = LIBRARY_TIMEOUT_MS),
            serverUrl,
            indexOffset = offset,
        )

    /**
     * A queue in full, a page at a time. [cap] is a backstop against a server that
     * ignores `offset` and hands back the same page for ever.
     */
    suspend fun allQueueItems(queueId: String, cap: Int = 5_000): List<MaQueueItem> {
        val all = mutableListOf<MaQueueItem>()
        var offset = 0
        while (offset < cap) {
            val page = queueItems(queueId, offset)
            all += page
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return all
    }

    /** Remove an item from the queue by index or item id. */
    suspend fun deleteQueueItem(queueId: String, itemIdOrIndex: String) =
        api.sendCommand("player_queues/delete_item", buildJsonObject {
            put("queue_id", queueId); put("item_id_or_index", itemIdOrIndex)
        })

    /** Clear the entire queue. */
    suspend fun clearQueue(queueId: String) =
        api.sendCommand("player_queues/clear", buildJsonObject { put("queue_id", queueId) })

    /** Move a queue item by a position shift (0 = top/next, negative = up, positive = down). */
    suspend fun moveQueueItem(queueId: String, itemId: String, posShift: Int) =
        api.sendCommand("player_queues/move_item", buildJsonObject {
            put("queue_id", queueId); put("queue_item_id", itemId); put("pos_shift", posShift)
        })

    /** Save the current queue as a playlist. */
    suspend fun saveQueueAsPlaylist(queueId: String, name: String) =
        api.sendCommand("player_queues/save_as_playlist", buildJsonObject {
            put("queue_id", queueId); put("name", name)
        })

    /**
     * Jump to a queue row. `play_index` takes `index: int | str` and resolves a
     * string as a **queue_item_id** — which is what callers should use: an id can't
     * drift out of step with the server's play order the way a position can.
     */
    suspend fun playQueueItem(queueId: String, queueItemId: String) =
        api.sendCommand("player_queues/play_index", buildJsonObject {
            put("queue_id", queueId); put("index", queueItemId)
        })

    /** The positional form of [playQueueItem], for a server that won't take an id. */
    suspend fun playQueueIndex(queueId: String, index: Int) =
        api.sendCommand("player_queues/play_index", buildJsonObject {
            put("queue_id", queueId); put("index", index)
        })

    // --- favorites ---------------------------------------------------------

    /**
     * Add an item to favorites. MA takes `item` as either a full media-item object
     * or a URI string; the URI is the shape we can always produce faithfully, and
     * the server resolves it back to the item itself.
     */
    suspend fun addFavorite(item: MaItem) {
        val uri = item.uri ?: "${item.mediaType}://${item.provider}/${item.itemId}"
        api.sendCommand("music/favorites/add_item", buildJsonObject { put("item", uri) })
    }

    /** Remove a library item from favorites by media_type + library_item_id. */
    suspend fun removeFavorite(item: MaItem) =
        api.sendCommand("music/favorites/remove_item", buildJsonObject {
            put("media_type", item.mediaType); put("library_item_id", item.itemId)
        })

    // --- sonic similarity --------------------------------------------------

    /** Find acoustically similar tracks. */
    suspend fun similarTracks(itemId: String, provider: String, limit: Int = 12) =
        MaParse.similarTracks(api.sendCommand("music/tracks/similar_tracks", buildJsonObject {
            put("item_id", itemId); put("provider_instance_id_or_domain", provider)
            put("limit", limit)
        }), serverUrl)

    /**
     * Natural-language music search via CLAP embeddings. `resolve=true` includes
     * track name/artist. The result comes back as either an array or a dict of
     * matches depending on the provider, so it is handed to the parser unchanged.
     */
    suspend fun sonicTextSearch(query: String, limit: Int = 12) = MaParse.similarTracks(
        api.sendCommand("sonic_similarity/text_search", buildJsonObject {
            put("query", query); put("limit", limit); put("resolve", true)
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
            put("queue_id", queueId); put("dont_stop_the_music_enabled", enabled)
        })

    // --- lyrics ------------------------------------------------------------

    /**
     * Lyrics for a track, as a `(lyrics, lrc_lyrics)` tuple.
     *
     * `metadata/get_track_lyrics` takes a whole `Track`, not a reference to one, so
     * the track is resolved first and the server's own object handed straight back
     * to it — a two-field stand-in fails to deserialise.
     */
    suspend fun getLyrics(item: MaItem): MaLyrics? {
        val track = api.sendCommand("music/tracks/get", itemRef(item))?.jsonObject ?: return null
        return MaParse.lyrics(api.sendCommand("metadata/get_track_lyrics", buildJsonObject {
            put("track", track)
        }))
    }

    // --- track preview -----------------------------------------------------

    /** A short preview URL for a track. The command returns the URL as a bare string. */
    suspend fun trackPreview(itemId: String, provider: String): String? =
        api.sendCommand("music/tracks/preview", buildJsonObject {
            put("provider_instance_id_or_domain", provider); put("item_id", itemId)
        })?.jsonPrimitive?.contentOrNull

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

    /** Remove several players from their groups in a single command (MA ≥ 2.9). */
    suspend fun ungroupMany(playerIds: List<String>) =
        api.sendCommand("players/cmd/ungroup_many", buildJsonObject {
            put("player_ids", JsonArray(playerIds.map { JsonPrimitive(it) }))
        })

    /** Group volume (fans out to members preserving ratios). [leaderId] = the group/leader. */
    suspend fun setGroupVolume(leaderId: String, level: Int) =
        api.sendCommand("players/cmd/group_volume", buildJsonObject {
            put("player_id", leaderId); put("volume_level", level.coerceIn(0, 100))
        })

    // --- queue transfer (cross-device handoff) ----------------------------

    /**
     * Transfer the entire queue (items, position, shuffle/repeat state) from one
     * player to another — the "tap a speaker, music moves there" feature.
     * [autoPlay] = start playback on the target immediately.
     */
    suspend fun transferQueue(sourceQueueId: String, targetQueueId: String, autoPlay: Boolean = true) =
        api.sendCommand("player_queues/transfer", buildJsonObject {
            put("source_queue_id", sourceQueueId)
            put("target_queue_id", targetQueueId)
            put("auto_play", autoPlay)
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

    // --- player identity + Sendspin format (player config) ----------------

    /**
     * Rename a player as far as Music Assistant is concerned.
     *
     * The Sendspin `client/hello` announces a name, but MA only uses it to *register*
     * a player it has never seen. After that the per-player config's `name` — which
     * MA surfaces as `display_name` and which [MaParse.players] already prefers — wins
     * for good, so a rename that only goes out in a fresh hello reads back as whatever
     * the player was first registered under. This is the same call both reference
     * clients make (`config/players/save` with `values: {"name": …}`).
     */
    suspend fun renamePlayer(playerId: String, name: String) =
        api.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject { put("name", name) })
        })

    /**
     * The name Music Assistant currently has for a player, straight from its config.
     *
     * `name` is a **top-level** field of `PlayerConfig` (alongside `enabled` and
     * `default_name`) rather than one of the `values` config entries — `values` is a
     * map of `ConfigEntry` objects. `config/players/save` still takes the change
     * inside `values`, because MA's `PlayerConfig.update` lifts `name` and `enabled`
     * out of it; reading it back has to look at the top level.
     */
    suspend fun playerConfigName(playerId: String): String? {
        val res = api.sendCommand("config/players/get", buildJsonObject { put("player_id", playerId) })
            ?.jsonObject ?: return null
        return res["name"]?.jsonPrimitive?.contentOrNull
            ?: res["default_name"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * MA's own per-player preference for what Sendspin should stream. Kept in step
     * with the codec this client advertises so the two can't disagree; `"automatic"`
     * is MA's wording for "no preference".
     *
     * Best-effort: servers without the key ignore the save, which is why nothing
     * depends on the result.
     */
    /**
     * Point MA's own per-player format preference at [codec] (`"automatic"` for none).
     *
     * The value is **not** a bare codec name. `preferred_sendspin_format` is a
     * `ConfigEntry` whose `options` the server declares, and they carry the rate and
     * depth too (`flac_48000_16`, …) — which is why massdroid reads the options list
     * and matches by prefix rather than sending `"flac"`. Sending a value that isn't
     * one of the declared options is rejected, so the whole save is a no-op.
     *
     * Returns the option actually written, or null when the server has no such key
     * (older builds) or offers nothing matching.
     */
    suspend fun setPreferredSendspinFormat(playerId: String, codec: String): String? {
        val entry = playerConfigEntry(playerId, "preferred_sendspin_format") ?: return null
        val options = (entry["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val wanted = if (codec == "auto") "automatic" else codec
        // Exact match first (a server that does use bare names), then the prefixed
        // form, preferring the highest rate/depth the server lists for that codec.
        val chosen = options.firstOrNull { it == wanted }
            ?: options.filter { it.startsWith("${wanted}_") }.maxByOrNull { it.length }
            ?: return null
        api.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject { put("preferred_sendspin_format", chosen) })
        })
        return chosen
    }

    /** One `ConfigEntry` from a player's config, by key. */
    private suspend fun playerConfigEntry(playerId: String, key: String): JsonObject? {
        val res = api.sendCommand("config/players/get", buildJsonObject { put("player_id", playerId) })
            ?.jsonObject ?: return null
        val values = res["values"]?.jsonObject ?: return null
        // The key is sometimes protocol-wrapped ("<sub>||protocol||<key>"), the same
        // way the sync-delay key is — so match on the suffix rather than exactly.
        val match = values.keys.firstOrNull { it == key || it.endsWith("||$key") || it.endsWith(key) }
        return values[match]?.jsonObject
    }

    // --- per-player DSP (EQ, tone control, gain) ---------------------------

    /**
     * The DSP configuration for [playerId]. Returns a default config (disabled)
     * when the player has none set.
     */
    suspend fun getDspConfig(playerId: String): DspConfig? {
        val res = runCatching {
            api.sendCommand("config/players/dsp/get", buildJsonObject {
                put("player_id", playerId)
            })
        }.getOrNull()?.jsonObject ?: return null
        return DspParse.config(res)
    }

    /**
     * Save a DSP config for [playerId]. Admin-only on the server side.
     * MA validates the config and applies it live — changes take effect
     * without restarting playback.
     */
    suspend fun saveDspConfig(playerId: String, config: DspConfig): DspConfig? {
        val res = runCatching {
            api.sendCommand("config/players/dsp/save", buildJsonObject {
                put("player_id", playerId)
                put("config", DspParse.toJson(config))
            })
        }.getOrNull()?.jsonObject ?: return null
        return DspParse.config(res)
    }

    // --- DSP presets (reusable configs across players) ---------------------

    /** All user-defined DSP presets. */
    suspend fun getDspPresets(): List<DspPreset> {
        val res = runCatching {
            api.sendCommand("config/dsp_presets/get")
        }.getOrNull() ?: return emptyList()
        val arr = res as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return DspParse.presets(arr)
    }

    /** Create or update a preset. Returns the saved preset with its id. */
    suspend fun saveDspPreset(preset: DspPreset): DspPreset? {
        val res = runCatching {
            api.sendCommand("config/dsp_presets/save", buildJsonObject {
                put("preset", DspParse.presetToJson(preset))
            })
        }.getOrNull()?.jsonObject ?: return null
        return DspParse.preset(res)
    }

    /** Remove a preset by id. */
    suspend fun removeDspPreset(presetId: String) {
        runCatching {
            api.sendCommand("config/dsp_presets/remove", buildJsonObject {
                put("preset_id", presetId)
            })
        }
    }

    // --- args -------------------------------------------------------------

    /**
     * Args for a `library_items` read.
     *
     * [favorite] and [orderBy] are the server's own filters — the app used to pull
     * whole categories back and sift them on the phone. [orderBy] is left unsent by
     * default: a value an older server doesn't recognise fails the whole command,
     * and these reads are best-effort.
     */
    private fun libraryArgs(
        offset: Int,
        limit: Int,
        favorite: Boolean? = null,
        orderBy: String? = null,
        search: String? = null,
    ) = buildJsonObject {
        put("limit", limit); put("offset", offset)
        favorite?.let { put("favorite", it) }
        orderBy?.let { put("order_by", it) }
        search?.let { put("search", it) }
    }

    private fun itemRef(item: MaItem) = buildJsonObject {
        put("item_id", item.itemId)
        put("provider_instance_id_or_domain", item.provider)
    }
}
