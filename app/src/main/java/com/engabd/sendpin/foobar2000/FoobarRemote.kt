package com.engabd.sendpin.foobar2000

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.audio.RemoteState

/**
 * foobar2000 as the thing that actually plays, with this phone driving it.
 *
 * See [RemotePlayback] for why it is this way round — the same argument as
 * MPD. foobar2000 is a player, not a library with a stream endpoint. Its
 * audio goes to whatever output device its own config names (the DAC on the
 * PC), and this phone becomes the remote.
 *
 * ## The one thing to know
 *
 * foobar2000's playlist and the app's queue have to stay the same list in the
 * same order, because every command here addresses a track by its *index* in
 * the playlist and every reading back does too. So the app's queue is pushed
 * to foobar2000's current playlist whole ([setQueue]) rather than a track at a
 * time, and every edit — remove, move, shuffle — is sent as the same edit on
 * both sides.
 *
 * A track's foobar2000 identity is its file path, which is exactly what the
 * browse screens carry as `itemId` and what [LocalTrack.scrobbleId] holds. A
 * track from anywhere else — a download, a phone file — has no foobar2000
 * path at all and is dropped rather than guessed at.
 *
 * ## Volume
 *
 * Beefweb's volume is an absolute value in the player's own scale — typically
 * dB, but configurable. The min/max from [FoobarClient.FoobarState] are used
 * to normalise the app's 0..1 float to that range, and to convert the reading
 * back. When min/max are absent (a server that doesn't report them), volume is
 * reported as null so the app leaves the control alone rather than showing a
 * slider pinned at nothing — the same treatment MPD gets for its -1.
 */
class FoobarRemote(private val client: FoobarClient) : RemotePlayback {

    /**
     * The foobar2000 path for a track, or null when it isn't foobar2000's to play.
     *
     * `scrobbleId` rather than `id`: the same convention [MpdRemote] uses —
     * `scrobbleId` is the library's own id for the track, which for foobar2000
     * is the file path.
     */
    private fun file(track: LocalTrack): String? =
        track.scrobbleId?.takeIf { track.scrobbleProvider == FoobarClient.PROVIDER }

    private fun files(tracks: List<LocalTrack>): List<String> = tracks.mapNotNull(::file)

    /**
     * The current playlist id, cached so queue operations don't need an extra
     * round trip on every call. Refreshed by [ensurePlaylist] at most every
     * [PLAYLIST_TTL_MS].
     */
    @Volatile
    private var cachedPlaylistId: String? = null

    @Volatile
    private var playlistFetchedAt: Long = 0

    private suspend fun ensurePlaylist(): String? {
        val now = System.currentTimeMillis()
        if (cachedPlaylistId == null || now - playlistFetchedAt > PLAYLIST_TTL_MS) {
            cachedPlaylistId = client.currentPlaylist()?.id
            playlistFetchedAt = now
        }
        return cachedPlaylistId
    }

    override suspend fun setQueue(tracks: List<LocalTrack>, startIndex: Int) {
        val playlistId = ensurePlaylist() ?: return
        client.replacePlaylist(playlistId, files(tracks), play = true, startIndex = startIndex)
    }

    override suspend fun addToQueue(tracks: List<LocalTrack>) {
        val playlistId = ensurePlaylist() ?: return
        client.addItems(playlistId, files(tracks))
    }

    override suspend fun playNext(tracks: List<LocalTrack>, afterIndex: Int) {
        val playlistId = ensurePlaylist() ?: return
        val at = afterIndex + 1
        val fileLists = files(tracks)
        if (fileLists.isEmpty()) return
        client.addItemsAt(playlistId, fileLists, at)
    }

    override suspend fun replaceUpcoming(tracks: List<LocalTrack>, afterIndex: Int) {
        val playlistId = ensurePlaylist() ?: return
        // Remove everything after the current track, then append the new tail.
        // The current entry keeps playing while the tail is swapped — the same
        // pattern as MpdClient.replaceAfter.
        val playlist = client.currentPlaylist() ?: return
        val from = afterIndex + 1
        if (playlist.itemCount > from) {
            val toRemove = (from until playlist.itemCount).toList()
            client.removeItems(playlistId, toRemove)
        }
        client.addItems(playlistId, files(tracks))
    }

