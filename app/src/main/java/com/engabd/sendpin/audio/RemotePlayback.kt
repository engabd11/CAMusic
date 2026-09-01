package com.engabd.sendpin.audio

/**
 * A library that plays the music **itself**, with this phone as its remote.
 *
 * Every other provider hands out a URL per track and this phone decodes it, which
 * is what [LocalPlayer] is for. MPD is the exception, and not by choice: MPD *is*
 * a player. Its only way to send audio elsewhere is the `httpd` output, a single
 * live stream of whatever it is playing — no duration, no position, no seek — so
 * a phone rendering that stream can show a title and nothing else. The first cut
 * did exactly that, and the result was a scrub bar that did nothing, a pause
 * button that paused the phone while the DAC played on, and the same music coming
 * out of two rooms at once.
 *
 * So the transport goes the other way. MPD keeps the queue, MPD decodes to
 * whatever output its own config names — the point of running MPD on a box with a
 * DAC — and [LocalPlayer] drives it and reads its state back. Nothing about the
 * screens above changes: they ask [LocalPlayer] for a position and a queue exactly
 * as before, and it answers with MPD's.
 *
 * Every call is suspending because every one of them is a round trip. They are
 * launched, not awaited, from [LocalPlayer]'s own scope: the UI updates
 * optimistically and [poll] corrects it a moment later, because a transport button
 * that waits for a LAN round trip before it moves feels broken.
 */
interface RemotePlayback {

    /** Replace the player's queue and start at [startIndex]. */
    suspend fun setQueue(tracks: List<LocalTrack>, startIndex: Int)

    /** Append, leaving what is playing alone. */
    suspend fun addToQueue(tracks: List<LocalTrack>)

    /** Insert directly after the entry at [afterIndex]. */
    suspend fun playNext(tracks: List<LocalTrack>, afterIndex: Int)

    suspend fun playAt(index: Int)
    suspend fun pause()
    suspend fun resume()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(ms: Long)

    /** 0..1, as the app spells volume. The implementation scales it. */
    suspend fun setVolume(volume: Float)

    suspend fun setShuffle(on: Boolean)

    /** `off`, `all` or `one`, as [LocalPlayer.repeatMode] spells it. */
    suspend fun setRepeat(mode: String)

    /**
     * Level the loudness, in the player's own engine.
     *
     * [ReplayGain.OFF], [ReplayGain.TRACK] or [ReplayGain.ALBUM] — the same three
     * words this app has always spelled the setting with. The phone's own
     * ReplayGain is a scalar on *its* output volume, which is no use at all when
     * the phone is decoding nothing; a remote player either levels its own output
     * or the setting is a lie on the settings screen. MPD reads the same tags off
     * the same files and applies them itself, so the preference is simply handed
     * over.
     */
    suspend fun setReplayGain(mode: String)

    suspend fun removeAt(index: Int)
    suspend fun move(from: Int, to: Int)
    suspend fun shuffleQueue()
    suspend fun clear()

    /**
     * What the player is doing, or null when it couldn't be reached.
     *
     * Null is not "stopped" — a Wi-Fi blip must not blank the screen or emit a
     * track change — so [LocalPlayer] holds its last reading and carries the
     * position on by hand until an answer comes back.
     */
    suspend fun poll(): RemoteState?
}

/** One reading of a [RemotePlayback]'s transport. */
data class RemoteState(
    val playing: Boolean,
    /** Nothing is loaded or the queue has run out. Distinct from merely paused. */
    val stopped: Boolean,
    /** Index into the player's queue, or -1 when it has nothing selected. */
    val index: Int,
    val positionMs: Long,
    /** 0 when the player didn't say, in which case the track's own metadata stands. */
    val durationMs: Long,
    /** 0..1, or null where the player has no volume of its own to report. */
    val volume: Float?,
)
