package com.engabd.sendpin.ui.screens.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.service.DrivingOverlayService
import com.engabd.sendpin.ui.design.SegmentedToggle
import com.engabd.sendpin.ui.theme.WarnAmber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Driving mode's setup, in one card.
 *
 * The one *safety* feature in this app rather than a polish or correctness one, and
 * the copy is written accordingly: it says what the feature is for, in the situation
 * it is for, rather than describing a mechanism.
 *
 * Three things to settle and they are asked in the order that matters — is it on,
 * which car, and only then which window mechanism. The mechanism is last on purpose:
 * the default costs nothing and most people should never reach the third question.
 */
@Composable
internal fun DrivingCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    val enabled by settings.drivingEnabled.collectAsState(initial = false)
    val mechanism by settings.drivingMechanism.collectAsState(initial = AppSettings.DRIVING_PIP)
    val carName by settings.drivingCarName.collectAsState(initial = "")
    // The address is what actually identifies the car — see the picker below.
    val carAddress by settings.drivingCarAddress.collectAsState(initial = "")

    // Re-read on every recomposition rather than remembered: both of these are
    // granted in a *different app* — a permission dialog and a Settings screen — so
    // a remembered value would still say "not granted" when the user came back
    // having granted it, which is the one moment the card is being read.
    val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    val overlayGranted = DrivingOverlayService.canDrawOverlay(context)

    var bonded by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(btGranted) { if (btGranted) bonded = bondedDevices(context) }

    val askBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) scope.launch { bonded = bondedDevices(context) } }

    SettingsCard(
        title = "Driving",
        lead = "Three very large controls, over whatever is on screen.",
        info = "A lot of cars have no Android Auto. The phone sits in a cradle running the map, " +
            "and skipping a track means leaving the map, finding this app, hitting a small " +
            "target and going back, while driving.\n\nThis puts the transport on top of the map " +
            "instead, at a size you can hit without looking.\n\nTip: set your car below as " +
            "well. Without it the controls have no way to know you have got in, and you will be " +
            "turning them on by hand every trip.",
    ) {
        ToggleRow(
            "Driving controls",
            "Appears when you connect to your car, and only while something is playing.",
            enabled, accent,
        ) { on -> scope.launch { settings.setDrivingEnabled(on) } }

        CardDivider()
        PauseForCallsRow(settings, accent, scope)
        CardDivider()
        SpeedFeaturesRow(settings, accent, scope)

        if (!enabled) return@SettingsCard

        CardDivider()

        // ── Which car ────────────────────────────────────────────────────────
        FieldLabel("Your car")
        if (!btGranted) {
            Note(
                "Needs permission to see which Bluetooth device is connected.",
                title = "Bluetooth permission",
                info = "Nothing is sent anywhere. The app compares the device that just connected " +
                    "against the one you pick below, on this phone, to work out whether you " +
                    "have got into the car.\n\nAndroid asks for the permission because the list " +
                    "of paired devices can identify you. The app never reads it for anything " +
                    "else.\n\nTip: if the list below is empty after you allow this, pair the " +
                    "phone with your car stereo first and then come back.",
            )
            OledButton("Allow Bluetooth", accent = accent, outline = true) {
                askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (bonded.isEmpty()) {
            Note("No paired devices yet. Pair the phone with your car stereo, then come back.")
        } else {
            Note(
                "Which of your paired devices is the car? Connecting to it turns the controls on; " +
                    "disconnecting turns them off.",
            )
            // A dropdown, not the segmented row this used to be. A phone that has been
            // in use for a while is paired with headphones, a watch, a speaker, a
            // previous car and a friend's stereo — and a segmented row gives each of
            // them an equal share of one screen width, so with more than about three
            // the names are unreadable and the ones past the edge cannot be tapped at
            // all. Every paired device is in this list, whatever the count.
            //
            // Matched on the *address* rather than the name: two devices can share a
            // name, and a car stereo's name is whatever its manufacturer put in
            // firmware. The name is still stored alongside, because that is what the
            // overlay says when it appears.
            DropdownPicker(
                options = bonded.map { it.second },
                subtitles = bonded.map { it.first },
                selectedIndex = bonded.indexOfFirst { it.first == carAddress }
                    .takeIf { it >= 0 }
                    ?: bonded.indexOfFirst { it.second == carName },
                accent = accent,
                placeholder = "Pick your car",
            ) { i ->
                val (address, name) = bonded[i]
                scope.launch { settings.setDrivingCar(address, name) }
            }
            if (carName.isBlank()) {
                Note("Nothing picked yet, the Quick Settings tile still works in the meantime.")
            }
            Note("${bonded.size} paired ${if (bonded.size == 1) "device" else "devices"}.")
        }

        CardDivider()

        // ── Which window ─────────────────────────────────────────────────────
        FieldLabel("How the controls appear")
        SegmentedToggle(
            options = listOf("Floating window", "Full-width bar"),
            selectedIndex = if (mechanism == AppSettings.DRIVING_OVERLAY) 1 else 0,
            modifier = Modifier.fillMaxWidth(),
        ) { i ->
            val next = if (i == 1) AppSettings.DRIVING_OVERLAY else AppSettings.DRIVING_PIP
            scope.launch { settings.setDrivingMechanism(next) }
        }
        Note(
            if (mechanism == AppSettings.DRIVING_OVERLAY) "A full-width bar. Largest targets."
            else "A small floating window. No permission needed.",
            title = "How the controls appear",
            info = if (mechanism == AppSettings.DRIVING_OVERLAY) {
                "A bar along the edge of the screen, as wide as the screen is, with the " +
                    "largest targets of the two. It needs permission to draw over other " +
                    "apps.\n\nIt can be dragged to the opposite edge, so it never has to sit " +
                    "over the map's own controls.\n\nTip: drag it to whichever edge your map " +
                    "keeps its buttons away from. The position is remembered."
            } else {
                "Picture-in-picture, the same floating window video apps use, so it needs " +
                    "no permission at all.\n\nThe system decides its size, which makes the " +
                    "buttons smaller than the full-width bar's, and it only appears if you open " +
                    "this app before starting the map.\n\nTip: if it never shows up, that last " +
                    "point is usually why. Open CAMusic, start playing, then switch to the map."
            },
        )

        if (mechanism == AppSettings.DRIVING_OVERLAY && !overlayGranted) {
            StatusLine(
                "Not allowed to draw over other apps yet, so the bar cannot appear.",
                health = Health.WARN,
                accent = WarnAmber,
            )
            OledButton("Allow drawing over apps", accent = WarnAmber, outline = true) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        Note("Either way, the controls never appear with nothing playing.")
    }
}

/**
 * Auto-pause on a ringing or answered call. Not tied to [DrivingCard]'s own toggle —
 * a call interrupting music is just as real parked as it is driving — but lives in
 * this card because it needs the same kind of runtime permission request the car
 * picker above does, and one settings card asking for phone permissions is enough.
 *
 * Never auto-resumes on hangup — see `PlaybackOwner.pause`'s doc for why — so this
 * only ever says what it does going in, not what happens coming out.
 */
@Composable
private fun PauseForCallsRow(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    val enabled by settings.pauseForCalls.collectAsState(initial = false)
    // Re-read on every recomposition, not remembered — see the identical BLUETOOTH_CONNECT
    // comment above: this is granted in a system dialog, not by this screen.
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

    val askPhoneState = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) {
            scope.launch { settings.setPauseForCalls(true) }
            com.engabd.sendpin.SendpinApp.instance.callPauseObserver.start()
        }
    }

    ToggleRow(
        "Pause for calls",
        "Pauses playback when the phone rings or you answer, and never resumes on its own.",
        enabled && granted, accent,
    ) { on ->
        if (on && !granted) {
            askPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
        } else {
            scope.launch { settings.setPauseForCalls(on) }
            if (on) com.engabd.sendpin.SendpinApp.instance.callPauseObserver.start()
            else com.engabd.sendpin.SendpinApp.instance.callPauseObserver.stop()
        }
    }
}

