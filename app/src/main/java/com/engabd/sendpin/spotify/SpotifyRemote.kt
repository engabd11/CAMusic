package com.engabd.sendpin.spotify

import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.audio.RemoteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.Player.EventsListener

/**
 * A Spotify queue playing **inside this process**, with the app as its remote.
 *
 * The shape is MPD's (`MpdRemote`), the reason is the opposite: MPD plays remotely
 * because it must, Spotify plays in-process because route B wants librespot's own
 * pipeline — decode, ReplayGain, gapless — and Light Sync reads the tap the sink
 * feeds. Either way `LocalPlayer` holds no ExoPlayer state for this queue; it
 * drives [Player] through here and reads state back in [poll].
 *
 * The librespot player owns its own queue context: [setQueue] loads the first uri
 * and librespot's `autoplay`/context machinery handles onward playback, so the
 * "queue" here is mirrored app-side for display and index math only.
 */
class SpotifyRemote(private val player: () -> Player?) : RemotePlayback {

    /** App-side mirror of what was loaded, for [poll]'s index and length. */
    private val queue = ArrayDeque<com.engabd.sendpin.audio.LocalTrack>()

    @Volatile
    private var index: Int = -1

    @Volatile
    private var paused: Boolean = false

    private fun requirePlayer(): Player =
        player() ?: throw IllegalStateException("Spotify session is not connected")

    private fun uriOf(track: com.engabd.sendpin.audio.LocalTrack) = "spotify:track:${track.id}"

    override suspend fun setQueue(tracks: List<com.engabd.sendpin.audio.LocalTrack>, startIndex: Int) =
        withContext(Dispatchers.IO) {
            queue.clear()
            queue.addAll(tracks)
            index = startIndex.takeIf { it in tracks.indices } ?: 0
            paused = false
            requirePlayer().load(uriOf(tracks[index]), true, false)
        }

    override suspend fun addToQueue(tracks: List<com.engabd.sendpin.audio.LocalTrack>) {
        // librespot 1.6.5 has no app-side queue-append: the load point owns the
        // context. Appending replaces the tail by re-loading the current uri first
        // is worse than an honest no-op with the toast the caller already shows.
        queue.addAll(tracks)
    }

    override suspend fun playNext(tracks: List<com.engabd.sendpin.audio.LocalTrack>, afterIndex: Int) {
        queue.addAll(afterIndex + 1, tracks)
    }

    override suspend fun playAt(indexToPlay: Int) = withContext(Dispatchers.IO) {
        index = indexToPlay
        paused = false
        requirePlayer().load(uriOf(queue.elementAt(indexToPlay)), true, false)
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        paused = true
        requirePlayer().pause()
        Unit
    }

    override suspend fun resume() = withContext(Dispatchers.IO) {
        paused = false
        requirePlayer().play()
        Unit
    }

    override suspend fun next() = withContext(Dispatchers.IO) {
        if (index < queue.size - 1) index += 1
        paused = false
        requirePlayer().next()
        Unit
    }

    override suspend fun previous() = withContext(Dispatchers.IO) {
        if (index > 0) index -= 1
        paused = false
        requirePlayer().previous()
        Unit
    }

    override suspend fun seekTo(ms: Long) = withContext(Dispatchers.IO) {
        requirePlayer().seek(ms.toInt())
        Unit
    }

    override suspend fun setVolume(volume: Float) = withContext(Dispatchers.IO) {
        requirePlayer().setVolume((volume * Player.VOLUME_MAX).toInt())
        Unit
    }

    override suspend fun setShuffle(on: Boolean) = withContext(Dispatchers.IO) {
        requirePlayer().setShuffle(on)
        Unit
    }

    override suspend fun setRepeat(mode: String) = withContext(Dispatchers.IO) {
        // "off" | "all" | "one" — librespot has track and context repeat.
        requirePlayer().setRepeat(mode == "one", mode == "all")
        Unit
    }

    override suspend fun setReplayGain(mode: String) = Unit

    override suspend fun removeAt(index: Int) {
        if (index == this.index) return
        queue.removeAt(index)
        if (index < this.index) this.index -= 1
    }

    override suspend fun move(from: Int, to: Int) {
        if (from == to) return
        val item = queue.removeAt(from)
        queue.add(to, item)
        index = when (index) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) index - 1 else index + 1
            else -> index
        }
    }

    override suspend fun shuffleQueue() {
        queue.shuffle()
    }

    override suspend fun clear() {
        queue.clear()
        index = -1
    }

    override suspend fun poll(): RemoteState? = withContext(Dispatchers.IO) {
        val p = player() ?: return@withContext null
        val timeMs = p.time().toLong().coerceAtLeast(0)
        RemoteState(
            playing = !paused,
            stopped = queue.isEmpty() || index < 0,
            index = index,
            positionMs = timeMs,
            durationMs = queue.getOrNull(index)?.durationMs ?: 0,
            volume = null,
            outputFormat = null,
        )
    }
}
