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
import com.engabd.sendpin.SendpinApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The driving bar as a Picture-in-Picture window — option E2, and the default.
 *
 * Costs **no special permission at all**, which is the whole reason it leads.
 * `PictureInPictureParams` takes [RemoteAction]s, so previous / play or pause /
 * next are expressible, and a PiP window floats over other apps including Maps.
 * For a feature someone sets up once in a car park, "no permission prompt" is a
 * large usability win — large enough to be worth prototyping before paying for
 * `SYSTEM_ALERT_WINDOW`.
 *
 * ## Its limits, stated rather than discovered
 *
 * - A small fixed window: the system decides the size, so "targets far larger than
 *   the 48dp minimum" is **not achievable here**. That is the single biggest reason
 *   [DrivingOverlayService] exists behind it.
 * - **No touch reaches the window's own content.** Android draws the window's buttons
 *   (the [RemoteAction]s below) in its own menu, shown when the window is tapped; a
 *   second tap on the menu's expand button returns to the app. That is the platform's
 *   design for every PiP window, which is why the content says "Tap for controls".
 * - A capped number of actions. Three is within every version's cap; a fourth is
 *   not guaranteed.
 * - **Entering requires the activity to be foreground at that moment**, so the flow
 *   is "open the app, then start navigating" rather than "start it from anywhere".
 *   On Android 12+ the window is armed with `setAutoEnterEnabled` while a drive is
 *   on, so leaving the app enters it with the system's own seamless transition;
 *   `MainActivity.onUserLeaveHint` is the fallback — see [maybeEnter].
 *
 * The actions are broadcasts rather than activity intents, so pressing one does not
 * bring the app forward over the map.
 */
object DrivingPip {

    private const val TAG = "DrivingPip"
    private const val ACTION_CONTROL = "com.engabd.sendpin.DRIVING_PIP_CONTROL"
    private const val EXTRA_CONTROL = "control"
    internal const val CONTROL_PREV = "prev"
    internal const val CONTROL_PLAY = "play"
    internal const val CONTROL_PAUSE = "pause"
    internal const val CONTROL_NEXT = "next"

    /** What the window shows and which play button it carries. */
    data class Now(
        val title: String = "",
        val artist: String = "",
        val artUrl: String? = null,
        val playing: Boolean = false,
    )

    /**
     * The track the driving controls are about, following whichever player owns the
     * session — the same choice [com.engabd.sendpin.ui.screens.DrivingBar] makes, so
     * the window and the bar can never disagree about what is playing.
     */
    fun nowPlaying(app: SendpinApp): Flow<Now> = combine(
        app.localPlayer.current,
        app.localPlayer.playing,
        app.maNowPlaying.now,
        app.playbackOwner.state,
    ) { local, localPlaying, ma, owner ->
        nowOf(local, localPlaying, ma, owner)
    }.distinctUntilChanged()

    /** [nowPlaying], read once — for the broadcast receiver, which cannot suspend. */
    private fun nowSnapshot(app: SendpinApp): Now = nowOf(
        app.localPlayer.current.value,
        app.localPlayer.playing.value,
        app.maNowPlaying.now.value,
        app.playbackOwner.state.value,
    )

    private fun nowOf(
        local: com.engabd.sendpin.audio.LocalTrack?,
        localPlaying: Boolean,
        ma: MaNowPlaying.Now?,
        owner: PlaybackOwner.State,
    ): Now {
        val isLocal = owner.sessionOwner == PlaybackOwner.Who.LOCAL
        return if (isLocal) {
            Now(local?.title.orEmpty(), local?.artist.orEmpty(), local?.artUrl, localPlaying)
        } else {
            Now(
                ma?.title.orEmpty(), ma?.artist.orEmpty(), ma?.artworkUrl,
                owner.sendspinPlaying || ma?.isPlaying == true,
            )
        }
    }

    /**
     * The window's three buttons, in order: previous, then *either* pause or play,
     * then next.
     *
     * It used to be one combined play-or-pause glyph, on the theory that the window's
     * buttons could not be changed once it was open. They can — the activity is alive
     * the whole time it is a PiP window, and `setPictureInPictureParams` replaces the
     * buttons in place — so the button now says what pressing it will do, like every
     * other transport control in the app.
     */
    internal fun controlsFor(playing: Boolean): List<String> =
        listOf(CONTROL_PREV, if (playing) CONTROL_PAUSE else CONTROL_PLAY, CONTROL_NEXT)

