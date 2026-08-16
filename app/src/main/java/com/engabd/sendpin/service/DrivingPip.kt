package com.engabd.sendpin.service

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.engabd.sendpin.R

/**
 * The driving bar as a Picture-in-Picture window — option E2, and the default.
 *
 * Costs **no special permission at all**, which is the whole reason it leads.
 * `PictureInPictureParams` takes [RemoteAction]s, so previous / play-pause / next
 * are expressible, and a PiP window floats over other apps including Maps. For a
 * feature someone sets up once in a car park, "no permission prompt" is a large
 * usability win — large enough to be worth prototyping before paying for
 * `SYSTEM_ALERT_WINDOW`.
 *
 * ## Its limits, stated rather than discovered
 *
 * - A small fixed window: the system decides the size, so "targets far larger than
 *   the 48dp minimum" is **not achievable here**. That is the single biggest reason
 *   [DrivingOverlayService] exists behind it.
 * - A capped number of actions. Three is within every version's cap; a fourth is
 *   not guaranteed.
 * - Easy to dismiss by accident.
 * - **Entering requires the activity to be foreground at that moment**, so the flow
 *   is "open the app, then start navigating" rather than "start it from anywhere".
 *   `MainActivity.onUserLeaveHint` is the hook — see [maybeEnter].
 *
 * The actions are broadcasts rather than activity intents, so pressing one does not
 * bring the app forward over the map.
 */
object DrivingPip {

    private const val ACTION_CONTROL = "com.engabd.sendpin.DRIVING_PIP_CONTROL"
    private const val EXTRA_CONTROL = "control"
    private const val CONTROL_PREV = "prev"
    private const val CONTROL_PLAY_PAUSE = "play_pause"
    private const val CONTROL_NEXT = "next"

    /**
     * Enter PiP if driving mode wants it and this build can do it.
     *
     * Called from `onUserLeaveHint` — the moment the user leaves for another app,
     * which is both the only moment the platform allows the transition and exactly
     * when the bar becomes useful.
     */
    fun maybeEnter(activity: Activity) {
        val app = activity.applicationContext as? com.engabd.sendpin.SendpinApp ?: return
        if (!app.drivingMode.active.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!activity.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
        ) return
        runCatching { activity.enterPictureInPictureMode(params(activity)) }
    }

    /** The window's shape and its three actions. */
    fun params(context: Context): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            // Wide and short: this is a transport bar, not a video. A squarer ratio
            // would waste the window on empty space and shrink the targets further,
            // and they are already the constraint here.
            .setAspectRatio(Rational(16, 5))
            .setActions(
                listOf(
                    action(context, CONTROL_PREV, R.drawable.ic_driving_prev, "Previous"),
                    action(context, CONTROL_PLAY_PAUSE, R.drawable.ic_driving_play_pause, "Play or pause"),
                    action(context, CONTROL_NEXT, R.drawable.ic_driving_next, "Next"),
                ),
            )
        return b.build()
    }

    private fun action(context: Context, control: String, iconRes: Int, title: String): RemoteAction {
        val intent = Intent(ACTION_CONTROL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_CONTROL, control)
        val pending = PendingIntent.getBroadcast(
            context,
            control.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(context, iconRes), title, title, pending)
    }

    /**
     * Routes a PiP button to whichever player owns the session.
     *
     * Through [PlaybackOwner] rather than through a copy of the routing rules, for
     * the reason that class exists: a control that pauses the wrong player while
     * someone is driving is the worst possible place to have got this wrong.
     */
    class ControlReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val owner = com.engabd.sendpin.SendpinApp.instance.playbackOwner
            when (intent?.getStringExtra(EXTRA_CONTROL)) {
                CONTROL_PREV -> owner.previous()
                CONTROL_PLAY_PAUSE -> owner.playPause()
                CONTROL_NEXT -> owner.next()
            }
        }
    }

    /** Registered by the Activity for as long as it may be in PiP. */
    fun registerControls(context: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
