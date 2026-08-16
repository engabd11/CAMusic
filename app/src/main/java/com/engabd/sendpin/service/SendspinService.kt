package com.engabd.sendpin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream

/**
 * The **media** notification service — shows album art, transport controls, and a
 * seek bar via a media3 [MediaSession], but only while music is playing.
 *
 * This service does NOT own the WebSocket connection or hold wake/wifi locks — that
 * is the job of [SendspinConnectionService], which stays alive permanently. This
 * service comes and goes with playback: it starts when a stream starts and stops
 * itself when playback ends, so the media notification is only present when there
 * is something to control.
 *
 * Swiping this notification away (or stopping it) does NOT kill the connection —
 * only the [SendspinConnectionService] notification's Stop action does that.
 *
 * The session is backed by [ShadePlayer], a [SimpleBasePlayer] facade rather than
 * a real decoder-backed player — unlike [LocalPlaybackService] (which wraps
 * [com.engabd.sendpin.audio.LocalPlayer]'s actual ExoPlayer 1:1), this notification
 * can be showing *either* this phone's own Sendspin stream *or* a remote MA
 * speaker (see [Shade]/[currentShade]), and transport here was never a matter of
 * driving a local player directly: [Playback.onPlayPause] et al. are themselves
 * RPCs to the MA server regardless of which engine renders this phone's own
 * audio. [ShadePlayer] just gives the OS (lock screen, Bluetooth, Android Auto)
 * a real [Player] to dispatch those same RPCs through, on top of the exact
 * button/PendingIntent notification this service already built.
 */
