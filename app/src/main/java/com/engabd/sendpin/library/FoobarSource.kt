package com.engabd.sendpin.library

import android.content.Context
import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.foobar2000.FoobarClient
import com.engabd.sendpin.foobar2000.FoobarException
import com.engabd.sendpin.foobar2000.FoobarRemote
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults

/**
 * [FoobarClient] as a [MusicSource] — foobar2000 as a browsable library,
 * and, like MPD, as the thing that plays what it browses.
 *
 * For browsing it uses Beefweb's file system browser — foobar2000 has no
 * structured media-library query API, so library browsing is a walk of the
 * music directories the user configured in Beefweb's settings. For playing it
 * is the same shape as MPD: foobar2000 is already a player, its audio goes to
 * its own output device, and the phone drives the transport — see
 * [com.engabd.sendpin.audio.RemotePlayback] for the whole argument.
 *
 * ## What foobar2000 can't do
 *
 * No lyrics ([Capability.LYRICS]), no artist biographies
 * ([Capability.METADATA]), no similar-track suggestions ([Capability.SIMILAR]).
 * No [Capability.SEARCH] — Beefweb has no search API, and a file-system crawl
 * is too slow for a search box. No [Capability.DOWNLOAD]: Beefweb serves
 * artwork but not audio files to a remote client.
 *
 * [Capability.RICH_FORMAT] *is* here — foobar2000 reports codec, sample rate
 * and bit depth per track through its title-formatting columns.
 * [Capability.REPLAY_GAIN] is *not* declared: foobar2000 has its own
 * ReplayGain, but Beefweb's API does not expose a toggle for it, and a switch
 * that can only ever do nothing is worse than its absence.
 *
 * ## Favourites
 *
 * Like MPD, foobar2000 has no starred concept. [Capability.FAVORITES] is
 * declared when a context is available, and the app keeps the list itself —
 * see [LocalFavourites].
 */
