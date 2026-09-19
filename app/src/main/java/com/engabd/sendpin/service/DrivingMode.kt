package com.engabd.sendpin.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
import android.os.SystemClock

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

    /**
     * The nominated car is connected, per [carReceiver] and [refreshCarConnection].
     * Mutable only on the main thread — the receiver, the settings collector and
     * the profile-proxy callbacks all run there, which is also what keeps the
     * epoch guard in [applyConnectionQuery] honest without any locking.
     */
    private val _carConnected = MutableStateFlow(false)

    /**
     * Whether the phone is connected to the *designated* car Bluetooth device.
     *
     * This is the strict battery gate for GPS: [SpeedMonitor] holds no location
     * subscription unless this is true (and one of the speed features is on). It
     * used to answer "car connected **or** the tile tapped" — the old
     * `speedWatchActive` — and that "or" was the expensive part: a tile tap on the
     * way to the shops kept a fix-a-second GPS subscription running for the rest
     * of the day, and there was no way for the subscription to notice that no car
     * was involved. A manual override still raises the driving bar — that is about
     * having big controls on screen, not about being in a car — but it
     * deliberately no longer starts GPS. Only the designated device does.
     */
    val carConnected: StateFlow<Boolean> = _carConnected

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

    private companion object {
        const val TAG = "DrivingMode"

        /**
         * How long after a car ACL connect a second one counts as the same arrival.
         *
         * Head units bring their profiles up one at a time and the gap between the
         * first and last can be several seconds. Longer than that, shorter than any
         * plausible "got out, got back in".
         */
        const val RECONNECT_DEBOUNCE_MS = 30_000L

        /**
         * The profiles [refreshCarConnection] consults for "is the car connected
         * right now". A car stereo always speaks at least one of these: A2DP for
         * media, HFP/HSP for calls. Both are asked because a head unit can bring
         * either up without the other, and because a "no" from A2DP alone would
         * not mean much before the hands-free answer came back.
         */
        val QUERIED_PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
    }

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
     *
     * This receiver is the trigger *and* the teardown for GPS: connect raises
     * [_carConnected], which [SpeedMonitor] turns into a location subscription
     * (plus its foreground-service anchor); disconnect clears it, and the gate
     * tears both down immediately — `removeUpdates` and the service stop run in
     * the same main-loop pass as this `onReceive`, with no delay, no debounce and
     * no grace period. GPS is for the drive, and the drive ends when the car does.
     */
    private val carReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Checked outright rather than safe-called through: a null intent has
            // nothing to say either way, and the explicit return is what lets the
            // rest of this read the intent directly.
            if (intent == null) return
            val action = intent.action ?: return

            // The adapter's own state first: it carries no EXTRA_DEVICE, so it has
            // to be answered before the device check below would throw it away.
            //
            // This is the hole the startup query alone left. `refreshCarConnection`
            // gives up silently on an adapter that is off, and it only ever ran on
            // a nomination change — so a phone whose Bluetooth came on *after* the
            // process started (turned on for the drive, or restored by airplane
            // mode going off) had no ACL transition to catch either, because the
            // car connected before anything was listening for it. `carConnected`
            // then read false for the whole trip, and with the gate now strictly
            // that flag, the alert could not fire at any speed.
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "adapter on, re-asking for the car")
                        refreshCarConnection(wantedCarAddress)
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        connectionQueryEpoch++
                        _carConnected.value = false
                    }
                }
                return
            }
            val wanted = wantedCarAddress
            if (wanted.isBlank()) return
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? =
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            if (device?.address != wanted) return
            Log.i(TAG, "car ${action.substringAfterLast('.')}")
            when (action) {
                // A fresh drive starts clean — a dismissal from the last one must
                // not carry over and leave the bar silently missing on this trip.
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    // Invalidate any in-flight connection query: a broadcast is
                    // newer information than a proxy answer that started earlier.
                    connectionQueryEpoch++
                    _carConnected.value = true
                    // …but only a *fresh* drive. A multi-profile head unit announces
                    // ACL connect once per profile (hands-free, then A2DP, sometimes
                    // more), several seconds apart. Clearing the dismissal on each of
                    // those brought the bar straight back after the X was pressed,
                    // which is the other half of the X reading as broken.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCarConnectAtMs > RECONNECT_DEBOUNCE_MS) dismissed.value = false
                    lastCarConnectAtMs = now
                }
                // DISCONNECT_REQUESTED arrives moments before DISCONNECTED — the
                // remote side has said it is hanging up. Treating it as severed
                // starts the GPS teardown a beat earlier; if the disconnect is
                // aborted, a subsequent broadcast (or the next query) restores the
                // state, and nothing here is expensive to redo.
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectionQueryEpoch++
                    _carConnected.value = false
                    // Leaving the car ends the drive, and with it any manual
                    // override. Otherwise a tile tap on the way to the shops would
                    // leave the bar up for the rest of the day.
                    manualOverride.value = false
                    dismissed.value = false
                }
            }
        }
    }

    /**
     * The audio framework's view of a Bluetooth sink arriving or leaving — see the
     * registration in `init` for why this exists beside [carReceiver].
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            if (added?.any { it.isBluetoothSink() } == true) refreshCarConnection(wantedCarAddress)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            if (removed?.any { it.isBluetoothSink() } == true) refreshCarConnection(wantedCarAddress)
        }

        private fun AudioDeviceInfo.isBluetoothSink(): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 33 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    @Volatile private var wantedCarAddress: String = ""

    /** When the nominated car last announced an ACL connect. See the receiver above. */
    @Volatile private var lastCarConnectAtMs: Long = 0L

    /**
     * Bumped on every event that makes an in-flight [refreshCarConnection] answer
     * stale: the receiver handling an ACL event for the car, or a new query being
     * issued. A proxy query can take hundreds of milliseconds to answer (the bind
     * to the Bluetooth service is a real IPC round trip), and an answer that
     * belonged to the world before a broadcast must not overwrite it.
     */
    @Volatile private var connectionQueryEpoch: Long = 0L

    /**
     * The chosen mechanism, mirrored for callers that cannot suspend.
     *
     * [DrivingPip.maybeEnter] runs inside `onUserLeaveHint`, which has to decide in
     * the frame it is given — there is no room there to read DataStore.
     */
    @Volatile
    var mechanism: String = AppSettings.DRIVING_PIP
        private set

    init {
        scope.launch {
            settings.drivingCarAddress.collect { address ->
                wantedCarAddress = address
                // A new (or cleared) nomination ends the previous car's claim
                // immediately — the old device must stop being treated as "the car"
                // the moment it is no longer the one nominated, and the query below
                // re-establishes the flag if the *new* car is already connected.
                connectionQueryEpoch++
                _carConnected.value = false
                refreshCarConnection(address)
            }
        }
        // Registered once, for the life of the process. The receiver itself is cheap
        // and returns immediately for any device that is not the nominated one; the
        // alternative — registering and unregistering as the setting changes — has
        // more states to get wrong than it saves work.
        //
        // RECEIVER_EXPORTED, and this is not optional. These broadcasts are sent by
        // the Bluetooth stack (`com.android.bluetooth`), which since 13 runs as its
        // own uid (1002, `bluetooth`) — not `system`. A receiver registered
        // NOT_EXPORTED only accepts broadcasts from its own app or the system uid,
        // so the platform dropped every ACL connect, disconnect and adapter-state
        // change to this receiver on the floor ("Exported Denial" in the system
        // log) and the car never appeared to connect: the speed watch sat on
        // "waiting for <car> to connect" for the whole drive. Exporting closes no
        // hole — ACTION_ACL_CONNECTED and ACTION_STATE_CHANGED are protected
        // broadcasts, so only the platform can send them regardless of the flag.
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                // Handled identically to DISCONNECTED above — the head start is
                // the point — so it has to be in the filter or it never arrives.
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                // Not an ACL event, but the one that decides whether the query
                // above can answer at all — see the receiver's own comment.
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(carReceiver, filter)
            }
        }
        // Belt and braces for the broadcast above: the audio framework's own
        // device list. When a car takes the phone's media output it appears here
        // as a Bluetooth A2DP sink, and when it drops it disappears — delivered
        // to any app, no permission needed, through a callback the framework
        // owns rather than a broadcast that a flag or a permission can silence.
        // It does not say *which* device, so it does not set the flag itself; it
        // re-asks the adapter, which does.
        runCatching {
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        }
        // The app coming forward is the moment a grant made in the system Settings
        // (Bluetooth, most likely — the query below is a no-op without it) first
        // becomes usable, and the one moment the user is looking at the answer.
        scope.launch {
            AppLifecycleObserver.register(app).foreground.collect { fg -> if (fg) refreshCarConnection() }
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
                    // The window follows the *drive*; only its visibility follows the
                    // foreground. Stopping the service when the app came forward meant
                    // starting it again when the app went back — and that second start
                    // is a background `startService`, which minSdk 31 refuses unless
                    // the process happens to be exempt. That is why the bar sometimes
                    // simply did not come back.
                    this@DrivingMode.mechanism = mechanism
                    if (on && mechanism == AppSettings.DRIVING_OVERLAY) {
                        DrivingOverlayService.start(app)
                        DrivingOverlayService.setVisible(!foreground)
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

    /**
     * Re-ask the adapter whether the nominated car is connected, using the current
     * nomination — the public door onto [refreshCarConnection] below.
     *
     * For the callers that know something has changed which that query is not
     * itself told about: `BLUETOOTH_CONNECT` being granted (until it is, the query
     * returns without asking anything, and nothing re-ran it), and
     * [SpeedMonitor.start] being called — the latter because the monitor's whole
     * gate is this flag, and a monitor starting into a stale `false` would watch
     * nothing for the rest of the drive.
     *
     * Cheap and idempotent: a no-op without a nomination, the permission or an
     * enabled adapter, and otherwise two async profile binds whose answers are
     * epoch-guarded against the broadcasts that outrank them.
     */
    fun refreshCarConnection() {
        refreshCarConnection(wantedCarAddress)
    }

    /**
     * Whether this process may hear about the car at all.
     *
     * Every way [carConnected] can become true needs `BLUETOOTH_CONNECT`: the ACL
     * broadcasts are sent with it as a required permission, and the profile query
     * refuses to run without it. So a nominated car with the grant missing is not
     * "waiting for the car" — it is a link that cannot close, and the screen that
     * reports the watch has to say so rather than describe a wait that never ends.
     */
    fun canSeeCar(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Asks the adapter whether the nominated car is connected *right now*.
     *
     * [carReceiver] only ever sees ACL *transitions*, and nothing else replays the
     * past: get into the car, let it pair, then open the app — the ordinary order —
     * or have the process killed and restarted by the media service mid-drive, and
     * no broadcast is coming. Without this query `carConnected` reads false for the
     * whole drive, and the GPS gate — now strictly this flag — would keep the speed
     * features dark for a trip that is already under way. It runs at process start
     * and whenever the nomination changes, and — through [refreshCarConnection] —
     * whenever the adapter comes on, the Bluetooth permission is granted, or
     * [SpeedMonitor.start] is called. Those three are the cases where the answer can
     * change without an ACL broadcast that this process was alive and permitted to
     * hear, and each of them used to leave the flag stuck false for a whole drive.
     *
     * The answer arrives through profile proxies (see [QUERIED_PROFILES] for why
     * there are two), which means a real IPC round trip — so nothing here blocks:
     * the bind is async and the answer lands in [applyConnectionQuery] on the main
     * thread. After that the receiver owns the state again; nothing is polled.
     *
     * Every exit is silent: a blank nomination, a missing `BLUETOOTH_CONNECT`
     * grant, no adapter or an off one all leave the flag exactly where it was —
     * false — which is the correct "no designated car" answer. The next ACL
     * broadcast repairs it the moment one arrives, and for the cases where none is
     * coming, the callers listed above ask again.
     */
    @SuppressLint("MissingPermission")
    private fun refreshCarConnection(address: String) {
        if (address.isBlank()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val adapter = runCatching {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: return
        if (!adapter.isEnabled) return
        val queryEpoch = ++connectionQueryEpoch
        val answers = mutableMapOf<Int, Boolean>()
        for (profile in QUERIED_PROFILES) {
            runCatching {
                adapter.getProfileProxy(
                    app,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                            val connected = runCatching {
                                proxy.connectedDevices.any { it.address == address }
                            }.getOrDefault(false)
                            runCatching { adapter.closeProfileProxy(profileId, proxy) }
                            applyConnectionQuery(queryEpoch, answers, profileId, connected, address)
                        }

                        override fun onServiceDisconnected(profileId: Int) {
                            // The proxy binding died, not the car link; ACL
                            // broadcasts remain the authority on that.
                        }
                    },
                    profile,
                )
            }
        }
    }

    /**
     * Fold one profile's answer into [_carConnected] — unless the world moved on
     * while it was in flight.
     *
     * The guards are the epoch and the still-current nomination: a newer query, an
     * ACL broadcast for the car, or a changed setting all invalidate the answer
     * (each bumps or re-issues the epoch). A "yes" applies alone — any queried
     * profile carrying the nominated device is a connected car. A "no" only
     * demotes once *every* queried profile has answered, so a slow hands-free bind
     * cannot briefly tear down a link A2DP has just reported.
     */
    private fun applyConnectionQuery(
        queryEpoch: Long,
        answers: MutableMap<Int, Boolean>,
        profileId: Int,
        connected: Boolean,
        address: String,
    ) {
        if (queryEpoch != connectionQueryEpoch) return
        if (wantedCarAddress != address) return
        if (connected) {
            if (!_carConnected.value) Log.i(TAG, "car found connected on profile $profileId")
            _carConnected.value = true
        } else {
            answers[profileId] = false
            if (answers.size == QUERIED_PROFILES.size) _carConnected.value = false
        }
    }
}
