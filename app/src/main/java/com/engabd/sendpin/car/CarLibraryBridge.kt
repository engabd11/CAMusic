package com.engabd.sendpin.car

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.MaSearchResults
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns configured library servers into Android Auto's browse tree, and turns a tap
 * back into playback — the one place that does both, so a mediaId means the same
 * thing on the way out (`children`/`item`) as it does on the way back in (`play`).
 *
 * Deliberately not [com.engabd.sendpin.ma.LibraryViewModel]: that's Activity-scoped
 * and its `play()` targets [ServerConfig.OPT_TARGET_PLAYER] — whichever remote MA
 * speaker is selected on the phone's Speakers screen. This class always targets
 * *this phone* (see [play]); reusing `LibraryViewModel` unmodified would let a track
 * tapped in the car start playing on a speaker elsewhere in the house.
 *
 * **What the driver sees is [CarBrowseOptions]' to decide**, not this class's: which
 * libraries appear and in what order, which shelves each one offers, how many items
 * a shelf loads, whether rows are covers or names, whether artists are circles,
 * whether the shelves are headed, and whether a single library gets a folder of its
 * own at all. Every one of those is a setting under Settings › Driving & Android
 * Auto › Android Auto, and every default here is the tree as it shipped.
 *
 * No `MediaItem` this class returns ever carries a credentialed URL. Subsonic,
 * Jellyfin, Emby and Plex all put an account secret in their stream *and* cover
 * URLs, and `CarMediaLibraryService` is `exported="true"` for Android Auto to bind
 * to. Streams are resolved at play time inside this process; covers go out as
 * opaque `content://` URIs that [CarArtworkProvider] resolves — see [CarArtwork].
 */
@OptIn(UnstableApi::class)
class CarLibraryBridge(private val app: SendpinApp) {

    private val settings = AppSettings(app)
    private val maRepo = MaRepository(app.maApi)

    // Concurrent because [sourceFor] runs on whichever thread the MediaLibraryService
    // callback landed on, and two browse requests for the same server can be in flight
    // at once. A plain HashMap resized under that is a corrupted map, not a lost entry.
    private val sourceCache = ConcurrentHashMap<String, MusicSource>()

    /**
     * What a browsed folder last returned, keyed by its media id.
     *
     * Not an optimisation so much as a correctness fix that pays for itself twice.
     * `onGetChildren` is called **once per page** — a browser that asks for fifty
     * items twenty at a time called this three times, and each call re-ran the whole
     * network fetch and then threw away everything outside its slice. In a car that
     * is three round trips to a home server for one screen, over whatever signal the
     * phone has at the time, and the three answers need not even agree with each
     * other.
     *
     * Short-lived and never negative: an empty answer is not cached, so a shelf that
     * failed because the Wi-Fi dropped out of range of the house retries the moment
     * the driver taps it again.
     */
    private val childCache = ConcurrentHashMap<String, CachedChildren>()

    /**
     * The edge length the browser wants artwork at, from its root hints.
     *
     * Android Auto states its own figure and it varies by head unit; asking a home
     * server for a 1000 px cover to draw it at 240 is bandwidth spent on a mobile
     * connection for nothing. [CarArtworkProvider.DEFAULT_PX] until a browser says
     * otherwise.
     */
    @Volatile
    private var artworkPx: Int = CarArtworkProvider.DEFAULT_PX

    fun setArtworkSizeHint(px: Int) {
        if (px > 0) artworkPx = px.coerceIn(CarArtworkProvider.MIN_PX, CarArtworkProvider.MAX_PX)
    }

    /**
     * Drop everything remembered about the tree.
     *
     * Called when the appearance settings change under a car that is already plugged
     * in — every cached row was built with the old options, down to whether it has a
     * cover on it. See [CarMediaLibraryService].
     */
    fun invalidate() {
        childCache.clear()
        cachedSearch = null
    }

    // ── Root / browse tree ──────────────────────────────────────────────────

