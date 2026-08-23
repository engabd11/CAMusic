package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.ThemeChoice
import kotlinx.coroutines.launch

/**
 * MVP subset of the phone's `SettingsScreen.kt`: Libraries (switch the active
 * server, reusing [LibraryViewModel] exactly) and Appearance (theme choice,
 * reusing [AppSettings] exactly). Downloads/DSP/Stats/Driving stay off this
 * screen per the TV plan's deferred scope — none of them apply here yet.
 */
@Composable
fun TvSettingsScreen(libraryViewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current

    val servers by libraryViewModel.allServers.collectAsStateWithLifecycle()
    val activeConfig by libraryViewModel.activeServerConfig.collectAsStateWithLifecycle()
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = "oled")

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Settings", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        SectionLabel("Libraries")
        Spacer(Modifier.height(10.dp))
        if (servers.isEmpty()) {
            Text("No libraries configured yet.", color = TextMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                servers.forEach { server ->
                    TvTile(
                        onClick = { libraryViewModel.selectServer(server) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(server.kind.label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (activeConfig?.id == server.id) {
                                Text("Active", color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Appearance")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeChoice.entries.forEach { choice ->
                TvTile(
                    onClick = { scope.launch { settings.setTheme(choice.key) } },
                    shape = RoundedCornerShape(100),
                ) {
                    Text(
                        choice.label,
                        color = if (choice.key == themeKey) accent else TextMuted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("About")
        Spacer(Modifier.height(10.dp))
        Text("CAMusic TV — v${BuildConfig.VERSION_NAME}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}