    override suspend fun playAt(index: Int) {
        val playlistId = ensurePlaylist() ?: return
        client.playItem(playlistId, index)
    }

    override suspend fun pause() = client.pause()
    override suspend fun resume() = client.play()
    override suspend fun next() = client.next()
    override suspend fun previous() = client.previous()

    override suspend fun seekTo(ms: Long) {
        client.seekTo(ms.coerceAtLeast(0L) / 1000.0)
    }

    override suspend fun setVolume(volume: Float) {
        // Normalise 0..1 to Beefweb's scale. Without min/max we can't set
        // a meaningful absolute value, so we skip — the app's volume control
        // is hidden when poll() reports null volume.
        val state = client.playerState() ?: return
        val min = state.volumeMin ?: return
        val max = state.volumeMax ?: return
        val scaled = min + (max - min) * volume.coerceIn(0f, 1f)
        client.setVolume(scaled)
    }

    /**
     * foobar2000's shuffle is a playback mode, not a queue reorder — the same
     * as MPD's `random` flag. Beefweb controls this through player options,
     * which are not fully documented. For now, this is a no-op; the queue
     * shuffle is handled client-side by the app's own shuffle.
     */
    override suspend fun setShuffle(on: Boolean) {
        // Beefweb's playback mode options are not well-documented in the API.
        // The app's own shuffle handles the queue order; this is left as a
        // no-op rather than guessing at an undocumented endpoint.
    }

    override suspend fun setRepeat(mode: String) {
        // Same as shuffle — Beefweb's repeat options are player-specific and
        // not documented in the API. Left as a no-op.
    }

    /**
     * ReplayGain is controlled in foobar2000's own preferences, not through
     * Beefweb's API. The capability is not declared, so the app never calls
     * this — but the interface requires it.
     */
    override suspend fun setReplayGain(mode: String) {
        // No-op — see class docs and FoobarSource capabilities.
    }

    override suspend fun removeAt(index: Int) {
        val playlistId = ensurePlaylist() ?: return
        client.removeItems(playlistId, listOf(index))
    }

    override suspend fun move(from: Int, to: Int) {
        val playlistId = ensurePlaylist() ?: return
        client.moveItem(playlistId, from, to)
    }

    override suspend fun shuffleQueue() {
        // Client-side shuffle would require reading the whole playlist,
        // shuffling, and rewriting it. Left for a future iteration.
    }

    override suspend fun clear() {
        val playlistId = ensurePlaylist() ?: return
        client.clearPlaylist(playlistId)
    }

    /**
     * The output device name, cached rather than fetched every [poll] — the
     * set of outputs essentially never changes while an app session runs.
     */
    @Volatile
    private var cachedOutputName: String? = null

    @Volatile
    private var outputNameFetchedAt: Long = 0

    private suspend fun outputName(): String? {
        val now = System.currentTimeMillis()
        if (outputNameFetchedAt == 0L || now - outputNameFetchedAt > OUTPUT_NAME_TTL_MS) {
            try {
                cachedOutputName = client.outputs().firstOrNull { it.isActive }?.name
                outputNameFetchedAt = now
            } catch (_: FoobarException) {
                outputNameFetchedAt = now
            }
        }
        return cachedOutputName
    }

    override suspend fun poll(): RemoteState? {
        val s = client.playerState() ?: return null

        // Normalise volume to 0..1, or null when min/max are unknown.
        val volume = if (s.volumeValue != null && s.volumeMin != null && s.volumeMax != null) {
            val range = s.volumeMax - s.volumeMin
            if (range > 0) {
                ((s.volumeValue - s.volumeMin) / range).toFloat().coerceIn(0f, 1f)
            } else null
        } else null

        // The active item's columns carry the format; the player state itself
        // doesn't include them in the same way MPD's `status` does. We report
        // what we can — the output device name is the main thing.
        return RemoteState(
            playing = s.playing,
            stopped = s.stopped,
            index = s.itemIndex,
            positionMs = (s.positionSeconds * 1000).toLong(),
            durationMs = (s.durationSeconds * 1000).toLong(),
            volume = volume,
            outputFormat = null,  // Beefweb doesn't report output format in player state
            outputDeviceName = outputName(),
        )
    }

    private companion object {
        const val PLAYLIST_TTL_MS = 30_000L
        const val OUTPUT_NAME_TTL_MS = 20_000L
    }
}