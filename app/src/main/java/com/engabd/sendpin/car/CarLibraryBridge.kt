package com.engabd.sendpin.car

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
 * No `MediaItem` this class returns ever carries a real stream/cover URL (see
 * [toMediaItem]/[browsableItem]): `CarMediaLibraryService` is `exported="true"` for
 * Android Auto to bind to, and Subsonic/Jellyfin URLs embed credentials in their
 * query string. Metadata only, matching how `SendspinService`'s `ShadePlayer`
 * already treats the one facade this app ships today.
 */
@OptIn(UnstableApi::class)
class CarLibraryBridge(private val app: SendpinApp) {

    private val settings = AppSettings(app)
    private val maRepo = MaRepository(app.maApi)

    // Concurrent because [sourceFor] runs on whichever thread the MediaLibraryService
    // callback landed on, and two browse requests for the same server can be in flight
    // at once. A plain HashMap resized under that is a corrupted map, not a lost entry.
    private val sourceCache = ConcurrentHashMap<String, MusicSource>()

    // ── Root / browse tree ──────────────────────────────────────────────────

    suspend fun children(parentId: String, rootChildrenLimit: Int): List<MediaItem> = when (parentId) {
        CarMediaId.ROOT -> {
            val (shown, overflow) = splitServers(rootChildrenLimit)
            shown.map { serverTabItem(it) } + if (overflow.isNotEmpty()) listOf(moreFolderItem()) else emptyList()
        }
        CarMediaId.MORE -> splitServers(rootChildrenLimit).second.map { serverTabItem(it) }
        else -> when (val id = CarMediaId.parse(parentId)) {
            is CarMediaId.Server -> shelvesFor(id)
            is CarMediaId.Shelf -> shelfItems(id).map { it.toMediaItem(id.serverId) }
            is CarMediaId.Item -> itemChildren(id).map { it.toMediaItem(id.serverId) }
            null -> emptyList()
        }
    }

