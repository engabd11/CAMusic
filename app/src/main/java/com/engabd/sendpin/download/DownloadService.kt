package com.engabd.sendpin.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.service.OpenAppRequest
import com.engabd.sendpin.service.openAppIntent

/**
 * Keeps the process running while downloads are in flight.
 *
 * Without it the downloads ran in a process the system was free to freeze or reclaim
 * the moment the app left the screen — which is the moment someone starts an album
 * downloading and puts the phone in a pocket. A `dataSync` foreground service is what
 * the platform provides for exactly this: a transfer the user asked for, with a
 * notification saying so.
 *
 * Held and released by [DownloadManager] around each run; stops itself when the last
 * run ends. Failing to start (a start from the background, which Android 12+ refuses)
 * is not fatal — the downloads carry on for as long as the process lives, and the
 * persisted [DownloadQueue] picks up anything left the next time it starts.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        val n = notification(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Downloading")
        runCatching {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.onFailure { Log.w(TAG, "Could not run downloads in the foreground: ${it.message}") }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1004
        private const val ACTION_STOP = "com.engabd.sendpin.STOP_DOWNLOADS"
        private const val EXTRA_TEXT = "text"

        private val lock = Any()
        private var holds = 0

        /** A run is starting: make sure the service is up. */
        fun hold(context: Context, text: String) {
            synchronized(lock) { holds++ }
            runCatching {
                context.startForegroundService(
                    Intent(context, DownloadService::class.java).putExtra(EXTRA_TEXT, text),
                )
            }.onFailure { Log.w(TAG, "Download service not started: ${it.message}") }
        }

        /** A run has ended: stop the service once none remain. */
        fun release(context: Context) {
            val last = synchronized(lock) { holds = (holds - 1).coerceAtLeast(0); holds == 0 }
            if (last) runCatching {
                context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
            }
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while music is being downloaded for offline listening"
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context, text: String): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading music")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context, OpenAppRequest.DOWNLOADS))
                .build()
    }
}
