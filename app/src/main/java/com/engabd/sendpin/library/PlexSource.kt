package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.plex.PlexClient

/**
 * [PlexClient] as a [MusicSource].
 *
 * ## What Plex can't do here
 *
 * No [Capability.FAVORITES]: Plex's music library has a 0–10 `userRating`, not a
 * star, and mapping one onto the other would be a guess about what a user meant by
 * rating a track a 6. No [Capability.PLAYLIST_WRITE]: creating one needs the
 * server's own machine identifier woven into a `server://` URI per track, which is
 * more surface than this pass is confident enough of to ship silently wrong. No
 * [Capability.LYRICS], [Capability.SIMILAR], [Capability.METADATA] or
 * [Capability.SAVED_QUEUE] — none has a documented public endpoint worth relying on.
 * No [Capability.RICH_FORMAT]: Plex's `Media` element gives a codec, a bitrate and a
 * channel count, but never a sample rate or bit depth, so the badge would always be
 * missing half of what the capability promises.
 */
class PlexSource(private val client: PlexClient) : MusicSource {

    override val kind: ServerKind = ServerKind.PLEX
    override val providerId: String get() = PlexClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need Plex itself. */
    val plex: PlexClient get() = client

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.GENRES,
        Capability.PLAYLIST_READ,
        Capability.TRACKS,
        Capability.DOWNLOAD,
        Capability.HISTORY,
        Capability.SCROBBLE,
    )

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "Plex refused the request", isAuth = it.isAuth)
    }

    override suspend fun artists(): List<MaItem> = client.artists()
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> = client.albums(offset, limit)
    override suspend fun playlists(): List<MaItem> = client.playlists()

    override suspend fun artistDetail(id: String) = client.artistDetail(id)
    override suspend fun albumDetail(id: String) = client.albumDetail(id)
    override suspend fun playlistTracks(id: String): List<MaItem> = client.playlistTracks(id)
    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> = client.tracks(offset, limit)
    override suspend fun children(item: MaItem): List<MaItem> = client.children(item)
    override suspend fun tracksUnder(item: MaItem): List<MaItem> = client.tracksUnder(item)
    override suspend fun song(id: String): MaItem? = client.item(id)

    override suspend fun recentlyAdded(limit: Int): List<MaItem> = client.recentlyAdded(limit)
    override suspend fun recentlyPlayed(limit: Int): List<MaItem> = client.recentlyPlayed(limit)
    override suspend fun mostPlayed(limit: Int): List<MaItem> = client.mostPlayed(limit)
    override suspend fun randomSongs(size: Int): List<MaItem> = client.randomSongs(size)
    override suspend fun randomAlbums(limit: Int): List<MaItem> = client.randomAlbums(limit)

    // Plex has no starred-item listing worth relying on for music (see the class
    // doc), so there is nothing to favourite over: an always-empty result here would
    // be indistinguishable from a broken one.
    override suspend fun favorites(): MaSearchResults =
        MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun search(query: String, limit: Int): MaSearchResults = client.search(query, limit)

    override suspend fun genres(): List<MaItem> = client.genres()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        client.songsByGenre(genre, count, offset)

    override fun streamUrl(id: String, format: String): String = client.streamUrl(id, format)
    override fun downloadUrl(id: String): String = client.downloadUrl(id)
    override fun coverUrl(id: String?, size: Int): String? = client.coverUrl(id, size)

    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?, positionMs: Long?) {
        client.reportPlayback(id, completed, JellyfinSource.resolvePosition(positionMs, startedAtMs))
    }

    override suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) =
        client.reportProgress(id, positionMs, paused)
}
