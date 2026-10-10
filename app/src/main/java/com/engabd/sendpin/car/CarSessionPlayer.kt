package com.engabd.sendpin.car

import android.graphics.Bitmap
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.UnifiedNowPlaying
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * A [SimpleBasePlayer] facade over [UnifiedNowPlaying] — no decoder, no real
 * playlist, just enough of the [Player] contract for Android Auto to show and
 * drive whatever is currently playing across this app's three playback paths
 * (local, Sendspin-self, a remote MA speaker). Built in the same spirit as
 * [com.engabd.sendpin.service.SendspinService]'s `ShadePlayer`, generalised to
 * cover [com.engabd.sendpin.audio.LocalPlayer] as well, and reading from
 * [UnifiedNowPlaying] instead of re-deriving the same union a third time.
 *
 * Transport always routes through [PlaybackOwner][com.engabd.sendpin.service.PlaybackOwner] —
 * never dispatched directly to one engine — so a button pressed here can never
 * address the wrong player, matching every other surface outside the two
 * services' own notifications.
 *
 * Never carries a real media URI (see [mediaItemData]): only [MediaMetadata], with
 * artwork embedded as decoded bytes rather than a URL. `CarMediaLibraryService` is
 * `exported="true"` for Android Auto to bind to it, and Subsonic/Jellyfin stream
 * (and cover) URLs embed credentials in their query string — nothing that can
 * carry one may cross that boundary.
 */
@OptIn(UnstableApi::class)
class CarSessionPlayer(looper: Looper, private val scope: CoroutineScope) : SimpleBasePlayer(looper) {

    private val app get() = SendpinApp.instance
    private val playbackOwner get() = app.playbackOwner
    private val unifiedNowPlaying get() = app.unifiedNowPlaying
    private val localPlayer get() = app.localPlayer
    private val playback get() = app.playback
    private val maNowPlaying get() = app.maNowPlaying

    /**
     * How far a rewind / fast-forward press moves, or 0 for no such buttons.
     *
     * Read from settings and re-read while the car is connected, because the buttons
     * appearing at all is what the setting controls: media3 derives the legacy
     * session's `ACTION_REWIND` / `ACTION_FAST_FORWARD` from whether this player
     * advertises [Player.COMMAND_SEEK_BACK] / [Player.COMMAND_SEEK_FORWARD], and
     * Android Auto draws its transport row from those actions.
     */
    private var seekIncrementMs: Long = 0L

