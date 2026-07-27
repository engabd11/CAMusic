package com.engabd.sendpin.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.protocol.SendspinClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class SendspinService : Service() {
    companion object {
        const val CHANNEL_ID = "sendspin_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.engabd.sendpin.PLAY_PAUSE"
        const val ACTION_NEXT = "com.engabd.sendpin.NEXT"
        const val ACTION_STOP = "com.engabd.sendpin.STOP"
        const val ACTION_CONNECT = "com.engabd.sendpin.CONNECT"
        const val ACTION_DISCONNECT = "com.engabd.sendpin.DISCONNECT"
        const val EXTRA_SERVER_URL = "server_url"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var client: SendspinClient? = null
    private var engine: SendspinAudioEngine? = null

    private var trackTitle = ""
    private var artist = ""
    private var album = ""
    private var isPlaying = false
    private var serverUrl = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> { /* track advance is server-driven */ }
            ACTION_STOP -> stopPlayback()
            ACTION_CONNECT -> {
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
                connect(serverUrl)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect(url: String) {
        serverUrl = url
        val playerId = PlayerIdentity.getPlayerId(this)
        val playerName = PlayerIdentity.getDefaultPlayerName()
        val deviceInfo = PlayerIdentity.getDeviceInfo()

        val c = SendspinClient(); client = c
        val eng = SendspinAudioEngine(c.clock); engine = eng

        serviceScope.launch {
            c.nowPlaying.collect { np ->
                trackTitle = np?.title ?: ""
                artist = np?.artist ?: ""
                album = np?.album ?: ""
                updateNotification()
            }
        }
        serviceScope.launch {
            c.streamEvents.collect { ev ->
                when (ev) {
                    is SendspinClient.StreamEvent.Start -> { eng.start(ev.format); isPlaying = true; updateNotification() }
                    SendspinClient.StreamEvent.End -> { eng.stop(); isPlaying = false; updateNotification() }
                    SendspinClient.StreamEvent.Clear -> eng.flush()
                }
            }
        }
        serviceScope.launch {
            c.serverCommands.collect { cmd ->
                when (cmd.command) {
                    "volume" -> cmd.volume?.let { eng.setVolume(it / 100f) }
                    "mute" -> cmd.mute?.let { eng.setVolume(if (it) 0f else 1f) }
                }
            }
        }
        serviceScope.launch { c.audioFrames.collect { bytes -> eng.submit(bytes) } }

        requestAudioFocus()
        c.connect(url, playerId, playerName, deviceInfo, FormatNegotiator.supportedFormats)
        updateNotification()
    }

    private fun disconnect() {
        engine?.stop(); engine = null
        client?.close(); client = null
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Local play/pause ducks output (playback is server-driven for a player@v1).
    private fun togglePlayPause() {
        isPlaying = !isPlaying
        engine?.setVolume(if (isPlaying) 1f else 0f)
        client?.sendClientState(muted = !isPlaying)
        updateNotification()
    }

    private fun stopPlayback() {
        engine?.stop()
        isPlaying = false
        trackTitle = ""; artist = ""; album = ""
        client?.sendClientState()
        updateNotification()
    }

    // --- Audio Focus ---

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(afChangeListener)
                .build()
            result = audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(afChangeListener)
        }
        hasAudioFocus = false
    }

    // Duck rather than tear down, so the stream stays in sync when focus returns.
    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> { hasAudioFocus = true; engine?.setVolume(1.0f) }
            AudioManager.AUDIOFOCUS_LOSS -> { hasAudioFocus = false; engine?.setVolume(0f) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { hasAudioFocus = false; engine?.setVolume(0f) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine?.setVolume(0.2f)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) engine?.setVolume(0f)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running when swiped from recents — music should continue.
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sendspin Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current track and playback controls"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this, 0, Intent(this, SendspinService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 1, Intent(this, SendspinService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, SendspinService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = trackTitle.ifEmpty { "Sendspin" }
        val contentText = if (artist.isNotEmpty()) "$artist — $album" else "Music Assistant"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", null)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
