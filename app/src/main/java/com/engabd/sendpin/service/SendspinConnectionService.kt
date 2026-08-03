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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pb.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        observeConnection()
        return START_STICKY
    }

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
        val title = if (connected) "Sendspin" else "Sendspin — reconnecting"
        val text =
            if (connected) "Ready — announcements will play here"
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sendspin Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the player connected and reachable for announcements"
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

    override fun onTrimMemory(level: Int) {
        // When the app goes to the background (not destroyed), send a warm
        // goodbye so MA holds the player slot for 30 seconds. A full disconnect
        // drops the player from MA's speaker list immediately; "restart" keeps
        // it visible so a quick app switch doesn't lose the player.
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            pb.disconnect(reason = "restart")
        }
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        releaseLocks()
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}