    private fun availableCommands(): Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SHUFFLE_MODE,
            // A row in the car's queue view, and its repeat button. Both are only
            // acted on for this phone's own queue; see [handleSeek] and
            // [handleSetRepeatMode].
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE,
            // Without these two, *nothing in the browse tree plays*, and nothing in
            // `CarLibraryBridge` ever runs to say so.
            //
            // A tap in Android Auto arrives as `MediaSessionCompat.Callback
            // .onPlayFromMediaId`, which media3 handles in
            // `MediaSessionLegacyStub.handleMediaRequest` — and that dispatches under
            // COMMAND_SET_MEDIA_ITEM. `ConnectedControllersManager
            // .isPlayerCommandAvailable` requires the command in the *player's* own
            // `getAvailableCommands()` as well as in the controller's granted set, so
            // a facade that omits it has its media requests dropped before
            // `MediaLibrarySession.Callback.onSetMediaItems` is ever consulted.
            // COMMAND_PREPARE for the `prepareIfCommandAvailable()` on the same path.
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_PREPARE,
        )
        // Only when the driver asked for them. Two extra targets on a screen glanced
        // at from behind the wheel is a real cost on a three-minute song and a real
        // gain on a two-hour set, which is exactly the kind of call the app should
        // not be making on someone's behalf.
        .apply {
            if (seekIncrementMs > 0) {
                addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            }
        }
        .build()

    private var artworkBytes: ByteArray? = null
    private var loadedArtworkUrl: String? = null
    private var artworkJob: Job? = null
    private var collectJob: Job? = null
    private var settingsJob: Job? = null
    private var queueJob: Job? = null

    /** Begin reflecting [UnifiedNowPlaying]. Call once the session/player is attached. */
    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            var published: UnifiedNowPlaying.Snapshot? = null
            unifiedNowPlaying.state.collect { snapshot ->
                if (snapshot.artworkUrl != loadedArtworkUrl) {
                    loadedArtworkUrl = snapshot.artworkUrl
                    fetchArtwork(snapshot.artworkUrl)
                }
                // The snapshot re-emits four times a second for the position alone,
                // and every invalidateState rebuilt three media items (media3 copies
                // the artwork bytes into each), diffed the State and shipped it to
                // the car over binder — for the whole drive. The same storm
                // ShadePlayer was cured of, cured the same way: a tick that lands
                // where the extrapolation already was is no news.
                val shapeChanged = published?.copy(positionMs = 0) != snapshot.copy(positionMs = 0)
                if (shapeChanged) {
                    published = snapshot
                    anchor(snapshot.positionMs)
                    invalidateState()
                } else {
                    onPositionTick(snapshot.positionMs)
                }
            }
        }
        queueJob = scope.launch {
            kotlinx.coroutines.flow.combine(localPlayer.queue, localPlayer.repeatMode) { q, r -> q.size to r }
                .collect { invalidateState() }
        }
        settingsJob = scope.launch {
            AppSettings(app).carBrowseOptions.collect { options ->
                if (options.seekMs != seekIncrementMs) {
                    seekIncrementMs = options.seekMs
                    invalidateState()
                }
            }
        }
    }

    fun stopObserving() {
        queueJob?.cancel(); queueJob = null
        collectJob?.cancel(); collectJob = null
        settingsJob?.cancel(); settingsJob = null
        artworkJob?.cancel(); artworkJob = null
    }

    /** The last position published, and when — see [onPositionTick]. */
    @Volatile private var anchorPositionMs = 0L
    @Volatile private var anchorAtMs = android.os.SystemClock.elapsedRealtime()
    @Volatile private var publishedPlaying = false

    private fun anchor(positionMs: Long) {
        anchorPositionMs = positionMs
        anchorAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Re-publish only when the position left the extrapolation: a seek, a stall. */
    private fun onPositionTick(positionMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val expected = if (publishedPlaying) anchorPositionMs + (now - anchorAtMs) else anchorPositionMs
        anchor(positionMs)
        if (kotlin.math.abs(positionMs - expected) > POSITION_REANCHOR_MS) invalidateState()
    }

    override fun getState(): State {
        val snapshot = unifiedNowPlaying.state.value
        publishedPlaying = snapshot.isPlaying
        val current = mediaItemData(snapshot, uid = "current")
        return State.Builder()
            .setAvailableCommands(availableCommands())
            .setPlaybackState(if (snapshot.title.isBlank()) STATE_IDLE else STATE_READY)
            .setPlayWhenReady(snapshot.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(snapshot.shuffleOn)
            // Unconditionally large, matching ShadePlayer: BasePlayer.seekToPrevious()
            // only takes the "go to the previous item" branch under this threshold -
            // whether a skip restarts the current item or moves back a track is a
            // server/engine decision, never this facade's to make on its own.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            // Zero is not a legal increment, so the "off" case still has to name one —
            // the commands above are what decide whether the buttons exist at all.
            .setSeekBackIncrementMs(seekIncrementMs.takeIf { it > 0 } ?: DEFAULT_SEEK_MS)
            .setSeekForwardIncrementMs(seekIncrementMs.takeIf { it > 0 } ?: DEFAULT_SEEK_MS)
            .apply {
                val window = localWindow(snapshot)
                if (snapshot.title.isBlank()) {
                    // Nothing loaded anywhere: an empty playlist, which is the truth, and
                    // which is what makes media3 ask onPlaybackResumption when a play
                    // arrives. The placeholder timeline below told media3 there was
                    // always something to play, so a Bluetooth play after a reboot went
                    // to play() on nothing instead of to the saved queue.
                    setPlaylist(emptyList())
                } else if (window != null) {
                    // This phone's own queue: the real one, so the car's queue view lists
                    // what is coming and a tap on a row plays it.
                    publishedWindow = window
                    setPlaylist(localPlaylist(window, snapshot))
                    setCurrentMediaItemIndex(window.currentRow)
                    setRepeatMode(
                        when (localPlayer.repeatMode.value) {
                            "all" -> Player.REPEAT_MODE_ALL
                            "one" -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        },
                    )
                    setContentPositionMs(
                        PositionSupplier.getExtrapolating(anchorPositionMs, if (snapshot.isPlaying) 1f else 0f),
                    )
                } else {
                    publishedWindow = null
                    setPlaylist(
                        listOf(mediaItemData(snapshot, uid = "placeholder-prev"), current, mediaItemData(snapshot, uid = "placeholder-next")),
                    )
                    setCurrentMediaItemIndex(1)
                    // The car runs the bar forward itself between anchors.
                    setContentPositionMs(
                        PositionSupplier.getExtrapolating(anchorPositionMs, if (snapshot.isPlaying) 1f else 0f),
                    )
                }
            }
            .build()
    }

    /** The window last published, so a tapped row maps back to the queue it was drawn from. */
    @Volatile private var publishedWindow: CarQueueWindow? = null

    /** The local queue's window, when this phone's own queue is what is playing. */
    private fun localWindow(snapshot: UnifiedNowPlaying.Snapshot): CarQueueWindow? {
        if (snapshot.owner != UnifiedNowPlaying.Owner.LOCAL || snapshot.title.isBlank()) return null
        val queue = localPlayer.queue.value
        return CarQueueWindow.of(queue.size, localPlayer.index.value)
    }

    private fun localPlaylist(window: CarQueueWindow, snapshot: UnifiedNowPlaying.Snapshot): List<MediaItemData> {
        val queue = localPlayer.queue.value
        val rows = (window.start until window.endExclusive).map { i ->
            val t = queue[i]
            val current = i - window.start == window.currentRow
            MediaItemData.Builder("$i:${t.id}")
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("$i:${t.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(if (current) snapshot.title else t.title)
                                .setArtist(if (current) snapshot.artist else t.artist)
                                .setAlbumTitle(if (current) snapshot.album else t.album)
                                .apply {
                                    // The cover travels with the playing row only: media3
                                    // copies artwork bytes into every item it ships.
                                    if (current) artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                                }
                                .build(),
                        )
                        .build(),
                )
                .setDurationUs(
                    (if (current) snapshot.durationMs else t.durationMs).takeIf { it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET,
                )
                .setIsSeekable(true)
                .build()
        }
        return if (window.trailingNext) rows + mediaItemData(snapshot, uid = "placeholder-next") else rows
    }

    private fun mediaItemData(snapshot: UnifiedNowPlaying.Snapshot, uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(
            MediaItem.Builder()
                .setMediaId(uid)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(snapshot.title.ifBlank { "CAMusic" })
                        .setArtist(
                            snapshot.artist.ifBlank {
                                snapshot.playerName?.let { "Playing on $it" }
                            },
                        )
                        .setAlbumTitle(snapshot.album)
                        .apply {
                            artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                        }
                        .build(),
                )
                .build(),
        )
        .setDurationUs(snapshot.durationMs.takeIf { it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET)
        .setIsSeekable(true)
        .build()

    /**
     * The requested state, not a toggle.
     *
     * Which player it reaches is still [PlaybackOwner][com.engabd.sendpin.service.PlaybackOwner]'s
     * decision — same as every other surface outside the two services' own
     * notifications — but *what to ask it for* has to follow the argument here.
     * media3 calls `play()` unconditionally at the end of `handleMediaRequest`,
     * straight after [CarLibraryBridge.play] has already started the track, and
     * `SimpleBasePlayer.setPlayWhenReady` does not filter a redundant value: a
     * blind toggle paused every track the moment it was tapped.
     */
    /** A resumption just loaded the saved queue; see [handleSetPlayWhenReady]. */
    private var justRestored = false

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        when {
            // Explicit, never a toggle — a race must not turn a pause into a resume.
            // Same reasoning as PlaybackOwner.pause()'s own doc.
            !playWhenReady -> playbackOwner.pause()
            // The play() media3 sends straight after a resumption. Addressed to the
            // local player directly: PlaybackOwner learns about the queue just loaded
            // through a flow that has not caught up yet, and routed this to the
            // Sendspin player instead — the queue came back, paused.
            justRestored -> { justRestored = false; localPlayer.resume() }
            // Nothing loaded anywhere — a fresh process, started by the button itself.
            // Play means the last queue, where it was left.
            unifiedNowPlaying.state.value.title.isBlank() && localPlayer.restoreSaved() -> localPlayer.resume()
            !unifiedNowPlaying.state.value.isPlaying -> playbackOwner.playPause()
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * A no-op, deliberately: [CarLibrarySessionCallback.onSetMediaItems] has already
     * started the real playback by the time media3 forwards the item list here, and
     * this facade has no playlist of its own to put it in ([getState] always reports
     * the same three-entry placeholder timeline). Declared only because
     * `COMMAND_SET_MEDIA_ITEM` has to be available for the tap to arrive at all.
     */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // Except for resumption: `onPlaybackResumption` hands media3 the resume item
        // and media3 sets it here, with no onSetMediaItems in between. Loading the
        // saved queue now means the play() media3 sends next has something to play.
        if (mediaItems.getOrNull(startIndex)?.mediaId == CarResume.RESUME_ID) {
            justRestored = localPlayer.restoreSaved() || localPlayer.queue.value.isNotEmpty()
        }
        return Futures.immediateVoidFuture()
    }

    /** Likewise: there is no decoder here to prepare. */
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playbackOwner.next()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playbackOwner.previous()
            // A row tapped in the car's queue view.
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val window = publishedWindow
                val target = window?.queueIndexOf(mediaItemIndex)
                when {
                    window == null -> playbackOwner.next()
                    target == null -> playbackOwner.next()     // the trailing stand-in
                    target != localPlayer.index.value -> localPlayer.playAt(target)
                }
            }
            else -> {
                when (unifiedNowPlaying.state.value.owner) {
                    UnifiedNowPlaying.Owner.LOCAL -> localPlayer.seekTo(positionMs)
                    UnifiedNowPlaying.Owner.REMOTE -> maNowPlaying.seekTo(positionMs)
                    else -> playback.onMediaSeek((positionMs / 1000).toInt())
                }
                // The bar lands where it was dragged, not where the last tick said.
                anchor(positionMs)
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /** The car's repeat button. This phone's own queue only; Music Assistant's is left alone. */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (unifiedNowPlaying.state.value.owner == UnifiedNowPlaying.Owner.LOCAL) {
            localPlayer.setRepeatMode(
                when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "all"
                    Player.REPEAT_MODE_ONE -> "one"
                    else -> "off"
                },
            )
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        // A toggle, not a set — same reasoning as handleSetPlayWhenReady, and
        // PlaybackOwner.toggleShuffle() already covers all three players.
        playbackOwner.toggleShuffle()
        return Futures.immediateVoidFuture()
    }

    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            artworkBytes = null
            invalidateState()
            return
        }
        artworkJob = scope.launch {
            try {
                val req = ImageRequest.Builder(app)
                    .data(url)
                    .allowHardware(false)
                    .size(ART_PX)
                    .build()
                val result = app.imageLoader.execute(req)
                if (result is SuccessResult) {
                    val bmp = result.drawable.toBitmap()
                    artworkBytes = ByteArrayOutputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    invalidateState()
                }
            } catch (_: Exception) { }
        }
    }

    private companion object {
        const val ART_PX = 512

        /** Only ever reported, never used: see the call site. */
        const val DEFAULT_SEEK_MS = 15_000L

        /** Past this from where the car's bar already is, the position is news. Same as ShadePlayer. */
        const val POSITION_REANCHOR_MS = 600L
    }
}
