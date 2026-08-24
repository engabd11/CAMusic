package com.engabd.sendpin.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed (or substantially-listened) play, for the Stats screen.
 *
 * Written once per track from [com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel]'s
 * existing track-change collectors — see there for the completion threshold, which
 * mirrors [com.engabd.sendpin.ma.LibraryViewModel.submitWhenPlayed]'s scrobble
 * threshold (half the track, or four minutes) so a skip doesn't inflate the stats
 * the same way it doesn't inflate a scrobble.
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Epoch millis when this play was logged. */
    val timestamp: Long,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The `MusicSource.providerId` (or `MaNowPlaying`'s backend name) this played through. */
    val provider: String,
    val codec: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val durationPlayedMs: Long = 0,
    /**
     * Snapshot of the track's offline scan at play time, for the Stats screen's
     * Listening DNA section — opt-in via `AppSettings.listeningDna`, null when off
     * or when the track had no scan at the time. Snapshotted rather than joined at
     * query time: `trackId` here is an MA-queue-scoped id, not the id space
     * `TrackScanRepository` keys scans by, so there is no reliable way to look a
     * scan back up from a stored history row after the fact.
     */
    val bpm: Float? = null,
    /** [com.engabd.sendpin.audio.MusicalKey.tonic], 0-11. */
    val keyTonic: Int? = null,
    /** [com.engabd.sendpin.audio.MusicalMode] name, "MAJOR" or "MINOR". */
    val keyMode: String? = null,
    /** [com.engabd.sendpin.audio.IntensityProfile.character], 0..1. */
    val energy: Float? = null,
)
