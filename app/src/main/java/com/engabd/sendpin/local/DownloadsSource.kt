package com.engabd.sendpin.local

import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.library.SourceError
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import java.io.File

/**
 * Offline downloads as a first-class library.
 *
 * Downloads used to be a category grafted into the browse tree — a flat list of every
 * downloaded track, with its own bespoke rendering branch in the library screen, and
 * no artists, no albums, no tiles. That is the one library guaranteed to work with
 * every server switched off, and it had the least usable views in the app.
 *
 * Behind [MusicSource] it gets all of them for free, and the special cases go away.
 * The management screen ([com.engabd.sendpin.ui.screens.DownloadsScreen]) stays where
 * it is and keeps doing what it does — bytes on disk, retries, delete-all — because
 * none of that is a thing a `MusicSource` knows about, and it should not grow one.
 *
 * ## Snapshot per call, deliberately
 *
 * [LocalMediaSource] caches its scan in a `by lazy`, which is right there: MediaStore
 * does not change under it within a session in any way worth re-scanning for. The
 * download index changes every time a file lands or is deleted, so every method here
 * reads [DownloadManager.downloads] fresh. This is the one thing not to copy from the
 * template next door.
 */
class DownloadsSource(private val downloads: DownloadManager) : MusicSource {

    override val kind: ServerKind = ServerKind.DOWNLOADS
    override val providerId: String = MusicSources.DOWNLOAD_PROVIDER
    override val serverUrl: String = "On this phone"

    /** Nothing to transcode: a download is the original file. */
    override var streamFormat: String = "raw"

    /**
     * Search, and the format badge, and nothing else.
     *
     * No `PLAYLIST_READ`, no `GENRES`, no `FAVORITES` — see [Capability]'s own note
     * that a shelf which is always empty is worse than the feature being absent.
     * `DOWNLOAD` in particular would be circular.
     */
    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.TRACKS,
        Capability.RICH_FORMAT,
    )

    private fun all(): List<DownloadedTrack> = downloads.downloads.value

    /** Never unreachable: the files are on the phone or they are not there at all. */
    override suspend fun probe(): SourceError? = null

    override suspend fun artists(): List<MaItem> = DownloadsIndex.artists(all())

    override suspend fun albums(offset: Int, limit: Int): List<MaItem> =
        DownloadsIndex.albums(all()).drop(offset).take(limit)

    override suspend fun playlists(): List<MaItem> = emptyList()

    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> =
        DownloadsIndex.artistDetail(all(), id)

    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> =
        DownloadsIndex.albumDetail(all(), id)

    override suspend fun playlistTracks(id: String): List<MaItem> = emptyList()

    override suspend fun tracks(offset: Int, limit: Int): List<MaItem> =
        DownloadsIndex.items(all()).drop(offset).take(limit)

    override suspend fun children(item: MaItem): List<MaItem> = when (item.mediaType) {
        "artist" -> DownloadsIndex.artistAlbums(all(), item.itemId)
        "album" -> DownloadsIndex.albumTracks(all(), item.itemId)
        else -> emptyList()
    }

    override suspend fun tracksUnder(item: MaItem): List<MaItem> =
        DownloadsIndex.tracksUnder(all(), item)

    override suspend fun song(id: String): MaItem? = downloads.get(id)?.let(DownloadsIndex::item)

    override suspend fun recentlyAdded(limit: Int): List<MaItem> =
        DownloadsIndex.recentlyAdded(all(), { runCatching { File(it.filePath).lastModified() }.getOrDefault(0L) }, limit)

    override suspend fun favorites(): MaSearchResults =
        MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun randomSongs(size: Int): List<MaItem> =
        DownloadsIndex.items(all()).shuffled().take(size)

    override suspend fun randomAlbums(limit: Int): List<MaItem> =
        DownloadsIndex.albums(all()).shuffled().take(limit)

    override suspend fun search(query: String, limit: Int): MaSearchResults =
        DownloadsIndex.search(all(), query, limit)

    /**
     * A `file://` URL, not the bare path: media3 resolves the former, and
     * `TrackScanRepository` falls back to this when a track has no `localPath`.
     *
     * Belt and braces in the common case — playback goes through
     * [DownloadManager.toLocalTrack], which sets `localPath` directly, and a
     * DownloadsSource item's `itemId` *is* the download id, so that lookup hits.
     */
    override fun streamUrl(id: String, format: String): String =
        downloads.get(id)?.filePath?.let { "file://$it" } ?: ""

    override fun downloadUrl(id: String): String = streamUrl(id)

    override fun coverUrl(id: String?, size: Int): String? = id?.let { downloads.get(it)?.artUri }
}
