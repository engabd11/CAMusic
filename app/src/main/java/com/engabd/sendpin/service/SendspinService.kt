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
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps the process (and the shared [Playback] connection)
 * alive in the background and shows a rich media notification backed by a
 * [MediaSessionCompat]. The notification carries album art, play/pause/skip/stop
 * actions, and a seek bar — the second-most-important surface after Now Playing.
 *
 * It does NOT own the connection — it observes [SendpinApp.playback].
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
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var artworkJob: Job? = null
    private val pb get() = SendpinApp.instance.playback

    private var mediaSession: MediaSessionCompat? = null
    private var cachedArtwork: android.graphics.Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { pb.disconnect(); stopSelf(); return START_NOT_STICKY }
            ACTION_PLAY_PAUSE -> pb.onPlayPause()
            ACTION_NEXT -> pb.onMediaNext()
            ACTION_PREV -> pb.onMediaPrevious()
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        if (intent?.action == null || intent.action == ACTION_CONNECT) {
            startForegroundNow()
            observe()
        }
        return START_STICKY
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "Sendspin").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = pb.onPlayPause()
                override fun onPause() = pb.onPlayPause()
                override fun onSkipToNext() = pb.onMediaNext()
                override fun onSkipToPrevious() = pb.onMediaPrevious()
                override fun onStop() { pb.disconnect() }
                override fun onSeekTo(pos: Long) = pb.onMediaSeek(pos / 1000)
            })
            isActive = true
        }
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            // Observe everything that changes the notification — title, artist, art, playing state, position, duration.
            combine(
                pb.trackTitle, pb.artist, pb.album, pb.isPlaying, pb.artworkUrl,
            ) { _, _, _, _, _ -> Unit }
                .collect { updateNotification() }
        }
        // Update the MediaSession's playback state + metadata independently.
        scope.launch {
            pb.isPlaying.collect { isPlaying ->
                updatePlaybackState(isPlaying)
            }
        }
        scope.launch {
            combine(pb.trackTitle, pb.artist, pb.album, pb.artworkUrl) { title, artist, album, art ->
                MetadataBundle(title, artist, album, art)
            }.collect { meta ->
                updateMetadata(meta)
                fetchArtwork(meta.artworkUrl)
            }
        }
    }

    private data class MetadataBundle(val title: String, val artist: String, val album: String, val artworkUrl: String?)

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val posMs = pb.playbackPositionMs
        val durMs = pb.playbackDurationMs
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
            .setState(state, posMs, 1.0f)
        if (durMs > 0) builder.setBufferedPosition(durMs)
        mediaSession?.setPlaybackState(builder.build())
    }

    private fun updateMetadata(meta: MetadataBundle) {
        val durMs = pb.playbackDurationMs
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title.ifBlank { "Sendspin" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durMs > 0) durMs else 0)
        cachedArtwork?.let { md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        mediaSession?.setMetadata(md.build())
    }

    private fun fetchArtwork(url: String?) {
        if (url == null) { cachedArtwork = null; return }
        artworkJob?.cancel()
        artworkJob = scope.launch {
            try {
                val loader = (application as SendpinApp).newImageLoader() as ImageLoader
                val req = ImageRequest.Builder(this@SendspinService).data(url).allowHardware(false).build()
                val result = loader.execute(req)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    val bmp2 = android.graphics.Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    val canvas = android.graphics.Canvas(bmp2)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    cachedArtwork = bmp2
                    updateMetadata(MetadataBundle(pb.trackTitle.value, pb.artist.value, pb.album.value, url))
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = pb.trackTitle.value.ifEmpty { "Sendspin" }
        val text = pb.artist.value.ifEmpty { pb.connectionStatus.value }
        val isPlaying = pb.isPlaying.value

        val content = TaskStackBuilder.create(this).apply {
            addNextIntent(Intent(this, MainActivity::class.java))
        }

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
            .setContentIntent(content.pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
            .addAction(playPauseIcon, playPauseLabel, playPause)
            .addAction(android.R.drawable.ic_media_next, "Next", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Album art — the single most important visual.
        cachedArtwork?.let { builder.setLargeIcon(it) }

        // MediaStyle with the session + transport row (shows play/pause/skip on lock screen).
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)   // prev, play/pause, next
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sendspin Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the player connected and shows the current track"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the app is swiped away while not playing, stop the service + connection.
        if (!pb.isPlaying.value) { pb.disconnect(); stopSelf() }
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