    suspend fun children(parentId: String, rootChildrenLimit: Int): List<MediaItem> {
        val options = settings.carBrowseOptionsNow()
        return when (parentId) {
            CarMediaId.ROOT -> rootChildren(options, rootChildrenLimit)
            CarMediaId.MORE -> {
                val overflow = options.splitForRoot(visibleLibraries(options), rootChildrenLimit).second
                overflow.map { libraryItem(it, options) }
            }
            else -> when (val id = CarMediaId.parse(parentId)) {
                is CarMediaId.Server -> shelvesFor(id.serverId, options)
                is CarMediaId.Shelf -> shelfChildren(id, options)
                is CarMediaId.Item -> itemChildren(id, options).map { it.toMediaItem(id.serverId, options) }
                is CarMediaId.Message, null -> emptyList()
            }
        }
    }

    /**
     * The car's first screen.
     *
     * Three shapes, in order of how common the setup is. One library and
     * [CarBrowseOptions.flattenSingleLibrary] on — which is almost everybody —
     * hoists that library's shelves to the root, so the first tap in the car is
     * "Recently added" rather than the name of the only server there is. Several
     * libraries get a folder each, capped by what the browser said it can draw, with
     * the rest behind "More libraries". None at all gets a sentence explaining that,
     * rather than a blank screen.
     */
    private suspend fun rootChildren(options: CarBrowseOptions, limit: Int): List<MediaItem> {
        val libraries = visibleLibraries(options)
        if (libraries.isEmpty()) return listOf(noLibrariesItem())
        if (options.flattenSingleLibrary && libraries.size == 1) {
            return shelvesFor(libraries.first().id, options)
        }
        val (shown, overflow) = options.splitForRoot(libraries, limit)
        return shown.map { libraryItem(it, options) } +
            if (overflow.isNotEmpty()) listOf(moreFolderItem(options)) else emptyList()
    }

    /**
     * One node, resolved from its id alone.
     *
     * Every node the tree hands out has to be answerable here, not just the two that
     * were — a browser is free to resolve any id it holds without having browsed to it,
     * and an unanswered one comes back as an error rather than as a row.
     */
    suspend fun item(mediaId: String): MediaItem? {
        val options = settings.carBrowseOptionsNow()
        if (mediaId == CarMediaId.ROOT) return rootItem(options)
        if (mediaId == CarMediaId.MORE) return moreFolderItem(options)
        return when (val id = CarMediaId.parse(mediaId)) {
            is CarMediaId.Server -> configFor(id.serverId)?.let { libraryItem(it, options) }
            is CarMediaId.Shelf -> shelfItem(id, options)
            is CarMediaId.Item -> resolve(id).toMediaItem(id.serverId, options)
            is CarMediaId.Message -> messageItem(id.title, id.subtitle)
            null -> null
        }
    }

    private suspend fun shelfItem(id: CarMediaId.Shelf, options: CarBrowseOptions): MediaItem? {
        val config = configFor(id.serverId) ?: return null
        val shelf = options.shelves(CarShelf.offeredBy(abilitiesOf(config))).firstOrNull { it.key == id.key }
            ?: return null
        return shelfMediaItem(id.serverId, shelf, options)
    }

    /**
     * The library item behind an id, with its metadata — as opposed to
     * [toPlaceholderItem], which is only ever enough to *address* it.
     *
     * The placeholder cannot stand in for the real thing here. It has no name, and its
     * `uri` is null for a local-source item by construction (that is the whole point of
     * [CarMediaId.Item.uri]), which makes [MaItem.playable] read false for a track that
     * plainly is — so a browser resolving a track this way was told it could not play
     * it. A track is re-read; a container falls back to the placeholder with its uri
     * restored, which is all [MaItem.playable] and [MaItem.browsable] need.
     */
    private suspend fun resolve(id: CarMediaId.Item): MaItem {
        val placeholder = id.toPlaceholderItem()
        if (id.mediaType == "track" && MusicSources.isLocalProvider(id.provider)) {
            sourceFor(id.serverId)?.song(id.itemId)?.let { return it }
        }
        return if (placeholder.uri != null) placeholder else placeholder.copy(uri = id.itemId)
    }

    /**
     * The browse root, carrying the app-wide content style the driver chose.
     *
     * Android Auto reads the style hints on the root as the default for every row
     * that does not override them, which is what makes "Compact list" mean the whole
     * tree rather than the shelves this app happened to remember to tag.
     */
    private fun rootItem(options: CarBrowseOptions): MediaItem = MediaItem.Builder()
        .setMediaId(CarMediaId.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("CAMusic")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setExtras(defaultStyleExtras(options))
                .build(),
        )
        .build()

