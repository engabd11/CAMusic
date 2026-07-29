package com.engabd.sendpin.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

        /** Session/notification artwork edge, in px. Big enough to look sharp on a
         *  lock screen, small enough to cross Binder without a TransactionTooLarge. */
        private const val ART_PX = 512
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    private var artworkJob: Job? = null
    private val pb get() = SendpinApp.instance.playback

    private var mediaSession: MediaSessionCompat? = null
    private var cachedArtwork: android.graphics.Bitmap? = null
    private var loadedArtworkUrl: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        acquireLocks()
    }

    /**
     * A foreground service keeps the *process* alive; it does not keep the CPU or
     * the Wi-Fi radio awake. Without these an idle phone can doze between
     * announcements, stalling the WebSocket's 5s keepalive and delaying — or
     * dropping — the very TTS this service exists to receive.
     *
     * The cost is real: an always-reachable receiver is an always-on radio. That
     * is the trade the Stop action in the notification exists to let the user make.
     */
    // WakelockTimeout: a timeout is exactly wrong here. There is no bounded amount
    // of work to wait for — the point is to stay reachable indefinitely. The lock is
    // bounded by the service's own lifetime instead, which the user ends with Stop.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sendspin:receiver")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sendspin:receiver")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pb.disconnect(); stopSelf(); return START_NOT_STICKY
        }
        // Every other path must reach startForeground(): the system kills a service
        // started with startForegroundService() that doesn't promote itself in time,
        // and notification-action intents land here too.
        startForegroundNow()
        observe()
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> pb.onPlayPause()
            ACTION_NEXT -> pb.onMediaNext()
            ACTION_PREV -> pb.onMediaPrevious()
            ACTION_CONNECT, null -> Unit          // just keep the service alive
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_STICKY
    }

    private fun setupMediaSession() {
        // FLAG_HANDLES_MEDIA_BUTTONS / FLAG_HANDLES_TRANSPORT_CONTROLS are implied
        // since Lollipop and deprecated — setting them buys nothing.
        mediaSession = MediaSessionCompat(this, "Sendspin").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = pb.onPlayPause()
                override fun onPause() = pb.onPlayPause()
                override fun onSkipToNext() = pb.onMediaNext()
                override fun onSkipToPrevious() = pb.onMediaPrevious()
                override fun onStop() { pb.disconnect() }
                override fun onSeekTo(pos: Long) = pb.onMediaSeek((pos / 1000).toInt())
            })
            isActive = true
        }
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            // Everything the notification renders, including connection state — the
            // idle text says whether the receiver is actually reachable.
            combine(
                listOf<Flow<Any?>>(
                    pb.trackTitle, pb.artist, pb.album, pb.isPlaying,
                    pb.artworkUrl, pb.connected, pb.connectionStatus,
                )
            ) { it.toList() }
                .distinctUntilChanged()
                .collect { updateNotification() }
        }
        // Update the MediaSession's playback state + metadata independently.
        // Position has to be in here too, or the lock-screen scrubber freezes at
        // wherever the track happened to be when play/pause last flipped.
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
                // Only refetch when the artwork itself changed — a duration tick
                // must not restart the image load.
                if (meta.artworkUrl != loadedArtworkUrl) {
                    loadedArtworkUrl = meta.artworkUrl
                    fetchArtwork(meta.artworkUrl)
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
        // Playback speed drives the system's own position extrapolation between
        // updates — 0 while paused, or the scrubber keeps crawling.
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
                // Coil's singleton loader (built by SendpinApp) — sharing it reuses the
                // memory/disk cache instead of standing up a new loader per track.
                val req = ImageRequest.Builder(this@SendspinService)
                    .data(url)
                    .allowHardware(false)      // need a software bitmap to hand to the session
                    .size(ART_PX)              // cover art is ~1200px; the session bitmap
                    .build()                   // crosses Binder, so keep it small
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = pb.isPlaying.value
        val title = pb.trackTitle.value.ifEmpty { "Sendspin" }
        // With nothing playing this is not a dead notification — it is the receiver
        // standing by, and saying so is what makes the persistent entry make sense.
        val text = pb.artist.value.ifEmpty {
            if (pb.connected.value) "Ready — announcements will play here"
            else pb.connectionStatus.value
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

    /**
     * Swiping the app out of Recents is not a request to go offline.
     *
     * This player exists to be *reachable* — Music Assistant pushes announcements
     * and TTS to it whether or not music is playing, and it can only receive them
     * while this service holds the connection open. Tearing down when idle meant
     * the one state announcements matter most in was the one that killed the
     * socket. The service now ends only on the notification's Stop action (or
     * `Playback.disconnect()`, which is the same user intent from the UI).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
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