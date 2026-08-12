package com.engabd.sendpin.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.engabd.sendpin.ma.MaAudioFormat

/**
 * Room entity for a downloaded audio file.
 *
 * Mirrors the legacy [com.engabd.sendpin.download.DownloadedTrack] fields so the
 * JSON index can be imported without loss. The source provider is stored as a
 * string because the provider tag is a simple discriminator, not a foreign key.
 */
@Entity(tableName = "downloads")
data class DownloadedTrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String? = null,
    val filePath: String,
    /** Server cover URL, kept only to re-fetch if the local cover is missing. */
    val image: String? = null,
    val album: String? = null,
    /** Absolute path to the cached cover beside the audio. */
    val coverPath: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int? = null,
    val albumId: String? = null,
    /** Original file format recorded at download time. */
    val codec: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitRate: Int = 0,
    val channels: Int = 0,
    val sizeBytes: Long = 0L,
    /** The library this came from, as a MusicSource.providerId. */
    val sourceProvider: String? = null,
) {
    fun format(): MaAudioFormat? =
        if (codec.isNullOrBlank()) null
        else MaAudioFormat(
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitRate = bitRate,
            channels = channels,
            sizeBytes = sizeBytes,
        )
}
