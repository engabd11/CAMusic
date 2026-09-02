package com.engabd.sendpin.service

import android.graphics.Bitmap
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.audio.LocalTrack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * A [SimpleBasePlayer] facade over [LocalPlayer] for the one mode ExoPlayer cannot
 * itself describe: [LocalPlayer.remoteActive], where MPD is decoding the queue and
 * this phone is only its remote.
 *
 * [LocalPlaybackService] used to wrap `player.exoPlayer` in its `MediaSession`
 * unconditionally. In remote mode that ExoPlayer is deliberately idle and empty —
 * see [LocalPlayer.remote] — so everything the OS drives through the session (the
 * lock screen and its scrubber, Bluetooth transport buttons, the output switcher)
 * addressed a player with nothing loaded and nothing playing: pressing play on a
 * lock screen or a car head unit did nothing. This is what the session wraps
 * instead whenever [LocalPlayer.remoteActive] is true, so those surfaces see what
 * MPD is actually doing.
 *
 * Built in the same shape as [com.engabd.sendpin.car.CarSessionPlayer] and
 * `SendspinService`'s `ShadePlayer` — the two existing [SimpleBasePlayer] facades
 * in this codebase — but unlike either of them this one has a *real* playlist to
 * report: [LocalPlayer.queue] already *is* the MPD queue, one entry per
 * [LocalTrack], so [getState] builds the actual timeline rather than a three-item
 * placeholder wrapped around a single "current" item. That is also why queue taps
 * ([Player.COMMAND_SEEK_TO_MEDIA_ITEM]) are handled here at all — neither of those
 * two facades has a real queue to seek into.
 *
 * Transport dispatches straight to [LocalPlayer], not through
 * [PlaybackOwner][com.engabd.sendpin.service.PlaybackOwner] the way
 * `CarSessionPlayer` and `ShadePlayer` do. Those two serve control surfaces reachable
 * while *any* of the app's players might be the one making sound, so they have to
 * ask `PlaybackOwner`/`UnifiedNowPlaying` which one that is. This facade never has
 * that question: it is only ever built while [LocalPlayer.remoteActive] is true, so
 * the answer is always "this one" — the same reasoning [LocalPlaybackService]'s own
 * notification buttons already dispatch on, straight to `player`, in its
 * `onStartCommand`.
 *
 * Position between the once-a-second polls [LocalPlayer] does of MPD (see
 * `LocalPlayer.REMOTE_POLL_MS`) comes from an extrapolating [PositionSupplier]
 * rather than a value re-stated on every tick — a supplier is cheap to build and
 * keeps the lock-screen scrubber moving smoothly between refreshes instead of
 * visibly stepping once a second.
 */
