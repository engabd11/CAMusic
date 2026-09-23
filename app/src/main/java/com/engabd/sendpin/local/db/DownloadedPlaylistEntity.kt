package com.engabd.sendpin.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playlist that was downloaded as a playlist, rather than as a pile of songs.
 *
 * Downloading a playlist used to flatten it to its tracks and throw the container
 * away — `LibraryViewModel.download` resolved `tracksUnder(item)` and nothing
 * downstream ever saw the playlist again. The files were all there, filed under their
 * own albums and artists, and the thing the user had actually asked for was gone. On
 * a plane, rebuilding it by hand was the only way back.
 *
 * Kept in Room rather than in SharedPreferences beside
 * [com.engabd.sendpin.library.LocalFavourites] because membership here is **ordered**
 * and can run to hundreds of rows. A favourite is a set; a playlist is a sequence,
 * and the sequence is most of the point.
 */
@Entity(tableName = "downloaded_playlists")
data class DownloadedPlaylistEntity(
    /**
     * The playlist's own id, namespaced by where it came from.
     *
     * Namespaced rather than the server's raw id because two libraries can both hand
     * out playlist "3" — see [com.engabd.sendpin.local.DownloadedPlaylists.idFor].
     */
    @PrimaryKey
    val id: String,
    val name: String,
    /** The `MusicSource.providerId` this was downloaded from. */
    val sourceProvider: String? = null,
    /** The playlist's id on that server, for re-syncing it later. */
    val sourceId: String? = null,
    /** Server cover URL, kept only to re-fetch if the local cover is missing. */
    val image: String? = null,
    /** Absolute path to a cached cover, when the playlist had one of its own. */
    val coverPath: String? = null,
    val createdAt: Long = 0L,
    /** Last time tracks were added from the server. Drives "Updated …" in the UI. */
    val updatedAt: Long = 0L,
)

/**
 * One track's place in one downloaded playlist.
 *
 * Deliberately **not** a foreign key onto `downloads`. A playlist and its files are
 * deleted by different actions at different times — a track can be removed to free
 * space while the playlist stays, and the playlist can be forgotten while its files
 * stay because they are also in an album the user keeps. A real foreign key with
 * cascade would make one of those silently destroy the other. Membership for a track
 * that is no longer downloaded is simply skipped when the playlist is read, and
 * pruned when a delete goes through [com.engabd.sendpin.local.DownloadedPlaylists].
 *
 * [position] is the track's place in the playlist as the server gave it, which is the
 * one thing an album-grouped download cannot reconstruct.
 */
@Entity(
    tableName = "downloaded_playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId"), Index("trackId")],
)
data class DownloadedPlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Int,
)
