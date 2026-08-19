package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.subsonic.AlbumInfo
import com.engabd.sendpin.subsonic.ArtistInfo
import com.engabd.sendpin.subsonic.SavedQueue
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.subsonic.SubsonicError

/**
 * [SubsonicClient] as a [MusicSource].
 *
 * Delegation only — the client is unchanged and still does all the work, because it
 * is the most exercised code in the app and there was nothing wrong with it. What
 * this adds is the boundary: everything above it now talks to an interface, so
 * Navidrome stops being a special case in `LibraryViewModel` and the next server is
 * an addition rather than an edit.
 *
 * ## Navidrome and "Subsonic-compatible" are one client, two capability sets
 *
 * They speak the same protocol; what differs is how much of it. OpenSubsonic is a set
 * of *extensions* — `songLyrics` for synced lyrics, `formatRestrictions` and the
 * `replayGain` object for the quality badge — that a server advertises through
 * `getOpenSubsonicExtensions`. So the capability set is **probed** rather than
 * declared per kind: an Airsonic that has grown the extensions gets the lyrics pane,
 * and a Navidrome too old for them doesn't offer one it cannot fill.
 */
class SubsonicSource(
    private val client: SubsonicClient,
    override val kind: ServerKind = ServerKind.NAVIDROME,
    /** Extensions the server advertised. Empty until [probeCapabilities] has run. */
    private var extensions: Set<String> = emptySet(),
) : MusicSource {

    override val providerId: String get() = SubsonicClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for the few call sites that still need Subsonic itself. */
    val subsonic: SubsonicClient get() = client

    override val capabilities: Set<Capability>
        get() = buildSet {
            // Every Subsonic server, back to the original, has these.
            add(Capability.SEARCH)
            add(Capability.GENRES)
            add(Capability.FAVORITES)
            add(Capability.PLAYLIST_READ)
            add(Capability.PLAYLIST_WRITE)
            add(Capability.DOWNLOAD)
            add(Capability.HISTORY)
            add(Capability.SCROBBLE)
            add(Capability.METADATA)
            add(Capability.SIMILAR)
            add(Capability.SAVED_QUEUE)
            // …and these only where the server said so. `songLyrics` is the
            // OpenSubsonic extension behind `getLyricsBySongId`; without it the app
            // would be offering a pane it can only ever fill with a shrug.
            if ("songLyrics" in extensions) add(Capability.LYRICS)
            // A plain server sends no `samplingRate`, `bitDepth`, `bitRate` or
            // `replayGain`, so the badge can only name the codec. Navidrome sends
            // them all, which is the whole reason the quality card is worth opening.
            if ("formatRestrictions" in extensions || "songLyrics" in extensions) {
                add(Capability.RICH_FORMAT)
            }
        }

    /**
     * Ask the server which OpenSubsonic extensions it has.
     *
     * Called once after connecting. A server that doesn't know the endpoint answers
     * with an error, which is itself the answer — it has none. Older Navidrome versions
     * and plain Subsonic servers will return 404 or 405, which we treat as "no extensions".
     */
    suspend fun probeCapabilities() {
        extensions = runCatching { client.openSubsonicExtensions().keys }.getOrElse {
            // Server doesn't support getOpenSubsonicExtensions (404/405) - use baseline capabilities
            emptySet()
        }
    }

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(
            message = it.message ?: "The server refused the request",
            isAuth = SubsonicError.isAuth(it.code),
        )
    }

    override suspend fun artists(): List<MaItem> = client.artists()

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        client.albumList("alphabeticalByName", limit, offset)

    override suspend fun playlists(): List<MaItem> = client.playlists()

    override suspend fun artistDetail(id: String) = client.artistDetail(id)
    override suspend fun albumDetail(id: String) = client.albumDetail(id)
    override suspend fun playlistTracks(id: String): List<MaItem> = client.playlistTracks(id)
    override suspend fun children(item: MaItem): List<MaItem> = client.children(item)
    override suspend fun tracksUnder(item: MaItem): List<MaItem> = client.tracksUnder(item)
    override suspend fun song(id: String): MaItem? = client.song(id)

    override suspend fun recentlyAdded(limit: Int): List<MaItem> = client.albumList("newest", limit)
    override suspend fun recentlyPlayed(limit: Int): List<MaItem> = client.albumList("recent", limit)
    override suspend fun mostPlayed(limit: Int): List<MaItem> = client.albumList("frequent", limit)
    override suspend fun favorites(): MaSearchResults = client.starred()
    override suspend fun randomSongs(size: Int): List<MaItem> = client.randomSongs(size)
    override suspend fun randomAlbums(limit: Int): List<MaItem> = client.albumList("random", limit)

    override suspend fun search(query: String, limit: Int): MaSearchResults = client.search(query, limit)

    override suspend fun genres(): List<MaItem> = client.genres()
    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<MaItem> =
        client.songsByGenre(genre, count, offset)

    override fun streamUrl(id: String, format: String): String = client.streamUrl(id, format)
    override fun downloadUrl(id: String): String = client.downloadUrl(id)
    override fun coverUrl(id: String?, size: Int): String? = client.coverUrl(id, size)

    override suspend fun setStarred(item: MaItem, starred: Boolean) = client.setStarred(item, starred)

    // Subsonic's `time` *is* the epoch this parameter carries, so it passes straight
    // through — the one provider where that is true. `positionMs` is Jellyfin-only
    // (Subsonic's `scrobble` has no position field) and is ignored here.
    override suspend fun scrobble(id: String, completed: Boolean, startedAtMs: Long?, positionMs: Long?) =
        client.scrobble(id, submission = completed, timeMs = startedAtMs)

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? =
        client.createPlaylist(name, songIds)

    override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        client.updatePlaylist(playlistId, addSongIds = songIds)
    }

    override suspend fun deletePlaylist(id: String) = client.deletePlaylist(id)

    override suspend fun lyrics(songId: String): MaLyrics? = client.getLyrics(songId)
    override suspend fun artistInfo(id: String, similarCount: Int): ArtistInfo =
        client.artistInfo(id, similarCount)
    override suspend fun albumInfo(id: String): AlbumInfo = client.albumInfo(id)
    override suspend fun topSongs(artistName: String, count: Int): List<MaItem> =
        client.getTopSongs(artistName, count)
    override suspend fun similarSongs(id: String, count: Int): List<MaItem> =
        client.getSimilarSongs(id, count)

    override suspend fun savedQueue(): SavedQueue? = client.playQueue()
    override suspend fun saveQueue(songIds: List<String>, currentId: String?, positionMs: Long) =
        client.savePlayQueue(songIds, currentId, positionMs)
}
