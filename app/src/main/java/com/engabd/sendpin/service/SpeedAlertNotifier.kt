package com.engabd.sendpin.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.engabd.sendpin.MainActivity
import com.engabd.sendpin.R

/**
 * The seen-and-felt half of the speed-limit alert: a heads-up notification and a
 * pulse, alongside [SpeedAlertSound]'s tone.
 *
 * ## Why a tone was never enough
 *
 * The alert used to be a fraction of a second of audio and nothing else, which
 * makes it the one kind of warning a car is worst at delivering. It goes out over
 * the same Bluetooth link the music is on, under a track that has only just begun
 * to duck, next to road noise, to a driver who is deliberately not looking at the
 * phone — and if it is missed, it leaves nothing behind. There is no way to check
 * afterwards whether it fired and you did not hear it, or never fired at all,
 * which is exactly the position the feature's own bug report was written from.
 *
 * So the alert now arrives on three channels at once:
 *
 *  * **Heard** — [SpeedAlertSound], ducking the music on the media route.
 *  * **Seen** — a heads-up notification naming the speed and the limit, so a glance
 *    at the cradle answers "what is the limit here and how far over am I".
 *  * **Felt** — a short double pulse, which is the only one of the three that
 *    survives a phone in a pocket and a stereo turned up.
 *
 * The notification is also the record. It lingers in the shade after the heads-up
 * banner goes, so a driver who heard nothing can still see that it fired.
 *
 * ## The channel
 *
 * `IMPORTANCE_HIGH` — without it there is no heads-up banner and the whole point of
 * the visible half is lost — but **silent**, with vibration off. Both of those are
 * deliberate: the channel must not add a notification chime on top of the alert
 * sound the driver actually chose, and the pulse is fired directly by [vibrate] so
 * that it still happens on a phone where notifications are switched off entirely.
 *
 * A channel's importance and sound are fixed at creation and cannot be changed by a
 * later `createNotificationChannel`, so [CHANNEL_ID] carries a version suffix. Bump
 * it rather than editing the channel in place if these ever need to change.
 */
object SpeedAlertNotifier {

    /**
     * Warn that [speedKmh] is over [limitKmh].
     *
     * Safe to call from the location callback on the main thread: everything here
     * is a binder call, and every one of them is wrapped, because a driver is not
     * the person to hand an exception to over a notification.
     */
    fun alert(context: Context, speedKmh: Int, limitKmh: Int, autoDetected: Boolean) {
        val app = context.applicationContext
        // The pulse first, and outside the notification path entirely. It is the
        // channel that survives a locked phone in a pocket, and it must not be lost
        // with the notification when POST_NOTIFICATIONS was never granted.
        vibrate(app)
        post(app, speedKmh, limitKmh, autoDetected)
    }

    /**
     * Two short pulses, a beat apart.
     *
     * Not one: a single buzz is what every message this phone receives feels like,
     * and the driver has to be able to tell this apart from one without looking.
     */
    private fun vibrate(app: Context) {
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(PULSE_PATTERN, -1))
        }
    }

    private fun post(app: Context, speedKmh: Int, limitKmh: Int, autoDetected: Boolean) {
        // Android 13+ needs the runtime grant, and a notify() without it is dropped
        // silently. Nothing to do about it here — the tone and the pulse have both
        // already happened — but there is no point building the notification.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            createChannel(app)
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, build(app, speedKmh, limitKmh, autoDetected))
        }
    }

    private fun build(app: Context, speedKmh: Int, limitKmh: Int, autoDetected: Boolean): Notification {
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val over = (speedKmh - limitKmh).coerceAtLeast(0)
        return NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle("$speedKmh km/h in a $limitKmh zone")
            .setContentText(
                "You are $over km/h over the limit" +
                    if (autoDetected) " on this road." else ", the one you set.",
            )
            .setSmallIcon(R.drawable.ic_stat_speed_alert)
            .setContentIntent(open)
            .setAutoCancel(true)
            // A navigation announcement, which is what this is — it is also what
            // gets it shown by a head unit and by Do Not Disturb's driving rules.
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            // The banner, rather than a quiet line in the shade. PRIORITY_HIGH is
            // what carries the channel's importance on the older API this compat
            // builder still targets.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The sound is [SpeedAlertSound]'s job and the pulse is [vibrate]'s.
            // Without this the shade adds a chime of its own on top of both.
            .setSilent(true)
            // Re-alert on each beep rather than updating in place silently: the
            // second warning of a drive matters as much as the first.
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            // Gone on its own well before the next zone. A speed warning that is
            // still in the shade an hour later is not information, it is litter.
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
    }

    private fun createChannel(app: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Speed limit alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns you when your GPS speed goes over the limit."
            // See the class doc: silent and buzz-free by design, so the sound and
            // the pulse are the ones this app chose rather than two extra ones.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /** Wait, buzz, wait, buzz — see [vibrate]. */
    private val PULSE_PATTERN = longArrayOf(0, 180, 120, 180)

    /** Versioned: a channel's importance cannot be edited after creation. */
    private const val CHANNEL_ID = "speed_alert_v1"
    private const val NOTIFICATION_ID = 4132

    /** How long the warning stays in the shade before dismissing itself. */
    private const val TIMEOUT_MS = 60_000L
}