@OptIn(UnstableApi::class)
class RemoteSessionPlayer(
    looper: Looper,
    private val localPlayer: LocalPlayer,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {

    private val app get() = SendpinApp.instance

    private val availableCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            // Tapping an entry in the queue sheet, or in a car head unit's own
            // playlist view. Without it in the player's own available set,
            // ConnectedControllersManager drops the request before handleSeek is
            // ever consulted — the same failure shape CarSessionPlayer's comment on
            // COMMAND_SET_MEDIA_ITEM describes for a different command.
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
        )
        .build()

    /** Bytes for whichever track [loadedArtworkUrl] names — see [artworkBytesFor]. */
    private var artworkBytes: ByteArray? = null
    private var loadedArtworkUrl: String? = null
    private var artworkJob: Job? = null
    private var collectJob: Job? = null

    /** Begin reflecting [localPlayer]. Call once the session wrapping this is built. */
    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            launch { localPlayer.queue.collect { invalidateState() } }
            launch { localPlayer.index.collect { invalidateState() } }
            launch { localPlayer.playing.collect { invalidateState() } }
            launch { localPlayer.durationMs.collect { invalidateState() } }
            launch { localPlayer.shuffle.collect { invalidateState() } }
            launch { localPlayer.repeatMode.collect { invalidateState() } }
            launch {
                localPlayer.current.collect { track ->
                    if (track?.artUrl != loadedArtworkUrl) {
                        loadedArtworkUrl = track?.artUrl
                        fetchArtwork(track?.artUrl)
                    }
                }
            }
            launch {
                // Re-baseline the extrapolated position roughly as often as
                // LocalPlayer itself hears back from MPD. Doing this on every
                // position tick instead (LocalPlayer refreshes its own positionMs
                // every 250ms, poll or no) would call into the session four times a
                // second for no reason — the PositionSupplier in getState() is what
                // fills the gaps between these, not this loop.
                while (isActive) {
                    delay(1_000)
                    invalidateState()
                }
            }
        }
    }

    /**
     * Stop reflecting [localPlayer]. Call when the session wrapping this is torn
     * down.
     *
     * Deliberately not `stop()`: that is [Player.stop] — a transport command, and
     * one this facade implements as [handleStop], where it means "stop the music".
     * A lifecycle teardown sharing that name would have hidden it.
     */
    fun detach() {
        collectJob?.cancel(); collectJob = null
        artworkJob?.cancel(); artworkJob = null
    }

    override fun getState(): State {
        val queue = localPlayer.queue.value
        val index = localPlayer.index.value.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val playing = localPlayer.playing.value
        val positionMs = localPlayer.positionMs.value

        val builder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaybackState(if (queue.isEmpty()) STATE_IDLE else STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(localPlayer.shuffle.value)
            .setRepeatMode(repeatModeToPlayer(localPlayer.repeatMode.value))
            // Long.MAX_VALUE, matching CarSessionPlayer/ShadePlayer: whether a skip
            // restarts the current track or moves back one is [LocalPlayer.previous]'s
            // decision to make — it already applies that same threshold for MPD — not
            // this facade's to make a second time by also cutting BasePlayer off here.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .setPlaylist(playlistFor(queue))
            .setContentPositionMs(
                if (playing) PositionSupplier.getExtrapolating(positionMs, /* playbackSpeed= */ 1f)
                else PositionSupplier.getConstant(positionMs),
            )
        if (queue.isNotEmpty()) builder.setCurrentMediaItemIndex(index)
        return builder.build()
    }

    /** The last playlist built, and the three things it was built out of. */
    private var cachedPlaylist: List<MediaItemData> = emptyList()
    private var cachedQueue: List<LocalTrack>? = null
    private var cachedArtUrl: String? = null
    private var cachedArtBytes: ByteArray? = null

    /**
     * The queue as media3 items, rebuilt only when something in it actually changed.
     *
     * [getState] is called on every invalidation, and one of those is a bare
     * once-a-second re-baseline of the position — see [start]. Rebuilding every
     * item's metadata for that would mean allocating the whole queue, artwork bytes
     * included, once a second for a picture that has not changed; a queue is a whole
     * album often enough, and can be the whole library.
     */
    private fun playlistFor(queue: List<LocalTrack>): List<MediaItemData> {
        val fresh = queue === cachedQueue &&
            loadedArtworkUrl == cachedArtUrl &&
            artworkBytes === cachedArtBytes
        if (!fresh) {
            cachedQueue = queue
            cachedArtUrl = loadedArtworkUrl
            cachedArtBytes = artworkBytes
            cachedPlaylist = queue.mapIndexed { i, track -> mediaItemData(track, uid = "$i:${track.id}") }
        }
        return cachedPlaylist
    }

    private fun mediaItemData(track: LocalTrack, uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(
            MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title.ifBlank { "CAMusic" })
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .apply {
                            artworkBytesFor(track, loadedArtworkUrl, artworkBytes)?.let {
                                setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                        }
                        .build(),
                )
                .build(),
        )
        .setDurationUs(track.durationMs.takeIf { it > 0 }?.let { it * 1_000L } ?: C.TIME_UNSET)
        .setIsSeekable(true)
        .build()

    /**
     * The requested state, not a toggle — unlike `CarSessionPlayer`'s
     * `UnifiedNowPlaying`-routed [handleSetPlayWhenReady], [LocalPlayer.resume] and
     * [LocalPlayer.pause] are already exactly that, so there is no redundant-`play()`
     * race here to guard against.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) localPlayer.resume() else localPlayer.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> localPlayer.next()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> localPlayer.previous()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> localPlayer.playAt(mediaItemIndex)
            else -> localPlayer.seekTo(positionMs)
        }
        // As ShadePlayer's own handleSeek notes: the real change lands asynchronously,
        // off LocalPlayer's next poll or ticker update, which already invalidates this
        // state on its own — this just snaps the transport UI back onto the right
        // index immediately rather than leaving it on the tapped one for a moment.
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        // A set, not a toggle: unlike CarSessionPlayer's PlaybackOwner.toggleShuffle(),
        // LocalPlayer.setShuffle already takes the exact value the caller asked for.
        localPlayer.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        localPlayer.setRepeatMode(repeatModeToApp(repeatMode))
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        localPlayer.stop()
        return Futures.immediateVoidFuture()
    }

    /**
     * Bytes for [url], embedded into [MediaMetadata] rather than left as a URI.
     *
     * A [LocalTrack.artUrl] for MPD is an `mpd-art://` URL (see `MpdArtFetcher`)
     * that only this app's own Coil [ImageLoader][coil.ImageLoader] — wired to the
     * live MPD connection — knows how to resolve; the system UI process rendering
     * the lock screen has no such loader and could not fetch it itself. Unlike
     * `CarSessionPlayer`, which decodes to bytes because `CarMediaLibraryService` is
     * `exported="true"` and a Subsonic/Jellyfin URL carries credentials in its query
     * string, `LocalPlaybackService` is not exported and `mpd-art://` carries no
     * credentials at all — so the same bytes-not-a-URI approach is used here for the
     * first reason (the URL is unresolvable outside this process), not the second.
     */
    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            artworkBytes = null
            invalidateState()
            return
        }
        artworkJob = scope.launch {
            try {
                val request = ImageRequest.Builder(app)
                    .data(url)
                    .allowHardware(false)
                    .size(ART_PX)
                    .build()
                val result = app.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.drawable.toBitmap()
                    artworkBytes = ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    invalidateState()
                }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val ART_PX = 512

        /** [LocalPlayer.repeatMode]'s `"off"`/`"all"`/`"one"`, as media3 spells them. */
        internal fun repeatModeToPlayer(mode: String): Int = when (mode) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        /** The reverse of [repeatModeToPlayer], for a mode the OS names directly. */
        internal fun repeatModeToApp(mode: Int): String = when (mode) {
            Player.REPEAT_MODE_ALL -> "all"
            Player.REPEAT_MODE_ONE -> "one"
            else -> "off"
        }

        /**
         * Which bytes, if any, [track] should show as its artwork.
         *
         * Matched by URL rather than by queue position: fetching art for every queue
         * slot on each refresh would be one more MPD `readpicture`/`albumart` round
         * trip per track (see `MpdArtFetcher`) for art the lock screen never shows
         * beyond the current item anyway, so only [loadedArtworkUrl] — whatever
         * [fetchArtwork] last resolved — gets bytes. A URL match also means a track
         * that shares its album's art with the one actually playing shows it too,
         * and a stale index during a fetch-in-flight never shows the wrong cover.
         */
        internal fun artworkBytesFor(track: LocalTrack, loadedArtworkUrl: String?, artworkBytes: ByteArray?): ByteArray? =
            if (artworkBytes != null && track.artUrl != null && track.artUrl == loadedArtworkUrl) artworkBytes else null
    }
}
