package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.mpd.MpdClient

/**
 * [MpdClient] as a [MusicSource] — Music Player Daemon as a browsable library
 * that this phone plays via ExoPlayer over MPD's HTTP output stream.
 *
 * MPD is the same shape of thing as Navidrome or Jellyfin: it lists artists and
 * albums, answers a search, and hands out a URL ExoPlayer can open. The
 * difference is that the "URL" is MPD's continuous HTTP output stream — to
 * play a specific track, [preparePlayback] adds it to MPD's queue and starts
 * playback, then ExoPlayer opens the stream URL. See [MpdClient.streamUrl].
 *
 * ## What MPD can't do
 *
 * No lyrics ([Capability.LYRICS]), no artist biographies
 * ([Capability.METADATA]), no similar-track suggestions
 * ([Capability.SIMILAR]). MPD's metadata comes entirely from file tags, so
 * [Capability.RICH_FORMAT] *is* here — MPD reports codec, sample rate and bit
 * depth per track. No [Capability.FAVORITES] (MPD has no starred concept
 * natively), no [Capability.DOWNLOAD] (the HTTP stream is real-time, not
 * seekable for downloads), no [Capability.HISTORY] (MPD has no play history
 * without stickers).
 */
class MpdSource(private val client: MpdClient) : MusicSource {

    override val kind: ServerKind = ServerKind.MPD
    override val providerId: String get() = MpdClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need MPD itself. */
    val mpd: MpdClient get() = client

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.GENRES,
        Capability.PLAYLIST_READ,
        Capability.TRACKS,
        Capability.RICH_FORMAT,
    )

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "MPD refused the request", isAuth = it.isAuth)
    }

    // ── Browse ────────────────────────────────────────────────────────────

    override suspend fun artists(): List<MaItem> = client.artists()
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        client.albums(offset = offset, limit = limit)
    override suspend fun playlists(): List<MaItem> = client.playlists()

    override suspend fun artistDetail(id: String) = client.artistDetail(id)
    override suspend fun albumDetail(id: String) = client.albumDetail(id)
    override suspend fun playlistTracks(id: String): List<MaItem> = client.playlistTracks(id)

    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> = client.tracks(offset, limit)
    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> client.albums(artist = item.itemId)
        "album" -> client.albumDetail(item.itemId).second
        "playlist" -> client.playlistTracks(item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album" -> client.albumDetail(item.itemId).second
        "playlist" -> client.playlistTracks(item.itemId)
        "artist" -> client.albums(artist = item.itemId).flatMap { album ->
            client.albumDetail(album.itemId).second
        }
        else -> emptyList()
    }

    override suspend fun song(id: String): MaItem? = client.song(id)

    // ── Shelves ───────────────────────────────────────────────────────────

    override suspend fun recentlyAdded(limit: Int): List<MaItem> = client.recentlyAdded(limit)
    override suspend fun favorites(): MaSearchResults = client.favorites()
    override suspend fun randomSongs(size: Int): List<MaItem> = client.randomSongs(size)

    // ── Search ────────────────────────────────────────────────────────────

    override suspend fun search(query: String, limit: Int): MaSearchResults =
        client.search(query, limit)

    // ── Genres ────────────────────────────────────────────────────────────

    override suspend fun genres(): List<MaItem> = client.genres()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        client.songsByGenre(genre, count, offset)

    // ── URLs ──────────────────────────────────────────────────────────────

    override fun streamUrl(id: String, format: String): String = client.streamUrl(id, format)
    override fun downloadUrl(id: String): String = client.downloadUrl(id)
    override fun coverUrl(id: String?, size: Int): String? = client.coverUrl(id, size)

    /**
     * Clear MPD's queue, add this track, and start playback so the HTTP stream
     * outputs the right track when ExoPlayer opens [streamUrl].
     *
     * This is the one provider where [preparePlayback] is not a no-op — every
     * other provider serves a per-track URL. See [MusicSource.preparePlayback].
     */
    override suspend fun preparePlayback(id: String) = client.preparePlayback(id)

    /** The one source that answers true. See [MusicSource.needsPreparePlayback]. */
    override val needsPreparePlayback: Boolean = true
}
