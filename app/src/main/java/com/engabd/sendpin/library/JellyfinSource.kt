package com.engabd.sendpin.library

import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults

/**
 * [JellyfinClient] as a [MusicSource] — the first provider added through the
 * interface rather than through `LibraryViewModel`.
 *
 * That is the point of it: nothing above this file knows Jellyfin exists. The library
 * asks a `MusicSource` for artists and gets them; the download manager asks for a
 * `downloadUrl` and gets one; the quality badge reads the `MaAudioFormat` the client
 * filled in. If adding the second provider had needed edits scattered through the
 * view models, the abstraction would not have been worth building.
 *
 * ## What Jellyfin can't do
 *
 * No similar-tracks endpoint, so [Capability.SIMILAR] is absent and the "more like
 * this" shelf is left out rather than shown empty — radio mode on this source falls
 * back to the local generator. No artist biographies through a public endpoint worth
 * relying on, so no [Capability.METADATA]. No cross-device play queue, so no
 * [Capability.SAVED_QUEUE]. Everything else it does *better* than plain Subsonic:
 * its per-track `MediaStreams` carry a full format reading, which is why
 * [Capability.RICH_FORMAT] is unconditional here and probed on Subsonic.
 */
class JellyfinSource(private val client: JellyfinClient) : MusicSource {

    override val kind: ServerKind = ServerKind.JELLYFIN
    override val providerId: String get() = JellyfinClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need Jellyfin itself. */
    val jellyfin: JellyfinClient get() = client

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.GENRES,
        Capability.FAVORITES,
        Capability.PLAYLIST_READ,
        Capability.PLAYLIST_WRITE,
        Capability.DOWNLOAD,
        Capability.LYRICS,
        Capability.HISTORY,
        Capability.SCROBBLE,
        Capability.RICH_FORMAT,
    )

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "Jellyfin refused the request", isAuth = it.isAuth)
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

    /**
     * Jellyfin's playback reporting wants a *position within the track*, and
     * [startedAtMs] is a wall-clock epoch — forwarding it raw put `PositionTicks`
     * about fifty-six years into a three-minute song.
     *
     * The elapsed time since [startedAtMs] *is* a usable position, though, and it is
     * what the completion report should carry: sending 0 forever meant Jellyfin could
     * never build a resume point, so "continue listening" never had anything in it.
     * It is wall-clock rather than player-clock, so a pause inflates it — bounded
     * below by 0 and left otherwise honest, since the alternative on this call path is
     * no position at all. The accurate figure travels on [reportProgress], which the
     * player drives from its own position.
     */
    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?) {
        val positionMs = startedAtMs
            ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
            ?: 0L
        client.reportPlayback(id, completed, positionMs)
    }

    override suspend fun reportProgress(id: String, positionMs: Long, paused: Boolean) =
        client.reportProgress(id, positionMs, paused)

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
        client.createPlaylist(name, songIds)

    override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) =
        client.addToPlaylist(playlistId, songIds)

    override suspend fun deletePlaylist(id: String) = client.deleteItem(id)

    override suspend fun lyrics(songId: String): MaLyrics? = client.lyrics(songId)
}