    /**
     * When the floating window should open by itself.
     *
     * Only on the moment driving mode turns **on** while the app is in front — never
     * merely because the app is in front *while* it is on. The old rule entered on
     * every resume with driving mode active, so tapping the window's expand button,
     * or opening the app from the launcher during a drive, put the app on screen for
     * a quarter of a second and threw it straight back into the window: the app
     * could not be reached at all while driving, which read as a crash.
     *
     * Kept separate and pure so the rule is tested rather than remembered. Fed every
     * value of driving mode for as long as the activity exists — not only while it is
     * resumed — so a drive that started while the app was in the background is not
     * mistaken for one starting the moment the app is opened.
     */
    class EntryGate {
        private var lastActive = false

        /** Driving mode is [active]; true when this is the moment to enter. */
        fun onDriving(active: Boolean, resumed: Boolean): Boolean {
            val rising = active && !lastActive
            lastActive = active
            return rising && resumed
        }
    }

    /**
     * Enter PiP if driving mode wants it and this build can do it.
     *
     * Called from `onUserLeaveHint` when the auto-enter arming is not in place, and on
     * the moment a drive starts with the app in front (see [EntryGate]).
     */
    fun maybeEnter(activity: Activity, now: Now) {
        val app = activity.applicationContext as? SendpinApp ?: return
        if (!app.drivingMode.active.value) return
        // The mechanism the user actually chose. This never read the setting, so
        // "Floating window" changed nothing at all — PiP was attempted in both modes,
        // and in bar mode it fought the overlay for the same moment.
        if (!wantsPip(app)) return
        if (!supported(activity)) return
        // Failures are logged rather than swallowed. This used to be a bare
        // runCatching, and the ratio it was handed was one the platform rejects on
        // every device — so the default driving mechanism threw on every attempt and
        // nothing anywhere said so.
        runCatching { activity.enterPictureInPictureMode(params(activity, now, autoEnter = true)) }
            .onFailure { android.util.Log.w(TAG, "Could not enter Picture-in-Picture", it) }
    }

    /** Whether this device has Picture-in-Picture at all. */
    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Whether the floating window is the chosen mechanism.
     *
     * Read synchronously off the mirror `DrivingMode` keeps, because the callers
     * are an `onUserLeaveHint` and a lifecycle callback — neither can suspend, and the
     * answer is needed in the same frame the transition has to happen in.
     */
    fun wantsPip(app: SendpinApp): Boolean =
        app.drivingMode.mechanism == com.engabd.sendpin.data.AppSettings.DRIVING_PIP

    /**
     * The widest ratio [PictureInPictureParams] accepts (2.39:1). A getter rather than
     * a stored value, so loading this object never builds an Android `Rational` —
     * which keeps its pure parts ([controlsFor], [EntryGate]) testable on the JVM.
     */
    internal val MAX_ASPECT: Rational get() = Rational(239, 100)

    /**
     * The window's shape, its three buttons and — on Android 12+ — whether leaving
     * the app should open it by itself ([autoEnter]).
     */
    fun params(context: Context, now: Now, autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            // Wide and short: this is a transport bar, not a video. A squarer ratio
            // would waste the window on empty space and shrink the targets further,
            // and they are already the constraint here.
            //
            // As wide as the platform allows, and no wider. PictureInPictureParams
            // accepts 1:2.39 to 2.39:1 and throws IllegalArgumentException outside
            // it; this was 16:5 (3.2:1), so PiP never opened on any device.
            .setAspectRatio(MAX_ASPECT)
            .setActions(controlsFor(now.playing).map { action(context, it) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(autoEnter)
            // Not a video: a cross-fade on resize reads better than stretching text.
            b.setSeamlessResizeEnabled(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Shown in the system's own window menu, so the track is readable there too.
            if (now.title.isNotBlank()) b.setTitle(now.title)
            if (now.artist.isNotBlank()) b.setSubtitle(now.artist)
        }
        return b.build()
    }

    private fun action(context: Context, control: String): RemoteAction {
        val (iconRes, title) = when (control) {
            CONTROL_PREV -> R.drawable.ic_driving_prev to "Previous"
            CONTROL_PAUSE -> R.drawable.ic_driving_pause to "Pause"
            CONTROL_PLAY -> R.drawable.ic_driving_play to "Play"
            else -> R.drawable.ic_driving_next to "Next"
        }
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
     * someone is driving is the worst possible place to have got this wrong. Play and
     * pause are explicit rather than a toggle, so a press that races a track change
     * still does what its icon said.
     */
    class ControlReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = SendpinApp.instance
            val owner = app.playbackOwner
            when (intent?.getStringExtra(EXTRA_CONTROL)) {
                CONTROL_PREV -> owner.previous()
                CONTROL_PAUSE -> owner.pause()
                CONTROL_PLAY -> if (!nowSnapshot(app).playing) owner.playPause()
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