    /**
     * The same defaults again, as [LibraryParams].
     *
     * Both are needed and neither is redundant. media3's legacy path — which is what
     * Android Auto is — turns these params into the `BrowserRoot` extras bundle,
     * which is where the platform documents the app-wide content style as living;
     * the root `MediaItem`'s own extras are what a modern `MediaBrowser` reads. The
     * same bundle, offered twice, because two generations of browser look in two
     * places for it.
     */
    private fun rootParams(options: CarBrowseOptions): LibraryParams =
        LibraryParams.Builder().setExtras(defaultStyleExtras(options)).build()

    /** The root and its params together, so a caller reads the settings once. */
    suspend fun rootResult(): Pair<MediaItem, LibraryParams> {
        val options = settings.carBrowseOptionsNow()
        return rootItem(options) to rootParams(options)
    }

    /**
     * The app-wide default: how a folder row looks, and how a track row looks, for
     * everything below the root that says nothing of its own.
     */
    private fun defaultStyleExtras(options: CarBrowseOptions): Bundle = CarContentStyle.extras(
        browsableChildren = options.folderStyle(),
        playableChildren = options.itemStyle("track"),
    )

    private suspend fun visibleLibraries(options: CarBrowseOptions): List<ServerConfig> =
        options.libraries(settings.servers.first()) { it.id }

    private suspend fun shelvesFor(serverId: String, options: CarBrowseOptions): List<MediaItem> {
        val config = configFor(serverId) ?: return listOf(noLibrariesItem())
        val shelves = options.shelves(CarShelf.offeredBy(abilitiesOf(config)))
        if (shelves.isEmpty()) return listOf(messageItem("Nothing to show", "This library offers no shelves"))
        return shelves.map { shelfMediaItem(serverId, it, options) }
    }

    /** What a configured library can actually fill a shelf from. See [CarShelf.offeredBy]. */
    private suspend fun abilitiesOf(config: ServerConfig): CarLibraryAbilities {
        if (config.kind == ServerKind.MUSIC_ASSISTANT) {
            return CarLibraryAbilities(musicAssistant = true, favourites = true, playlists = true)
        }
        val source = sourceFor(config.id)
        return CarLibraryAbilities(
            musicAssistant = false,
            favourites = source?.has(Capability.FAVORITES) == true,
            playlists = source?.has(Capability.PLAYLIST_READ) == true,
        )
    }

    private suspend fun shelfChildren(id: CarMediaId.Shelf, options: CarBrowseOptions): List<MediaItem> {
        val items = cached(id.encode()) { shelfItems(id, options) }
        if (items.isEmpty()) {
            val shelf = CarShelf.byKey(id.key)
            return listOf(messageItem("Nothing here yet", shelf?.title?.let { "$it is empty" }))
        }
        return items.map { it.toMediaItem(id.serverId, options) }
    }

