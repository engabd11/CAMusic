package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
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
import com.engabd.sendpin.tv.design.tvDpadExitField
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * The address/credentials form, shared between onboarding and (in a future
 * pass) Settings → Libraries — matching the phone's own `OnboardingWizard.kt`
 * `ConfigStep`/`LibrariesSettings.kt` split, just kept as one TV composable
 * instead of two. Same [LibraryViewModel] connect call the phone form makes:
 * build a [ServerConfig], `switchTo` it, `connect`. `switchTo` is what routes
 * a Music Assistant kind to `Backend.MA` and everything else to
 * `Backend.SUBSONIC` internally — this form does not need to know that split.
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
    val accent = LocalAccent.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var url by remember(kind) { mutableStateOf("") }
    var username by remember(kind) { mutableStateOf("") }
    var password by remember(kind) { mutableStateOf("") }

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

    fun connect() {
        val config = ServerConfig(
            kind = kind,
            label = kind.label,
            url = url.trim(),
            username = username,
            password = password,
        )
        scope.launch {
            settings.saveServers(listOf(config))
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
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFE05B5B), style = MaterialTheme.typography.bodySmall)
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
