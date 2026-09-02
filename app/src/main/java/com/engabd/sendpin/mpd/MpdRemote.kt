package com.engabd.sendpin.mpd

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemotePlayback
import com.engabd.sendpin.audio.RemoteState

/**
 * MPD as the thing that actually plays, with this phone driving it.
 *
 * See [RemotePlayback] for why it is this way round. In short: MPD's only route
 * to another device is a live stream with no position and no seek, and MPD is
 * usually running on the box the DAC is plugged into anyway — so the sound stays
 * where the hardware is and the phone becomes the remote.
 *
 * ## The one thing to know
 *
 * MPD's queue and the app's queue have to stay the same list in the same order,
 * because every command here addresses a track by its *index* in MPD's queue and
 * every reading back does too. So the app's queue is pushed to MPD whole
 * ([setQueue]) rather than a track at a time, and every edit — remove, move,
 * shuffle — is sent as the same edit on both sides.
 *
 * A track's MPD identity is its file path, which is exactly what the browse
 * screens already carry as `itemId` and what [LocalTrack.scrobbleId] holds. A
 * track from anywhere else — a download, a phone file — has no MPD path at all
 * and is dropped rather than guessed at.
 */
class MpdRemote(private val client: MpdClient) : RemotePlayback {

    /**
     * The MPD path for a track, or null when it isn't MPD's to play.
     *
     * `scrobbleId` rather than `id`: `id` is whatever the queue built the entry
     * from, while `scrobbleId` is the library's own id for it, which for MPD is
     * the file path — the same string [MpdClient.playQueue] adds.
     */
    private fun file(track: LocalTrack): String? =
        track.scrobbleId?.takeIf { track.scrobbleProvider == MpdClient.PROVIDER }

    private fun files(tracks: List<LocalTrack>): List<String> = tracks.mapNotNull(::file)

    override suspend fun setQueue(tracks: List<LocalTrack>, startIndex: Int) {
        client.playQueue(files(tracks), startIndex)
    }

    override suspend fun addToQueue(tracks: List<LocalTrack>) = client.enqueue(files(tracks))

    override suspend fun playNext(tracks: List<LocalTrack>, afterIndex: Int) =
        client.enqueueNext(files(tracks), afterIndex)

    override suspend fun playAt(index: Int) = client.playAt(index)
    override suspend fun pause() = client.pause()
    override suspend fun resume() = client.resume()
    override suspend fun next() = client.next()
    override suspend fun previous() = client.previous()
    override suspend fun seekTo(ms: Long) = client.seekMs(ms)

    override suspend fun setVolume(volume: Float) =
        client.setVolume((volume.coerceIn(0f, 1f) * 100).toInt())

    /**
     * MPD's `random` shuffles the order it plays its queue in, leaving the queue
     * as the user built it — the same thing ExoPlayer's shuffle mode does, and
     * the same thing the queue screen expects to see.
     */
    override suspend fun setShuffle(on: Boolean) = client.setRandom(on)

    override suspend fun setRepeat(mode: String) = client.setRepeat(mode)
    override suspend fun setReplayGain(mode: String) = client.setReplayGainMode(mode)

    override suspend fun removeAt(index: Int) = client.removeAt(index)
    override suspend fun move(from: Int, to: Int) = client.moveInQueue(from, to)
    override suspend fun shuffleQueue() = client.shuffleQueue()
    override suspend fun clear() = client.clearQueue()

    /**
     * The name of MPD's enabled output, cached rather than fetched every
     * [poll] — the set of outputs essentially never changes while an app
     * session runs, so asking once every second next to a `status` poll that
     * does change that often would be one extra round trip a second for
     * nothing. Refreshed at most every [OUTPUT_NAME_TTL_MS].
     */
    @Volatile
    private var cachedOutputName: String? = null

    /**
     * When the last `outputs` attempt landed, succeed or fail, or 0 for never.
     *
     * The freshness question is "have we asked recently", not "do we have a
     * name": a server with no output enabled — or one whose outputs it does not
     * report — answers with no name at all, and keying the cache on the name
     * being null would send a second connection alongside every one-second
     * `status` poll, forever, to be told the same nothing again. A failed
     * attempt counts for the same reason, and leaves the last good name up.
     */
    @Volatile
    private var outputNameFetchedAt: Long = 0

    private suspend fun outputName(): String? {
        val now = System.currentTimeMillis()
        if (outputNameFetchedAt == 0L || now - outputNameFetchedAt > OUTPUT_NAME_TTL_MS) {
            try {
                cachedOutputName = client.outputs().firstOrNull { it.enabled }?.name
                outputNameFetchedAt = now
            } catch (_: MpdException) {
                // Leave the last known name in place — a dropped `outputs` call is
                // not a reason to blank a label that was correct a moment ago, and
                // not a reason to retry it every poll either: the next TTL window
                // asks again.
                outputNameFetchedAt = now
            }
        }
        return cachedOutputName
    }

    override suspend fun poll(): RemoteState? {
        val s = client.status() ?: return null
        val (rate, bits, channels) = MpdClient.parseAudioFormat(s.audioFormat) ?: Triple(0, 0, 0)
        return RemoteState(
            playing = s.playing,
            stopped = s.stopped,
            index = s.songIndex,
            positionMs = s.elapsedMs,
            durationMs = s.durationMs,
            // MPD reports -1 for a mixer it has no control over — a bit-perfect
            // ALSA output with software volume off, which is exactly how a DAC
            // like this is set up. Null so the app leaves the control alone
            // rather than showing a slider pinned at nothing.
            volume = s.volume.takeIf { it >= 0 }?.let { it / 100f },
            outputFormat = s.audioFormat?.let {
                RemoteAudioFormat(rate, bits, channels, s.bitrateKbps)
            },
            outputDeviceName = outputName(),
        )
    }

    private companion object {
        const val OUTPUT_NAME_TTL_MS = 20_000L
    }
}
