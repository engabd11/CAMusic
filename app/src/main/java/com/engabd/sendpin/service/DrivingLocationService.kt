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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.engabd.sendpin.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * [SpeedMonitor] is actually listening — which is itself gated on the designated
 * car Bluetooth device being *connected*, one of the two speed features being
 * enabled, and the location permission being granted — and it stops the moment
 * any of those stops being true. The Bluetooth link going down tears the whole
 * thing down with it: no subscription, no service, no notification, no battery.
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
 *
 * ## When Android lets it start
 *
 * The car connecting is what starts the watch, and the car almost always connects
 * while CAMusic is in the background. From there Android refuses this service
 * outright (`Background started FGS: Disallowed`, reproduced on an emulator): a
 * `location` service only gets while-in-use location if it starts while the app is
 * on screen, or from something the user tapped. Started from either of those, it
 * keeps location for the rest of the drive after the app leaves the screen.
 *
 * So a refusal is not the end of it. It posts a "speed watch paused" notification
 * whose tap starts this service directly, without opening the app or covering the
 * map, and [SpeedMonitor] starts it again the next time CAMusic comes on screen.
 * Previously nothing retried: the watch was marked as listening, so opening the
 * app skipped the start, and location stopped every time the app left the screen
 * for the whole drive.
 */
class DrivingLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
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
        }.onSuccess {
            _refusal.value = null
            _running.value = true
            cancelPaused(this)
        }.onFailure {
            // Survivable: the alert falls back to whatever location the platform
            // still hands a backgrounded app, which is little but not nothing, and
            // everything keeps working while CAMusic is the app on screen.
            //
            // Survivable is not the same as invisible, though, and logcat is the one
            // place a driver will never look. This is the exact shape of "the app
            // said it was watching and nothing ever beeped": fixes arrive while
            // CAMusic is on screen, and the moment the map comes forward the
            // subscription goes quiet with nothing to show for it. Said out loud.
            android.util.Log.w(TAG, "startForeground failed: ${it.message}")
            _refusal.value = REFUSED_MESSAGE
            notifyPaused(this)
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

    companion object {
        private const val TAG = "DrivingLocation"
        private const val CHANNEL_ID = "driving_location"
        private const val NOTIFICATION_ID = 4131
        private const val PAUSED_CHANNEL_ID = "driving_location_paused"
        private const val PAUSED_NOTIFICATION_ID = 4132

        /**
         * One sentence, written for a driver rather than for a stack trace. Android
         * gives several different reasons for the same outcome — a background start
         * on 12+, the while-in-use rule for a `location`-typed service on 14+ — and
         * the remedy is the same for all of them, so the remedy is what it says.
         */
        private const val REFUSED_MESSAGE =
            "Android would not start the speed watch while CAMusic was in the background, " +
                "so speed is only read while CAMusic is on screen. Tap the \"Speed watch " +
                "paused\" notification, or open CAMusic, and it keeps running for the rest " +
                "of the drive."

        private val _running = MutableStateFlow(false)

        /**
         * True while the service is actually in the foreground, holding location.
         * [SpeedMonitor] reads it to decide whether coming on screen needs to start it.
         */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _refusal = MutableStateFlow<String?>(null)

        /**
         * Non-null while the platform is refusing to run this service — see
         * [REFUSED_MESSAGE]. Exposed rather than logged because the failure is
         * silent by nature: the subscription survives, the alert does not.
         *
         * Cleared when the service starts cleanly and when [stop] takes the watch
         * down — deliberately *not* in `onDestroy`. The platform kills a service
         * that never reached `startForeground`, so clearing there would wipe the
         * message a few seconds after setting it, which is the one case it exists
         * for.
         */
        val refusal: StateFlow<String?> = _refusal.asStateFlow()

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
            }.onFailure {
                // A refused *launch* never reaches onStartCommand, so it has to be
                // reported from here or this half of the failure stays silent.
                android.util.Log.w(TAG, "startForegroundService refused: ${it.message}")
                _refusal.value = REFUSED_MESSAGE
                notifyPaused(context)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DrivingLocationService::class.java)) }
            cancelPaused(context)
            // Not left to onDestroy: a service that never managed to start has none
            // to run, and a stale refusal would outlive the watch it was about.
            _refusal.value = null
        }

        /**
         * Say the watch is paused, with a tap that starts it.
         *
         * The tap goes straight to this service, not to the app: a notification tap
         * is one of the things Android accepts as the user asking, so the service
         * started from it gets location, and the driver's map stays on screen.
         */
        private fun notifyPaused(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannels(context)
            val resume = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, DrivingLocationService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, PAUSED_CHANNEL_ID)
                .setContentTitle("Speed watch paused")
                .setContentText("Tap to start reading your speed for this drive.")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(resume)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(PAUSED_NOTIFICATION_ID, notification) }
        }

        private fun cancelPaused(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(PAUSED_NOTIFICATION_ID) }
        }

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Speed watching", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while driving mode is reading GPS speed."
                    setShowBadge(false)
                },
            )
            // Default importance, silent: it has to be seen in the status bar to be
            // tapped, but it arrives as the car connects and must not sound over that.
            manager.createNotificationChannel(
                NotificationChannel(
                    PAUSED_CHANNEL_ID, "Speed watch paused", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Shown when Android would not start the speed watch in the " +
                        "background. Tap it to start the watch."
                    setSound(null, null)
                    setShowBadge(false)
                },
            )
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
