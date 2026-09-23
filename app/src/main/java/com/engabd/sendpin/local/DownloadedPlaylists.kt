package com.engabd.sendpin.local

import android.content.Context
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.local.db.DownloadPlaylistDao
import com.engabd.sendpin.local.db.DownloadedPlaylistEntity
import com.engabd.sendpin.local.db.DownloadedPlaylistTrackEntity
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.PlaylistWithCount
import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Playlists that were downloaded as playlists, and stayed playlists.
 *
 * Downloading a playlist used to flatten it: `LibraryViewModel.download` resolved
 * `tracksUnder(item)` and the container was discarded on the spot, so the files
 * arrived filed under their own albums and artists and the thing actually asked for
 * no longer existed anywhere on the phone. Rebuilding it by hand, offline, was the
 * only way back.
 *
 * This records the container and the order alongside the files. Deliberately
 * **alongside**: a downloaded playlist adds nothing to disk and duplicates no audio,
 * so a track belonging to a playlist is the same file that shows up under its album.
 *
 * Process-scoped, hung off `SendpinApp` beside `downloads` — the codebase's
 * convention, no DI.
 */
class DownloadedPlaylists(context: Context) {

    private val dao: DownloadPlaylistDao = LocalMediaDatabase.get(context).downloadPlaylistDao()

    /** Every downloaded playlist as a library item, newest first. */
    val playlists: Flow<List<MaItem>> = dao.observeAll().map { rows -> rows.map { it.toItem() } }

    suspend fun all(): List<MaItem> = dao.all().map { it.toItem() }

    suspend fun isEmpty(): Boolean = dao.all().isEmpty()

    /** One playlist's downloaded tracks, in the order the playlist put them. */
    suspend fun tracks(playlistId: String): List<DownloadedTrack> =
        dao.tracksOf(playlistId).map { it.toModel() }

    /** One playlist's downloaded tracks as library items, in playlist order. */
    suspend fun trackItems(playlistId: String): List<MaItem> =
        tracks(playlistId).map(DownloadsIndex::item)

    suspend fun get(playlistId: String): MaItem? =
        dao.all().firstOrNull { it.id == playlistId }?.toItem()

    /**
     * Record (or extend) a downloaded playlist.
     *
     * Called **after** a download run with the ids that actually landed, so a run that
     * half-succeeded produces a half playlist rather than a playlist full of rows
     * pointing at files that were never written. That is also why this takes ids
     * rather than the requested items.
     *
     * Re-downloading the same playlist appends rather than replaces: tracks already
     * filed keep the position they had, and new ones go on the end. Replacing would
     * renumber a playlist the user may have been part-way through, and the server's
     * order for tracks it already gave us has not changed.
     */
    suspend fun record(
        provider: String?,
        sourceId: String,
        name: String,
        image: String?,
        trackIds: List<String>,
    ): String {
        val id = DownloadsIndex.playlistId(provider, sourceId)
        val now = System.currentTimeMillis()
        val existing = dao.get(id)
        dao.upsert(
            DownloadedPlaylistEntity(
                id = id,
                name = name,
                sourceProvider = provider,
                sourceId = sourceId,
                image = image ?: existing?.image,
                coverPath = existing?.coverPath,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        appendMembers(id, trackIds)
        return id
    }

    /** Add ids not already filed under this playlist, after whatever is there. */
    private suspend fun appendMembers(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val known = dao.trackIdsOf(playlistId).toHashSet()
        var next = dao.maxPosition(playlistId) + 1
        val fresh = trackIds.filter { it.isNotBlank() && known.add(it) }
        if (fresh.isEmpty()) return
        dao.addMembers(fresh.map { DownloadedPlaylistTrackEntity(playlistId, it, next++) })
    }

    /**
     * Forget a playlist. The files stay unless [alsoDeleteTracks] is handled by the
     * caller — this class never deletes audio, because a track in a playlist is
     * usually also in an album the user is keeping.
     */
    suspend fun remove(playlistId: String) = dao.remove(playlistId)

    suspend fun removeAll() = dao.removeAll()

    /**
     * Drop a track from every playlist holding it.
     *
     * Called when its file is deleted. Membership is not a foreign key (see
     * [DownloadedPlaylistTrackEntity]), so nothing prunes this automatically — and a
     * stale row would otherwise sit in the table for the lifetime of the install.
     * Reads already skip it, so this is hygiene rather than correctness.
     */
    suspend fun forgetTrack(trackId: String) = dao.forgetTrack(trackId)

    /** The track ids this playlist already holds — for "update from server". */
    suspend fun knownTrackIds(playlistId: String): Set<String> =
        dao.trackIdsOf(playlistId).toHashSet()

    private fun PlaylistWithCount.toItem(): MaItem =
        DownloadsIndex.playlistItem(id, name, trackCount, image)
}
