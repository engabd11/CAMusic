package com.engabd.sendpin.library

import com.engabd.sendpin.emby.EmbyClient
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults

/**
 * [EmbyClient] as a [MusicSource]. See `JellyfinSource` for the pattern — the two are
 * near-identical adapters over near-identical APIs, kept as separate files because
 * the day Emby and Jellyfin diverge on a capability is the day sharing one would be a
 * bug in one of them.
 *
 * ## What Emby can't do
 *
 * No lyrics endpoint worth relying on, so no [Capability.LYRICS]. No similar-tracks
 * endpoint, so no [Capability.SIMILAR] — radio mode falls back to the local
 * generator, as it does on Jellyfin. No cross-device play queue, so no
 * [Capability.SAVED_QUEUE]. [Capability.RICH_FORMAT] holds unconditionally: Emby's
 * `MediaSources[].MediaStreams[]` carries the same complete format reading
 * Jellyfin's does.
 */
class EmbySource(private val client: EmbyClient) : MusicSource {

    override val kind: ServerKind = ServerKind.EMBY
    override val providerId: String get() = EmbyClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need Emby itself. */
    val emby: EmbyClient get() = client

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.GENRES,
        Capability.FAVORITES,
        Capability.PLAYLIST_READ,
        Capability.PLAYLIST_WRITE,
        Capability.DOWNLOAD,
        Capability.HISTORY,
        Capability.SCROBBLE,
        Capability.RICH_FORMAT,
    )

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "Emby refused the request", isAuth = it.isAuth)
    }

    override suspend fun artists(): List<MaItem> = client.artists()
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> = client.albums(offset, limit)
    override suspend fun playlists(): List<MaItem> = client.playlists()

    override suspend fun artistDetail(id: String) = client.artistDetail(id)
    override suspend fun albumDetail(id: String) = client.albumDetail(id)
    override suspend fun playlistTracks(id: String): List<MaItem> = client.playlistTracks(id)
    override suspend fun children(item: MaItem): List<MaItem> = client.children(item)
    override suspend fun tracksUnder(item: MaItem): List<MaItem> = client.tracksUnder(item)
    override suspend fun song(id: String): MaItem? = client.item(id)

    override suspend fun recentlyAdded(limit: Int): List<MaItem> = client.recentlyAdded(limit)
    override suspend fun recentlyPlayed(limit: Int): List<MaItem> = client.recentlyPlayed(limit)
    override suspend fun mostPlayed(limit: Int): List<MaItem> = client.mostPlayed(limit)
    override suspend fun favorites(): MaSearchResults = client.favorites()
    override suspend fun randomSongs(size: Int): List<MaItem> = client.randomSongs(size)
    override suspend fun randomAlbums(limit: Int): List<MaItem> = client.randomAlbums(limit)

    override suspend fun search(query: String, limit: Int): MaSearchResults = client.search(query, limit)

    override suspend fun genres(): List<MaItem> = client.genres()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        client.songsByGenre(genre, count, offset)

    override fun streamUrl(id: String, format: String): String = client.streamUrl(id, format)
    override fun downloadUrl(id: String): String = client.downloadUrl(id)
    override fun coverUrl(id: String?, size: Int): String? = client.coverUrl(id, size)

    override suspend fun setStarred(item: MaItem, starred: Boolean) =
        client.setFavorite(item.itemId, starred)

    /** Position over wall-clock, same reasoning as `JellyfinSource.scrobble`. */
    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?, positionMs: Long?) {
        client.reportPlayback(id, completed, JellyfinSource.resolvePosition(positionMs, startedAtMs))
    }

    override suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) =
        client.reportProgress(id, positionMs, paused)

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
        client.createPlaylist(name, songIds)

    override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) =
        client.addToPlaylist(playlistId, songIds)

    override suspend fun deletePlaylist(id: String) = client.deleteItem(id)
}
