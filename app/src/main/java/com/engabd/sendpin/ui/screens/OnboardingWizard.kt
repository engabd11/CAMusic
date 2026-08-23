package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import com.engabd.sendpin.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.rememberIsIgnoringBatteryOptimizations
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.local.LocalMediaSource
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.theme.accentTextFieldColors
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.engabd.sendpin.ui.design.Motion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * First-launch onboarding wizard.
 * 1. Pick a music source: MA / Navidrome / Jellyfin / local files.
 * 2. Configure the chosen source (address + credentials, or a folder picker for local).
 * 3. Optional Light Sync setup: Home Assistant and/or direct Hue bridge.
 * 4. Optional speakers setup for Music Assistant (this phone as a Sendspin player).
 *
 * Any step can be skipped. Completing or skipping writes [AppSettings.ONBOARDING_COMPLETED]
 * and hands back to [App].
 */
@Composable
fun OnboardingWizard(
    playerVm: PlayerViewModel = viewModel(),
    libraryVm: LibraryViewModel = viewModel(),
    onDone: () -> Unit = {},
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var chosenKind by remember { mutableStateOf<ServerKind?>(null) }
    var serverConfig by remember { mutableStateOf<ServerConfig?>(null) }

    // Registering this phone as a Music Assistant speaker only means anything on the
    // Music Assistant backend. It used to be shown to everyone, so someone who picked
    // Navidrome or "this device" was handed a full MA registration form with no
    // explanation that it did not apply to them.
    val needsSpeakers = chosenKind == ServerKind.MUSIC_ASSISTANT
    val total = if (needsSpeakers) 5 else 4

    fun finish(skipped: Boolean = false) {
        scope.launch {
            settings.setOnboardingCompleted(true)
            // Recorded, so a skipped setup can be told from a completed one. Both used
            // to write the same thing, which meant the app could never tell whether a
            // missing server was a deliberate choice or an interrupted wizard.
            settings.setOnboardingSkipped(skipped)
            onDone()
        }
    }

    /** The step after [from], skipping the ones this setup doesn't need. */
    fun next(from: Int): Int = when {
        from == 2 && !needsSpeakers -> 4   // straight from Light Sync to Permissions
        else -> from + 1
    }

    fun previous(from: Int): Int = when {
        from == 4 && !needsSpeakers -> 2
        else -> from - 1
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 460.dp, 40.dp, (-80).dp, 0.34f)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp).padding(top = 48.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WizardHeader(
                step = if (needsSpeakers || step < 4) step else 3,
                total = total,
                accent = accent,
                onBack = { if (step > 0) step = previous(step) },
            )
            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = step,
                // On the app's motion tokens rather than bare defaults: position moves
                // on a spatial spring, alpha on an effects spring. The wizard was the
                // one screen still using `slideInHorizontally()` / `fadeIn()` raw, so it
                // felt subtly unlike everything the user sees next.
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(Motion.spatial()) { it } + fadeIn(Motion.effects()) togetherWith
                            slideOutHorizontally(Motion.spatial()) { -it / 2 } + fadeOut(Motion.effects())
                    } else {
                        slideInHorizontally(Motion.spatial()) { -it } + fadeIn(Motion.effects()) togetherWith
                            slideOutHorizontally(Motion.spatial()) { it / 2 } + fadeOut(Motion.effects())
                    }
                },
                label = "wizard",
            ) { s ->
                when (s) {
                    0 -> SourceStep(
                        accent = accent,
                        onPick = { kind ->
                            chosenKind = kind
                            serverConfig = ServerConfig(kind = kind)
                            step = 1
                        },
                    )
                    1 -> ConfigStep(
                        kind = chosenKind ?: ServerKind.NAVIDROME,
                        config = serverConfig ?: ServerConfig(kind = chosenKind ?: ServerKind.NAVIDROME),
                        accent = accent,
                        libraryVm = libraryVm,
                        settings = settings,
                        onNext = { next ->
                            serverConfig = next
                            step = 2
                        },
                        onBack = { step = 0 },
                    )
                    2 -> LightSyncStep(
                        accent = accent,
                        settings = settings,
                        onNext = { step = next(2) },
                        onBack = { step = 1 },
                    )
                    3 -> if (needsSpeakers) {
                        SpeakersStep(
                            playerVm = playerVm,
                            accent = accent,
                            onNext = { step = 4 },
                            onBack = { step = previous(3) },
                        )
                    } else {
                        // Non-MA backends (Navidrome, Jellyfin, Local) don't need speaker registration.
                        // Skip directly to permissions rather than showing an irrelevant MA form.
                        LaunchedEffect(Unit) { step = 4 }
                        Box {} // Empty placeholder while transitioning
                    }
                    4 -> PermissionsStep(
                        accent = accent,
                        onDone = { finish() },
                        onBack = { step = previous(4) },
                    )
                    else -> finish()
                }
            }

            Spacer(Modifier.height(20.dp))
            TextButton(
                "Skip setup",
                accent = accent,
            ) { finish(skipped = true) }
        }
    }
}

