package com.engabd.sendpin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import kotlinx.coroutines.launch

/**
 * The media notification for the **standalone** player — Navidrome-direct and
 * offline playback. [SendspinService] does the same job for the Music Assistant
 * stream; the two are separate because they follow different state and are never
 * meaningfully both playing.
 *
 * Without this, starting a Navidrome track gave the phone no lock-screen controls,
 * no headset buttons and nothing in the shade — and Android was free to kill the
 * process mid-song, which is fatal for "put an album on and pocket the phone".
 */
class LocalPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "sendspin_local_playback"
        const val NOTIFICATION_ID = 1002
        const val ACTION_PLAY_PAUSE = "com.engabd.sendpin.LOCAL_PLAY_PAUSE"
        const val ACTION_NEXT = "com.engabd.sendpin.LOCAL_NEXT"
        const val ACTION_PREV = "com.engabd.sendpin.LOCAL_PREV"
        const val ACTION_STOP = "com.engabd.sendpin.LOCAL_STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocalPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalPlaybackService::class.java))
        }

        private const val ART_PX = 512
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var artworkJob: Job? = null
    private var observing = false
    private val player get() = SendpinApp.instance.localPlayer

    private var mediaSession: MediaSessionCompat? = null
    private var artwork: Bitmap? = null
    private var loadedArtUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "SendspinLocal").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.previous()
                override fun onStop() = player.stop()
                override fun onSeekTo(pos: Long) = player.seekTo(pos)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
            ACTION_STOP -> { player.stop(); stopSelf(); return START_NOT_STICKY }
            else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        startForegroundNow()
        observe()
        return START_STICKY
    }

    private fun observe() {
        if (observing) return
        observing = true
        // Metadata and the notification body follow the track.
        scope.launch {
            player.current.collect { track ->
                updateMetadata()
                updateNotification()
                if (track?.artUrl != loadedArtUrl) {
                    loadedArtUrl = track?.artUrl
                    fetchArtwork(track?.artUrl)
                }
            }
        }
        // The session's playback state carries the position, which is how the lock
        // screen and Bluetooth head units draw a moving progress bar — so it follows
        // the 500ms tick. The *notification* only changes when play/pause does;
        // rebuilding and re-posting it twice a second was pure waste and risks the
        // system throttling the updates that matter.
        scope.launch {
            player.positionMs.collect { updatePlaybackState(player.playing.value, it) }
        }
        scope.launch {
            player.playing.collect {
                updatePlaybackState(it, player.positionMs.value)
                updateNotification()
            }
        }
        scope.launch { player.durationMs.collect { updateMetadata() } }
    }

    private fun updatePlaybackState(isPlaying: Boolean, posMs: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, posMs, if (isPlaying) 1f else 0f)
                .build()
        )
    }

    private fun updateMetadata() {
        val t = player.current.value
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, t?.title.orEmpty().ifBlank { "Sendspin" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t?.artist.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, t?.album.orEmpty())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.durationMs.value.coerceAtLeast(0))
        artwork?.let { md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        mediaSession?.setMetadata(md.build())
    }

    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            artwork = null
            updateMetadata(); updateNotification()
            return
        }
        artworkJob = scope.launch {
            try {
                val result = imageLoader.execute(
                    ImageRequest.Builder(this@LocalPlaybackService)
                        .data(url).allowHardware(false).size(ART_PX).build()
                )
                if (result is SuccessResult) {
                    artwork = result.drawable.toBitmap()
                    updateMetadata(); updateNotification()
                }
            } catch (_: Exception) {
            }
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
        val track = player.current.value
        val isPlaying = player.playing.value

        val open = openAppIntent(this, OpenAppRequest.LOCAL)

        fun pi(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode,
            Intent(this, LocalPlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track?.title.orEmpty().ifBlank { "Sendspin" })
            .setContentText(
                listOfNotNull(track?.artist, track?.album).joinToString(" — ")
                    .ifBlank { if (track?.offline == true) "Offline" else "" }
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pi(ACTION_PREV, 11))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                pi(ACTION_PLAY_PAUSE, 12),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT, 13))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(ACTION_STOP, 14))

        artwork?.let { builder.setLargeIcon(it) }
        mediaSession?.let {
            builder.setStyle(MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Offline & Navidrome playback", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controls for music playing on this phone"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        artworkJob?.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