@OptIn(UnstableApi::class)
class SendspinService : Service() {
    companion object {
        const val CHANNEL_ID = "sendspin_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.engabd.sendpin.CONNECT"
        const val ACTION_STOP = "com.engabd.sendpin.STOP"
        const val ACTION_PLAY_PAUSE = "com.engabd.sendpin.PLAY_PAUSE"
        const val ACTION_NEXT = "com.engabd.sendpin.NEXT"
        const val ACTION_PREV = "com.engabd.sendpin.PREV"
        const val ACTION_START_MEDIA = "com.engabd.sendpin.START_MEDIA"
        const val ACTION_STOP_MEDIA = "com.engabd.sendpin.STOP_MEDIA"
        const val ACTION_IDLE_MEDIA = "com.engabd.sendpin.IDLE_MEDIA"

        /**
         * How long the notification survives with nothing playing.
         *
         * The Sendspin server ends the stream between *every* track, so a skip looks
         * exactly like the end of listening for a second or two. Waiting a minute
         * before retiring the notification is what tells them apart.
         */
        const val IDLE_GRACE_MS = 60_000L

        /** Start the media notification (called when a stream starts). */
        fun startMedia(context: android.content.Context) {
            val intent = Intent(context, SendspinService::class.java).apply { action = ACTION_START_MEDIA }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Nothing is streaming — start the grace period.
         *
         * Deliberately an intent to the running service rather than `stopService`: a
         * `stopService` from outside cannot be taken back, so the countdown has to
         * live inside the service where the next `stream/start` can cancel it. It also
         * keeps the service up across the gap, which avoids rebuilding the session and
         * re-fetching the artwork — and avoids restarting a foreground service from
         * the background, which Android 12+ restricts.
         */
        fun idleMedia(context: android.content.Context) {
            val intent = Intent(context, SendspinService::class.java).apply { action = ACTION_IDLE_MEDIA }
            runCatching { context.startService(intent) }
        }

        /** Retire the media notification now (an explicit stop, or a disconnect). */
        fun stopMedia(context: android.content.Context) {
            context.stopService(Intent(context, SendspinService::class.java))
        }

        private const val ART_PX = 512
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var artworkJob: Job? = null
    /** The countdown started by [armIdleTimeout]; non-null while the grace period runs. */
    private var idleJob: Job? = null
    private val pb get() = SendpinApp.instance.playback
    private val ma get() = SendpinApp.instance.maNowPlaying

    /**
     * What the shade is currently reflecting.
     *
     * Two different things are decided here, separately. **Transport routing** (and
     * `isPlaying`) follows this phone's own Sendspin stream whenever it's the one
     * making the sound — [local] stays true for as long as this phone is the selected
     * MA player, so play/pause/next/prev keep addressing [Playback] exactly as before.
     * **Metadata** (title/artist/album/artwork) is a separate choice: it always prefers
     * [MaNowPlaying]'s richer, REST-polled data when it describes the same player,
     * because the Sendspin protocol's own `server/state.metadata` is optional and
     * partial by spec — a progress-only tick, or the stream ending on pause, leaves
     * [Playback]'s fields blank far more often than MA's own player state does.
     */
    private data class Shade(
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
        val isPlaying: Boolean,
        /** True when this is this phone's own Sendspin stream. */
        val local: Boolean,
        /** The remote player's name; null when [local]. */
        val playerName: String?,
        /** The shown player's volume as 0..100, for the session's device volume. */
        val volume: Int,
        val muted: Boolean,
    )

    /** The precedence rule, in one place so the notification and the session agree. */
    private fun currentShade(): Shade {
        val remote = ma.now.value
        // Prefer MA's own data whenever it's about the player this shade would
        // otherwise show: either a genuinely different, remote speaker, or this same
        // phone (isSelf) where MA's polled metadata is simply more complete than the
        // Sendspin protocol's own — see the class doc above.
        if (remote != null && (remote.isSelf || !pb.isPlaying.value)) {
            return Shade(
                title = remote.title,
                artist = remote.artist,
                album = remote.album,
                artworkUrl = remote.artworkUrl,
                durationMs = remote.durationMs,
                // Local playback state is still the faster, more accurate signal for
                // this phone's own stream than MA's ~5s poll.
                isPlaying = if (remote.isSelf) pb.isPlaying.value else remote.isPlaying,
                local = remote.isSelf,
                playerName = if (remote.isSelf) null else remote.playerName,
                // When this phone is the player, the volume the listener hears is the
                // phone's own media volume; when a speaker is, it is MA's figure for
                // that speaker. Same rule as the transport routing above.
                volume = if (remote.isSelf) deviceVolumePercent() else remote.volumeLevel,
                muted = if (remote.isSelf) deviceVolumePercent() <= 0 else remote.muted,
            )
        }
        return Shade(
            title = pb.trackTitle.value,
            artist = pb.artist.value,
            album = pb.album.value,
            artworkUrl = pb.artworkUrl.value,
            durationMs = pb.durationMs.value,
            isPlaying = pb.isPlaying.value,
            local = true,
            playerName = null,
            volume = deviceVolumePercent(),
            muted = deviceVolumePercent() <= 0,
        )
    }

    private fun deviceVolumePercent(): Int =
        (SendpinApp.instance.deviceVolume.read() * 100).toInt().coerceIn(0, 100)

    /** Route a transport action to whichever player the shade is showing. */
    private fun route(localAction: () -> Unit, remoteAction: () -> Unit) {
        if (currentShade().local) localAction() else remoteAction()
    }

    private var mediaSession: MediaSession? = null
    private var shadePlayer: ShadePlayer? = null
    private var cachedArtwork: Bitmap? = null
    /** PNG-encoded [cachedArtwork], for [ShadePlayer.getState] - encoded once per fetch, not once per poll. */
    private var cachedArtworkBytes: ByteArray? = null
    private var loadedArtworkUrl: String? = null
    @Volatile private var mediaActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_STOP_MEDIA -> {
                // Stop the media notification — NOT the connection.
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_IDLE_MEDIA -> armIdleTimeout()
            // Routed the same way the session callbacks are — the notification's own
            // buttons must not address a different player from the lock screen's.
            ACTION_PLAY_PAUSE -> route({ pb.onPlayPause() }, { ma.playPause() })
            ACTION_NEXT -> route({ pb.onMediaNext() }, { ma.next() })
            ACTION_PREV -> route({ pb.onMediaPrevious() }, { ma.previous() })
            ACTION_CONNECT, null, ACTION_START_MEDIA -> {
                // Promote to foreground and start observing.
                cancelIdleTimeout()
                startForegroundNow()
                observe()
            }
            // Media button intents (headset, Bluetooth) are routed by the system to
            // the media3 MediaSession, which dispatches them through ShadePlayer
            // directly - nothing to do here for ACTION_MEDIA_BUTTON. (Matches
            // LocalPlaybackService's identical migration off MediaButtonReceiver.)
            else -> {}
        }
        return START_STICKY
    }

    /**
     * Begin the countdown to retiring the notification, unless one is already running.
     *
     * The notification is detached from the foreground rather than removed, so it stays
     * on screen (dismissible, showing the paused track) for the whole grace period —
     * which is the point. [cancelIdleTimeout] on the next stream puts it back.
     */
    private fun armIdleTimeout() {
        if (!mediaActive || idleJob != null) return
        idleJob = scope.launch {
            delay(IDLE_GRACE_MS)
            idleJob = null
            stopForegroundAndSelf()
        }
    }

    private fun cancelIdleTimeout() {
        idleJob?.cancel()
        idleJob = null
    }

    private fun stopForegroundAndSelf() {
        mediaActive = false
        cancelIdleTimeout()
        observeJob?.cancel()
        artworkJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A [SimpleBasePlayer] facade over [currentShade] — no decoder, no real
     * playlist, just enough of the [Player] contract for the OS (lock screen,
     * Bluetooth, Android Auto) to show and drive whatever this notification is
     * already showing. Transport goes to whatever the shade is showing, same
     * rule as this service's own PendingIntent-based notification buttons:
     * these used to go unconditionally to `Playback.playerId` — this phone —
     * so a headset button pressed while a speaker was playing paused the
     * phone, which was not the thing making any sound.
     *
     * [availablePrev]/[availableNext] pad the real, single "current" item with
     * placeholder neighbours purely so [Player.hasNextMediaItem]/
     * [Player.hasPreviousMediaItem] read true: `BasePlayer.seekToNext()` /
     * `seekToPrevious()` silently no-op (never even calling [handleSeek]) for
     * a single-item playlist, since real ExoPlayer-style "next" is playlist
     * navigation - ours is an RPC to Music Assistant instead, and MA (not this
     * class) decides what "next" actually means. The placeholders are never
     * shown; [getState] always reports index 1 (the real item) as current.
     */
    private inner class ShadePlayer(looper: Looper) : SimpleBasePlayer(looper) {

        // Not a companion object: Kotlin doesn't allow one inside an inner class.
        // One instance per ShadePlayer (one per service lifetime) costs nothing.
        private val availableCommands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                // Volume, so the hardware rocker reaches whatever is actually making
                // the sound. Without these the session published no device volume at
                // all, and the rocker fell through to this phone's STREAM_MUSIC even
                // while a speaker in another room was playing — the on-screen slider
                // moved the speaker and the buttons moved something inaudible.
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()

        /** Kept current by [observe]'s position collector; [getState] reads it synchronously. */
        @Volatile var latestPositionMs: Long = 0L

        /** [invalidateState] is protected; this is what the outer service calls instead. */
        fun refresh() = invalidateState()

        override fun getState(): State {
            val shade = currentShade()
            val current = mediaItemData(shade, uid = "current")
            return State.Builder()
                .setAvailableCommands(availableCommands)
                .setPlaybackState(if (shade.title.isBlank() && !pb.connected.value) STATE_IDLE else STATE_READY)
                .setPlayWhenReady(shade.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                // Unconditionally large: BasePlayer.seekToPrevious() only takes the
                // "go to the previous item" branch when the current position is at
                // or under this - otherwise it restarts the current item instead.
                // MA owns that restart-vs-previous decision server-side (matching
                // the old MediaSessionCompat.Callback.onSkipToPrevious(), which
                // always routed straight to pb.onMediaPrevious()/ma.previous() with
                // no client-side threshold), so this must never trigger the restart
                // branch on its own.
                .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
                .setPlaylist(
                    listOf(mediaItemData(shade, uid = "placeholder-prev"), current, mediaItemData(shade, uid = "placeholder-next")),
                )
                .setCurrentMediaItemIndex(1)
                .setContentPositionMs(latestPositionMs)
                // REMOTE when a speaker is playing, so the OS shows a "casting" volume
                // slider rather than pretending this is the phone's own output; LOCAL
                // when this phone *is* the Sendspin player, where it genuinely is.
                // Both are published on a 0..100 scale so the two cases share one unit
                // and MA's own 0..100 needs no conversion.
                .setDeviceInfo(
                    DeviceInfo.Builder(
                        if (shade.local) DeviceInfo.PLAYBACK_TYPE_LOCAL else DeviceInfo.PLAYBACK_TYPE_REMOTE,
                    )
                        .setMinVolume(0)
                        .setMaxVolume(100)
                        .build(),
                )
                .setDeviceVolume(shade.volume.coerceIn(0, 100))
                .setIsDeviceMuted(shade.muted)
                .build()
        }

        override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
            setVolumePercent(deviceVolume)
            return Futures.immediateVoidFuture()
        }

        override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
            setVolumePercent(currentShade().volume + volumeStepPercent())
            return Futures.immediateVoidFuture()
        }

        override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
            setVolumePercent(currentShade().volume - volumeStepPercent())
            return Futures.immediateVoidFuture()
        }

        override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
            // Mute is volume 0 on both paths. The pre-mute level is not restored here:
            // MA has no mute-state of its own to restore from, and inventing one in the
            // shade would drift from whatever the Speakers screen shows.
            if (muted) setVolumePercent(0)
            return Futures.immediateVoidFuture()
        }

