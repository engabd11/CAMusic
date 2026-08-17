package com.engabd.sendpin.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.SendpinApp

/**
 * Pauses playback when the phone starts ringing or answers a call.
 *
 * Deliberately never auto-resumes on hangup — see [PlaybackOwner.pause]'s doc: the
 * listener may have been mid-conversation, not mid-song, and resuming into that is
 * a worse surprise than staying paused. A notification offers the tap back instead.
 *
 * Opt-in via `AppSettings.pauseForCalls` (default off) and gated on
 * `READ_PHONE_STATE`, requested only when the setting is turned on — the app asks
 * for nothing here it doesn't already have a use for.
 */
class CallPauseObserver(private val context: Context) {

    private val telephonyManager get() = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /** Only set when *this* observer is the reason playback paused — never resumed automatically, only used to decide whether the "paused for a call" notification is warranted. */
    @Volatile private var pausedByCall = false

    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            val owner = SendpinApp.instance.playbackOwner
            when (state) {
                TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (!pausedByCall && owner.state.value.anyPlaying) {
                        pausedByCall = true
                        owner.pause()
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (pausedByCall) {
                        pausedByCall = false
                        notifyPausedForCall()
                    }
                }
            }
        }
    }

    /** No-op, silently, unless the permission is already granted — the caller decides whether to ask for it first. */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel()
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
    }

    fun stop() {
        runCatching { telephonyManager.unregisterTelephonyCallback(callback) }
    }

    private fun notifyPausedForCall() {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Music paused for a call")
            .setContentText("Tap to open CAMusic and resume")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Paused for calls", NotificationManager.IMPORTANCE_LOW)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "call_pause"
        private const val NOTIFICATION_ID = 4822
    }
}
