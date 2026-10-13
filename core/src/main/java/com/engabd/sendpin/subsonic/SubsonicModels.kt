package com.engabd.sendpin.subsonic

import androidx.compose.runtime.Immutable
import com.engabd.sendpin.ma.MaItem

// Library-wide answers that MusicSource returns for every kind of server. They kept
// the Subsonic package (and so every import of them) when they moved here from
// SubsonicClient.kt, so that :core can declare MusicSource without the client.

/**
 * A play queue another client left on the server, for cross-device resume.
 *
 * @param changedBy the client name that saved it — worth showing, because "resume
 *   from your laptop" is a different offer from "resume from this phone".
 */
data class SavedQueue(
    val tracks: List<MaItem>,
    val index: Int,
    val positionMs: Long,
    val changedBy: String?,
)

/**
 * What `getArtistInfo2` actually carries. Only the biography was ever read, and the
 * call explicitly asked for zero similar artists.
 */
@Immutable
data class ArtistInfo(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val similar: List<MaItem> = emptyList(),
)

/** What `getAlbumInfo2` carries, beyond the notes the app was reading. */
data class AlbumInfo(
    val notes: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
)
