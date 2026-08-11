package com.engabd.sendpin.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The **persistent** foreground service — the one that stays in the notification
 * shade permanently until the user taps Stop.
 *
 * Its only job is to keep the process alive so the [Playback] WebSocket connection
 * (which lives at process scope in [SendpinApp]) stays reachable for Music Assistant
 * announcements and TTS — whether or not music is currently playing.
 *
 * The notification it shows is a *connection* notification: a small, quiet, ongoing
 * entry with a Stop action. It deliberately does NOT use MediaStyle or a
 * MediaSession — that belongs to [SendspinService], which comes and goes with
 * the music. This separation means:
 *
 *  - Swiping away the media notification does not kill the connection.
 *  - The connection notification survives idle periods so TTS still arrives.
 *  - The media notification is only present when there's something to control.
 *
 * The wake lock and Wi-Fi lock live here, not in the media service, because they
 * are needed for reachability (announcements) not for playback.
 */
class SendspinConnectionService : Service() {
    companion object {
        const val CHANNEL_ID = "sendspin_connection"
        const val NOTIFICATION_ID = 1000   // below the media notification (1001)
        const val ACTION_START = "com.engabd.sendpin.START_CONNECTION"
        const val ACTION_STOP = "com.engabd.sendpin.STOP_CONNECTION"

        /** Start the persistent connection service. */
        fun start(context: Context) {
            val intent = Intent(context, SendspinConnectionService::class.java).apply {
                action = ACTION_START
            }
            // Android 12+ refuses a foreground service started from the background,
            // and the refusal is an exception — thrown here on a background
            // dispatcher, where nothing was catching it, so it took the process down.
            // A connection that cannot show its notification yet is a degraded state,
            // not a crash: the reconnect loop and the next foreground moment both get
            // another chance at it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                android.util.Log.w("SendspinConnection", "couldn't start service: ${it.message}")
            }
        }