/**
 * Speed limit alert and speed-adaptive volume — both GPS-driven, so both share one
 * `ACCESS_FINE_LOCATION` request. Nothing here reads location for anything but a
 * speed reading; see `SpeedMonitor`'s own doc for the "why fine, not coarse".
 *
 * The limit source is a toggle: "Auto-detect" looks up the posted limit from the
 * speed-zone database bundled in the app (no internet, no tracking), or "Manual"
 * uses a typed-in limit — the original behaviour, preserved as the fallback for
 * everywhere the data does not cover, which is everywhere outside Victoria.
 */
@Composable
private fun SpeedFeaturesRow(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    val alertEnabled by settings.speedLimitAlertEnabled.collectAsState(initial = false)
    val adaptiveEnabled by settings.speedAdaptiveVolume.collectAsState(initial = false)
    val limitKmh by settings.drivingSpeedLimitKmh.collectAsState(initial = 0)
    val tolerancePct by settings.drivingSpeedTolerancePct.collectAsState(initial = 5)
    val autoDetect by settings.speedLimitAutoDetect.collectAsState(initial = false)
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    // The detected limit from GPS — exposed by SpeedMonitor for the UI to display
    val detectedLimit by com.engabd.sendpin.SendpinApp.instance.speedMonitor
        .detectedLimitKmh.collectAsState(initial = null)
    val limitDataStatus by com.engabd.sendpin.SendpinApp.instance.speedMonitor
        .limitDataStatus.collectAsState(initial = null)
    val activeLimit by com.engabd.sendpin.SendpinApp.instance.speedMonitor
        .activeLimitKmh.collectAsState(initial = null)
    // Whether fixes are actually arriving. The first question anyone asks when an
    // alert did not fire, and until now the screen could not answer it.
    val fixStatus by com.engabd.sendpin.SendpinApp.instance.speedMonitor
        .fixStatus.collectAsState(initial = null)

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> if (ok) com.engabd.sendpin.SendpinApp.instance.speedMonitor.start() }

    fun requestThenSet(on: Boolean, set: suspend () -> Unit) {
        if (on && !granted) { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        scope.launch { set() }
    }

    // The description line under "Speed limit alert" — shows what's in effect right now.
    val alertDescription = if (alertEnabled && granted) {
        val effective = activeLimit
        if (effective != null && effective > 0) {
            val trigger = com.engabd.sendpin.service.SpeedAlert.triggerSpeedKmh(effective, tolerancePct).toInt() + 1
            if (autoDetect) "Auto: $effective km/h here — beeps at $trigger km/h"
            else "Beeps at $trigger km/h and above"
        } else if (autoDetect) {
            "Detecting limit from location…"
        } else {
            "Set a limit below to activate"
        }
    } else {
        "A gentle tone if your GPS speed goes well over a limit you set"
    }

    ToggleRow(
        "Speed limit alert",
        alertDescription,
        alertEnabled && granted, accent,
    ) { on -> requestThenSet(on) { settings.setSpeedLimitAlertEnabled(on) } }

    if (alertEnabled && granted) {
        // ── Warning sound ─────────────────────────────────────────────────
        val soundId by settings.speedAlertSound.collectAsState(initial = AppSettings.SPEED_ALERT_TONE)
        val soundOptions = com.engabd.sendpin.service.SpeedAlertSound.OPTIONS
        val soundLabel = com.engabd.sendpin.service.SpeedAlertSound.labelFor(soundId)
        FieldLabel("Warning sound")
        DropdownPicker(
            options = soundOptions.map { it.label },
            selectedIndex = soundOptions.indexOfFirst { it.id == soundId }.takeIf { it >= 0 } ?: 0,
            accent = accent,
            placeholder = "Choose a sound",
        ) { i -> scope.launch { settings.setSpeedAlertSound(soundOptions[i].id) } }
        OledButton("Preview \"$soundLabel\"", accent = accent, outline = true) {
            com.engabd.sendpin.service.SpeedAlertSound.play(context, soundId)
        }
        Note(
            "Plays over the music, on the same output the music is on — the track " +
                "ducks for it and comes back after.",
        )

        // ── Is it actually watching? ───────────────────────────────────────
        Note(
            fixStatus ?: "Not watching — starts when the car connects and something is playing.",
            title = "GPS",
            info = "Driving mode watches your speed while the car is connected (or " +
                "the switch above is on by hand) and something is playing.\n\nWhile " +
                "it is watching, a notification says so: that is what keeps Android " +
                "delivering GPS fixes once you switch to your map. Android stops " +
                "sending location to an app that is not on screen and has no such " +
                "notification, which is why the alert used to go quiet the moment " +
                "you opened Google Maps.\n\nIf this line says it is waiting for a " +
                "fix for more than a minute or two, check that Location is on and " +
                "that the phone can see the sky.",
        )

        // ── Limit source toggle: Auto-detect vs Manual ──────────────────────
        FieldLabel("Limit source")
        com.engabd.sendpin.ui.design.SegmentedToggle(
            options = listOf("Auto-detect", "Manual"),
            selectedIndex = if (autoDetect) 0 else 1,
            modifier = Modifier.fillMaxWidth(),
        ) { i ->
            scope.launch { settings.setSpeedLimitAutoDetect(i == 0) }
        }

        if (autoDetect) {
            // What the data is doing, from the provider itself rather than a second
            // guess at it. This used to promise a ~50 MB monthly download that no
            // code path could ever perform.
            val detected = detectedLimit
            Note(
                when {
                    detected != null -> "Detected: $detected km/h on this road."
                    limitDataStatus != null -> limitDataStatus.orEmpty()
                    else -> "Reads the posted limit from the map data in the app."
                },
                title = "Auto-detect",
                info = "Victoria's speed zones ship inside the app, so this needs no " +
                    "internet and sends nothing anywhere. Your location is compared " +
                    "against that data on this phone and then forgotten.\n\nThe data " +
                    "covers Victoria only, and it is a snapshot: roadworks and recent " +
                    "changes will not be in it. Anywhere it has no answer — including " +
                    "every other state — the manual limit below is used instead, which " +
                    "is why it is worth setting.\n\nTip: the limit shown here is what " +
                    "the alert is actually using. If it stays blank while you drive, " +
                    "the data has nothing for that road and the fallback is doing the " +
                    "work.",
            )
        } else {
            // Manual limit input (original behaviour)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OledField(
                    value = if (limitKmh > 0) limitKmh.toString() else "",
                    onChange = { v -> scope.launch { settings.setDrivingSpeedLimitKmh(v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                    label = "Limit (km/h)", placeholder = "e.g. 100", accent = accent,
                    modifier = Modifier.weight(1f),
                )
                OledField(
                    value = tolerancePct.toString(),
                    onChange = { v -> scope.launch { settings.setDrivingSpeedTolerancePct(v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                    label = "Tolerance (%)", placeholder = "5", accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Tolerance is always shown — it applies to both auto and manual modes
        if (autoDetect) {
            OledField(
                value = tolerancePct.toString(),
                onChange = { v -> scope.launch { settings.setDrivingSpeedTolerancePct(v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                label = "Tolerance (%)", placeholder = "5", accent = accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Manual fallback limit is still relevant in auto-detect mode — it's used
        // when the database has no data for the current location.
        if (autoDetect) {
            FieldLabel("Manual fallback")
            OledField(
                value = if (limitKmh > 0) limitKmh.toString() else "",
                onChange = { v -> scope.launch { settings.setDrivingSpeedLimitKmh(v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                label = "Fallback limit (km/h)", placeholder = "e.g. 60", accent = accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Note("Used when the database has no data for your location (e.g. outside Victoria).")
        }
    }

    Spacer(Modifier.height(4.dp))
    ToggleRow(
        "Speed-adaptive volume",
        "Nudges volume up at speed to compensate for road noise, fading back down when you slow.",
        adaptiveEnabled && granted, accent,
    ) { on -> requestThenSet(on) { settings.setSpeedAdaptiveVolume(on) } }

    if ((alertEnabled || adaptiveEnabled) && !granted) {
        Note("Needs location permission. GPS speed only, and nothing is stored or sent anywhere.")
        OledButton("Allow location", accent = accent, outline = true) {
            askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

/**
 * The phone's bonded devices, as `address to name`.
 *
 * Bonded, not discovered: a car stereo is something the phone is already paired
 * with, and scanning for one would ask for location permission on top of everything
 * else for a list the user has already curated.
 */
@SuppressLint("MissingPermission")
private suspend fun bondedDevices(context: android.content.Context): List<Pair<String, String>> =
    runCatching {
        val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        manager?.adapter?.bondedDevices.orEmpty()
            .map { it.address to (it.name ?: it.address) }
            .sortedBy { it.second }
    }.getOrDefault(emptyList())
