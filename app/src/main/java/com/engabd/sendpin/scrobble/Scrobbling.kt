package com.engabd.sendpin.scrobble

import kotlinx.serialization.Serializable

/**
 * One listen, as a scrobbling service wants it: what played and when it started.
 *
 * Built from the track at the moment it counted as played (half the track or four
 * minutes — the convention Last.fm and ListenBrainz both use), so it carries only
 * what those services read.
 */
@Serializable
data class Play(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    /** Wall-clock start of the listen, in epoch milliseconds. */
    val startedAtMs: Long,
    val trackNumber: Int? = null,
) {
    /** Both services refuse a listen without a title and an artist. */
    val scrobblable: Boolean get() = title.isNotBlank() && artist.isNotBlank()
}

/**
 * A place listens are sent — ListenBrainz or Last.fm.
 *
 * A server library (Navidrome, Jellyfin…) is not one of these: it has its own report
 * path through `MusicSource.scrobble`, and the queue carries those separately.
 */
interface ScrobbleService {
    /** Stable id, used to tag queued listens: "listenbrainz", "lastfm". */
    val id: String

    /** "Now playing" — best effort, never queued: it is stale the moment it is late. */
    suspend fun nowPlaying(play: Play)

    /** A completed listen. Throws [ScrobbleException] with [ScrobbleException.retry] telling the queue what to do. */
    suspend fun submit(play: Play)
}

/**
 * Why a submission failed, and whether sending it again later can help.
 *
 * A dropped connection or a service having a bad minute is worth retrying; a revoked
 * token or a listen the service rejects as invalid will fail the same way forever, and
 * keeping it would only block the queue behind it.
 */
class ScrobbleException(message: String, val retry: Boolean) : Exception(message)
