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
import com.engabd.sendpin.service.UnifiedNowPlaying
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

    private val availableCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_SHUFFLE_MODE,
        )
        .build()

    private var artworkBytes: ByteArray? = null
    private var loadedArtworkUrl: String? = null
    private var artworkJob: Job? = null
    private var collectJob: Job? = null

    /** Begin reflecting [UnifiedNowPlaying]. Call once the session/player is attached. */
    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            unifiedNowPlaying.state.collect { snapshot ->
                if (snapshot.artworkUrl != loadedArtworkUrl) {
                    loadedArtworkUrl = snapshot.artworkUrl
                    fetchArtwork(snapshot.artworkUrl)
                }
                invalidateState()
            }
        }
    }

    fun stopObserving() {
        collectJob?.cancel(); collectJob = null
        artworkJob?.cancel(); artworkJob = null
    }

    override fun getState(): State {
        val snapshot = unifiedNowPlaying.state.value
        val current = mediaItemData(snapshot, uid = "current")
        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaybackState(if (snapshot.title.isBlank()) STATE_IDLE else STATE_READY)
            .setPlayWhenReady(snapshot.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(snapshot.shuffleOn)
            // Unconditionally large, matching ShadePlayer: BasePlayer.seekToPrevious()
            // only takes the "go to the previous item" branch under this threshold -
            // whether a skip restarts the current item or moves back a track is a
            // server/engine decision, never this facade's to make on its own.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .setPlaylist(
                listOf(mediaItemData(snapshot, uid = "placeholder-prev"), current, mediaItemData(snapshot, uid = "placeholder-next")),
            )
            .setCurrentMediaItemIndex(1)
            .setContentPositionMs(snapshot.positionMs)
            .build()
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

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // A toggle either way — matches the transport buttons on every other
        // surface, all of which route through PlaybackOwner rather than a direct
        // play()/pause() so the *right* player resumes regardless of which one
        // the OS decided to address.
        playbackOwner.playPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playbackOwner.next()
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playbackOwner.previous()
            else -> when (unifiedNowPlaying.state.value.owner) {
                UnifiedNowPlaying.Owner.LOCAL -> localPlayer.seekTo(positionMs)
                UnifiedNowPlaying.Owner.REMOTE -> maNowPlaying.seekTo(positionMs)
                else -> playback.onMediaSeek((positionMs / 1000).toInt())
            }
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
    }
}