        /** Stop the persistent connection service (and thus the connection). */
        fun stop(context: Context) {
            context.stopService(Intent(context, SendspinConnectionService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pb get() = SendpinApp.instance.playback

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observeJob: kotlinx.coroutines.Job? = null
    private var playbackWatchJob: kotlinx.coroutines.Job? = null

    /**
     * Whether the connection is in **idle mode** — audio is not flowing, so the
     * aggressive low-latency WiFi lock is released and the protocol timers are
     * relaxed (see [SendspinClient.setIdleMode]). The wake lock stays held either
     * way, because Doze would delay incoming WebSocket frames and that is the one
     * thing TTS reachability cannot survive.
     *
     * Toggled by [enterIdleMode]/[enterActiveMode] from [Playback]'s isPlaying flow.
     */
    @Volatile private var idleMode = false

    /** Set when the user taps Stop, so [onDestroy] doesn't send a second goodbye. */
    private var stoppedByUser = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        watchPlayback()
    }

    /**
     * Drive idle/active transitions from the play state. Audio flowing → active:
     * hold the low-latency WiFi lock so the stream is not starved. Audio stopped
     * past the engine's own linger → idle: release it so the radio drops to
     * power-save, and tell the client to relax its timer loops.
     *
     * The wake lock is *not* released in idle — without it Doze delays incoming
     * WebSocket frames, which is exactly the TTS reachability this service exists
     * to provide. The WiFi lock is the expensive one (it keeps the radio in
     * high-power mode); the wake lock is the cheap one (it lets the CPU idle
     * at a low clock without sleeping).
     */
    private fun watchPlayback() {
        if (playbackWatchJob != null) return
        playbackWatchJob = scope.launch {
            pb.isPlaying.distinctUntilChanged().collect { playing ->
                if (playing) enterActiveMode() else enterIdleMode()
            }
        }
    }

    /**
     * Downgrade to idle: release the low-latency WiFi lock and tell the protocol
     * client to relax its timer loops. The wake lock stays held.
     *
     * Called when playback stops (after the engine's own linger, so a between-tracks
     * gap does not flap the WiFi radio). Idempotent — already-idle is a no-op.
     */
    private fun enterIdleMode() {
        if (idleMode) return
        idleMode = true
        releaseWifiLock()
        SendpinApp.instance.playback.setClientIdleMode(true)
    }

    /**
     * Upgrade to active: re-acquire the low-latency WiFi lock and tell the protocol
     * client to resume fast timer loops. Called on stream/start — including TTS
     * announcements, which need the radio awake *now*, not after a power-save wakeup.
     *
     * Idempotent — already-active is a no-op.
     */
    private fun enterActiveMode() {
        if (!idleMode) return
        idleMode = false
        acquireWifiLock()
        SendpinApp.instance.playback.setClientIdleMode(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppedByUser = true
            pb.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        observeConnection()
        // Start in idle mode if nothing is playing — the service is being started
        // for reachability (TTS), not because audio is flowing, so there is no
        // reason to hold the low-latency WiFi lock or run the fast timer loops.
        // watchPlayback() will upgrade to active when a stream starts.
        if (!pb.isPlaying.value) enterIdleMode()
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        acquireWakeLock()
        acquireWifiLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sendspin:receiver")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sendspin:receiver")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * Release the WiFi lock alone — used when entering idle mode. The radio drops
     * to power-save, which is the single biggest background battery saving.
     */
    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /**
     * Update the notification text when **connection** state changes — and nothing
     * else.
     *
     * Playback is deliberately not watched here. This notification is about
     * reachability for announcements and TTS; the media notification in
     * [SendspinService] is the one that follows the music. Having this one narrate the
     * track as well meant two entries in the shade saying the same thing, both
     * re-rendering on every play/pause, and the announcements entry changing its mind
     * about what it was for depending on whether music happened to be playing.
     */
    private fun observeConnection() {
        if (observeJob != null) return
        observeJob = scope.launch {
            pb.connectionStatus.collect { _ -> updateNotification() }
        }
        scope.launch {
            pb.connected.collect { _ -> updateNotification() }
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

    /**
     * A *connection* notification, and only that.
     *
     * The text says what this service is for and whether it can do it. It used to
     * narrate the current track instead — "Playing here — …" / "Playing on …" — which
     * made it a second, worse copy of the media notification that changed on every
     * play/pause. What the user wants to know from this entry is whether the phone is
     * still reachable for announcements, which is true or false regardless of whether
     * music happens to be playing.
     */
    private fun buildNotification(): Notification {
        val connected = pb.connected.value
        val title = if (connected) "CAMusic player online" else "CAMusic - reconnecting"
        // "Announcements will play here" was read, reasonably, as *this notification
        // being an audio destination* — a second stream the music was coming out of,
        // separate from the media player. It never was: there is one player, and this
        // entry is the background connection that keeps it registered with Music
        // Assistant so playback and announcements both arrive without waiting for the
        // app to be opened. The text now says that instead of describing a stream.
        val text =
            if (connected) "Connected to Music Assistant - keeps playback and announcements ready"
            else pb.connectionStatus.value

        val open = openAppIntent(this, OpenAppRequest.CONNECTION)

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SendspinConnectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)   // not on lock screen
            // "Disconnect", not "Stop": it does not stop the music, it takes the
            // player off Music Assistant entirely — after which it has to be turned
            // back on in Settings. Calling it Stop invited it to be used as a pause.
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Its own channel so it can be hidden from the shade in system settings
            // without touching the media notification. Android requires a foreground
            // service to post *something* while the connection is held, so hiding the
            // channel is the only way to get it out of the way while staying online —
            // there is no in-app setting that can do it.
            val channel = NotificationChannel(CHANNEL_ID, "Player connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps this phone registered with Music Assistant so playback " +
                    "and announcements start without opening the app"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The connection stays alive when the app is swiped from Recents —
        // the whole point is to be reachable for TTS.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Warm goodbye. This service is START_STICKY, so the common reason we
        // land here without the user having tapped Stop is the system reclaiming
        // the process — and it will start us again. Telling MA `"restart"` makes
        // it hold the player slot for its ~30 s resume grace, so the player does
        // not blink out of the speaker list in the gap. An explicit Stop already
        // sent `"user_request"` from onStartCommand, which is the one case where
        // we *want* MA to drop us immediately.
        if (!stoppedByUser) {
            runCatching { pb.disconnect(stopService = false, reason = "restart") }
        }
        releaseLocks()
        observeJob?.cancel()
        playbackWatchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}