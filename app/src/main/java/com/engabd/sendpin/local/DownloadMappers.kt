package com.engabd.sendpin.local

import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.local.db.DownloadedTrackEntity

/** Convert the legacy JSON model to its Room entity. */
fun DownloadedTrack.toEntity(): DownloadedTrackEntity = DownloadedTrackEntity(
    id = id,
    title = title,
    artist = artist,
    filePath = filePath,
    image = image,
    album = album,
    coverPath = coverPath,
    durationMs = durationMs,
    trackNumber = trackNumber,
    albumId = albumId,
    codec = format?.codec,
    sampleRate = format?.sampleRate ?: 0,
    bitDepth = format?.bitDepth ?: 0,
    bitRate = format?.bitRate ?: 0,
    channels = format?.channels ?: 0,
    sizeBytes = format?.sizeBytes ?: 0L,
    sourceProvider = sourceProvider,
)

/** Convert a Room entity back to the public model. */
fun DownloadedTrackEntity.toModel(): DownloadedTrack = DownloadedTrack(
    id = id,
    title = title,
    artist = artist,
    filePath = filePath,
    image = image,
    album = album,
    coverPath = coverPath,
    durationMs = durationMs,
    trackNumber = trackNumber,
    albumId = albumId,
    format = format(),
    sourceProvider = sourceProvider,
)
