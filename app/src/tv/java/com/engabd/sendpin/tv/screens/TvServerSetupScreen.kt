package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.local.LocalMediaSource
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.tv.design.TvButton
import com.engabd.sendpin.tv.design.TvError
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.tv.design.tvDpadExitField
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The four sources a TV install can be pointed at, in the order the phone's
 * `OnboardingWizard.kt` offers them. Subsonic and the planned kinds stay off
 * this list for the same reason they are off the phone's first screen: they are
 * a Settings-depth choice, not a first-question one.
 */
private val TvLibraryKinds = listOf(
    ServerKind.MUSIC_ASSISTANT to "A Music Assistant server on your network",
    ServerKind.NAVIDROME to "A Navidrome (or OpenSubsonic) server",
    ServerKind.JELLYFIN to "A Jellyfin server",
    ServerKind.LOCAL to "Music files on this device",
)

/**
 * The source picker, shared by first-run onboarding and Settings → Libraries →
 * Add a library, so the two cannot drift apart on which kinds they offer.
 */
@Composable
fun TvLibraryKindTiles(onPick: (ServerKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvLibraryKinds.forEach { (kind, description) ->
            TvTile(onClick = { onPick(kind) }, modifier = Modifier.width(560.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(kind.label, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * The address/credentials form, shared between onboarding and Settings →
 * Libraries — matching the phone's own `OnboardingWizard.kt` `ConfigStep` /
 * `LibrariesSettings.kt` split, just kept as one TV composable instead of two.
 * Same [LibraryViewModel] connect call the phone form makes: build a
 * [ServerConfig], `switchTo` it, `connect`. `switchTo` is what routes a Music
 * Assistant kind to `Backend.MA` and everything else to `Backend.SUBSONIC`
 * internally — this form does not need to know that split.
 *
 * Every field uses [tvDpadExitField] — see that function's doc for why a bare
 * D-pad-focused `TextField` cannot be trusted to hand DPAD_DOWN off on its own
 * (verified live on the emulator building this screen).
 */
@Composable
fun TvServerSetupScreen(
    kind: ServerKind,
    libraryViewModel: LibraryViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var url by remember(kind) { mutableStateOf("") }
    var username by remember(kind) { mutableStateOf("") }
    var password by remember(kind) { mutableStateOf("") }

    // Stable for as long as this form is open, rather than minted inside `connect`:
    // a first Connect that fails on a mistyped address is meant to be corrected and
    // retried, and a fresh id per attempt would leave a dead half-configured entry
    // in the server list behind every one of those retries.
    val configId = remember(kind) { UUID.randomUUID().toString() }

    // Declared in the manifest since the beginning and requested nowhere on the
    // phone either until LibrariesSettings.kt's LocalFolderCard added this exact
    // launcher — see that file's doc comment. Verified live: connecting to
    // ServerKind.LOCAL without it fails with LocalMediaSource's own
    // "Allow access to audio on this device" SourceError, so onboarding has to
    // ask before Connect can do anything for this kind.
    var audioGranted by remember { mutableStateOf(LocalMediaSource.hasAudioPermission(context)) }
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> audioGranted = granted }

    val ready by libraryViewModel.ready.collectAsStateWithLifecycle()
    val connecting by libraryViewModel.connecting.collectAsStateWithLifecycle()
    val connError by libraryViewModel.connError.collectAsStateWithLifecycle()

    // The remote's own Back key, not only the on-screen button below: on a TV the
    // hardware key is what a user reaches for first, and without this it walks
    // straight out of the app instead of back to the source picker.
    BackHandler(onBack = onBack)

    fun connect() {
        val config = ServerConfig(
            id = configId,
            kind = kind,
            label = kind.label,
            url = url.trim(),
            username = username,
            password = password,
        )
        scope.launch {
            // Read-modify-write rather than `saveServers(listOf(config))`. This form
            // is reachable from Settings as well as first-run onboarding, and there a
            // whole-list write would silently delete every library already set up.
            // Same replace-by-id-or-append `LibrariesSettings.kt`'s own `save` does,
            // for the same reason.
            val existing = settings.servers.first()
            settings.saveServers(
                if (existing.any { it.id == config.id }) existing.map { if (it.id == config.id) config else it }
                else existing + config,
            )
            settings.setActiveServer(config.id)
            libraryViewModel.switchTo(config)
            libraryViewModel.connect()
        }
    }

    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center) {
        Text("Connect to ${kind.label}", color = TextPrimary, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        if (kind == ServerKind.LOCAL) {
            Text(
                if (audioGranted) "CAMusic will scan this device's music."
                else "CAMusic needs permission to read this device's music before it can scan anything.",
                color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(480.dp),
            )
            if (!audioGranted) {
                Spacer(Modifier.height(16.dp))
                TvButton(onClick = { audioPermission.launch(LocalMediaSource.AUDIO_PERMISSION) }) {
                    Text("Grant access to music")
                }
            }
        } else {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                // The kind's own example address. Worth more here than on the phone:
                // this gets typed on an on-screen keyboard at one D-pad press per
                // character, so guessing wrong at the scheme or port is expensive.
                placeholder = { Text(kind.urlHint, color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.width(480.dp).tvDpadExitField()
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.width(480.dp).tvDpadExitField()
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.width(480.dp).tvDpadExitField()
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
            )
        }

        connError?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = TvError, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val canConnect = !connecting && (kind != ServerKind.LOCAL || audioGranted)
            TvButton(onClick = ::connect, enabled = canConnect) {
                Text(if (connecting) "Connecting…" else if (ready) "Connected" else "Connect")
            }
            TvButton(onClick = onBack) { Text("Back") }
            if (ready) {
                TvButton(onClick = onConnected) { Text("Continue") }
            }
        }
    }
}
