package com.engabd.sendpin.local

import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults

/**
 * Downloads, arranged as a library.
 *
 * A `DownloadedTrack` already carries album, album id, artist, track and disc number —
 * everything an artist/album hierarchy needs. What was missing was anywhere to put it:
 * Downloads was a hand-built flat list injected into the browse tree, so the one place
 * in the app you are guaranteed to be able to play from had none of the views every
 * other library has. This synthesises those views in memory, and [DownloadsSource]
 * hands them to the ordinary browse screens.
 *
 * Pure, and separate from [DownloadsSource] for the usual reason: this is the half
 * worth pinning with tests, and it needs no `Context` to run.
 */
object DownloadsIndex {

    private const val PROVIDER = MusicSources.DOWNLOAD_PROVIDER

    /**
     * A stable album id for a downloaded track.
     *
     * The source server's own album id where there is one — it is a real identity and
     * it groups two pressings of the same record correctly. Rows written before that
     * field existed have none, so those fall back to the name pair, which is what any
     * grouping-by-title would have given anyway.
     */
    fun albumId(t: DownloadedTrack): String =
        t.albumId?.takeIf { it.isNotBlank() }
            ?: "dlalbum:${(t.album ?: "").lowercase()}|${(t.artist ?: "").lowercase()}"

    fun artistId(name: String): String = "dlartist:${name.lowercase()}"

    private fun albumName(t: DownloadedTrack) = t.album?.takeIf { it.isNotBlank() } ?: "Unknown album"

    private fun artistName(t: DownloadedTrack) = t.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"

    /**
     * One downloaded track as a library item.
     *
     * `uri` is the bare file path, not a `file://` URL. That is what the rest of the
     * app already compares against for a download, and changing it here to match
     * [DownloadsSource.streamUrl] would quietly break those comparisons.
     */
    fun item(d: DownloadedTrack): MaItem = MaItem(
        itemId = d.id,
        provider = PROVIDER,
        name = d.title,
        uri = d.filePath,
        mediaType = "track",
        subtitle = d.artist,
        image = d.artUri,
        duration = (d.durationMs / 1000).toInt().takeIf { it > 0 },
        album = d.album,
        parentId = d.albumId,
        trackNumber = d.trackNumber,
        discNumber = d.discNumber,
        audioFormat = d.format,
    )

    /**
     * Downloads grouped by album and in track order, so a downloaded album reads —
     * and plays — as an album rather than in whatever order the files landed in.
     */
    fun items(list: List<DownloadedTrack>): List<MaItem> = list.sortedWith(trackOrder).map(::item)

    /**
     * Disc before track: a two-disc album otherwise interleaves, playing track 1 of
     * disc 1, track 1 of disc 2, track 2 of disc 1…
     */
    private val trackOrder = compareBy<DownloadedTrack>(
        { it.album ?: "" },
        { it.discNumber ?: 0 },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.title },
    )

    fun artists(list: List<DownloadedTrack>): List<MaItem> = list
        .groupBy { artistName(it).lowercase() }
        .map { (_, tracks) ->
            val name = artistName(tracks.first())
            MaItem(
                itemId = artistId(name),
                provider = PROVIDER,
                name = name,
                uri = artistId(name),
                mediaType = "artist",
                subtitle = "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}",
                image = tracks.firstNotNullOfOrNull { it.artUri },
                duration = null,
            )
        }
        .sortedBy { it.name.lowercase() }

    fun albums(list: List<DownloadedTrack>): List<MaItem> = list
        .groupBy { albumId(it) }
        .map { (id, tracks) -> albumItem(id, tracks) }
        .sortedBy { it.name.lowercase() }

    private fun albumItem(id: String, tracks: List<DownloadedTrack>): MaItem {
        val first = tracks.minWithOrNull(trackOrder) ?: tracks.first()
        return MaItem(
            itemId = id,
            provider = PROVIDER,
            name = albumName(first),
            uri = id,
            mediaType = "album",
            subtitle = first.artist,
            image = tracks.firstNotNullOfOrNull { it.artUri },
            duration = null,
        )
    }

    fun albumTracks(list: List<DownloadedTrack>, albumId: String): List<MaItem> =
        items(list.filter { albumId(it) == albumId })

    fun albumDetail(list: List<DownloadedTrack>, albumId: String): Pair<MaItem?, List<MaItem>> {
        val members = list.filter { albumId(it) == albumId }
        if (members.isEmpty()) return null to emptyList()
        return albumItem(albumId, members) to items(members)
    }

    fun artistAlbums(list: List<DownloadedTrack>, artistId: String): List<MaItem> =
        albums(list.filter { artistId(artistName(it)) == artistId })

    fun artistDetail(list: List<DownloadedTrack>, artistId: String): Pair<MaItem?, List<MaItem>> {
        val members = list.filter { artistId(artistName(it)) == artistId }
        if (members.isEmpty()) return null to emptyList()
        val name = artistName(members.first())
        val artist = MaItem(
            itemId = artistId,
            provider = PROVIDER,
            name = name,
            uri = artistId,
            mediaType = "artist",
            subtitle = null,
            image = members.firstNotNullOfOrNull { it.artUri },
            duration = null,
        )
        return artist to albums(members)
    }

    fun tracksUnder(list: List<DownloadedTrack>, item: MaItem): List<MaItem> = when (item.mediaType) {
        "album" -> albumTracks(list, item.itemId)
        "artist" -> items(list.filter { artistId(artistName(it)) == item.itemId })
        "track" -> listOfNotNull(list.firstOrNull { it.id == item.itemId }?.let(::item))
        else -> emptyList()
    }

    fun search(list: List<DownloadedTrack>, query: String, limit: Int): MaSearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return MaSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        val hits = list.filter {
            it.title.lowercase().contains(q) ||
                it.artist?.lowercase()?.contains(q) == true ||
                it.album?.lowercase()?.contains(q) == true
        }
        return MaSearchResults(
            artists = artists(hits).take(limit),
            albums = albums(hits).take(limit),
            tracks = items(hits).take(limit),
            playlists = emptyList(),
        )
    }

    /**
     * Newest first, by whatever [mtimeOf] says.
     *
     * The clock is injected because a download has no "added" field of its own — the
     * file's own modification time is the only record — and reading it is `java.io`,
     * which this file deliberately does not touch.
     */
    fun recentlyAdded(
        list: List<DownloadedTrack>,
        mtimeOf: (DownloadedTrack) -> Long,
        limit: Int,
    ): List<MaItem> = list
        .sortedByDescending(mtimeOf)
        .take(limit)
        .map(::item)
}
