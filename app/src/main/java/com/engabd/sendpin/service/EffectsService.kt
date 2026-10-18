package com.engabd.sendpin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps an ambience show alive while the app is not on screen.
 *
 * `DirectLightSync` already takes a partial wake lock and a Wi-Fi lock, so the 60 Hz
 * render loop survives the screen going off on its own. What it does not survive is the
 * process being frozen: an `AudioTrack` writer coroutine in a backgrounded app is
 * subject to App Standby and cached-process restrictions, and an effect that stops the
 * moment the phone is put down is not an ambience effect.
 *
 * Its own service rather than reusing one of the others. `LocalPlaybackService` wraps a
 * media3 `MediaSession` over `LocalPlayer.exoPlayer`, which does not exist here — the
 * sound is synthesised, there is no player and no queue to expose. `SendspinService`
 * follows the Music Assistant stream. The manifest already establishes that separate
 * players get separate services.
 *
 * No media session on purpose: an ambience show is not a track. Putting it on the lock
 * screen with transport controls would invite "next", "previous" and a scrub bar for a
 * thing with no length and no neighbours.
 */
class EffectsService : Service() {

    companion object {
        const val CHANNEL_ID = "effects_ambience"
        /**
         * Its own id. This was 1002, the same as LocalPlaybackService's — and an
         * ambience show can run over music, so starting one replaced the media
         * notification with "Ambience show running", and stopping it could take the
         * music's notification away with it.
         */
        const val NOTIFICATION_ID = 1003
        const val ACTION_START = "com.engabd.sendpin.START_EFFECT"
        const val ACTION_STOP = "com.engabd.sendpin.STOP_EFFECT"
        const val ACTION_SET_SLEEP = "com.engabd.sendpin.SET_EFFECT_SLEEP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"

        /** The last minute of a sleep timer is spent easing the room and the sound to nothing. */
        const val FADE_OUT_S = 60

        private val _remainingS = MutableStateFlow<Int?>(null)

        /**
         * Seconds the running show has left before the sleep timer ends it, or null.
         *
         * Lives here, in the service, rather than in the Effects view model. The view
         * model is Activity-scoped; the service is what keeps the show alive when the
         * Activity is gone, so it is the only thing that can honestly promise "stop in
         * an hour".
         */
        val remainingS: StateFlow<Int?> = _remainingS.asStateFlow()

        /** Requested by the UI when a show starts. */
        fun start(context: Context, title: String, sleepMinutes: Int = 0) {
            val intent = Intent(context, EffectsService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SLEEP_MINUTES, sleepMinutes)
            }
            // Same guard as SendspinConnectionService: Android 12+ refuses a foreground
            // service started from the background, and the refusal is an exception. A
            // show whose notification cannot be posted is degraded, not a crash.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                android.util.Log.w("EffectsService", "couldn't start service: ${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, EffectsService::class.java)) }
        }

        /**
         * Change the sleep timer of the show that is running. 0 turns it off.
         *
         * Only meaningful while a show is up; the service is already in the foreground
         * then, so a plain `startService` is enough to reach it.
         */
        fun setSleepTimer(context: Context, minutes: Int) {
            val intent = Intent(context, EffectsService::class.java).apply {
                action = ACTION_SET_SLEEP
                putExtra(EXTRA_SLEEP_MINUTES, minutes)
            }
            runCatching { context.startService(intent) }.onFailure {
                android.util.Log.w("EffectsService", "couldn't set sleep timer: ${it.message}")
            }
        }

        /**
         * Set by the UI so the notification's Stop action can reach the running show
         * without this service knowing anything about the light engine.
         *
         * A callback rather than a binder: the show is owned by a process-scoped object
         * already, and giving this service a reference to it would make the service the
         * thing that has to be alive for the show to stop.
         */
        @Volatile var onStopRequested: (() -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepJob: Job? = null

    /** True once a show's notification is up, so a stray SET_SLEEP cannot start a timer for nothing. */
    private var showing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_SLEEP -> {
                if (showing) armSleep(intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0))
            }
            else -> {
                createChannel()
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Ambience"
                startForegroundCompat(buildNotification(title))
                showing = true
                armSleep(intent?.getIntExtra(EXTRA_SLEEP_MINUTES, 0) ?: 0)
            }
        }
        // Deliberately not sticky. A restarted service would have no show to keep
        // alive — the session died with the process — so it would post a notification
        // for an effect that is not running.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure {
            android.util.Log.w("EffectsService", "startForeground failed: ${it.message}")
        }
    }

    private fun buildNotification(title: String): Notification {
        val open = openAppIntent(this, OpenAppRequest.EFFECTS)
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, EffectsService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Ambience show running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Ambience effects", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a lighting effect is running."
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /**
     * Count [minutes] down, ease the show out over the last [FADE_OUT_S] seconds, then end it.
     *
     * The fade goes through [SendpinApp.setAmbienceFade] (lights and sound together) and
     * the end through [SendpinApp.stopAmbienceShow], the same app-level calls the
     * notification's Stop action uses, so nothing here needs the Activity. A timer shorter
     * than the fade spends all of itself fading. Re-arming restores full brightness and
     * volume first, so extending a timer that was already fading brings the room back up.
     */
    private fun armSleep(minutes: Int) {
        sleepJob?.cancel()
        val app = applicationContext as? SendpinApp
        app?.setAmbienceFade(1f)
        if (minutes <= 0) {
            _remainingS.value = null
            return
        }
        val totalMs = minutes * 60_000L
        val fadeMs = minOf(FADE_OUT_S * 1_000L, totalMs)
        sleepJob = scope.launch {
            val endAt = SystemClock.elapsedRealtime() + totalMs
            while (true) {
                val leftMs = endAt - SystemClock.elapsedRealtime()
                if (leftMs <= 0) break
                _remainingS.value = ((leftMs + 999) / 1000).toInt()
                if (leftMs <= fadeMs) app?.setAmbienceFade(leftMs.toFloat() / fadeMs)
                delay(250)
            }
            _remainingS.value = null
            app?.stopAmbienceShow()
        }
    }

    override fun onDestroy() {
        onStopRequested = null
        sleepJob?.cancel()
        scope.cancel()
        _remainingS.value = null
        showing = false
        super.onDestroy()
    }
}
