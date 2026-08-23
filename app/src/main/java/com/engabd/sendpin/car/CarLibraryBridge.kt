package com.engabd.sendpin.car

import android.os.Bundle
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
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.MaSearchResults
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
    private val sourceCache = mutableMapOf<String, MusicSource>()

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

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == CarMediaId.ROOT) return rootItem()
        return when (val id = CarMediaId.parse(mediaId)) {
            is CarMediaId.Server -> configFor(id.serverId)?.let { serverTabItem(it) }
            is CarMediaId.Item -> id.toPlaceholderItem().toMediaItem(id.serverId)
            else -> null
        }
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
            maRepo.children(placeholder)
        }
    }.getOrDefault(emptyList())

    // ── Search ───────────────────────────────────────────────────────────────

    suspend fun search(query: String): List<MediaItem> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
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

    private suspend fun playLocal(id: CarMediaId.Item) {
        val source = sourceFor(id.serverId) ?: return
        val placeholder = id.toPlaceholderItem()
        val items = if (id.mediaType == "track") listOf(placeholder) else source.tracksUnder(placeholder)
        if (items.isEmpty()) return
        val tracks = items.map { app.downloads.toLocalTrack(it, streamUrl = source.streamUrl(it.itemId)) }
        runCatching { maRepo.stop(PlayerIdentity.getPlayerId(app)) }
        app.localPlayer.setQueue(tracks, 0)
    }

    private suspend fun playMusicAssistant(id: CarMediaId.Item) {
        val uri = id.uri ?: return
        app.localPlayer.stop()
        runCatching { maRepo.playOn(PlayerIdentity.getPlayerId(app), listOf(uri), "replace", radioMode = false) }
    }

    // ── Shared lookups ───────────────────────────────────────────────────────

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

    private companion object {
        const val SHELF_LIMIT = 50
        const val TRACK_SHELF_LIMIT = 100
        const val SEARCH_PER_SOURCE_LIMIT = 15
        const val SEARCH_RESULT_CAP = 50
        const val SEARCH_TIMEOUT_MS = 7_000L
        val GRID_CHILD_TYPES = setOf("artist", "album", "playlist", "genre")
    }
}
