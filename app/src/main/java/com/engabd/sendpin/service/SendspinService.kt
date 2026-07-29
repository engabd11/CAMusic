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
import androidx.core.app.TaskStackBuilder
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

        /** Start the media notification (called when a stream starts). */
        fun startMedia(context: android.content.Context) {
            val intent = Intent(context, SendspinService::class.java).apply { action = ACTION_START_MEDIA }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the media notification (called when a stream ends). */
        fun stopMedia(context: android.content.Context) {
            context.stopService(Intent(context, SendspinService::class.java))
        }

        private const val ART_PX = 512
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var artworkJob: Job? = null
    private val pb get() = SendpinApp.instance.playback

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
            ACTION_PLAY_PAUSE -> pb.onPlayPause()
            ACTION_NEXT -> pb.onMediaNext()
            ACTION_PREV -> pb.onMediaPrevious()
            ACTION_CONNECT, null, ACTION_START_MEDIA -> {
                // Promote to foreground and start observing.
                startForegroundNow()
                observe()
            }
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_STICKY
    }

    private fun stopForegroundAndSelf() {
        mediaActive = false
        observeJob?.cancel()
        artworkJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "Sendspin").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = pb.onPlayPause()
                override fun onPause() = pb.onPlayPause()
                override fun onSkipToNext() = pb.onMediaNext()
                override fun onSkipToPrevious() = pb.onMediaPrevious()
                override fun onStop() { stopForegroundAndSelf() }
                override fun onSeekTo(pos: Long) = pb.onMediaSeek((pos / 1000).toInt())
            })
            isActive = true
        }
    }

    private fun observe() {
        if (observeJob != null) return
        mediaActive = true
        observeJob = scope.launch {
            combine(
                listOf<Flow<Any?>>(
                    pb.trackTitle, pb.artist, pb.album, pb.isPlaying,
                    pb.artworkUrl, pb.connected, pb.connectionStatus,
                )
            ) { it.toList() }
                .distinctUntilChanged()
                .collect { updateNotification() }
        }
        scope.launch {
            combine(pb.isPlaying, pb.positionMs) { isPlaying, pos -> isPlaying to pos }
                .collect { (isPlaying, pos) -> updatePlaybackState(isPlaying, pos) }
        }
        scope.launch {
            combine(
                pb.trackTitle, pb.artist, pb.album, pb.artworkUrl, pb.durationMs,
            ) { title, artist, album, art, dur ->
                MetadataBundle(title, artist, album, art, dur)
            }.distinctUntilChanged().collect { meta ->
                updateMetadata(meta)
                if (meta.artworkUrl != loadedArtworkUrl) {
                    loadedArtworkUrl = meta.artworkUrl
                    fetchArtwork(meta.artworkUrl)
                }
            }
        }
        // When playback stops entirely, the media notification is no longer needed.
        // The connection service keeps the WebSocket alive.
        scope.launch {
            pb.isPlaying.collect { playing ->
                if (!playing && !mediaActive) {
                    // Already not showing — nothing to do.
                } else if (!playing && mediaActive && pb.trackTitle.value.isBlank()) {
                    // Nothing playing at all — retire the media notification.
                    stopForegroundAndSelf()
                }
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
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title.ifBlank { "Sendspin" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, meta.durationMs.coerceAtLeast(0))
        cachedArtwork?.let { md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        mediaSession?.setMetadata(md.build())
    }

    private fun currentMetadata() = MetadataBundle(
        pb.trackTitle.value, pb.artist.value, pb.album.value,
        pb.artworkUrl.value, pb.durationMs.value,
    )

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

    private fun updateNotification() {
        if (!mediaActive) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = pb.isPlaying.value
        val title = pb.trackTitle.value.ifEmpty { "Sendspin" }
        val text = pb.artist.value.ifEmpty {
            if (pb.connected.value) "Ready" else pb.connectionStatus.value
        }

        val open = TaskStackBuilder.create(this)
            .addNextIntent(Intent(this, MainActivity::class.java))
            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
            val channel = NotificationChannel(CHANNEL_ID, "Sendspin Playback", NotificationManager.IMPORTANCE_LOW).apply {
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