@Composable
private fun WizardHeader(
    step: Int,
    total: Int,
    accent: Color,
    onBack: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(78.dp).shadow(30.dp, RoundedCornerShape(24.dp), ambientColor = accent, spotColor = accent)
                .clip(RoundedCornerShape(24.dp)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_mushroom), contentDescription = null, tint = Ink, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("CAMusic", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Set up where your music comes from, then lights and speakers if you want them.",
            color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            repeat(total) { i ->
                Box(
                    Modifier.size(if (i == step) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == step) accent else TextFaint),
                )
            }
        }
    }
}

@Composable
private fun SourceStep(
    accent: Color,
    onPick: (ServerKind) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Choose your music source", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("You can add more later in Settings → Libraries.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))

        SourceRow(
            icon = Icons.Default.Cloud,
            label = "Music Assistant",
            blurb = "Play to any speaker on the network with grouping and a server-side queue.",
            accent = accent,
        ) { onPick(ServerKind.MUSIC_ASSISTANT) }

        SourceRow(
            icon = Icons.Default.Storage,
            label = "Navidrome",
            blurb = "A fast self-hosted Subsonic server, with synced lyrics and ReplayGain.",
            accent = accent,
        ) { onPick(ServerKind.NAVIDROME) }

        SourceRow(
            icon = Icons.Default.Dns,
            label = "Jellyfin",
            blurb = "Open-source media server with a music library and original-file streaming.",
            accent = accent,
        ) { onPick(ServerKind.JELLYFIN) }

        SourceRow(
            icon = Icons.Default.Smartphone,
            label = "This device",
            blurb = "Music already on the phone or SD card, indexed by MediaStore.",
            accent = accent,
        ) { onPick(ServerKind.LOCAL) }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    label: String,
    blurb: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.a(0.14f)).border(1.dp, accent.a(0.35f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(blurb, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConfigStep(
    kind: ServerKind,
    config: ServerConfig,
    accent: Color,
    libraryVm: LibraryViewModel,
    settings: AppSettings,
    onNext: (ServerConfig) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var label by remember(config.id) { mutableStateOf(config.label) }
    var url by remember(config.id) { mutableStateOf(config.url) }
    var user by remember(config.id) { mutableStateOf(config.username) }
    var pass by remember(config.id) { mutableStateOf(config.password) }
    var token by remember(config.id) { mutableStateOf(config.token) }
    var folderUris by remember(config.id) { mutableStateOf(parseFolderUris(config.option(LocalMediaSource.OPT_FOLDER_URIS))) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folderUris = listOf(treeUri)
    }

    val connecting by libraryVm.connecting.collectAsStateWithLifecycle()
    val ready by libraryVm.ready.collectAsStateWithLifecycle()
    val connError by libraryVm.connError.collectAsStateWithLifecycle()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Connect to ${kind.label}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(kind.blurb, color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(18.dp))

        if (kind != ServerKind.LOCAL) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server address") },
                placeholder = { Text(kind.urlHint) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(10.dp))
        }

        when (kind.auth) {
            com.engabd.sendpin.library.AuthStyle.USER_PASSWORD, com.engabd.sendpin.library.AuthStyle.OPTIONAL_USER_PASSWORD -> {
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = accentTextFieldColors(accent),
                )
            }
            com.engabd.sendpin.library.AuthStyle.TOKEN -> {
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Access token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = accentTextFieldColors(accent),
                )
            }
            else -> Unit
        }

        if (kind == ServerKind.LOCAL) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass)
                    .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)).clickable { launcher.launch(null) }
                    .padding(16.dp),
            ) {
                Column {
                    Text("Music folder", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (folderUris.isEmpty()) "Tap to choose a folder."
                        else folderUris.joinToString { it.lastPathSegment ?: it.toString() },
                        color = if (folderUris.isEmpty()) TextMuted else TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (connError != null) {
            Spacer(Modifier.height(12.dp))
            Text(connError!!, color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))
        OledButton(
            when {
                connecting -> "Connecting…"
                ready -> "Connected"
                else -> "Connect"
            },
            enabled = !connecting && (kind == ServerKind.LOCAL || url.isNotBlank()),
            accent = accent,
        ) {
            val next = config.copy(
                label = label.ifBlank { kind.label },
                url = url.trim(),
                username = user,
                password = pass,
                token = token,
                options = if (kind == ServerKind.LOCAL) mapOf(LocalMediaSource.OPT_FOLDER_URIS to encodeFolderUris(folderUris)) else emptyMap(),
            )
            scope.launch {
                settings.saveServers(listOf(next))
                settings.setActiveServer(next.id)
                libraryVm.switchTo(next)
                libraryVm.connect()
            }
        }

        if (ready) {
            Spacer(Modifier.height(16.dp))
            val finalConfig = rememberUpdatedState(config.copy(
                label = label.ifBlank { kind.label },
                url = url.trim(),
                username = user,
                password = pass,
                token = token,
                options = if (kind == ServerKind.LOCAL) mapOf(LocalMediaSource.OPT_FOLDER_URIS to encodeFolderUris(folderUris)) else emptyMap(),
            ))
            OledButton("Continue", accent = accent, outline = true) { onNext(finalConfig.value) }
        }
    }
}

@Composable
private fun LightSyncStep(
    accent: Color,
    settings: AppSettings,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var setupHa by remember { mutableStateOf(false) }
    var setupDirect by remember { mutableStateOf(false) }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Light Sync (optional)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("Sync room lights to your music. Skip this and add it later from Settings → Light Sync.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(20.dp))

        ToggleRow("Home Assistant", "Use the syncoV2 Hue integration.", setupHa, accent) { setupHa = it }
        if (setupHa) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = haUrl, onValueChange = { haUrl = it }, label = { Text("HA URL") }, placeholder = { Text("http://192.168.0.10:8123") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = haToken, onValueChange = { haToken = it },
                label = { Text("Long-lived token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                colors = accentTextFieldColors(accent),
            )
        }

        Spacer(Modifier.height(12.dp))
        ToggleRow("Direct Hue Bridge", "Talk to the bridge itself, no Home Assistant needed.", setupDirect, accent) { setupDirect = it }
        if (setupDirect) {
            Spacer(Modifier.height(10.dp))
            // Pairing needs the physical button on the bridge pressed within 30 seconds,
            // and then an entertainment area chosen — neither of which belongs in a
            // first-run flow the user may be running on the bus. Saying what is still
            // missing, rather than "pairing happens later", is what stops this reading
            // as finished when it is not: nothing lights up until both are done.
            Text(
                "Two things still to do: press the button on the bridge to pair, then pick an " +
                    "entertainment area. Both are in Settings → Light Sync → Direct, and the " +
                    "lights stay off until they're done.",
                color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        OledButton("Continue", accent = accent) {
            scope.launch {
                if (setupHa && haUrl.isNotBlank() && haToken.isNotBlank()) {
                    settings.setHomeAssistant(haUrl.trim(), haToken.trim())
                }
                if (setupDirect) {
                    settings.setLightSyncMode("direct")
                } else if (setupHa) {
                    settings.setLightSyncMode("ha")
                }
                onNext()
            }
        }
    }
}

/**
 * The two platform permissions the app cannot work around, asked with a reason.
 *
 * Neither was ever requested in context. `POST_NOTIFICATIONS` was fired cold in
 * `MainActivity.onCreate` — before the wizard had even drawn — with a callback that
 * ignored the answer, so a denial was never noticed, never explained and never asked
 * again, while all three foreground services needed it. Battery optimisation was never
 * mentioned at all, which is why `SendspinConnectionService` exists (to survive Doze)
 * and yet gets killed on Xiaomi, Samsung and Oppo with nothing telling the user why.
 *
 * Both are optional and skippable. The battery one deliberately opens the *list* screen
 * rather than the direct per-app request dialog: the direct intent needs a permission
 * whose use is restricted on Play, and this needs no declaration at all.
 */
@Composable
private fun PermissionsStep(
    accent: Color,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var notificationsGranted by remember {
        mutableStateOf(
            !needsNotifications || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // The real, live-checked exemption — not just "the settings screen was opened".
    // Opening the list and never actually flipping the app to Unrestricted used to
    // read as done here, which is no better than not asking at all.
    val batteryGranted = rememberIsIgnoringBatteryOptimizations()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Two permissions", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Both are optional, and both are the difference between playback that keeps going " +
                "and playback that stops when you put the phone down.",
            color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(22.dp))

        PermissionRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            body = "The playback controls on your lock screen are a notification. Without this " +
                "there is nowhere to show them, and Android may stop playback in the background.",
            done = notificationsGranted,
            accent = accent,
            actionLabel = "Allow",
            enabled = needsNotifications && !notificationsGranted,
        ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

        Spacer(Modifier.height(14.dp))

        PermissionRow(
            icon = Icons.Default.BatteryAlert,
            title = "Unrestricted battery",
            body = "Android's battery saver closes the connection to Music Assistant while the " +
                "screen is off, on some phones within minutes. Find CAMusic in the list and " +
                "set it to Unrestricted.",
            done = batteryGranted,
            accent = accent,
            actionLabel = if (batteryGranted) "Done" else "Open settings",
            enabled = !batteryGranted,
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OledButton("Back", accent = accent, outline = true, modifier = Modifier.weight(1f)) { onBack() }
            OledButton("Finish", accent = accent, modifier = Modifier.weight(1f)) { onDone() }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean,
    accent: Color,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Default.CheckCircle else icon,
            contentDescription = null,
            tint = if (done) accent else TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(body, color = TextMuted, fontSize = 12.sp)
            if (enabled) {
                Spacer(Modifier.height(6.dp))
                OledButton(actionLabel, accent = accent, outline = true, onClick = onClick)
            }
        }
    }
}

@Composable
private fun SpeakersStep(
    playerVm: PlayerViewModel,
    accent: Color,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val discovered by playerVm.discoveredServers.collectAsStateWithLifecycle()
    val discovering by playerVm.isDiscovering.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(playerVm.deviceName) }

    LaunchedEffect(Unit) { playerVm.startDiscovery() }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Speakers (optional)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text("Register this phone as a Music Assistant player, or skip and do it later.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Player name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("MA username (if any)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("MA password (if any)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors = accentTextFieldColors(accent),
        )

        Spacer(Modifier.height(16.dp))
        if (discovering && discovered.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                Text("Scanning…", color = TextMuted, fontSize = 13.sp)
            }
        }
        discovered.forEach { s ->
            ServerRow(s.name, "${s.host}:${s.port}", accent) {
                playerVm.connectToServer(s.webSocketUrl, user, pass, name)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = manual, onValueChange = { manual = it }, label = { Text("Or enter WebSocket URL") }, placeholder = { Text("ws://192.168.0.10:8095/ws") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OledButton("Skip", accent = accent, outline = true, modifier = Modifier.weight(1f)) { onNext() }
            OledButton(
                "Register",
                accent = accent,
                enabled = manual.isNotBlank() || discovered.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                val url = if (manual.isNotBlank()) manual else discovered.firstOrNull()?.webSocketUrl ?: ""
                playerVm.connectToServer(url, user, pass, name)
                onNext()
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    blurb: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass)
            .border(1.dp, if (checked) accent else HairlineSoft, RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(blurb, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.a(0.4f)))
    }
}

@Composable
private fun ServerRow(label: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.a(0.14f)), contentAlignment = Alignment.Center) {
            Text("MA", color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        Text("Connect", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun TextButton(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text,
        color = accent,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(100)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun parseFolderUris(json: String?): List<Uri> {
    if (json.isNullOrBlank()) return emptyList()
    val out = mutableListOf<Uri>()
    val arr = JSONArray(json)
    for (i in 0 until arr.length()) out += Uri.parse(arr.getString(i))
    return out
}

private fun encodeFolderUris(uris: List<Uri>): String = JSONArray(uris.map { it.toString() }).toString()
