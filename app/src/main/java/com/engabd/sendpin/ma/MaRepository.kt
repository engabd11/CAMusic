package com.engabd.sendpin.ma

import kotlinx.serialization.json.*

/**
 * High-level Music Assistant library + control, on top of [MaApiClient]. Command
 * names and argument shapes follow the MA WS API (see the massdroid MaCommands
 * contracts, MIT). Queue commands address a **queue**, not a player: use
 * [activeQueueId] (or [playOn], which does it for you) rather than assuming the two
 * ids are the same — for a synced player they are not.
 */
class MaRepository(
    val api: MaApiClient,
    /**
     * Called on the way into anything that starts playback, before the command is
     * sent — see [playMedia].
     *
     * A parameter with a default rather than a hard reference so the class stays
     * constructible without the Application singleton, and so a test can watch it.
     */
    private val onPlaybackRequested: () -> Unit = {
        runCatching { com.engabd.sendpin.SendpinApp.instance.playback.wakePlayerSocket() }
    },
    /**
     * Called when a command has *replaced* a queue, so the position bar can hold at
     * zero until sound is actually out — see [com.engabd.sendpin.service.Playback.playStartSeq].
     *
     * Distinct from [onPlaybackRequested], which fires for enqueues too: adding to the
     * queue leaves the playing track alone, and freezing its position would be wrong.
     */
    private val onQueueReplaced: () -> Unit = {
        runCatching { com.engabd.sendpin.SendpinApp.instance.playback.noteQueueReplaced() }
    },
) {

    private val serverUrl: String? get() = api.serverUrl

    // --- library browse ---------------------------------------------------

    companion object {
        /**
         * Cached `player_id -> (queue_id, fetched at)`.
         *
         * In the companion rather than the instance because every screen constructs its
         * own [MaRepository] over the one process-wide [MaApiClient] — a per-instance
         * cache would be cold on every navigation, which is most of the taps that
         * mattered. Keyed by player id and guarded by [queueIdServer] so switching
         * Music Assistant servers cannot serve one server's queue id for another's.
         */
        private val queueIdCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

        /** Which server [queueIdCache] holds answers for. */
        @Volatile private var queueIdServer: String? = null

        /**
         * How long a queue id is trusted.
         *
         * The answer only changes when grouping changes. Thirty seconds is short enough
         * that a group made from the Music Assistant web UI is picked up while the user
         * is still looking at the screen, and long enough that scrolling a list and
         * tapping four songs costs one lookup rather than four round trips.
         */
        private const val QUEUE_ID_TTL_MS = 30_000L

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

        /**
         * Player-config keys that mean "shift this speaker's audio in time", best
         * first.
         *
         * `sendspin_sync_delay` is the Sendspin protocol's own key (older builds).
         * `sendspin_static_delay` is what sendspin-cli and newer MA builds expose
         * — the same setting under a clearer name. `sync_adjust` is Music
         * Assistant's own generic equivalent and is what a Sonos or Chromecast
         * member carries. Matched by *suffix*, because a provider may namespace
         * the key (e.g. `sendspin-cli-DESKTOP||protocol||sendspin_static_delay`).
         */
        private val SYNC_KEYS = listOf("sendspin_sync_delay", "sendspin_static_delay", "sync_adjust")

        /**
         * The same list with repeats of one media item removed, first copy kept.
         *
         * Identity is provider + media type + id, all three. A library item's own
         * `provider` is always `library` and Music Assistant numbers library items
         * *per media type*, so album 5 and track 5 are both `library|5` and are not
         * the same thing — dropping the media type here would silently swallow one
         * of them.
         */
        fun distinctItems(items: List<MaItem>): List<MaItem> =
            items.distinctBy { "${it.provider}|${it.mediaType}|${it.itemId}" }

        /**
         * `MediaNotFoundError.error_code` — the code Music Assistant puts on the wire
         * beside the (localised, from 2.10) message. Matched on rather than on the
         * text, which is whatever language the connection asked for.
         */
        const val ERR_MEDIA_NOT_FOUND = 2

        /** What [describePlayFailure] says when the item is demonstrably there. */
        const val UNPLAYABLE_MESSAGE =
            "Music Assistant found this track but couldn't play it — check the server log " +
                "for the provider that holds the file"

        /**
         * The `preferred_sendspin_format` option that stands for [wanted], or null.
         *
         * [wanted] is a bare codec (`flac`, `pcm`, `opus`) or MA's `automatic`. The
         * options are `codec:rate:depth:channels`, built by mapping the client's **own
         * advertised `supported_formats`, in the order it advertised them**.
         *
         * **First** match, not the highest rate the server happens to list. The
         * override this writes is a whole fixed format, not a codec: with none set the
         * server plays the client's first compatible format (aiosendspin
         * `_ensure_preferred_format`, `compatible[0]`), so the first option for the
         * codec is the one already in use — writing it changes nothing about what
         * streams and only makes the choice explicit and sticky across reconnects.
         * Pinning `flac:96000:24:2` instead would have Music Assistant hand every
         * 44.1/48 kHz source over at 96 kHz, which is bandwidth spent to arrive at the
         * same audio. `FormatNegotiator` puts 48 kHz first for that reason (and for
         * grouped-sync compatibility); this keeps the two agreeing.
         *
         * The exact-match pass first, so `automatic` — and any server that does list
         * bare codec names — still resolves.
         */
        fun matchFormatOption(options: List<String>, wanted: String): String? =
            options.firstOrNull { it == wanted }
                ?: options.firstOrNull { it.substringBefore(':') == wanted }

        /**
         * The error a failed play should actually carry, given whether the item it
         * named still resolves on the server. See [describePlayFailure] for why.
         *
         * Separated from the probe so the decision can be exercised without a socket:
         * [itemFound] is false both for an item the server really doesn't have and for
         * a probe that couldn't be made, and in neither case do we have grounds to
         * contradict the server's own message.
         */
        fun playFailure(e: MaApiException, itemFound: Boolean): Exception =
            if (e.code == ERR_MEDIA_NOT_FOUND && !e.isTransport && itemFound) {
                MaApiException(UNPLAYABLE_MESSAGE, e.code)
            } else {
                e
            }
    }

    suspend fun artists(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/artists/library_items", offset, limit)

    suspend fun albums(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/albums/library_items", offset, limit)

    suspend fun tracks(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/tracks/library_items", offset, limit)

    suspend fun playlists(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/playlists/library_items", offset, limit)

    /**
     * Internet radio stations in the library.
     *
     * Radio was already *playable* — `MaItem.PLAYABLE` has carried it from the start,
     * and a station reached through search or a recommendation shelf plays fine — but
     * there was no way to browse the stations Music Assistant has, because nothing
     * ever sent this command.
     */
    suspend fun radios(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/radios/library_items", offset, limit)

    /** Podcasts in the library. Each one opens into its episodes. */
    suspend fun podcasts(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/podcasts/library_items", offset, limit)

    /** Audiobooks in the library. Each one opens into its chapters. */
    suspend fun audiobooks(offset: Int = 0, limit: Int = PAGE_SIZE) =
        libraryPage("music/audiobooks/library_items", offset, limit)

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

    /**
     * Everything starred, per media type.
     *
     * `favorite = true` is a filter on the same `library_items` command every browse
     * already uses — `music/tracks/library_items` and friends — so the Starred
     * category costs one call per type and needs nothing new on the server side. The
     * two shelf loaders below are the same query with a shelf-sized limit.
     */
    suspend fun favoriteTracks(limit: Int = 200) =
        MaParse.items(
            api.sendCommand(
                "music/tracks/library_items",
                libraryArgs(0, limit, favorite = true),
                timeoutMs = LIBRARY_TIMEOUT_MS,
            ),
            serverUrl,
        )

    suspend fun favoritePlaylists(limit: Int = 100) =
        MaParse.items(
            api.sendCommand(
                "music/playlists/library_items",
                libraryArgs(0, limit, favorite = true),
                timeoutMs = LIBRARY_TIMEOUT_MS,
            ),
            serverUrl,
        )

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

    /**
     * Personalised recommendations — `RecommendationFolder[]` with nested items,
     * flattened into one shelf.
     *
     * Flattened *and* deduplicated. The folders are separate answers to separate
     * questions — "recently played", "favourite artists", "random albums" — so the
     * same album is routinely in two or three of them, and the flattened shelf listed
     * it two or three times. Every other shelf comes back from a single command and
     * cannot do this; this one has to be told.
     */
    suspend fun recommendations(): List<MaItem> {
        val folders = api.sendCommand("music/recommendations") as? JsonArray ?: return emptyList()
        return distinctItems(
            folders.flatMap { f ->
                val o = f as? JsonObject ?: return@flatMap emptyList()
                val items = o["items"] as? JsonArray ?: return@flatMap emptyList()
                MaParse.items(items, serverUrl)
            }
        )
    }

    /**
     * The Discover rows the server offers, without their contents.
     *
     * MA 2.10 split the recommendations API in two: this listing names the rows, and
     * [recommendationItems] fills one. The app never adopted the split, so [recommendations]
     * above — which reads `folder["items"]` — has been returning nothing at all on any
     * 2.10 server, and the whole "For you" shelf silently disappeared. Worse, everything
     * *behind* those rows disappeared with it: the built-in provider alone offers sixteen,
     * including "Random artists", "Forgotten Albums", "Most Played Tracks" and
     * "Never / Rarely Played", and music providers add their own on top.
     *
     * Reading the rows generically is what makes those appear — and any row a future
     * server or a newly-installed provider grows, without another change here.
     */
    suspend fun recommendationRows(): List<MaRecommendationRow> =
        MaParse.recommendationRows(api.sendCommand("music/recommendations"), serverUrl)

    /**
     * The contents of one Discover row.
     *
     * Deduplicated for the same reason [recommendations] was: a row like "Recent artists"
     * can legitimately list one artist under two provider mappings.
     */
    suspend fun recommendationItems(row: MaRecommendationRow): List<MaItem> = distinctItems(
        MaParse.items(
            api.sendCommand("music/recommendations/items", buildJsonObject {
                put("provider", row.provider)
                put("item_id", row.itemId)
            }),
            serverUrl,
        )
    )

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

    /**
     * The episodes of a podcast, in the order Music Assistant returns them.
     *
     * Server-side this is an `AsyncGenerator[PodcastEpisode]`, which arrives over the
     * WebSocket in chunks — [MaApiClient] already accumulates those, so it reads here
     * like any other list command. Arguments are `item_id` +
     * `provider_instance_id_or_domain`, which is exactly what [itemRef] builds.
     */
    suspend fun podcastEpisodes(item: MaItem) =
        MaParse.items(api.sendCommand("music/podcasts/podcast_episodes", itemRef(item)), serverUrl)

    /**
     * The chapters of an audiobook.
     *
     * `music/audiobooks/audiobook_chapters` is not in the MA 2.10.0 API command
     * reference — neither the full (188-page) nor the partial (50-page) docs list it.
     * The closest command that does exist is `music/audiobooks/audiobook_versions`,
     * which returns alternative *copies* of the audiobook across providers, not
     * chapters — a different thing entirely. It is possible that chapters are only
     * available as part of the full audiobook object from `music/audiobooks/get`, or
     * that the command has not yet been exposed via the WebSocket API.
     *
     * An unknown command comes back null, which parses to an empty list — so the
     * worst case is an audiobook that opens empty, not a crash. Left as-is until a
     * live server confirms what the right call is.
     */
    suspend fun audiobookChapters(item: MaItem) =
        MaParse.items(api.sendCommand("music/audiobooks/audiobook_chapters", itemRef(item)), serverUrl)

    // --- versions ----------------------------------------------------------

    /**
     * Every copy of this track Music Assistant can find, across all providers.
     *
     * The one command in the API that speaks directly to why someone runs a local
     * library alongside streaming: the same song exists as a 16/44 stream, a 24/96
     * purchase and a CD rip, and MA already knows about all three. Without this the
     * app plays whichever one the library row happened to come from.
     */
    suspend fun trackVersions(item: MaItem): List<MaItem> =
        MaParse.items(api.sendCommand("music/tracks/track_versions", itemRef(item)), serverUrl)

    /** As [trackVersions], for a whole album. */
    suspend fun albumVersions(item: MaItem): List<MaItem> =
        MaParse.items(api.sendCommand("music/albums/album_versions", itemRef(item)), serverUrl)

    /** An artist's top tracks, aggregated across their providers. */
    suspend fun topTracks(item: MaItem): List<MaItem> =
        MaParse.items(api.sendCommand("music/artists/top_tracks", itemRef(item)), serverUrl)

    /** Artists MA's providers consider similar to this one. */
    suspend fun similarArtists(item: MaItem): List<MaItem> =
        MaParse.items(api.sendCommand("music/artists/similar_artists", itemRef(item)), serverUrl)

    // --- playlist CRUD (MA) ------------------------------------------------

    /**
     * Create a new playlist and return its library item.
     *
     * The command is `music/playlists/create_playlist` — not `.../create`, which
     * does not exist. `provider_instance_or_domain` is deliberately omitted so MA
     * picks the provider itself; note the name has no `_id_`, unlike the
     * `provider_instance_id_or_domain` every read command takes.
     */
    suspend fun createPlaylist(name: String): MaItem? {
        val res = api.sendCommand("music/playlists/create_playlist", buildJsonObject {
            put("name", name)
        })
        return MaParse.item(res, serverUrl)
    }

    /**
     * Delete a playlist from the library.
     *
     * `music/playlists/remove` is the library-record delete every media type
     * shares, and it takes a **bare `item_id`** — not the `item_id` +
     * `provider_instance_id_or_domain` pair [itemRef] builds for the read
     * commands. Sending the pair makes MA reject the call.
     */
    suspend fun deletePlaylist(item: MaItem) {
        api.sendCommand("music/playlists/remove", buildJsonObject {
            put("item_id", item.itemId)
        })
    }

    /**
     * Add tracks to an existing playlist.
     *
     * `uris` are MA item URIs, and [db_playlist_id] is the *library* id — a
     * provider-side id will not resolve. Returns once MA has queued the
     * background task, which is not the same as the tracks being visible.
     */
    suspend fun addPlaylistTracks(playlist: MaItem, uris: List<String>) {
        if (uris.isEmpty()) return
        api.sendCommand("music/playlists/add_playlist_tracks", buildJsonObject {
            put("db_playlist_id", playlist.itemId)
            put("uris", JsonArray(uris.map { JsonPrimitive(it) }))
        })
    }

    /**
     * Remove tracks from a playlist by their **provider playlist positions**,
     * not by item id — MA's parameter is `positions_to_remove`.
     */
    suspend fun removePlaylistTracks(playlist: MaItem, positions: List<Int>) {
        if (positions.isEmpty()) return
        api.sendCommand("music/playlists/remove_playlist_tracks", buildJsonObject {
            put("db_playlist_id", playlist.itemId)
            put("positions_to_remove", JsonArray(positions.map { JsonPrimitive(it) }))
        })
    }

    /**
     * Update a playlist's metadata (name, etc.) via `music/playlists/update`.
     *
     * The API takes `item_id` (the library playlist id) and `update` (a Playlist
     * object with the fields to change). It is an **ADMIN-only** command server-side
     * — a non-admin login is refused. Only the fields the caller sets are included in
     * the `update` object; everything else is left for the server to keep as-is.
     *
     * [name] is the common case — rename a playlist. The `update` object is built
     * minimally rather than sending a full Playlist, because the server treats absent
     * fields as "keep" and the app doesn't always have every field fresh.
     */
    suspend fun updatePlaylist(playlist: MaItem, name: String? = null) {
        val update = buildJsonObject {
            name?.let { put("name", it) }
        }
        api.sendCommand("music/playlists/update", buildJsonObject {
            put("item_id", playlist.itemId)
            put("update", update)
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
        "podcast" -> podcastEpisodes(item)
        "audiobook" -> audiobookChapters(item)
        else -> emptyList()
    }

    // --- players + playback ----------------------------------------------

    suspend fun players() = MaParse.players(api.sendCommand("players/all"), serverUrl)

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
     *
     * ## Cached, because it was a round trip on every tap
     *
     * This sits in front of [playOn], so it used to add a full WebSocket round trip
     * ahead of *every* play, enqueue and shuffle in the app — the answer being fetched
     * again each time to learn something that only changes when grouping changes. On a
     * phone that had let its socket doze, that round trip is also the one that waits out
     * the reconnect backoff, so the tap-to-sound delay was two serial round trips at
     * best and a reconnect plus two at worst.
     *
     * The answer is now held for [QUEUE_ID_TTL_MS]. That is short enough that a
     * grouping change made in another app is picked up within a few seconds, and long
     * enough that a burst of taps costs one lookup. [invalidateQueueId] drops it
     * immediately for the case the app already knows about — this phone's own grouping
     * changing underneath it.
     */
    suspend fun activeQueueId(playerId: String): String {
        val now = System.currentTimeMillis()
        if (queueIdServer != serverUrl) { queueIdCache.clear(); queueIdServer = serverUrl }
        queueIdCache[playerId]?.let { (id, at) -> if (now - at < QUEUE_ID_TTL_MS) return id }
        val res = runCatching {
            api.sendCommand("player_queues/get_active_queue", buildJsonObject { put("player_id", playerId) })
        }.getOrNull()?.jsonObject
        val id = res?.get("queue_id")?.jsonPrimitive?.contentOrNull ?: playerId
        // Only a real answer is cached. Falling back to the player id is what happens
        // when the socket is down, and caching that would keep the wrong id in place
        // for the whole TTL once it came back.
        if (res != null) queueIdCache[playerId] = id to now
        return id
    }

    /** Forget the cached queue id — call when this player's grouping changes. */
    fun invalidateQueueId(playerId: String? = null) {
        if (playerId == null) queueIdCache.clear() else queueIdCache.remove(playerId)
    }

    /** A single player's state, or null when the server doesn't know it. */
    suspend fun getPlayer(playerId: String): MaPlayer? {
        val res = runCatching {
            api.sendCommand("players/get", buildJsonObject {
                put("player_id", playerId); put("raise_unavailable", false)
            })
        }.getOrNull() ?: return null
        return MaParse.players(JsonArray(listOf(res)), serverUrl).firstOrNull()
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
     * is left exposed only for callers that already hold one. The optional parameters
     * below are passed straight through to [playMedia] — see its KDoc for what each
     * does and why they are nullable.
     */
    suspend fun playOn(
        playerId: String,
        uris: List<String>,
        option: String = "replace",
        radioMode: Boolean = false,
        startItem: String? = null,
        sortBy: String? = null,
        shuffle: Boolean? = null,
        startFromBeginning: Boolean? = null,
        user: String? = null,
    ) {
        // Ahead of the queue lookup, not just inside [playMedia]: that lookup is
        // itself a round trip, so a socket left to its backoff would be waited on
        // before the wake ever happened.
        onPlaybackRequested()
        playMedia(
            activeQueueId(playerId),
            uris,
            option,
            radioMode,
            startItem = startItem,
            sortBy = sortBy,
            shuffle = shuffle,
            startFromBeginning = startFromBeginning,
            user = user,
        )
    }

    /**
     * `player_queues/play_media`, argument-for-argument as the official Music
     * Assistant app sends it (`api/Request.kt: Player.play`).
     *
     * `radio_mode` is sent **explicitly**. Leaving it out is what broke enqueueing:
     * with the flag absent Music Assistant fell back to the queue's own radio setting,
     * and on a queue that already had items the added tracks were discarded in favour
     * of its generated ones — which is exactly the reported behaviour, that "add" only
     * worked when the queue was empty. It is a defaulted parameter server-side, so
     * omitting it looked harmless; it isn't. The flag is deprecated in 2.10 —
     * translated to a `radio_playlist://` dynamic playlist — but it still works and
     * existing callers depend on it, so it stays.
     *
     * The optional parameters below are MA 2.10.0 additions. All default to null so
     * they are omitted from the JSON unless a caller sets them — existing call sites
     * send exactly what they sent before.
     *
     * - [startItem] — "Optional item to start the playlist or album from." A URI
     *   string. This is the clean way to play an album from track N: send every track
     *   as `media` with `option = "replace"` and `start_item = <track N uri>`. Before
     *   this the app played the tapped track alone, appended the rest in a second
     *   `play_media` with `option = "add"`, then called `play` — three commands for
     *   what the API was designed to do in one.
     * - [sortBy] — "Optional sort key to order tracks before applying start_item."
     * - [shuffle] — "Play the media shuffled (or explicitly in order). Only applies to
     *   the options that start playing right away (play/replace), and never to a
     *   dynamic source." Omit to follow the queue's own shuffle setting.
     * - [startFromBeginning] — "Start a podcast episode at position 0, ignoring any
     *   saved resume position." The stored progress itself is left untouched.
     * - [user] — "Optional user_id or username of the user to execute this command on
     *   behalf of. Requires the users.impersonate scope when targeting another user."
     */
    suspend fun playMedia(
        playerId: String,
        uris: List<String>,
        option: String = "replace",
        radioMode: Boolean = false,
        startItem: String? = null,
        sortBy: String? = null,
        shuffle: Boolean? = null,
        startFromBeginning: Boolean? = null,
        user: String? = null,
    ) {
        // Every "play this" in the app funnels through here, which makes it the one
        // place worth telling this phone's player socket that music is wanted. Both
        // sockets die together when the phone dozes, and both come back on a backoff
        // that widens to fifteen seconds — so the command can land perfectly while
        // Music Assistant still has no player to stream to, and the song starts
        // whenever the retry timer happens to fire. Non-blocking, and a no-op while
        // the socket is up, which is the usual case.
        onPlaybackRequested()
        try {
            api.sendCommand("player_queues/play_media", buildJsonObject {
                put("media", JsonArray(uris.map { JsonPrimitive(it) }))
                put("option", option)
                put("radio_mode", radioMode)
                put("queue_id", playerId)
                startItem?.let { put("start_item", it) }
                sortBy?.let { put("sort_by", it) }
                shuffle?.let { put("shuffle", it) }
                startFromBeginning?.let { put("start_from_beginning", it) }
                user?.let { put("user", it) }
            })
            // After the command lands, not before: a play that the server refused has
            // not replaced anything, and freezing the bar for it would leave the
            // position of the track still playing stuck at zero.
            if (option == "replace") onQueueReplaced()
        } catch (e: MaApiException) {
            throw describePlayFailure(e, uris)
        }
    }

    /**
     * Say which of Music Assistant's two "media not found" failures this was.
     *
     * From 2.10 the server localises its errors, and `player_queues/play_media`
     * answers the same `MediaNotFoundError` — "The requested media item could not be
     * found." — for two failures that have nothing to do with each other:
     *
     *  - a uri it could not resolve, which is ours to fix; and
     *  - a track it resolved perfectly well and then could not get audio for. The
     *    resolve loop swallows a bad uri and ends with "there is nothing to play
     *    here", so this is in fact the *only* one of the two that reaches a client
     *    under the generic wording — `play_index` catches the load error, throws
     *    `MediaNotFoundError` with a message of its own, and the translation layer
     *    then replaces that message with the generic one. The real reason (the
     *    provider refused, the file has gone, ffmpeg failed) is left in the server
     *    log and nowhere else.
     *
     * Repeating "could not be found" to someone looking at the track in the app is
     * worse than useless — it points them at the library, which is the one thing that
     * is fine. So ask: `music/item_by_uri` is the same lookup `play_media` starts
     * with. If the item comes back, the uri was never the problem.
     *
     * Anything we can't establish leaves the server's own message alone: a probe that
     * fails because the socket went away must not be read as "the item is missing".
     */
    private suspend fun describePlayFailure(e: MaApiException, uris: List<String>): Exception {
        if (e.code != ERR_MEDIA_NOT_FOUND || e.isTransport) return e
        val uri = uris.firstOrNull() ?: return e
        val found = runCatching {
            api.sendCommand("music/item_by_uri", buildJsonObject { put("uri", uri) }, timeoutMs = 10_000)
        }.getOrNull() != null
        return playFailure(e, found)
    }

    suspend fun play(playerId: String) = cmd("play", playerId)
    suspend fun pause(playerId: String) = cmd("pause", playerId)
    suspend fun stop(playerId: String) = cmd("stop", playerId)
    suspend fun next(playerId: String) = cmd("next", playerId)
    suspend fun previous(playerId: String) = cmd("previous", playerId)

    /**
     * Toggle play/pause on a queue — the single `player_queues/play_pause` command.
     *
     * Music Assistant has a dedicated toggle that reads the queue's current state and
     * does the opposite, which is cheaper and less race-prone than the app checking
     * state and then sending `play` or `pause` — by the time the check comes back the
     * state can have moved (another client paused it, a notification action fired) and
     * the command built from the stale state does the wrong thing. [play] and [pause]
     * remain for callers that need a specific direction rather than a toggle.
     */
    suspend fun playPause(playerId: String) =
        api.sendCommand("player_queues/play_pause", buildJsonObject {
            put("queue_id", playerId)
        })

    suspend fun seek(playerId: String, positionSec: Int) =
        api.sendCommand("players/cmd/seek", buildJsonObject {
            put("player_id", playerId); put("position", positionSec.coerceAtLeast(0))
        })

    suspend fun setVolume(playerId: String, level: Int) =
        api.sendCommand("players/cmd/volume_set", buildJsonObject {
            put("player_id", playerId); put("volume_level", level.coerceIn(0, 100))
        })

    /**
     * Real mute, as opposed to setting the volume to zero and losing where it was.
     * MA restores the previous level on unmute, which volume_set cannot do.
     */
    suspend fun setMuted(playerId: String, muted: Boolean) =
        api.sendCommand("players/cmd/volume_mute", buildJsonObject {
            put("player_id", playerId); put("muted", muted)
        })

    suspend fun setShuffle(queueId: String, enabled: Boolean) =
        api.sendCommand("player_queues/shuffle", buildJsonObject {
            put("queue_id", queueId); put("shuffle_enabled", enabled)
        })

    /**
     * Shuffle on whatever queue [playerId] is actually using — the [playOn] of shuffle.
     *
     * Every "shuffle this album/artist/playlist" button was handing [setShuffle] a
     * *player* id. On an ungrouped player the two ids happen to be equal, so it worked;
     * on a synced player they are not, and the flag landed on a queue nobody was
     * listening to while the music played on in order. Same trap [playOn] exists to
     * close, and the reason the KDoc at the top of this file says to route through it.
     */
    suspend fun setShuffleOn(playerId: String, enabled: Boolean) =
        setShuffle(activeQueueId(playerId), enabled)

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

    /** Move a queue item to the end of the queue. */
    suspend fun moveQueueItemEnd(queueId: String, itemId: String) =
        api.sendCommand("player_queues/move_item_end", buildJsonObject {
            put("queue_id", queueId); put("queue_item_id", itemId)
        })

    /** Save the current queue as a playlist. */
    suspend fun saveQueueAsPlaylist(queueId: String, name: String) =
        api.sendCommand("player_queues/save_as_playlist", buildJsonObject {
            put("queue_id", queueId); put("name", name)
        })

    /**
     * Get a single queue's full state by queue_id, or null if not found.
     * Lighter than `player_queues/all` when only one queue is needed.
     */
    suspend fun getQueue(queueId: String) =
        api.sendCommand("player_queues/get", buildJsonObject { put("queue_id", queueId) })

    /** Seek to a position (seconds) within the current track on the given queue. */
    suspend fun seekQueue(queueId: String, positionSec: Int) =
        api.sendCommand("player_queues/seek", buildJsonObject {
            put("queue_id", queueId); put("position", positionSec.coerceAtLeast(0))
        })

    /** Stop playback on the given queue. */
    suspend fun stopQueue(queueId: String) =
        api.sendCommand("player_queues/stop", buildJsonObject { put("queue_id", queueId) })

    /** Pause playback on the given queue. */
    suspend fun pauseQueue(queueId: String) =
        api.sendCommand("player_queues/pause", buildJsonObject { put("queue_id", queueId) })

    /** Play (start) playback on the given queue. */
    suspend fun playQueue(queueId: String) =
        api.sendCommand("player_queues/play", buildJsonObject { put("queue_id", queueId) })

    /** Resume playback on the given queue, optionally with a fade-in. */
    suspend fun resumeQueue(queueId: String, fadeIn: Boolean? = null) =
        api.sendCommand("player_queues/resume", buildJsonObject {
            put("queue_id", queueId)
            fadeIn?.let { put("fade_in", it) }
        })

    /** Skip forward/backward by [seconds] in the current track (negative = back). */
    suspend fun skipQueue(queueId: String, seconds: Int) =
        api.sendCommand("player_queues/skip", buildJsonObject {
            put("queue_id", queueId); put("seconds", seconds)
        })

    /** Next track on the given queue. */
    suspend fun nextTrack(queueId: String) =
        api.sendCommand("player_queues/next", buildJsonObject { put("queue_id", queueId) })

    /** Previous track on the given queue. */
    suspend fun previousTrack(queueId: String) =
        api.sendCommand("player_queues/previous", buildJsonObject { put("queue_id", queueId) })

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

    // --- play status (playlog) ---------------------------------------------

    /**
     * Mark a media item as played in the server's playlog via `music/mark_played`.
     *
     * The API takes `media_item` as a full media-item object (Artist, Album, Track,
     * etc.) or — per the Swagger schema — a bare URI string. The URI is what the app
     * can always produce, so it is used here for the same reason as [addFavorite]:
     * the server resolves it back to the item itself.
     *
     * All parameters except [item] are optional and only sent when non-null:
     * - [fullyPlayed] — "If True, mark the item as fully played."
     * - [secondsPlayed] — "The number of seconds played."
     * - [isPlaying] — "If True, the item is currently playing."
     * - [userid] — "The user ID to mark the item as played for (instead of the
     *   current user)."
     * - [queueId] — "The queue ID where the item was played."
     * - [userInitiated] — "If True, the playback was initiated by the user (e.g.
     *   enqueued)."
     */
    suspend fun markPlayed(
        item: MaItem,
        fullyPlayed: Boolean? = null,
        secondsPlayed: Int? = null,
        isPlaying: Boolean? = null,
        userid: String? = null,
        queueId: String? = null,
        userInitiated: Boolean? = null,
    ) {
        val uri = item.uri ?: "${item.mediaType}://${item.provider}/${item.itemId}"
        api.sendCommand("music/mark_played", buildJsonObject {
            put("media_item", uri)
            fullyPlayed?.let { put("fully_played", it) }
            secondsPlayed?.let { put("seconds_played", it) }
            isPlaying?.let { put("is_playing", it) }
            userid?.let { put("userid", it) }
            queueId?.let { put("queue_id", it) }
            userInitiated?.let { put("user_initiated", it) }
        })
    }

    /**
     * Mark a media item as unplayed in the server's playlog via
     * `music/mark_unplayed`.
     *
     * The API takes `media_item` (same shape as [markPlayed]) and an optional
     * `userid` to act on behalf of a different user. The URI form is used for the
     * same reason as [markPlayed] and [addFavorite].
     */
    suspend fun markUnplayed(item: MaItem, userid: String? = null) {
        val uri = item.uri ?: "${item.mediaType}://${item.provider}/${item.itemId}"
        api.sendCommand("music/mark_unplayed", buildJsonObject {
            put("media_item", uri)
            userid?.let { put("userid", it) }
        })
    }

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

    /** Volume up by one step on the given player. */
    suspend fun volumeUp(playerId: String) = cmd("volume_up", playerId)

    /** Volume down by one step on the given player. */
    suspend fun volumeDown(playerId: String) = cmd("volume_down", playerId)

    /** Resume (or restart) playback on the given player, optionally with a source or media. */
    suspend fun resumePlayer(playerId: String) = cmd("resume", playerId)

    /** Toggle play/pause on the given player (not queue — the player-level toggle). */
    suspend fun playPausePlayer(playerId: String) = cmd("play_pause", playerId)

    /**
     * Play an announcement (TTS/chime URL) on the given player.
     * [preAnnounce] = play a chime before the announcement. [volumeLevel] = override
     * volume for the announcement only. [preAnnounceUrl] = custom pre-announce chime.
     */
    suspend fun playAnnouncement(
        playerId: String,
        url: String,
        preAnnounce: Boolean? = null,
        volumeLevel: Int? = null,
        preAnnounceUrl: String? = null,
    ) = api.sendCommand("players/cmd/play_announcement", buildJsonObject {
        put("player_id", playerId); put("url", url)
        preAnnounce?.let { put("pre_announce", it) }
        volumeLevel?.let { put("volume_level", it.coerceIn(0, 100)) }
        preAnnounceUrl?.let { put("pre_announce_url", it) }
    })

    /** Add the currently playing item on the given player to favorites. */
    suspend fun addCurrentlyPlayingToFavorites(playerId: String) =
        api.sendCommand("players/add_currently_playing_to_favorites", buildJsonObject {
            put("player_id", playerId)
        })

    /** Find a player by its display name (case-insensitive, exact match server-side). */
    suspend fun getPlayerByName(name: String) =
        api.sendCommand("players/get_by_name", buildJsonObject { put("name", name) })

    /**
     * Create a permanent group player (ADMIN). [members] = child player IDs.
     * [dynamic] = members can change at runtime.
     */
    suspend fun createGroupPlayer(provider: String, name: String, members: List<String>, dynamic: Boolean? = null) =
        api.sendCommand("players/create_group_player", buildJsonObject {
            put("provider", provider); put("name", name)
            put("members", JsonArray(members.map { JsonPrimitive(it) }))
            dynamic?.let { put("dynamic", it) }
        })

    /** Remove a player permanently (ADMIN). */
    suspend fun removePlayer(playerId: String) =
        api.sendCommand("players/remove", buildJsonObject { put("player_id", playerId) })

    // --- library counts ----------------------------------------------------

    /** Total track count in the library, optionally filtered. */
    suspend fun trackCount(favoriteOnly: Boolean? = null) =
        api.sendCommand("music/tracks/count", buildJsonObject {
            favoriteOnly?.let { put("favorite_only", it) }
        })?.jsonPrimitive?.intOrNull ?: 0

    /** Total album count in the library. */
    suspend fun albumCount(favoriteOnly: Boolean? = null) =
        api.sendCommand("music/albums/count", buildJsonObject {
            favoriteOnly?.let { put("favorite_only", it) }
        })?.jsonPrimitive?.intOrNull ?: 0

    /** Total artist count in the library. */
    suspend fun artistCount(favoriteOnly: Boolean? = null, albumArtistsOnly: Boolean? = null) =
        api.sendCommand("music/artists/count", buildJsonObject {
            favoriteOnly?.let { put("favorite_only", it) }
            albumArtistsOnly?.let { put("album_artists_only", it) }
        })?.jsonPrimitive?.intOrNull ?: 0

    /** Total playlist count in the library. */
    suspend fun playlistCount() =
        api.sendCommand("music/playlists/count", buildJsonObject {})?.jsonPrimitive?.intOrNull ?: 0

    /** Total podcast count in the library. */
    suspend fun podcastCount(favoriteOnly: Boolean? = null) =
        api.sendCommand("music/podcasts/count", buildJsonObject {
            favoriteOnly?.let { put("favorite_only", it) }
        })?.jsonPrimitive?.intOrNull ?: 0

    /** Total radio station count in the library. */
    suspend fun radioCount() =
        api.sendCommand("music/radios/count", buildJsonObject {})?.jsonPrimitive?.intOrNull ?: 0

    // --- genres ------------------------------------------------------------

    /** Browse genres in the library, paginated. */
    suspend fun genres(offset: Int = 0, limit: Int = PAGE_SIZE, favorite: Boolean? = null, search: String? = null) =
        MaParse.items(
            api.sendCommand("music/genres/library_items", buildJsonObject {
                put("limit", limit); put("offset", offset)
                favorite?.let { put("favorite", it) }
                search?.let { put("search", it) }
            }),
            serverUrl,
        )

    /** All tracks for a given genre. */
    suspend fun genreTracks(genreItem: MaItem) =
        MaParse.items(api.sendCommand("music/genres/tracks", itemRef(genreItem)), serverUrl)

    // --- browse & item lookup ---------------------------------------------

    /**
     * Browse the MA music provider tree from a path string (e.g. "spotify/browse/your-music").
     * Returns a mixed list of browse folders and media items.
     */
    suspend fun browse(path: String? = null) =
        MaParse.items(api.sendCommand("music/browse", buildJsonObject {
            path?.let { put("path", it) }
        }), serverUrl)

    /**
     * Get a single media item by its media_type, item_id, and provider.
     * More general than `music/tracks/get` — works for any media type.
     */
    suspend fun item(mediaType: String, itemId: String, provider: String, allowUpdateMetadata: Boolean? = null) =
        MaParse.item(api.sendCommand("music/item", buildJsonObject {
            put("media_type", mediaType)
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
            allowUpdateMetadata?.let { put("allow_update_metadata", it) }
        }), serverUrl)

    /** Get a track by its name, optionally with artist and album for disambiguation. */
    suspend fun trackByName(trackName: String, artistName: String? = null, albumName: String? = null, trackVersion: String? = null) =
        MaParse.item(api.sendCommand("music/track_by_name", buildJsonObject {
            put("track_name", trackName)
            artistName?.let { put("artist_name", it) }
            albumName?.let { put("album_name", it) }
            trackVersion?.let { put("track_version", it) }
        }), serverUrl)

    /** All albums a track appears on (compilations, reissues, etc.). */
    suspend fun trackAlbums(item: MaItem) =
        MaParse.items(api.sendCommand("music/tracks/track_albums", itemRef(item)), serverUrl)

    /** Top/featured albums for an artist (across all their providers, deduplicated). */
    suspend fun artistTopAlbums(item: MaItem, providerFilter: String? = null) =
        MaParse.items(api.sendCommand("music/artists/top_albums", buildJsonObject {
            put("item_id", item.itemId)
            put("provider_instance_id_or_domain", item.provider)
            providerFilter?.let { put("provider_filter", it) }
        }), serverUrl)

    /** Full details for a single podcast. */
    suspend fun getPodcast(item: MaItem) =
        MaParse.item(api.sendCommand("music/podcasts/get", itemRef(item)), serverUrl)

    /** A single podcast episode by its provider podcast id and episode id. */
    suspend fun podcastEpisode(podcastItem: MaItem, episodeId: String) =
        MaParse.item(api.sendCommand("music/podcasts/podcast_episode", buildJsonObject {
            put("item_id", episodeId)
            put("provider_instance_id_or_domain", podcastItem.provider)
        }), serverUrl)

    /** Full details for a single radio station. */
    suspend fun getRadio(item: MaItem) =
        MaParse.item(api.sendCommand("music/radios/get", itemRef(item)), serverUrl)

    /** All versions of a radio station across providers. */
    suspend fun radioVersions(item: MaItem) =
        MaParse.items(api.sendCommand("music/radios/radio_versions", itemRef(item)), serverUrl)

    /** Full details for a single playlist (more fields than library_items). */
    suspend fun getPlaylist(item: MaItem) =
        MaParse.item(api.sendCommand("music/playlists/get", itemRef(item)), serverUrl)

    /** Export a playlist to M3U8 format. Returns the M3U8 string. */
    suspend fun exportPlaylist(playlistItemId: String): String? =
        api.sendCommand("music/playlists/export_playlist", buildJsonObject {
            put("db_playlist_id", playlistItemId)
        })?.jsonPrimitive?.contentOrNull

    /**
     * Import a playlist from M3U8 data, creating a new builtin playlist.
     * [libraryMatching] = attempt to find tracks by searching providers using metadata.
     */
    suspend fun importPlaylist(m3uData: String, libraryMatching: Boolean? = null) =
        MaParse.item(api.sendCommand("music/playlists/import_playlist", buildJsonObject {
            put("m3u_data", m3uData)
            libraryMatching?.let { put("library_matching", it) }
        }), serverUrl)

    /**
     * Refresh a media item's metadata by requesting its full object or searching
     * for substitutes. Best-effort — the server may not find a replacement.
     */
    suspend fun refreshItem(item: MaItem) =
        MaParse.item(api.sendCommand("music/refresh_item", itemRef(item)), serverUrl)

    /**
     * Trigger a music provider sync (background task). [mediaTypes] = only sync
     * these types (None = all). [providers] = only sync these provider instances
     * (None = all).
     */
    suspend fun syncProviders(mediaTypes: List<String>? = null, providers: List<String>? = null) =
        api.sendCommand("music/sync", buildJsonObject {
            mediaTypes?.let { put("media_types", JsonArray(it.map { JsonPrimitive(it) })) }
            providers?.let { put("providers", JsonArray(it.map { JsonPrimitive(it) })) }
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
     * The per-player sync delay, from the player config.
     *
     * The key varies (plain `sendspin_sync_delay` or a protocol-wrapped
     * `<sub>||protocol||sendspin_sync_delay`), so match by suffix and carry the exact
     * key back for the save.
     *
     * `sendspin_sync_delay` only exists on Sendspin-protocol players. A Sonos,
     * Chromecast, AirPlay, Snapcast or Squeezelite member has no such entry, and this
     * used to return null for all of them — which the speakers screen rendered as a
     * dash between two buttons that then did nothing. Music Assistant exposes a
     * generic [SYNC_KEYS] equivalent on most of those, so they are tried in turn
     * before giving up.
     */
    suspend fun getSyncDelay(playerId: String): SyncDelay? {
        val res = api.sendCommand("config/players/get", buildJsonObject { put("player_id", playerId) })
            ?.jsonObject ?: return null
        val values = res["values"]?.jsonObject ?: return null
        val key = SYNC_KEYS.firstNotNullOfOrNull { suffix ->
            values.keys.firstOrNull { it.endsWith(suffix) }
        } ?: return null
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
     * Point MA's own per-player format preference at [codec] (`"automatic"` for none).
     *
     * MA's own per-player preference for what Sendspin should stream, kept in step
     * with the codec this client advertises so the two can't disagree. Best-effort:
     * the caller doesn't depend on the result, because the advertised format list
     * already decides what actually arrives.
     *
     * The value is **not** a bare codec name. `preferred_sendspin_format` is a
     * `ConfigEntry` whose `options` the server declares, so a value that isn't one of
     * them is rejected and the whole save is a silent no-op — which is exactly what
     * this did for every codec, because it matched them as `flac_48000_16`. Music
     * Assistant writes them as `codec:rate:depth:channels` (`flac:48000:24:2`), and
     * has for as long as the key has existed, so nothing but `"automatic"` ever
     * matched and the codec setting never reached the server. See
     * [matchFormatOption], which is where the shape is now decided.
     *
     * Returns the option actually written, or null when the server has no such key
     * (older builds, or a player whose Sendspin role isn't connected — the options are
     * built from the client's *live* advertised formats) or offers nothing matching.
     */
    suspend fun setPreferredSendspinFormat(playerId: String, codec: String): String? {
        val entry = playerConfigEntry(playerId, "preferred_sendspin_format") ?: return null
        val options = (entry["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val chosen = matchFormatOption(options, if (codec == "auto") "automatic" else codec)
            ?: return null
        api.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject { put("preferred_sendspin_format", chosen) })
        })
        return chosen
    }

    // --- per-player playback config (gapless, crossfade, …) ----------------

    /**
     * Every `ConfigEntry` Music Assistant declares for a player, as it declares it.
     *
     * Read rather than assumed. MA owns these settings and their spelling has moved
     * between versions — `crossfade` has been a boolean and a three-way mode, and the
     * keys arrive protocol-wrapped on some builds — so hard-coding a list here is a
     * guess that goes stale. The server states its own label, description, type and
     * permitted options for each one, and the UI renders whatever it is given. A
     * server that has never heard of a setting simply doesn't list it, and nothing has
     * to know why.
     */
    suspend fun playerConfigEntries(playerId: String): List<MaConfigEntry> {
        val res = api.sendCommand("config/players/get", buildJsonObject { put("player_id", playerId) })
            ?.jsonObject ?: return emptyList()
        val values = res["values"] as? JsonObject ?: return emptyList()
        return values.mapNotNull { (key, raw) -> MaParse.configEntry(key, raw as? JsonObject ?: return@mapNotNull null) }
    }

    /**
     * Write one config value back.
     *
     * **Admin-only on the server side**, like every other `config/players/save` — a
     * non-admin login is refused every time, which is the first thing to rule out when
     * a setting won't stick. The exception carries the server's own message rather
     * than being swallowed, because "it didn't save" and "it saved and did nothing"
     * are the two answers a user needs told apart.
     */
    suspend fun savePlayerConfigValue(playerId: String, key: String, value: JsonElement) {
        api.sendCommand("config/players/save", buildJsonObject {
            put("player_id", playerId)
            put("values", buildJsonObject { put(key, value) })
        })
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
     * The DSP configuration for [playerId], or null when the player has none set.
     *
     * **Throws** [MaApiException] rather than swallowing it, unlike most reads here.
     * A refusal and an unconfigured player are different answers, and collapsing both
     * to null is what let "the EQ does nothing" go undiagnosed: the caller turned null
     * into a default config, so a rejected request rendered as a clean, empty,
     * entirely believable equaliser. Null now means only what it says.
     *
     * `as? JsonObject`, not `.jsonObject` — the latter *throws* on an unexpected
     * shape, which is the crash [MaParse.str] documents.
     */
    suspend fun getDspConfig(playerId: String): DspConfig? {
        val res = api.sendCommand("config/players/dsp/get", buildJsonObject {
            put("player_id", playerId)
        })
        return (res as? JsonObject)?.let { DspParse.config(it) }
    }

    /**
     * Save a DSP config for [playerId]. **Admin-only on the server side** — a
     * non-admin login is refused every time, which is the first thing to rule out
     * when the equaliser is inaudible.
     *
     * Throws [MaApiException] carrying the server's own message and error code; see
     * [getDspConfig] for why this one doesn't swallow.
     */
    suspend fun saveDspConfig(playerId: String, config: DspConfig): DspConfig? {
        val res = api.sendCommand("config/players/dsp/save", buildJsonObject {
            put("player_id", playerId)
            put("config", DspParse.toJson(config))
        })
        return (res as? JsonObject)?.let { DspParse.config(it) }
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
     *
     * The parameters below are MA 2.10.0 additions, all nullable and omitted from
     * the JSON unless set — existing callers send exactly what they sent before:
     * - [provider] — "Filter by provider instance ID (single string or list)."
     * - [genre] — "Filter by genre id(s)."
     * - [playedOnly] — "Filter to only played tracks."
     * - [explicit] — "Filter by explicit content (True=only explicit, False=no
     *   explicit, None=all)."
     * - [summary] — "When True (default), return slim summary items containing only
     *   the fields needed for a list view. Set to False to get fully hydrated items."
     * - [reachableVia] — "Restrict results to items with a provider mapping
     *   reachable through one of these provider instance ids (OR semantics)."
     * - [user] — "Optional user_id or username of the user to execute this command
     *   on behalf of. Requires the users.impersonate scope when targeting another
     *   user."
     */
    private fun libraryArgs(
        offset: Int,
        limit: Int,
        favorite: Boolean? = null,
        orderBy: String? = null,
        search: String? = null,
        provider: String? = null,
        genre: Int? = null,
        playedOnly: Boolean? = null,
        explicit: Boolean? = null,
        summary: Boolean? = null,
        reachableVia: List<String>? = null,
        user: String? = null,
    ) = buildJsonObject {
        put("limit", limit); put("offset", offset)
        favorite?.let { put("favorite", it) }
        orderBy?.let { put("order_by", it) }
        search?.let { put("search", it) }
        provider?.let { put("provider", it) }
        genre?.let { put("genre", it) }
        playedOnly?.let { put("played_only", it) }
        explicit?.let { put("explicit", it) }
        summary?.let { put("summary", it) }
        reachableVia?.let { put("reachable_via", JsonArray(it.map { JsonPrimitive(it) })) }
        user?.let { put("user", it) }
    }

    private fun itemRef(item: MaItem) = buildJsonObject {
        put("item_id", item.itemId)
        put("provider_instance_id_or_domain", item.provider)
    }
}