    private suspend fun shelfItems(id: CarMediaId.Shelf, options: CarBrowseOptions): List<MaItem> = runCatching {
        val config = configFor(id.serverId) ?: return@runCatching emptyList<MaItem>()
        val limit = options.shelfItemLimit
        val trackLimit = options.trackItemLimit
        withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
            if (config.kind == ServerKind.MUSIC_ASSISTANT) {
                if (!maReady(config)) return@withTimeoutOrNull emptyList<MaItem>()
                when (id.key) {
                    CarShelf.RECENTLY_ADDED.key -> maRepo.recentlyAdded(limit)
                    CarShelf.RECENTLY_PLAYED.key -> maRepo.recentlyPlayed(limit)
                    CarShelf.FAVOURITE_ALBUMS.key -> maRepo.favoriteAlbums(limit)
                    CarShelf.FAVOURITE_ARTISTS.key -> maRepo.favoriteArtists(limit)
                    CarShelf.FAVOURITE_PLAYLISTS.key -> maRepo.favoritePlaylists(limit)
                    CarShelf.FAVOURITE_TRACKS.key -> maRepo.favoriteTracks(trackLimit)
                    else -> emptyList()
                }
            } else {
                val source = sourceFor(id.serverId) ?: return@withTimeoutOrNull emptyList<MaItem>()
                when (id.key) {
                    CarShelf.RECENTLY_ADDED.key -> source.recentlyAdded(limit)
                    CarShelf.ARTISTS.key -> source.artists().take(limit)
                    CarShelf.ALBUMS.key -> source.albums(limit = limit)
                    CarShelf.PLAYLISTS.key -> source.playlists().take(limit)
                    CarShelf.FAVOURITE_ALBUMS.key -> source.favorites().albums.take(limit)
                    CarShelf.FAVOURITE_ARTISTS.key -> source.favorites().artists.take(limit)
                    CarShelf.FAVOURITE_PLAYLISTS.key -> source.favorites().playlists.take(limit)
                    CarShelf.FAVOURITE_TRACKS.key -> source.favorites().tracks.take(trackLimit)
                    else -> emptyList()
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList<MaItem>())

    private suspend fun itemChildren(id: CarMediaId.Item, options: CarBrowseOptions): List<MaItem> =
        cached(id.encode()) {
            runCatching {
                val placeholder = id.toPlaceholderItem()
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    if (MusicSources.isLocalProvider(id.provider)) {
                        sourceFor(id.serverId)?.children(placeholder) ?: emptyList()
                    } else {
                        val config = configFor(id.serverId) ?: return@withTimeoutOrNull emptyList<MaItem>()
                        if (!maReady(config)) return@withTimeoutOrNull emptyList<MaItem>()
                        maRepo.children(placeholder)
                    }
                }.orEmpty().take(options.trackItemLimit)
            }.getOrDefault(emptyList<MaItem>())
        }

    /**
     * One folder's contents, from cache when it is fresh enough.
     *
     * A failure is never remembered — see [childCache] — and the whole map is dropped
     * rather than trimmed once it grows past [CHILDREN_CACHE_MAX]. Evicting the least
     * recently used entry would be better and is not worth a second data structure
     * here: the cap is dozens of folders, reaching it means a long browse session,
     * and the cost of being wrong is one re-fetch of a folder that is about to be
     * asked for anyway.
     */
    private suspend fun cached(key: String, fetch: suspend () -> List<MaItem>): List<MaItem> {
        childCache[key]?.let { entry ->
            if (SystemClock.elapsedRealtime() - entry.atMs < CHILDREN_CACHE_MS) return entry.items
        }
        val items = fetch()
        if (items.isNotEmpty()) {
            if (childCache.size >= CHILDREN_CACHE_MAX) childCache.clear()
            childCache[key] = CachedChildren(items, SystemClock.elapsedRealtime())
        }
        return items
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Every configured library, in parallel.
     *
     * The last answer is kept because media3's legacy browse path asks for it twice:
     * `MediaBrowserCompat.search` lands in `onSearch`, whose `notifySearchResultChanged`
     * is what makes the browser come back through `onGetSearchResult` for the items
     * themselves. Without this, one spoken query fanned out to every server on the
     * network twice — up to [SEARCH_TIMEOUT_MS] of it, in a car, before anything
     * appeared.
     */
    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        cachedSearch?.let { cached ->
            if (cached.query == query && SystemClock.elapsedRealtime() - cached.atMs < SEARCH_CACHE_MS) {
                return cached.results
            }
        }
        val results = searchAll(query)
        cachedSearch = CachedSearch(query, results, SystemClock.elapsedRealtime())
        return results
    }

    private suspend fun searchAll(query: String): List<MediaItem> = coroutineScope {
        val options = settings.carBrowseOptionsNow()
        val servers = visibleLibraries(options)
        servers.map { config ->
            async {
                runCatching {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchOne(config, query, options) } ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }.map { it.await() }.flatten().take(SEARCH_RESULT_CAP)
    }

    private suspend fun searchOne(
        config: ServerConfig,
        query: String,
        options: CarBrowseOptions,
    ): List<MediaItem> {
        val results: MaSearchResults = if (config.kind == ServerKind.MUSIC_ASSISTANT) {
            if (!maReady(config)) return emptyList()
            maRepo.search(query, SEARCH_PER_SOURCE_LIMIT)
        } else {
            val source = sourceFor(config.id) ?: return emptyList()
            if (!source.has(Capability.SEARCH)) return emptyList()
            source.search(query, SEARCH_PER_SOURCE_LIMIT)
        }
        return (results.tracks + results.albums + results.artists + results.playlists)
            .map { it.toMediaItem(config.id, options, mixedList = true) }
    }

    // ── Playback — always this phone, never a remote MA speaker ────────────
    //
    // LibraryViewModel.play() targets settings.targetPlayer (whichever remote
    // speaker the Speakers screen last selected) - correct for the phone UI, wrong
    // here. A track tapped in the car must make sound in the car.

    suspend fun play(mediaId: String) {
        val id = CarMediaId.parse(mediaId) as? CarMediaId.Item ?: return
        if (MusicSources.isLocalProvider(id.provider)) {
            playLocal(id)
        } else {
            playMusicAssistant(id)
        }
    }

    /**
     * "Hey Google, play X in CAMusic".
     *
     * The voice path never carries a `mediaId` — media3 turns `onPlayFromSearch` into
     * a [MediaItem] whose id is `MediaItem.DEFAULT_MEDIA_ID` and whose only content is
     * `requestMetadata.searchQuery` — so it cannot go through [play] at all. The first
     * playable hit is what the same query would have put at the top of the search
     * screen: [searchOne] already orders tracks ahead of albums, artists and playlists.
     */
    suspend fun playSearch(query: String): Boolean {
        val hit = search(query).firstOrNull { it.mediaMetadata.isPlayable == true } ?: return false
        play(hit.mediaId)
        return true
    }

    private suspend fun playLocal(id: CarMediaId.Item) {
        val source = sourceFor(id.serverId) ?: return
        val placeholder = id.toPlaceholderItem()
        // A single track is re-read from the server rather than played as the
        // placeholder, which carries an id and nothing else: `toLocalTrack` reads
        // `name`/`subtitle`/`image`/`duration` off the item it is given, so playing the
        // placeholder put an untitled, artless, zero-length track in the car's now
        // playing *and* in this phone's own media notification. The album and playlist
        // branches never had the problem - `tracksUnder` returns real items.
        val items = if (id.mediaType == "track") {
            listOf(source.song(id.itemId) ?: placeholder)
        } else {
            source.tracksUnder(placeholder)
        }
        if (items.isEmpty()) return
        val tracks = items.map { app.downloads.toLocalTrack(it, streamUrl = source.streamUrl(it.itemId)) }
        // Only worth sending when there is a socket to send it on. `sendCommand` waits
        // five seconds for a connection that a car-launched process has never opened
        // before answering null - five seconds of silence between the tap and the
        // music, for a stop that had nothing to stop.
        if (app.maApi.state.value == MaApiClient.State.CONNECTED) {
            runCatching { maRepo.stop(PlayerIdentity.getPlayerId(app)) }
        }
        app.localPlayer.setQueue(tracks, 0)
    }

    private suspend fun playMusicAssistant(id: CarMediaId.Item) {
        val uri = id.uri ?: return
        val config = configFor(id.serverId) ?: return
        if (!maReady(config)) return
        val me = PlayerIdentity.getPlayerId(app)
        app.localPlayer.stop()
        // Point the *app* at this phone, not just this one command.
        //
        // Playing to `me` while `OPT_TARGET_PLAYER` still names a speaker in the
        // kitchen only moves half the problem: `MaNowPlaying` keys everything it
        // publishes off the stored target, so the car would show the kitchen's track,
        // and its transport buttons - routed through `PlaybackOwner` like every other
        // surface - would pause the kitchen. Tapping a song in the car is as clear a
        // statement of "play it *here*" as the Speakers screen is of the opposite.
        settings.setTargetPlayer(me)
        runCatching { maRepo.playOn(me, listOf(uri), "replace", radioMode = false) }
    }

    // ── Shared lookups ───────────────────────────────────────────────────────

    /**
     * Open the shared Music Assistant socket if nothing has yet, and wait for it.
     *
     * `MaApiClient.connect` is called from exactly one place in the app — the
     * Activity-scoped `LibraryViewModel`. Android Auto binds `CarMediaLibraryService`
     * directly, so on a phone whose app has not been opened since boot every Music
     * Assistant call here reached a client that had never been given a URL: with no
     * address to dial `reconnectNow()` is a no-op, `sendCommand` waits out its
     * five-second "wait for CONNECTED" and answers null, and every MA shelf, search
     * and tap rendered empty — slowly, one shelf at a time.
     *
     * One socket per process, so this connects the *shared* client rather than opening
     * a second: a phone with two MA servers configured browses whichever one is
     * already connected, exactly as the phone's own Library tab does.
     */
    private suspend fun maReady(config: ServerConfig): Boolean {
        val api = app.maApi
        if (api.state.value == MaApiClient.State.CONNECTED) return true
        // A client that already has an address reconnects itself from inside
        // `sendCommand`; only one that has never had one needs telling.
        if (api.serverUrl == null && config.url.isNotBlank()) {
            api.connect(
                config.url,
                token = null,
                username = config.username.ifBlank { null },
                password = config.password.ifBlank { null },
            )
        }
        return withTimeoutOrNull(MA_CONNECT_TIMEOUT_MS) {
            api.state.first { it == MaApiClient.State.CONNECTED }
        } != null
    }

    /**
     * Start connecting before anything asks. Called off the root request, whose own
     * answer needs no server at all, so the socket is usually up by the time the first
     * shelf is opened rather than costing that shelf the handshake.
     */
    suspend fun warmUp() {
        val config = settings.servers.first().firstOrNull { it.kind == ServerKind.MUSIC_ASSISTANT } ?: return
        runCatching { maReady(config) }
    }

    private suspend fun configFor(serverId: String): ServerConfig? =
        settings.servers.first().firstOrNull { it.id == serverId }

    private suspend fun sourceFor(serverId: String): MusicSource? {
        sourceCache[serverId]?.let { return it }
        val config = configFor(serverId) ?: return null
        val source = MusicSources.create(app, config) ?: return null
        sourceCache[serverId] = source
        return source
    }

    private fun CarMediaId.Item.toPlaceholderItem() = MaItem(
        itemId = itemId, provider = provider, name = "", uri = uri,
        mediaType = mediaType, subtitle = null, image = null, duration = null,
    )

    // ── Artwork ─────────────────────────────────────────────────────────────

    /**
     * Let a browser read the covers it was just handed, and nothing else.
     *
     * [CarArtworkProvider] is not exported, so a `content://` URI in a browse row is
     * unreadable until the package holding it is granted access to that exact URI.
     * Granting per item rather than by prefix is a binder call per row with a cover,
     * paid once when a folder is first opened — and it is the form of the grant the
     * platform documents as working, which for a feature whose failure mode is a
     * silently blank thumbnail is worth more than the calls saved.
     */
    suspend fun grantArtwork(packageName: String?, items: List<MediaItem>) {
        if (packageName.isNullOrBlank()) return
        val uris = items.mapNotNull { it.mediaMetadata.artworkUri }
        if (uris.isEmpty()) return
        // Off the callback's main dispatcher: each grant is a blocking call into
        // ActivityManager, and a folder of fifty covers is fifty of them. Awaited
        // rather than launched, because the browser starts reading the URIs the
        // moment this result reaches it.
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                runCatching { app.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    private fun artworkUri(url: String?, options: CarBrowseOptions): Uri? =
        if (!options.artwork) null else CarArtworkProvider.uriFor(app, url, artworkPx)

    // ── MediaItem construction ──────────────────────────────────────────────

    /** A library folder. What is inside it is shelves, which are folder rows. */
    private fun libraryItem(config: ServerConfig, options: CarBrowseOptions): MediaItem =
        browsableItem(
            id = CarMediaId.Server(config.id).encode(),
            title = config.displayName,
            extras = CarContentStyle.extras(browsableChildren = options.folderStyle()),
        )

    private fun moreFolderItem(options: CarBrowseOptions): MediaItem =
        browsableItem(
            id = CarMediaId.MORE,
            title = "More libraries",
            extras = CarContentStyle.extras(browsableChildren = options.folderStyle()),
        )

    /**
     * A shelf. Its own shape comes from the library folder above it; what it declares
     * is how the albums, artists or tracks *inside* it should be laid out — which is
     * what [CarShelf.grid] has always described.
     */
    private fun shelfMediaItem(serverId: String, shelf: CarShelf, options: CarBrowseOptions): MediaItem {
        val childStyle = options.shelfChildStyle(shelf)
        return browsableItem(
            id = CarMediaId.Shelf(serverId, shelf.key).encode(),
            title = shelf.title,
            extras = CarContentStyle.extras(
                browsableChildren = childStyle,
                playableChildren = childStyle,
                group = if (options.groupTitles) shelf.group.title else null,
            ),
        )
    }

    private fun noLibrariesItem(): MediaItem =
        messageItem("No libraries set up", "Open CAMusic on your phone to add one")

    /**
     * A row that says why there is nothing to tap.
     *
     * Browsable and unplayable both false, so the car draws it as a plain,
     * unactionable line rather than as a folder that opens onto more nothing.
     */
    private fun messageItem(title: String, subtitle: String?): MediaItem = MediaItem.Builder()
        .setMediaId(CarMediaId.Message(title, subtitle).encode())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /**
     * A folder row: a library, a shelf, "More libraries".
     *
     * No artwork, deliberately. None of these *is* a record — a cover on a folder
     * would have to be one of the covers inside it, which is a decision this app has
     * no basis for making and the car has no space to show.
     */
    private fun browsableItem(id: String, title: String, extras: Bundle): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setExtras(extras)
                .build(),
        )
        .build()

    /**
     * A library row, with its cover as a `content://` URI rather than the URL it was
     * built from — see [CarArtwork] for why the URL itself can never leave.
     *
     * [mixedList] is what a search result is: a list where a track sits next to an
     * album next to an artist, and no single hint from the folder above can be right
     * for all three. Only there does a row declare its own shape; an ordinary browse
     * row inherits its shelf's, which is what keeps a shelf looking like one thing.
     */
    private fun MaItem.toMediaItem(
        serverId: String,
        options: CarBrowseOptions,
        mixedList: Boolean = false,
    ): MediaItem {
        val mid = CarMediaId.Item(
            serverId = serverId,
            provider = provider,
            mediaType = mediaType,
            itemId = itemId,
            uri = uri.takeUnless { MusicSources.isLocalProvider(provider) },
        ).encode()
        val childStyle = options.childStyleFor(mediaType)
        return MediaItem.Builder()
            .setMediaId(mid)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(browsable)
                    .setIsPlayable(playable)
                    .setArtworkUri(artworkUri(image, options))
                    .setExtras(
                        CarContentStyle.extras(
                            // Only a folder has children to describe.
                            browsableChildren = if (browsable) childStyle else null,
                            playableChildren = if (browsable) childStyle else null,
                            self = if (mixedList) options.itemStyle(mediaType) else null,
                        ),
                    )
                    .build(),
            )
            .build()
    }

    /** The last query and what it returned — see [search]. */
    private class CachedSearch(val query: String, val results: List<MediaItem>, val atMs: Long)

    private class CachedChildren(val items: List<MaItem>, val atMs: Long)

    private var cachedSearch: CachedSearch? = null

    private companion object {
        const val MA_CONNECT_TIMEOUT_MS = 6_000L
        const val SEARCH_PER_SOURCE_LIMIT = 15
        const val SEARCH_RESULT_CAP = 50
        const val SEARCH_TIMEOUT_MS = 7_000L

        /**
         * How long a folder's contents may take before the car gets an answer anyway.
         *
         * There was no limit at all: a server that had gone unreachable since the
         * phone left the house left `onGetChildren` waiting on a socket timeout, and
         * Android Auto showed a spinner for as long as that took. An empty shelf with
         * a line saying so, in seven seconds, is the better answer at 70 km/h.
         */
        const val BROWSE_TIMEOUT_MS = 7_000L

        /**
         * Long enough to cover the two calls one query makes, short enough that a
         * library which has changed since is not answered from it — see [search].
         */
        const val SEARCH_CACHE_MS = 30_000L

        /** Long enough to cover a paged browse and a scroll back up. See [childCache]. */
        const val CHILDREN_CACHE_MS = 120_000L
        const val CHILDREN_CACHE_MAX = 64
    }
}
