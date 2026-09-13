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
import com.engabd.sendpin.library.ProviderAppCredentials
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
import com.engabd.sendpin.ui.screens.settings.TidalSignInRow
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.theme.accentTextFieldColors
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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

    // The system Back key, which until now walked straight out of the app from
    // whichever step the user had reached — throwing away a half-filled server form
    // for what everywhere else in the app means "up one level". Left disabled on the
    // first step so Back there still leaves, which is what it should do from a screen
    // with nothing behind it.
    BackHandler(enabled = step > 0) { step = previous(step) }

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
                            server = serverConfig,
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
                    // Unreachable as the steps stand, and written as an effect rather
                    // than a bare call so it stays harmless if that ever changes:
                    // `finish()` in a composition body would fire on every
                    // recomposition instead of once.
                    else -> LaunchedEffect(Unit) { finish() }
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

        // The streaming accounts, set apart rather than mixed in. They are an
        // account and a sign-in rather than a server, and they are experimental —
        // no streaming service publishes an API for a player like this one, so
        // each leans on undocumented endpoints. Saying that here is cheaper than
        // a first-run user discovering it when a provider changes something.
        Spacer(Modifier.height(16.dp))
        Text(
            "Streaming accounts",
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Experimental: played by this phone, with light sync. Unofficial clients, so a " +
                "provider's next change can break one.",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))

        SourceRow(
            icon = Icons.Default.Radio,
            label = ServerKind.SPOTIFY.label,
            blurb = ServerKind.SPOTIFY.blurb,
            accent = accent,
        ) { onPick(ServerKind.SPOTIFY) }

        SourceRow(
            icon = Icons.Default.Equalizer,
            label = ServerKind.QOBUZ.label,
            blurb = ServerKind.QOBUZ.blurb,
            accent = accent,
        ) { onPick(ServerKind.QOBUZ) }

        SourceRow(
            icon = Icons.Default.CloudSync,
            label = ServerKind.TIDAL.label,
            blurb = ServerKind.TIDAL.blurb,
            accent = accent,
        ) { onPick(ServerKind.TIDAL) }
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
    // A streaming account's setup does not all fit in the form fields above: the
    // app's own Qobuz/Tidal registration and Tidal's signed-in token set live in
    // the config's options, and the sign-in row writes them itself. Holding the
    // whole config here keeps them together and hands them to formConfig(), which
    // rebuilds from the fields and would otherwise drop them on Connect.
    var cloudConfig by remember(config.id) { mutableStateOf(config) }

    // Whether Connect has been pressed for *this* source. The view model's connect
    // state is app-wide and outlives a step, so without this a failure against
    // Navidrome was still on screen after going back and picking Jellyfin — an error
    // about a server the form was no longer describing.
    var attempted by remember(config.id) { mutableStateOf(false) }
    // Set once the server has been written to the list, which happens on the first
    // Connect. From that point there is always a way forward, whatever the network
    // did: the library exists and is editable in Settings.
    var saved by remember(config.id) { mutableStateOf(false) }

    // Device-local libraries read MediaStore, which answers an ungranted query with an
    // empty list — so without this "no permission" and "no music on this phone" are
    // the same screen, and Connect fails with LocalMediaSource's own error. Settings →
    // Libraries and the TV setup screen both already ask; onboarding was the one entry
    // point that did not.
    var audioGranted by remember {
        mutableStateOf(kind.needsAddress || LocalMediaSource.hasAudioPermission(context))
    }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }

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

    /** What the form is currently describing, for both the save and the hand-off. */
    fun formConfig(): ServerConfig = config.copy(
        label = label.ifBlank { kind.label },
        url = url.trim(),
        username = user,
        password = pass,
        token = token,
        options = when {
            kind == ServerKind.LOCAL -> mapOf(LocalMediaSource.OPT_FOLDER_URIS to encodeFolderUris(folderUris))
            kind.cloudAccount -> cloudConfig.options
            else -> emptyMap()
        },
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Connect to ${kind.label}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(kind.blurb, color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(18.dp))

        if (kind.needsAddress) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server address") },
                placeholder = { Text(kind.urlHint) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(10.dp))
        }

        // Qobuz and Tidal identify the calling application as well as the account,
        // with a pair this build was not given — so ask, rather than fail at
        // Connect with what reads like a refused login. A build carrying a pair
        // (gradle properties → BuildConfig) never renders this.
        if (ProviderAppCredentials.asksUser(kind)) {
            val qobuz = kind == ServerKind.QOBUZ
            val idKey = if (qobuz) ServerConfig.OPT_QOBUZ_APP_ID else ServerConfig.OPT_TIDAL_CLIENT_ID
            val secretKey = if (qobuz) ServerConfig.OPT_QOBUZ_APP_SECRET else ServerConfig.OPT_TIDAL_CLIENT_SECRET
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = cloudConfig.option(idKey).orEmpty(),
                onValueChange = { cloudConfig = cloudConfig.withOption(idKey, it) },
                label = { Text(if (qobuz) "App ID" else "Client ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = cloudConfig.option(secretKey).orEmpty(),
                onValueChange = { cloudConfig = cloudConfig.withOption(secretKey, it) },
                label = { Text(if (qobuz) "App secret" else "Client secret") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${kind.label} identifies the app making the call with a registration of its " +
                    "own, separate from your account. This build carries none, so paste yours " +
                    "— it is kept with this library.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
            )
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
            com.engabd.sendpin.library.AuthStyle.LINKED_ACCOUNT -> if (kind == ServerKind.TIDAL) {
                // The same row Settings → Libraries uses: mint a code, open Tidal,
                // poll, and write the token set into the config. It saves into
                // `cloudConfig`, which Connect then carries forward.
                TidalSignInRow(
                    config = cloudConfig,
                    accent = accent,
                    scope = scope,
                    onSave = { cloudConfig = it },
                )
            }
            else -> Unit
        }

        if (kind == ServerKind.LOCAL) {
            if (!audioGranted) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass)
                        .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)).padding(16.dp),
                ) {
                    Text("Access to your music", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android has to allow this app to read audio files before anything can be " +
                            "listed. Nothing is uploaded — the files are read straight off the phone.",
                        color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OledButton("Allow access", accent = accent, outline = true) {
                        audioPermission.launch(LocalMediaSource.AUDIO_PERMISSION)
                    }
                }
            }
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
                        if (folderUris.isEmpty()) "Optional. Tap to narrow it to one folder, or leave it to use every audio file on the phone."
                        else folderUris.joinToString { it.lastPathSegment ?: it.toString() },
                        color = if (folderUris.isEmpty()) TextMuted else TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // Whether *this* form got the library connected. `ready` on its own is
        // app-wide and outlives the step, so after a successful connect, a Back and a
        // different source it would have said "Connected" about a form nobody had
        // filled in yet — and offered to carry it forward.
        // ...and only when nothing went wrong: a Music Assistant socket opens before
        // the login is checked, so `ready` alone said "Connected" over a server that
        // had just refused every command that needs one.
        val connectedHere = saved && ready && !connecting && connError == null

        // Only after an attempt of this form's own, and only while one isn't running —
        // the previous failure is not news about the address being tried now.
        if (attempted && !connecting && !connectedHere && connError != null) {
            Spacer(Modifier.height(12.dp))
            Text(connError!!, color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        // What a streaming account needs before Connect means anything: Tidal a
        // token from the sign-in above, the other two a filled login. Without this
        // the button is live on an empty form, and the first thing a first-run
        // user gets from their new library is a refusal.
        val cloudReady = when {
            !kind.cloudAccount -> true
            kind == ServerKind.TIDAL -> cloudConfig.option(ServerConfig.OPT_TIDAL_ACCESS_TOKEN) != null
            else -> user.isNotBlank() && pass.isNotBlank()
        }

        Spacer(Modifier.height(20.dp))
        OledButton(
            when {
                connecting -> "Connecting…"
                connectedHere -> "Connected"
                attempted -> "Try again"
                else -> "Connect"
            },
            enabled = !connecting && cloudReady &&
                (!kind.needsAddress || url.isNotBlank()) &&
                (kind != ServerKind.LOCAL || audioGranted),
            accent = accent,
        ) {
            val next = formConfig()
            attempted = true
            scope.launch {
                // Read-modify-write rather than replacing the list. The wizard can be
                // reached again by an install that saved a library and never finished
                // setup, and a whole-list write there deletes what is already there.
                // Same replace-by-id-or-append the TV setup screen and Settings use.
                val existing = settings.servers.first()
                settings.saveServers(
                    if (existing.any { it.id == next.id }) existing.map { if (it.id == next.id) next else it }
                    else existing + next,
                )
                settings.setActiveServer(next.id)
                saved = true
                libraryVm.switchTo(next)
                libraryVm.connect()
            }
        }

        // The way forward, and there is always one. Connecting can fail for reasons
        // this screen cannot fix — a server that is off, a VPN, a typo in a port — and
        // gating Continue on `ready` made the whole wizard a dead end when it did: the
        // only control left was "Skip setup", which abandons the rest of setup even
        // though the library had already been saved and was perfectly correct.
        if (saved && !connecting) {
            Spacer(Modifier.height(16.dp))
            if (!connectedHere) {
                Text(
                    "Saved as \"${label.ifBlank { kind.label }}\". You can carry on with setup and " +
                        "fix the connection later in Settings → Libraries — nothing else here depends on it.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            val finalConfig = rememberUpdatedState(formConfig())
            OledButton(
                if (connectedHere) "Continue" else "Continue anyway",
                accent = accent,
                outline = true,
            ) { onNext(finalConfig.value) }
        }

        Spacer(Modifier.height(10.dp))
        OledButton("Back", accent = accent, outline = true, onClick = onBack)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OledButton("Back", accent = accent, outline = true, modifier = Modifier.weight(1f), onClick = onBack)
            OledButton("Continue", accent = accent, modifier = Modifier.weight(1f)) {
                scope.launch {
                    val haReady = setupHa && haUrl.isNotBlank() && haToken.isNotBlank()
                    if (haReady) settings.setHomeAssistant(haUrl.trim(), haToken.trim())
                    // Only a mode that has something behind it. Flicking the Home
                    // Assistant switch and leaving the fields empty used to put Light
                    // Sync into "ha" mode with no address and no token — a feature
                    // reporting itself as set up while it had nothing to talk to.
                    when {
                        setupDirect -> settings.setLightSyncMode("direct")
                        haReady -> settings.setLightSyncMode("ha")
                    }
                    onNext()
                }
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
    /** The Music Assistant server the previous step connected to, if it did. */
    server: ServerConfig?,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val discovered by playerVm.discoveredServers.collectAsStateWithLifecycle()
    val discovering by playerVm.isDiscovering.collectAsStateWithLifecycle()
    // The server is already known: the previous step just connected to it. Filling it
    // in here means Register works on a network where mDNS does not — which is most
    // networks with client isolation, and every emulator — instead of leaving the
    // button dead under a "Scanning…" that quietly went away. The client normalises
    // the address (http → ws, appends /sendspin), so the API address is enough.
    var manual by remember { mutableStateOf(server?.url.orEmpty()) }
    var user by remember { mutableStateOf(server?.username.orEmpty()) }
    var pass by remember { mutableStateOf(server?.password.orEmpty()) }
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
        } else if (discovered.isEmpty()) {
            Text(
                if (manual.isNotBlank()) "Nothing announced itself on the network; the server from the previous step is filled in below."
                else "Nothing announced itself on the network. Enter the server's address below.",
                color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
            )
        }
        discovered.forEach { s ->
            ServerRow(s.name, "${s.host}:${s.port}", accent) {
                playerVm.connectToServer(s.webSocketUrl, user, pass, name)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = manual, onValueChange = { manual = it }, label = { Text("Server address") }, placeholder = { Text("http://192.168.0.10:8095") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = accentTextFieldColors(accent))

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OledButton("Back", accent = accent, outline = true, modifier = Modifier.weight(1f), onClick = onBack)
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
