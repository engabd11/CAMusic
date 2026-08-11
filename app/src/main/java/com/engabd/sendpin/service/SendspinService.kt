package com.engabd.sendpin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
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

/**
 * The **media** notification service — shows album art, transport controls, and a
 * seek bar via a [MediaSessionCompat], but only while music is playing.
 *
 * This service does NOT own the WebSocket connection or hold wake/wifi locks — that
 * is the job of [SendspinConnectionService], which stays alive permanently. This
 * service comes and goes with playback: it starts when a stream starts and stops
 * itself when playback ends, so the media notification is only present when there
 * is something to control.
 *
 * Swiping this notification away (or stopping it) does NOT kill the connection —
 * only the [SendspinConnectionService] notification's Stop action does that.
 */
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
        )
    }

    /** Route a transport action to whichever player the shade is showing. */
    private fun route(localAction: () -> Unit, remoteAction: () -> Unit) {
        if (currentShade().local) localAction() else remoteAction()
    }

    private var mediaSession: MediaSessionCompat? = null
    private var cachedArtwork: android.graphics.Bitmap? = null
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
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
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
     * Transport goes to whatever the shade is showing.
     *
     * These used to go unconditionally to `Playback.playerId` — this phone — so a
     * headset button pressed while a speaker was playing paused the phone, which was
     * not the thing making any sound.
     */
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "Sendspin").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = route({ pb.onPlayPause() }, { ma.playPause() })
                override fun onPause() = route({ pb.onPlayPause() }, { ma.playPause() })
                override fun onSkipToNext() = route({ pb.onMediaNext() }, { ma.next() })
                override fun onSkipToPrevious() = route({ pb.onMediaPrevious() }, { ma.previous() })
                override fun onStop() { stopForegroundAndSelf() }
                override fun onSeekTo(pos: Long) =
                    route({ pb.onMediaSeek((pos / 1000).toInt()) }, { ma.seekTo(pos) })
            })
            isActive = true
        }
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
            )
        ) { currentShade() }.distinctUntilChanged()

        observeJob = scope.launch {
            shade.collect { updateNotification(it) }
        }
        scope.launch {
            // Position comes from whichever side owns the shade: the Sendspin stream's
            // own playhead, or the projected position of the selected MA player.
            combine(shade, pb.positionMs, ma.positionMs) { s, localPos, remotePos ->
                Triple(s.isPlaying, if (s.local) localPos else remotePos, s)
            }.collect { (isPlaying, pos, _) -> updatePlaybackState(isPlaying, pos) }
        }
        scope.launch {
            shade.map {
                MetadataBundle(it.title, it.artist, it.album, it.artworkUrl, it.durationMs)
            }.distinctUntilChanged().collect { meta ->
                updateMetadata(meta)
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

    private fun updatePlaybackState(isPlaying: Boolean, posMs: Long = pb.playbackPositionMs) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1.0f else 0.0f
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, posMs, speed)
        mediaSession?.setPlaybackState(builder.build())
    }

    private fun updateMetadata(meta: MetadataBundle) {
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title.ifBlank { "CAMusic" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
            // -1, not 0, when the duration is not known — and it often is not, because
            // durationMs falls back to 0 whenever the server did not send one.
            //
            // 0 does not mean "unknown" to the platform, it means a track zero
            // milliseconds long, so every position sits at or past the end: the seek
            // bar in the notification and on the lock screen was pinned hard right for
            // the whole song. -1 is the documented value for an unknown duration, and
            // the system draws an indeterminate scrubber for it instead of a wrong one.
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, meta.durationMs.takeIf { it > 0 } ?: -1L)
        cachedArtwork?.let {
            // Some Android versions/launchers look for ART, others for ALBUM_ART.
            // Set both so the lock screen, the shade and Bluetooth head units all
            // resolve the cover rather than showing a grey placeholder.
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(md.build())
    }

    private fun currentMetadata() = currentShade().let {
        MetadataBundle(it.title, it.artist, it.album, it.artworkUrl, it.durationMs)
    }

    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            cachedArtwork = null
            updateMetadata(currentMetadata())
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
                    cachedArtwork = result.drawable.toBitmap()
                    // Refresh both the media session metadata and the notification so
                    // the cover appears the moment it is decoded, not only on the next
                    // play/pause state change.
                    updateMetadata(currentMetadata())
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
                MediaStyle()
                    .setMediaSession(session.sessionToken)
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
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}