package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch

private val OnboardingKinds = listOf(
    ServerKind.MUSIC_ASSISTANT to "A Music Assistant server on your network",
    ServerKind.NAVIDROME to "A Navidrome (or OpenSubsonic) server",
    ServerKind.JELLYFIN to "A Jellyfin server",
    ServerKind.LOCAL to "Music files on this device",
)

/**
 * First-run setup. Same four sources the phone's `OnboardingWizard.kt` opens
 * with (Music Assistant / Navidrome / Jellyfin / local files) — Subsonic and
 * multi-server management stay in Settings, matching the phone. No Light Sync
 * or speaker-registration steps here: those are real screens of their own
 * (`TvLightSyncScreen`, Settings) reachable once the app is open, so this
 * wizard only has to answer the one question nothing else can substitute for
 * — which library to play from.
 */
@Composable
fun TvOnboardingScreen(onDone: () -> Unit, libraryViewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var chosenKind by rememberSaveable { mutableStateOf<ServerKind?>(null) }

    fun finish(skipped: Boolean) {
        scope.launch {
            settings.setOnboardingCompleted(true)
            settings.setOnboardingSkipped(skipped)
            onDone()
        }
    }

    val kind = chosenKind
    if (kind == null) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.Center) {
                Text("Welcome to CAMusic", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("What should CAMusic play from?", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OnboardingKinds.forEach { (k, description) ->
                        TvTile(onClick = { chosenKind = k }, modifier = Modifier.width(560.dp)) {
                            Column(Modifier.padding(18.dp)) {
                                Text(k.label, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(description, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                com.engabd.sendpin.tv.design.TvButton(onClick = { finish(skipped = true) }) {
                    Text("Skip for now")
                }
            }
        }
    } else {
        TvServerSetupScreen(
            kind = kind,
            libraryViewModel = libraryViewModel,
            onConnected = { finish(skipped = false) },
            onBack = { chosenKind = null },
        )
    }
}
