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
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.MainActivity

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
        const val NOTIFICATION_ID = 1002       // above the media notification (1001)
        const val ACTION_START = "com.engabd.sendpin.START_EFFECT"
        const val ACTION_STOP = "com.engabd.sendpin.STOP_EFFECT"
        const val EXTRA_TITLE = "title"

        /** Requested by the UI when a show starts. */
        fun start(context: Context, title: String) {
            val intent = Intent(context, EffectsService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                onStopRequested?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                createChannel()
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Ambience"
                startForegroundCompat(buildNotification(title))
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
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    override fun onDestroy() {
        onStopRequested = null
        super.onDestroy()
    }
}