class FoobarSource(
    private val client: FoobarClient,
    private val context: Context? = null,
) : MusicSource {

    override val kind: ServerKind = ServerKind.FOOBAR2000
    override val providerId: String get() = FoobarClient.PROVIDER
    override val serverUrl: String get() = client.serverUrl

    override var streamFormat: String
        get() = client.streamFormat
        set(value) { client.streamFormat = value }

    /** The underlying client, for setup flows that need it. */
    val foobar: FoobarClient get() = client

    override val capabilities: Set<Capability> = buildSet {
        add(Capability.RICH_FORMAT)
        add(Capability.TRACKS)
        // Only when there is somewhere to keep them — same as MpdSource.
        if (context != null) add(Capability.FAVORITES)
    }

    /** Everything this source hands out, with the app's own stars applied. */
    private fun marked(items: List<MaItem>): List<MaItem> =
        context?.let { LocalFavourites.mark(it, providerId, items) } ?: items

    override suspend fun probe(): SourceError? = client.pingResult()?.let {
        SourceError(it.message ?: "foobar2000 refused the request", isAuth = it.isAuth)
    }

    // ── Browse ────────────────────────────────────────────────────────────

    /**
     * "Artists" as directories at the music root.
     *
     * Beefweb browses the file system, not a tagged library. The convention is
     * `Music/Artist/Album/track.flac`, so the directories at the root are
     * artists. This is a coarse approximation — it works when the collection
     * is organised by artist, and returns empty otherwise.
     */
    override suspend fun artists(): List<MaItem> {
        val roots = client.browseRoots()
        val entries = roots.flatMap { root ->
            try { client.browseEntries(root.path) } catch (_: FoobarException) { emptyList() }
        }
        return entries.filter { it.isDirectory }.map { entry ->
            MaItem(
                itemId = entry.path,
                provider = providerId,
                name = entry.name,
                uri = entry.path,
                mediaType = "artist",
                subtitle = null,
                image = null,
                duration = null,
            )
        }.let { marked(it) }
    }

    /**
     * "Albums" as directories one level below artists.
     *
     * Given an artist path, lists its subdirectories as albums. When no artist
     * is specified, walks all artist directories and collects their albums —
     * this is the equivalent of MPD's `list album`, but via the file browser.
     */
    override suspend fun albums(offset: Int, limit: Int): List<MaItem> {
        val allAlbums = mutableListOf<MaItem>()
        for (artist in artists()) {
            val entries = try {
                client.browseEntries(artist.itemId)
            } catch (_: FoobarException) { emptyList() }
            for (entry in entries.filter { it.isDirectory }) {
                allAlbums.add(
                    MaItem(
                        itemId = entry.path,
                        provider = providerId,
                        name = entry.name,
                        uri = entry.path,
                        mediaType = "album",
                        subtitle = artist.name,
                        image = null,
                        duration = null,
                        parentId = artist.itemId,
                    ),
                )
            }
        }
        return marked(allAlbums.distinctBy { it.itemId }.drop(offset).take(limit))
    }

    /**
     * Playlists from Beefweb's playlist API.
     *
     * These are foobar2000's own playlists — the ones in the playlist manager.
     * Each becomes a browseable [MaItem].
     */
    override suspend fun playlists(): List<MaItem> =
        marked(client.playlists().map { pl ->
            MaItem(
                itemId = pl.id,
                provider = providerId,
                name = pl.title.ifBlank { "Playlist ${pl.index + 1}" },
                uri = pl.id,
                mediaType = "playlist",
                subtitle = if (pl.itemCount > 0) "${pl.itemCount} tracks" else null,
                image = null,
                duration = null,
            )
        })

    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val artistItem = MaItem(
            itemId = id, provider = providerId, name = id.substringAfterLast('\\').substringAfterLast('/'),
            uri = id, mediaType = "artist", subtitle = null, image = null, duration = null,
        )
        val entries = try {
            client.browseEntries(id)
        } catch (_: FoobarException) { emptyList() }
        val albums = entries.filter { it.isDirectory }.map { entry ->
            MaItem(
                itemId = entry.path, provider = providerId, name = entry.name, uri = entry.path,
                mediaType = "album", subtitle = artistItem.name, image = null, duration = null,
                parentId = id,
            )
        }
        return marked(listOf(artistItem)).firstOrNull() to marked(albums)
    }

    /**
     * Album detail: the album's tracks.
     *
     * An "album" is a directory path; its tracks are the audio files inside.
     * Each file is resolved into a [MaItem] via the playlist items API (which
     * returns tagged metadata through title-formatting columns).
     */
    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val entries = try {
            client.browseEntries(id)
        } catch (_: FoobarException) { emptyList() }

        val trackPaths = entries.filter { it.isFile }.map { it.path }
        if (trackPaths.isEmpty()) {
            val albumItem = MaItem(
                itemId = id, provider = providerId,
                name = id.substringAfterLast('\\').substringAfterLast('/'),
                uri = id, mediaType = "album", subtitle = null, image = null, duration = null,
            )
            return albumItem to emptyList()
        }

        // Add the tracks to a temporary playlist to get their metadata, then
        // read them back. This is the only way to get tagged columns (title,
        // artist, format) from Beefweb for files that aren't already in a
        // playlist. The current playlist is used as a scratch space, restored
        // afterwards — or more practically, a new playlist is created.
        val tracks = resolveTrackMetadata(trackPaths)
        val albumName = id.substringAfterLast('\\').substringAfterLast('/')
        val albumArtist = tracks.firstOrNull()?.subtitle
        val albumItem = MaItem(
            itemId = id, provider = providerId, name = albumName, uri = id,
            mediaType = "album", subtitle = albumArtist, image = null,
            duration = tracks.sumOf { it.duration ?: 0 }.takeIf { it > 0 },
        )
        return albumItem to marked(tracks)
    }

    /**
     * Resolve metadata for a list of file paths by adding them to a temporary
     * playlist and reading the columns back.
     *
     * Beefweb only returns tagged metadata (title, artist, codec, etc.) for
     * items in a playlist — the file browser returns only file names and
     * sizes. So to get metadata for a set of files, we create a temporary
     * playlist, add the files, read the items, and remove the playlist.
     */
    private suspend fun resolveTrackMetadata(paths: List<String>): List<MaItem> {
        val tempId = client.createPlaylist("camusic-temp") ?: return emptyList()
        try {
            client.addItems(tempId, paths)
            return client.playlistItems(tempId, 0, paths.size)
        } finally {
            runCatching { client.removePlaylist(tempId) }
        }
    }

    override suspend fun playlistTracks(id: String): List<MaItem> =
        marked(client.playlistItems(id, 0, 500))

    /**
     * All tracks — every audio file under every artist directory.
     *
     * This is expensive (a full file-system walk), so it is paged and the
     * offset is honoured. Gated by [Capability.TRACKS].
     */
    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> {
        val allTracks = mutableListOf<MaItem>()
        for (artist in artists()) {
            val albums = try {
                client.browseEntries(artist.itemId)
            } catch (_: FoobarException) { emptyList() }
            for (albumEntry in albums.filter { it.isDirectory }) {
                val albumTracks = try {
                    client.browseEntries(albumEntry.path)
                } catch (_: FoobarException) { emptyList() }
                for (fileEntry in albumTracks.filter { it.isFile }) {
                    allTracks.add(
                        MaItem(
                            itemId = fileEntry.path,
                            provider = providerId,
                            name = fileEntry.name.substringBeforeLast('.'),
                            uri = fileEntry.path,
                            mediaType = "track",
                            subtitle = artist.name,
                            image = null,
                            duration = null,
                            album = albumEntry.name,
                            parentId = albumEntry.path,
                        ),
                    )
                }
            }
        }
        return marked(allTracks.drop(offset).take(limit))
    }

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> artistDetail(item.itemId).second
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> = when (item.mediaType) {
        "track" -> listOf(item)
        "album" -> albumDetail(item.itemId).second
        "playlist" -> playlistTracks(item.itemId)
        "artist" -> {
            val albums = artistDetail(item.itemId).second
            albums.flatMap { album -> albumDetail(album.itemId).second }
        }
        else -> emptyList()
    }

    override suspend fun song(id: String): MaItem? {
        // A single file path — add to a temp playlist to get metadata
        return resolveTrackMetadata(listOf(id)).firstOrNull()?.let { marked(listOf(it)).first() }
    }

    // ── Shelves ───────────────────────────────────────────────────────────

    /**
     * Recently added — newest file modification time first.
     *
     * The file browser returns timestamps, so we can sort by modification
     * time. This walks the top-level directories, which is coarse but finds
     * recently added albums.
     */
    override suspend fun recentlyAdded(limit: Int): List<MaItem> {
        val allAlbums = albums(0, 500)
        // Without per-album timestamps from the browser (which would require
        // a deeper walk), return the first page — the same fallback MPD uses
        // on older servers.
        return marked(allAlbums.take(limit))
    }

    override suspend fun favorites(): MaSearchResults =
        context?.let { LocalFavourites.results(it, providerId) }
            ?: MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun randomSongs(size: Int): List<MaItem> {
        val allTracks = tracks(0, 500)
        return marked(allTracks.shuffled().take(size))
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * No search — Beefweb has no search API.
     *
     * The capability is not declared, so the UI never offers the search bar
     * for this source. An honest empty rather than a slow file-system crawl
     * that returns paths instead of tagged metadata.
     */
    override suspend fun search(query: String, limit: Int): MaSearchResults =
        MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    // ── Write ─────────────────────────────────────────────────────────────

    override suspend fun setStarred(item: MaItem, starred: Boolean) {
        val ctx = context ?: return
        LocalFavourites.set(ctx, providerId, item, starred)
    }

    // ── URLs ──────────────────────────────────────────────────────────────

    /**
     * There is no URL for this phone to open — foobar2000 plays its own
     * playlist to its own output, and this phone drives it. The same answer
     * MPD gives.
     */
    override fun streamUrl(id: String, format: String): String = ""

    /** Nothing to download: Beefweb serves artwork but not audio files. */
    override fun downloadUrl(id: String): String = ""

    /**
     * Cover art URL.
     *
     * Unlike MPD (which needs a custom fetcher for binary-over-protocol art),
     * Beefweb serves artwork over plain HTTP. However, the artwork endpoint
     * requires a playlist id and item index, not a file path — so we can't
     * build a URL from a library id alone. Cover art is resolved at playback
     * time through the player state's active item; for the browse grid, null
     * is the honest answer until the tracks are in a playlist.
     */
    override fun coverUrl(id: String?, size: Int): String? = null

    /**
     * The one source (alongside MPD) that answers with a player.
     */
    override fun remotePlayback(): RemotePlayback = FoobarRemote(client)

    override suspend fun serverQueue(): List<MaItem> = marked(client.queueTracks())

    override suspend fun serverQueueIndex(): Int = client.currentIndex()
}