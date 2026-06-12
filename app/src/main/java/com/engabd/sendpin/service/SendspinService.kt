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
import com.engabd.sendpin.audio.AudioOutput
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.NativeFlacDecoder
import com.engabd.sendpin.audio.NegotiatedFormat
import com.engabd.sendpin.audio.PlaybackScheduler
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

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

    // Audio focus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // Protocol
    private var client: SendspinClient? = null
    private var clockSync: ClockSync? = null

    // Audio pipeline
    private var audioOutput: AudioOutput? = null
    private var flacDecoder: NativeFlacDecoder? = null
    private var scheduler: PlaybackScheduler? = null
    private var negotiatedFormat: NegotiatedFormat? = null

    // State
    private var trackTitle = ""
    private var artist = ""
    private var album = ""
    private var isPlaying = false
    private var serverUrl = ""
    private var wasPlayingBeforeFocusLoss = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Register noisy receiver (headphone unplug)
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
            ACTION_NEXT -> sendNextCommand()
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
        serviceScope.launch {
            try {
                val playerId = PlayerIdentity.getPlayerId(this@SendspinService)
                val playerName = PlayerIdentity.getDefaultPlayerName()
                val deviceInfo = PlayerIdentity.getDeviceInfo()

                client = SendspinClient(
                    serverUrl = url,
                    playerId = playerId,
                    playerName = playerName,
                    deviceInfo = deviceInfo,
                    playerV1Support = PlayerV1Support(
                        supportedFormats = FormatNegotiator.supportedFormats
                    ),
                )

                client!!.connect()
                client!!.connectionState.first { it == SendspinClient.ConnectionState.CONNECTED }

                clockSync = ClockSync(client!!)
                clockSync!!.initialSync()

                requestAudioFocus()

                // Listen for control messages
                launch {
                    client!!.messages.collect { msg -> handleMessage(msg) }
                }

                // Listen for audio frames -> feed to scheduler
                launch {
                    client!!.audioFrames.collect { frame ->
                        scheduler?.enqueue(frame)
                    }
                }

                updateNotification()
            } catch (e: Exception) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun disconnect() {
        tearDownAudioPipeline()
        abandonAudioFocus()
        clockSync = null
        client?.disconnect()
        client?.close()
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        isPlaying = false
        scheduler?.stop()
        scheduler = null
        audioOutput = null
        flacDecoder?.close()
        flacDecoder = null
        client?.send(Message.ClientState())
        updateNotification()
    }

    private fun resumePlayback() {
        val format = negotiatedFormat
        if (format == null || client == null) {
            // No active stream format — request play from server
            client?.send(Message.ClientState())
            return
        }
        setupAudioPipeline(format)
        isPlaying = true
        client?.send(Message.ClientState())
        updateNotification()
    }

    private fun stopPlayback() {
        tearDownAudioPipeline()
        negotiatedFormat = null
        isPlaying = false
        trackTitle = ""
        artist = ""
        album = ""
        client?.send(Message.ClientState())
        updateNotification()
    }

    private fun sendNextCommand() {
        client?.send(Message.ClientState())
        // Note: The server handles track advancement when we signal we're ready
    }

    private fun tearDownAudioPipeline() {
        scheduler?.stop()
        scheduler = null
        flacDecoder?.close()
        flacDecoder = null
        audioOutput = null
    }

    private fun handleMessage(msg: Message) {
        when (msg) {
            is Message.ServerState -> {
                trackTitle = msg.trackTitle
                artist = msg.artist
                album = msg.album
                isPlaying = msg.playing
                updateNotification()
            }
            is Message.StreamStart -> {
                val format = FormatNegotiator.resolve(msg.format) ?: return
                negotiatedFormat = format
                setupAudioPipeline(format)
                isPlaying = true
                updateNotification()
            }
            is Message.StreamEnd -> {
                tearDownAudioPipeline()
                negotiatedFormat = null
                isPlaying = false
                updateNotification()
            }
            is Message.ServerCommand -> {
                when (msg.command) {
                    "play" -> {
                        val format = negotiatedFormat
                        if (format != null) {
                            setupAudioPipeline(format)
                        }
                        isPlaying = true
                    }
                    "pause" -> {
                        tearDownAudioPipeline()
                        isPlaying = false
                    }
                    "stop" -> {
                        tearDownAudioPipeline()
                        negotiatedFormat = null
                        isPlaying = false
                    }
                    "volume" -> msg.volume?.let { audioOutput?.setVolume(it) }
                }
                updateNotification()
            }
            else -> {}
        }
    }

    private fun setupAudioPipeline(format: NegotiatedFormat) {
        // Tear down any existing pipeline first
        tearDownAudioPipeline()

        when (format.codec) {
            "flac" -> {
                flacDecoder = NativeFlacDecoder(
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                    bitDepth = format.bitDepth,
                )
            }
            "pcm" -> {
                flacDecoder?.close()
                flacDecoder = null
            }
        }

        audioOutput = AudioOutput(
            sampleRate = format.sampleRate,
            channels = format.channels,
            bitDepth = format.bitDepth,
        )

        val cs = clockSync
        val ao = audioOutput
        if (cs == null || ao == null) return

        scheduler = PlaybackScheduler(
            clockSync = cs,
            audioOutput = ao,
            flacDecoder = flacDecoder,
            negotiatedFormat = format,
        )
        scheduler!!.start()
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
                afChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
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
        wasPlayingBeforeFocusLoss = false
    }

    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    // Resume playback if we were playing before losing focus
                    val format = negotiatedFormat
                    if (format != null) {
                        setupAudioPipeline(format)
                        isPlaying = true
                    }
                    wasPlayingBeforeFocusLoss = false
                } else {
                    audioOutput?.setVolume(1.0f)
                }
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = isPlaying
                tearDownAudioPipeline()
                isPlaying = false
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                wasPlayingBeforeFocusLoss = isPlaying
                tearDownAudioPipeline()
                isPlaying = false
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = true
                audioOutput?.setVolume(0.2f)
            }
        }
    }

    // --- Becoming Noisy (headphone unplug) ---

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                tearDownAudioPipeline()
                isPlaying = false
                updateNotification()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service running when app is swiped away from recents.
        // Do NOT call stopSelf() — the user expects music to continue.
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sendspin Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current track and playback controls"
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SendspinService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SendspinService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SendspinService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = trackTitle.ifEmpty { "Sendspin" }
        val contentText = if (artist.isNotEmpty()) "$artist — $album"
                          else "Music Assistant"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", null) // Not yet implemented
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
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