    /**
     * One node, resolved from its id alone.
     *
     * Every node the tree hands out has to be answerable here, not just the two that
     * were — a browser is free to resolve any id it holds without having browsed to it,
     * and an unanswered one comes back as an error rather than as a row.
     */
    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == CarMediaId.ROOT) return rootItem()
        if (mediaId == CarMediaId.MORE) return moreFolderItem()
        return when (val id = CarMediaId.parse(mediaId)) {
            is CarMediaId.Server -> configFor(id.serverId)?.let { serverTabItem(it) }
            is CarMediaId.Shelf -> shelfItem(id)
            is CarMediaId.Item -> resolve(id).toMediaItem(id.serverId)
            null -> null
        }
    }

    private suspend fun shelfItem(id: CarMediaId.Shelf): MediaItem? {
        val config = configFor(id.serverId) ?: return null
        val source = if (config.kind == ServerKind.MUSIC_ASSISTANT) null else sourceFor(id.serverId)
        val spec = shelfSpecs(config, source).firstOrNull { it.key == id.key } ?: return null
        return browsableItem(CarMediaId.Shelf(id.serverId, spec.key).encode(), spec.title, grid = spec.grid)
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

    fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(CarMediaId.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("CAMusic")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private suspend fun splitServers(limit: Int): Pair<List<ServerConfig>, List<ServerConfig>> {
        val servers = settings.servers.first()
        val cap = limit.coerceAtLeast(1)
        if (servers.size <= cap) return servers to emptyList()
        val shown = servers.take((cap - 1).coerceAtLeast(1))
        return shown to servers.drop(shown.size)
    }

    private suspend fun shelvesFor(id: CarMediaId.Server): List<MediaItem> {
        val config = configFor(id.serverId) ?: return emptyList()
        val source = if (config.kind == ServerKind.MUSIC_ASSISTANT) null else sourceFor(id.serverId)
        return shelfSpecs(config, source).map { spec ->
            browsableItem(CarMediaId.Shelf(id.serverId, spec.key).encode(), spec.title, grid = spec.grid)
        }
    }

    private data class ShelfSpec(val key: String, val title: String, val grid: Boolean)

    private fun shelfSpecs(config: ServerConfig, source: MusicSource?): List<ShelfSpec> {
        val specs = mutableListOf(ShelfSpec("recentlyAdded", "Recently added", grid = true))
        if (config.kind == ServerKind.MUSIC_ASSISTANT) {
            specs += ShelfSpec("recentlyPlayed", "Recently played", grid = true)
            specs += ShelfSpec("favoriteAlbums", "Favorite albums", grid = true)
            specs += ShelfSpec("favoriteArtists", "Favorite artists", grid = true)
            specs += ShelfSpec("favoritePlaylists", "Favorite playlists", grid = true)
            specs += ShelfSpec("favoriteTracks", "Favorite tracks", grid = false)
        } else {
            if (source?.has(Capability.FAVORITES) == true) {
                specs += ShelfSpec("favoriteAlbums", "Favorite albums", grid = true)
                specs += ShelfSpec("favoriteArtists", "Favorite artists", grid = true)
                specs += ShelfSpec("favoritePlaylists", "Favorite playlists", grid = true)
                specs += ShelfSpec("favoriteTracks", "Favorite tracks", grid = false)
            }
            specs += ShelfSpec("artists", "Artists", grid = false)
            specs += ShelfSpec("albums", "Albums", grid = true)
            if (source?.has(Capability.PLAYLIST_READ) == true) {
                specs += ShelfSpec("playlists", "Playlists", grid = true)
            }
        }
        return specs
    }

    private suspend fun shelfItems(id: CarMediaId.Shelf): List<MaItem> = runCatching {
        val config = configFor(id.serverId) ?: return@runCatching emptyList()
        if (config.kind == ServerKind.MUSIC_ASSISTANT) {
            if (!maReady(config)) return@runCatching emptyList()
            when (id.key) {
                "recentlyAdded" -> maRepo.recentlyAdded(SHELF_LIMIT)
                "recentlyPlayed" -> maRepo.recentlyPlayed(SHELF_LIMIT)
                "favoriteAlbums" -> maRepo.favoriteAlbums(SHELF_LIMIT)
                "favoriteArtists" -> maRepo.favoriteArtists(SHELF_LIMIT)
                "favoritePlaylists" -> maRepo.favoritePlaylists(SHELF_LIMIT)
                "favoriteTracks" -> maRepo.favoriteTracks(TRACK_SHELF_LIMIT)
                else -> emptyList()
            }
        } else {
            val source = sourceFor(id.serverId) ?: return@runCatching emptyList()
            when (id.key) {
                "recentlyAdded" -> source.recentlyAdded(SHELF_LIMIT)
                "artists" -> source.artists()
                "albums" -> source.albums(limit = SHELF_LIMIT)
                "playlists" -> source.playlists()
                "favoriteAlbums" -> source.favorites().albums
                "favoriteArtists" -> source.favorites().artists
                "favoritePlaylists" -> source.favorites().playlists
                "favoriteTracks" -> source.favorites().tracks
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    private suspend fun itemChildren(id: CarMediaId.Item): List<MaItem> = runCatching {
        val placeholder = id.toPlaceholderItem()
        if (MusicSources.isLocalProvider(id.provider)) {
            sourceFor(id.serverId)?.children(placeholder) ?: emptyList()
        } else {
            val config = configFor(id.serverId) ?: return@runCatching emptyList()
            if (!maReady(config)) return@runCatching emptyList()
            maRepo.children(placeholder)
        }
    }.getOrDefault(emptyList())

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
        val servers = settings.servers.first()
        servers.map { config ->
            async {
                runCatching {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchOne(config, query) } ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }.map { it.await() }.flatten().take(SEARCH_RESULT_CAP)
    }

    private suspend fun searchOne(config: ServerConfig, query: String): List<MediaItem> {
        val results: MaSearchResults = if (config.kind == ServerKind.MUSIC_ASSISTANT) {
            if (!maReady(config)) return emptyList()
            maRepo.search(query, SEARCH_PER_SOURCE_LIMIT)
        } else {
            val source = sourceFor(config.id) ?: return emptyList()
            if (!source.has(Capability.SEARCH)) return emptyList()
            source.search(query, SEARCH_PER_SOURCE_LIMIT)
        }
        return (results.tracks + results.albums + results.artists + results.playlists)
            .map { it.toMediaItem(config.id) }
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

    // ── MediaItem construction ──────────────────────────────────────────────

    private fun serverTabItem(config: ServerConfig): MediaItem =
        browsableItem(CarMediaId.Server(config.id).encode(), config.displayName, grid = false)

    private fun moreFolderItem(): MediaItem =
        browsableItem(CarMediaId.MORE, "More libraries", grid = false)

    private fun browsableItem(id: String, title: String, grid: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setExtras(contentStyleExtras(grid))
                .build(),
        )
        .build()

    /**
     * No artwork URI: Subsonic/Jellyfin cover URLs embed credentials the same way
     * their stream URLs do (see this class's own doc), and Music Assistant's image
     * proxy is authenticated by a header this exported binder has no way to attach.
     * A browse row without a thumbnail is a fair trade against handing either kind
     * of URL to anything that can bind a `MediaBrowser` to this service.
     */
    private fun MaItem.toMediaItem(serverId: String): MediaItem {
        val mid = CarMediaId.Item(
            serverId = serverId,
            provider = provider,
            mediaType = mediaType,
            itemId = itemId,
            uri = uri.takeUnless { MusicSources.isLocalProvider(provider) },
        ).encode()
        return MediaItem.Builder()
            .setMediaId(mid)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(subtitle)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(browsable)
                    .setIsPlayable(playable)
                    .apply { if (browsable) setExtras(contentStyleExtras(grid = mediaType in GRID_CHILD_TYPES)) }
                    .build(),
            )
            .build()
    }

    private fun contentStyleExtras(grid: Boolean): Bundle {
        val style = if (grid) {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        } else {
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
        return Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        }
    }

    /** The last query and what it returned — see [search]. */
    private class CachedSearch(val query: String, val results: List<MediaItem>, val atMs: Long)

    private var cachedSearch: CachedSearch? = null

    private companion object {
        const val MA_CONNECT_TIMEOUT_MS = 6_000L
        const val SHELF_LIMIT = 50
        const val TRACK_SHELF_LIMIT = 100
        const val SEARCH_PER_SOURCE_LIMIT = 15
        const val SEARCH_RESULT_CAP = 50
        const val SEARCH_TIMEOUT_MS = 7_000L
        /**
         * Long enough to cover the two calls one query makes, short enough that a
         * library which has changed since is not answered from it — see [search].
         */
        const val SEARCH_CACHE_MS = 30_000L
        val GRID_CHILD_TYPES = setOf("artist", "album", "playlist", "genre")
    }
}
