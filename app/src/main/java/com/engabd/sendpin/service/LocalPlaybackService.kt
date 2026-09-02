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
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
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
 *
 * Uses media3 [MediaSession] wrapping the [com.engabd.sendpin.audio.LocalPlayer]'s
 * ExoPlayer, replacing the deprecated `MediaSessionCompat` / `PlaybackStateCompat` /
 * `MediaMetadataCompat` stack. The session reads playback state, position and
 * metadata from the player directly — no manual state pushing needed.
 *
 * That ExoPlayer is only the session's player some of the time, though: while
 * [com.engabd.sendpin.audio.LocalPlayer.remoteActive] is true — MPD, not this
 * phone, is decoding the queue — ExoPlayer sits idle and empty, and the session
 * wraps [RemoteSessionPlayer] instead. See [rebuildSession].
 */
@OptIn(UnstableApi::class)
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

    private var mediaSession: MediaSession? = null
    private var remoteSessionPlayer: RemoteSessionPlayer? = null
    private var remoteActiveJob: Job? = null
    private var artwork: Bitmap? = null
    private var loadedArtUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        rebuildSession(player.remoteActive.value)
        // remoteActive can flip while this service is alive — MPD isn't the only
        // library LocalPlayer will ever hold a queue for, and the listener can
        // switch to Navidrome/Jellyfin/a download mid-session. Watching it and
        // rebuilding is what keeps the lock screen, Bluetooth and the output
        // switcher pointed at whichever player is actually decoding something.
        remoteActiveJob = scope.launch {
            // drop(1): the first value StateFlow.collect delivers is always the
            // current one, already handled by the synchronous rebuildSession() call
            // above — collecting it again would tear the session down and rebuild
            // the identical one for nothing.
            player.remoteActive.drop(1).collect { active -> rebuildSession(active) }
        }
    }

    /**
     * Point the session at [RemoteSessionPlayer] (MPD driving its own transport) or
     * the real ExoPlayer (everything else), releasing whichever session already
     * holds the id first.
     *
     * Order matters: a media3 MediaSession's id is unique *per process*, and this
     * service and [SendspinService] once both left it unset — defaulting to the
     * same empty string — so whichever session was built second threw
     * IllegalStateException("Session ID must be unique") and crashed the whole
     * process, on-device, repeatedly. Building the replacement before releasing the
     * one already holding "local" is that same crash again, this time between two
     * sessions in this one service instead of between the two services.
     */
    private fun rebuildSession(remote: Boolean) {
        mediaSession?.release()
        mediaSession = null
        remoteSessionPlayer?.stop()
        remoteSessionPlayer = null

        // The session reads play state, position and metadata from whichever
        // Player it wraps directly, so the lock screen, Bluetooth head units and
        // Android Auto stay in sync without this service pushing state manually —
        // true of RemoteSessionPlayer here exactly as it was already true of
        // ExoPlayer.
        val target: Player = if (remote) {
            RemoteSessionPlayer(Looper.getMainLooper(), player, scope).also {
                remoteSessionPlayer = it
                it.start()
            }
        } else {
            player.exoPlayer
        }
        mediaSession = MediaSession.Builder(this, target).setId("local").build()
        // The posted notification carries this session's token inside its
        // MediaStyle, so one built against the session just released would leave the
        // shade's own transport addressing a dead session until the next track
        // change happened to rebuild it. Re-post it against the new one now.
        if (observing) updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.toggle()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
            ACTION_STOP -> { player.stop(); stopSelf(); return START_NOT_STICKY }
            // Media button intents (headset, Bluetooth) are routed by the system
            // to the media3 MediaSession, which dispatches them through the
            // Player directly. The old MediaButtonReceiver.handleIntent() was for
            // MediaSessionCompat; with media3 the session handles its own button
            // events, so nothing to do here for ACTION_MEDIA_BUTTON.
        }
        startForegroundNow()
        observe()
        return START_STICKY
    }

    private fun observe() {
        if (observing) return
        observing = true
        // The session reads metadata and play state from the ExoPlayer directly, so
        // all that's left for us is the notification body and the artwork bitmap —
        // the session can't supply a bitmap to the notification for us.
        scope.launch {
            player.current.collect { track ->
                updateNotification()
                if (track?.artUrl != loadedArtUrl) {
                    loadedArtUrl = track?.artUrl
                    fetchArtwork(track?.artUrl)
                }
            }
        }
        scope.launch {
            player.playing.collect { updateNotification() }
        }
    }

    private fun fetchArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) {
            artwork = null
            updateNotification()
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
                    updateNotification()
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
            .setContentTitle(track?.title.orEmpty().ifBlank { "CAMusic" })
            .setContentText(
                listOfNotNull(track?.artist, track?.album).joinToString(" - ")
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
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(it)
                    .setShowActionsInCompactView(0, 1, 2)
            )
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
        remoteActiveJob?.cancel()
        remoteSessionPlayer?.stop()
        remoteSessionPlayer = null
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}