        /**
         * One rocker press, as a percentage of the 0..100 scale the session publishes.
         *
         * Taken from the phone's own volume scale so a local press moves exactly one
         * hardware step and lands on a real level rather than between two. A remote
         * speaker has no such scale, so it gets the same feel by construction.
         */
        private fun volumeStepPercent(): Int =
            (SendpinApp.instance.deviceVolume.stepFraction() * 100).roundToInt().coerceAtLeast(1)

        private fun setVolumePercent(percent: Int) {
            val v = percent.coerceIn(0, 100)
            route(
                { SendpinApp.instance.deviceVolume.set(v / 100f) },
                { ma.setVolume(v / 100f) },
            )
            invalidateState()
        }

        private fun mediaItemData(shade: Shade, uid: String) = MediaItemData.Builder(uid)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(uid)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(shade.title.ifBlank { "CAMusic" })
                            .setArtist(shade.artist)
                            .setAlbumTitle(shade.album)
                            .apply {
                                cachedArtworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                            }
                            .build()
                    )
                    .build()
            )
            .setDurationUs(shade.durationMs.takeIf { it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            // A toggle either way, matching the old onPlay()/onPause() - both of
            // which routed to the exact same call regardless of which fired.
            route({ pb.onPlayPause() }, { ma.playPause() })
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> route({ pb.onMediaNext() }, { ma.next() })
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> route({ pb.onMediaPrevious() }, { ma.previous() })
                else -> route({ pb.onMediaSeek((positionMs / 1000).toInt()) }, { ma.seekTo(positionMs) })
            }
            // The real state change comes back asynchronously (server/state, or
            // Playback's own flows) and observe()'s collectors already invalidate
            // on that - this just snaps the transport UI (index 1, not 0 or 2)
            // back immediately instead of leaving it on the placeholder a moment.
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            stopForegroundAndSelf()
            return Futures.immediateVoidFuture()
        }
    }

    private fun setupMediaSession() {
        val player = ShadePlayer(Looper.getMainLooper())
        shadePlayer = player
        // A media3 MediaSession's id must be unique *per process*, and the default
        // (unset) id is the same empty string LocalPlaybackService's session also
        // used to default to — so whichever of the two was constructed second threw
        // IllegalStateException("Session ID must be unique") and crashed, which is
        // exactly what a device crash log caught. Both services now claim distinct ids.
        mediaSession = MediaSession.Builder(this, player).setId("sendspin").build()
    }

    private fun observe() {
        if (observeJob != null) return
        mediaActive = true
        // One flow for both sources, recomputed whenever either moves — so the shade
        // never shows the phone's stale last track while a speaker is playing.
        val shade: Flow<Shade> = combine(
            listOf<Flow<Any?>>(
                pb.trackTitle, pb.artist, pb.album, pb.isPlaying,
                pb.artworkUrl, pb.durationMs, pb.connected, pb.connectionStatus,
                ma.now,
                // The phone's own media volume. In the flow because `currentShade()`
                // reads it, so a rocker press — which arrives through a ContentObserver,
                // not through any of the above — has to recompute the shade or the
                // session keeps publishing the level from before the press.
                SendpinApp.instance.deviceVolume.level,
            )
        ) { currentShade() }.distinctUntilChanged()

        observeJob = scope.launch {
            shade.collect { updateNotification(it) }
        }
        scope.launch {
            // Position comes from whichever side owns the shade: the Sendspin stream's
            // own playhead, or the projected position of the selected MA player.
            combine(shade, pb.positionMs, ma.positionMs) { s, localPos, remotePos ->
                if (s.local) localPos else remotePos
            }.collect { pos ->
                shadePlayer?.let { it.latestPositionMs = pos; it.refresh() }
            }
        }
        // Volume is its own collector: it changes without the metadata changing, and
        // the session has to be re-published or the OS volume UI shows a stale level.
        scope.launch {
            shade.map { it.volume to it.muted }.distinctUntilChanged().collect {
                shadePlayer?.refresh()
            }
        }
        scope.launch {
            shade.map {
                MetadataBundle(it.title, it.artist, it.album, it.artworkUrl, it.durationMs)
            }.distinctUntilChanged().collect { meta ->
                shadePlayer?.refresh()
                if (meta.artworkUrl != loadedArtworkUrl) {
                    loadedArtworkUrl = meta.artworkUrl
                    fetchArtwork(meta.artworkUrl)
                }
            }
        }
        // When playback stops entirely the media notification is eventually no longer
        // needed — but "stopped" and "between tracks" look identical from here, and
        // the title goes blank in both. So this arms the grace period rather than
        // tearing down, and playback resuming cancels it. The connection service keeps
        // the WebSocket alive either way.
        scope.launch {
            shade.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (!mediaActive) return@collect
                if (playing) cancelIdleTimeout() else armIdleTimeout()
            }
        }
    }

    private data class MetadataBundle(
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
    )

    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            cachedArtwork = null
            cachedArtworkBytes = null
            shadePlayer?.refresh()
            return
        }
        artworkJob = scope.launch {
            try {
                val req = ImageRequest.Builder(this@SendspinService)
                    .data(url)
                    .allowHardware(false)
                    .size(ART_PX)
                    .build()
                val result = imageLoader.execute(req)
                if (result is SuccessResult) {
                    val bmp = result.drawable.toBitmap()
                    cachedArtwork = bmp
                    // Encoded once here, not on every ShadePlayer.getState() poll -
                    // see cachedArtworkBytes.
                    cachedArtworkBytes = ByteArrayOutputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    // Refresh both the media session metadata and the notification so
                    // the cover appears the moment it is decoded, not only on the next
                    // play/pause state change.
                    shadePlayer?.refresh()
                    updateNotification()
                }
            } catch (_: Exception) { }
        }
    }

    private fun startForegroundNow() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(shade: Shade = currentShade()) {
        if (!mediaActive) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(shade))
    }

    private fun buildNotification(shade: Shade = currentShade()): Notification {
        val isPlaying = shade.isPlaying
        val title = shade.title.ifEmpty { "CAMusic" }
        val text = shade.artist.ifEmpty {
            when {
                shade.playerName != null -> "Playing on ${shade.playerName}"
                pb.connected.value -> "Ready"
                else -> pb.connectionStatus.value
            }
        }

        val open = openAppIntent(this, OpenAppRequest.MEDIA)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        fun pi(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode,
            Intent(this, SendspinService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = pi(ACTION_STOP, 1)
        val playPause = pi(ACTION_PLAY_PAUSE, 2)
        val next = pi(ACTION_NEXT, 3)
        val prev = pi(ACTION_PREV, 4)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(false)   // the media notification is dismissible
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
            .addAction(playPauseIcon, playPauseLabel, playPause)
            .addAction(android.R.drawable.ic_media_next, "Next", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Name the speaker, so it's obvious the controls aren't driving the phone.
        shade.playerName?.let { builder.setSubText(it) }

        cachedArtwork?.let { builder.setLargeIcon(it) }

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "CAMusic Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the current track while music is playing"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observeJob?.cancel()
        artworkJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        shadePlayer = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}