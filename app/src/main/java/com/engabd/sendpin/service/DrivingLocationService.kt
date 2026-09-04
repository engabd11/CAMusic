package com.engabd.sendpin.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.engabd.sendpin.MainActivity

/**
 * The foreground service that keeps [SpeedMonitor]'s GPS subscription alive once
 * the user leaves the app.
 *
 * ## Why this has to exist
 *
 * `ACCESS_FINE_LOCATION` is a *while-in-use* grant. From Android 10 onward an app
 * that is not visible only keeps that grant while it runs a foreground service
 * **declared with the `location` type** — and none of this app's services were. The
 * media-playback service is not enough: the platform grants location on the type a
 * service actually started with, not on the fact that some foreground service exists.
 *
 * So the speed-limit alert worked for exactly as long as CAMusic was the app on
 * screen. The moment the driver switched to Google Maps — which is the entire
 * situation driving mode is built for — `LocationManager` went quiet, `onLocation`
 * stopped being called, and the alert could not fire however far over the limit the
 * car went. There was nothing to see: no error, no callback, no beep.
 *
 * This service is one line of purpose and a notification. It runs only while
 * [SpeedMonitor] is actually listening — which is itself gated on driving mode being
 * on *and* one of the two speed features being enabled *and* the location permission
 * being granted — and it stops the moment that stops being true.
 *
 * ## What it deliberately does not do
 *
 * It does not touch `LocationManager` itself. The subscription stays in
 * [SpeedMonitor], where the readings are used; splitting it across a service binder
 * would buy nothing and add a lifecycle to keep in step. This is a permission
 * anchor, and that is all it is.
 *
 * No `START_STICKY`, for the same reason [DrivingOverlayService] has none: if the
 * process goes, the drive is over as far as this is concerned. The car reconnecting
 * brings it all back.
 */
class DrivingLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /**
     * `startForeground` is attempted unconditionally, even though [start] has just
     * checked the permission that Android 14+ throws over when it is missing.
     *
     * The alternative — check again here and `stopSelf()` instead — is the worse
     * failure. A service reached through `startForegroundService` that stops without
     * ever going foreground is killed with
     * `Context.startForegroundService() did not then call Service.startForeground()`,
     * so the "safe" branch is the one that crashes. Attempting it and logging a
     * refusal is what the rest of this app's services do; see `EffectsService`.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            // minSdk is 31, so the typed overload is the only one that has ever run
            // here — there is no pre-Q branch to keep.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }.onFailure {
            // Survivable: the alert falls back to whatever location the platform
            // still hands a backgrounded app, which is little but not nothing, and
            // everything keeps working while CAMusic is the app on screen.
            android.util.Log.w(TAG, "startForeground failed: ${it.message}")
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Watching your speed")
            .setContentText("Reading GPS speed for driving mode.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Speed watching", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while driving mode is reading GPS speed."
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DrivingLocation"
        private const val CHANNEL_ID = "driving_location"
        private const val NOTIFICATION_ID = 4131

        /**
         * Start watching, if the grant is there to start with.
         *
         * `runCatching` around the start itself and not only around
         * `startForeground`: Android 12+ can refuse a foreground service *launched*
         * from the background, and while the case this covers — music already
         * playing, so the app is running its own media foreground service — is one
         * the platform allows, a driver is not the person to hand an exception to
         * over a notification that did not appear.
         */
        fun start(context: Context) {
            if (!hasLocationPermission(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DrivingLocationService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DrivingLocationService::class.java)) }
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
