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
        lead = "A lot of cars have no Android Auto. The phone sits in a cradle running the map, " +
            "and skipping a track means leaving it, finding this app, hitting a small target and " +
            "going back — while driving. This puts three very large controls over whatever is on " +
            "screen instead.",
    ) {
        ToggleRow(
            "Driving controls",
            "Appears when you connect to your car, and only while something is playing.",
            enabled, accent,
        ) { on -> scope.launch { settings.setDrivingEnabled(on) } }

        if (!enabled) return@SettingsCard

        CardDivider()

        // ── Which car ────────────────────────────────────────────────────────
        FieldLabel("Your car")
        if (!btGranted) {
            Note(
                "Needs permission to see which Bluetooth device you have connected. Nothing is " +
                    "sent anywhere — the app only checks whether the one you pick below is the " +
                    "one that just connected.",
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
            SegmentedToggle(
                options = bonded.map { it.second },
                selectedIndex = bonded.indexOfFirst { it.second == carName }.coerceAtLeast(-1),
                modifier = Modifier.fillMaxWidth(),
            ) { i ->
                val (address, name) = bonded[i]
                scope.launch { settings.setDrivingCar(address, name) }
            }
            if (carName.isBlank()) {
                Note("Nothing picked yet — the Quick Settings tile still works in the meantime.")
            }
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
            if (mechanism == AppSettings.DRIVING_OVERLAY) {
                "A full-width bar along the edge of the screen, with the largest targets. Needs " +
                    "permission to draw over other apps, and can be dragged to the other edge so " +
                    "it never sits over the map's own controls."
            } else {
                "A small floating window, the same one video apps use. Needs no permission at " +
                    "all, but the system decides its size — so the buttons are smaller than the " +
                    "full-width bar's, and it only appears if you open this app before starting " +
                    "the map."
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

        Note(
            "However it is turned on, the controls never appear with nothing playing — a " +
                "transport with nothing to transport is just clutter over a map.",
        )
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
