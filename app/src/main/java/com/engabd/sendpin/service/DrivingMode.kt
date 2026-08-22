package com.engabd.sendpin.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Driving mode: a slim, always-reachable transport for a phone in a cradle.
 *
 * A lot of cars have no Android Auto. The phone sits in a cradle running Google
 * Maps, and changing a track means leaving the map, finding the app, hitting a small
 * target and going back — while driving. This is the one item in the app that is a
 * *safety* feature rather than a polish or correctness one, and it is weighted
 * accordingly: three controls, very large, over whatever is on screen.
 *
 * ## What turns it on
 *
 * **Not** "is Google Maps in front". That framing is the expensive one: reading the
 * foreground app needs either `PACKAGE_USAGE_STATS` (a special grant through a
 * Settings screen) or an `AccessibilityService` (the most policy-sensitive
 * permission on the platform), and both are large costs for a detail the feature
 * does not depend on. The requirement is *control music without leaving the map*,
 * not *know that Maps is running*.
 *
 * So the trigger is the car's Bluetooth: when the phone connects to the bonded
 * device the user nominated as their stereo, driving mode turns on; when it
 * disconnects, off. That is exactly the situation being solved for, it is a single
 * runtime permission, and it needs no foreground-app inspection at all. A Quick
 * Settings tile and a Settings switch cover a car with no pairing, an aux cable, or
 * someone who simply wants it on.
 *
 * ## And it is always gated on playback
 *
 * Whatever the trigger says, the bar never appears with nothing playing. A transport
 * with nothing to transport is clutter over a map, which is the one thing this must
 * not be.
 */
class DrivingMode(private val app: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val settings = AppSettings(app)

    /** The car is connected, per [carReceiver]. */
    private val carConnected = MutableStateFlow(false)

    /**
     * Switched on by hand — the tile, or the Settings switch.
     *
     * Deliberately *not* persisted. A manual override is about this drive, and one
     * that survived a reboot would leave the bar over someone's home screen a day
     * later with no memory of having asked for it. The car trigger is the durable
     * half; this is the escape hatch beside it.
     */
    private val manualOverride = MutableStateFlow(false)

    /**
     * The bar was dismissed (the X) for the drive currently in progress.
     *
     * Separate from [manualOverride] on purpose. The bar's own X used to call
     * `setManual(false)` — which does nothing while the car trigger is still true,
     * and the car being connected is *why the bar is up* in the overwhelming
     * majority of cases. The X read as broken because it was: turning off an
     * override that was not what was showing the bar leaves [active] exactly where
     * it was. This flag is what the X actually needs — "not this drive" — and it is
     * cleared the moment the drive it was raised for ends, or a fresh one begins, so
     * dismissing today's drive never silently swallows tomorrow's.
     */
    private val dismissed = MutableStateFlow(false)

    private val owner get() = com.engabd.sendpin.SendpinApp.instance.playbackOwner

    /**
     * Whether the driving bar should be on screen right now.
     *
     * Four things have to agree: the feature is enabled at all, something is asking
     * for it (the car, or the user), it hasn't been dismissed for this drive, and
     * there is something playing to control.
     */
    val active: StateFlow<Boolean> = combine(
        settings.drivingEnabled,
        carConnected,
        manualOverride,
        dismissed,
        owner.state,
    ) { enabled, car, manual, hidden, playback ->
        // `sessionOwner`, not `soundOwner`: a paused track is still something the
        // driver wants a play button for. Getting that wrong would make the bar
        // vanish the moment it became most useful.
        enabled && (car || manual) && !hidden && playback.sessionOwner != PlaybackOwner.Who.NONE
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    /** Turn it on or off by hand, for the tile and the Settings switch. */
    fun setManual(on: Boolean) {
        manualOverride.value = on
        // A deliberate re-enable un-dismisses too, or the tile would look just as
        // broken as the X did: tapping it back on while the car is still connected
        // would otherwise stay hidden behind a dismissal from earlier in the drive.
        if (on) dismissed.value = false
    }

    fun toggleManual() { setManual(!manualOverride.value) }

    /** The X on the bar itself — hide it for the rest of this drive, not turn a switch off. */
    fun dismiss() { dismissed.value = true }

    /**
     * ACL connect/disconnect for the nominated car.
     *
     * Registered at runtime rather than in the manifest: `ACTION_ACL_CONNECTED` is
     * not deliverable to a manifest-declared receiver on modern Android, and a
     * runtime registration also means the process is already up to react — which it
     * is, because [SendspinConnectionService] keeps it that way.
     *
     * The address is compared rather than the name. Names are user-editable and
     * duplicated across a household's devices; a bonded MAC is the identity the user
     * actually picked.
     */
    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wanted = wantedCarAddress
            if (wanted.isBlank()) return
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? =
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            if (device?.address != wanted) return
            when (intent?.action) {
                // A fresh drive starts clean — a dismissal from the last one must
                // not carry over and leave the bar silently missing on this trip.
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    carConnected.value = true
                    dismissed.value = false
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    carConnected.value = false
                    // Leaving the car ends the drive, and with it any manual
                    // override. Otherwise a tile tap on the way to the shops would
                    // leave the bar up for the rest of the day.
                    manualOverride.value = false
                    dismissed.value = false
                }
            }
        }
    }

    @Volatile private var wantedCarAddress: String = ""

    init {
        scope.launch {
            settings.drivingCarAddress.collect { address ->
                wantedCarAddress = address
                if (address.isBlank()) carConnected.value = false
            }
        }
        // Registered once, for the life of the process. The receiver itself is cheap
        // and returns immediately for any device that is not the nominated one; the
        // alternative — registering and unregistering as the setting changes — has
        // more states to get wrong than it saves work.
        runCatching {
            app.registerReceiver(
                carReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
            )
        }
        // Start and stop the window that actually shows the bar. Which mechanism is
        // in play is read here rather than inside the service, so switching it takes
        // effect on the next activation instead of needing the service restarted.
        //
        // Gated on the app itself *not* being foreground. The overlay is
        // `TYPE_APPLICATION_OVERLAY` — it draws over every window on screen,
        // including this app's own — and its bar is deliberately opaque (see
        // DrivingBar's doc). Opening the app while driving mode was up used to
        // leave that opaque bar sitting over whatever screen the user had just
        // opened, hiding it with no way to see or reach what was underneath, for
        // a control the in-app Now Playing screen already offers at full size the
        // moment the app is the thing on screen.
        val appForeground = AppLifecycleObserver.register(app).foreground
        scope.launch {
            combine(active, settings.drivingMechanism, appForeground) { on, mechanism, foreground ->
                Triple(on, mechanism, foreground)
            }
                .distinctUntilChanged()
                .collect { (on, mechanism, foreground) ->
                    if (on && mechanism == AppSettings.DRIVING_OVERLAY && !foreground) {
                        DrivingOverlayService.start(app)
                    } else {
                        DrivingOverlayService.stop(app)
                    }
                    // Picture-in-Picture cannot be entered from here: the platform
                    // requires the *activity* to be foreground at the moment it
                    // enters. MainActivity watches [active] and enters on its way to
                    // the background instead — see its onUserLeaveHint.
                }
        }
    }
}
