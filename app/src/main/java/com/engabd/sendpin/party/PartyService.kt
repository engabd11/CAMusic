package com.engabd.sendpin.party

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.engabd.sendpin.R
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.service.OpenAppRequest
import com.engabd.sendpin.service.openAppIntent

/**
 * Keeps the process (and so the party's server) alive while a listening party is open.
 *
 * The server itself belongs to [PartyManager]; this service is only the foreground
 * notification that makes it legal to keep running with the screen off, with an "End
 * party" button on it. `dataSync` is the closest foreground type for a few phones
 * fetching a few small files over the local network; Android 15 limits that type to
 * about six hours a day, and [onTimeout] ends the party politely rather than letting the
 * system do it.
 */
class PartyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            (application as SendpinApp).partyManager.end()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        runCatching {
            startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.onFailure { Log.w(TAG, "Could not hold the party in the foreground: ${it.message}") }
        // Not sticky: a party that outlived its process has no server to come back to.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as SendpinApp).partyManager.end("The party ended because Android limits how long it can run in the background.")
        stopSelf()
    }

    companion object {
        private const val TAG = "PartyService"
        private const val CHANNEL_ID = "listening_party"
        private const val NOTIFICATION_ID = 1010
        private const val ACTION_END = "com.engabd.sendpin.END_PARTY"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PartyService::class.java)) }
                .onFailure { Log.w(TAG, "Party service not started: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PartyService::class.java)) }
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Listening party", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while guests can add songs to your queue from their own phones"
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context): Notification {
            val end = PendingIntent.getService(
                context, 0,
                Intent(context, PartyService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_camusic)
                .setContentTitle("Listening party is open")
                .setContentText("Guests on your Wi-Fi can add songs. Tap to see who is here.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context, OpenAppRequest.PARTY))
                .addAction(0, "End party", end)
                .build()
        }
    }
}
