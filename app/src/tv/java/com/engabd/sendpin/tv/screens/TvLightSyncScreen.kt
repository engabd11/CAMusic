package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.INTENSITY_AUTO
import com.engabd.sendpin.hue.SyncMode
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * Drives the same process-scoped [com.engabd.sendpin.hue.DirectLightSync] and
 * [AppSettings] the phone's "direct" Light Sync screen does (see
 * `ui/screens/LightSyncScreen.kt`'s `DirectLightSyncScreen`) — no ViewModel
 * needed, since every control there is a plain settings write the engine
 * watches. The Home Assistant transport (`LightSyncViewModel`, an entirely
 * separate WebSocket-based path) is deliberately not ported to TV: HA is a
 * companion-app concept, and "own playback, own bridge" is what the TV plan
 * scoped for v1.
 *
 * MVP subset: enable, entertainment area, intensity. Colour scheme, effect,
 * spatial and the creative layers stay phone-only for now — real controls but
 * more of them than a first TV pass needs to prove the pipeline works.
 */
@Composable
fun TvLightSyncScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as SendpinApp
    val settings = remember { AppSettings(context.applicationContext) }
    val direct = app.directLightSync
    val scope = rememberCoroutineScope()

    val live by direct.active.collectAsStateWithLifecycle()
    val syncError by direct.error.collectAsStateWithLifecycle()
    val configs by direct.entertainmentConfigs.collectAsStateWithLifecycle()
    val loadingConfigs by direct.configsLoading.collectAsStateWithLifecycle()
    val configError by direct.configsError.collectAsStateWithLifecycle()

    val bridgeIp by settings.hueBridgeIp.collectAsState(initial = "")
    val configId by settings.hueEntertainmentConfigId.collectAsState(initial = "")
    val enabled by settings.lightSyncEnabled.collectAsState(initial = false)
    val intensity by settings.lightSyncIntensity.collectAsState(initial = "high")

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Light Sync", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Text("Straight to the bridge", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))

        if (bridgeIp.isBlank()) {
            Text("No Hue bridge configured yet. Set one up in Settings.", color = TextMuted)
            return@Column
        }

        TvTile(onClick = { scope.launch { settings.setLightSyncEnabled(!enabled) } }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync lights to music", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(if (live) "Reacting to the beat" else if (enabled) "Waiting for something to play" else "Off", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { scope.launch { settings.setLightSyncEnabled(it) } })
            }
        }

        syncError?.let {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFE05B5B), style = MaterialTheme.typography.bodySmall)
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        SectionLabel("Entertainment area")
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        when {
            loadingConfigs -> Text("Loading areas from the bridge…", color = TextMuted)
            configs.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                configs.forEach { cfg ->
                    TvChoiceChip(cfg.name, selected = cfg.id == configId) {
                        scope.launch { settings.setHueConfigId(cfg.id) }
                    }
                }
            }
            else -> Text(configError ?: "No areas found. Create one in the Hue app.", color = TextMuted)
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        SectionLabel("Intensity")
        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvChoiceChip("Auto", selected = intensity == INTENSITY_AUTO) {
                scope.launch { settings.setLightSyncIntensity(INTENSITY_AUTO) }
            }
            SyncMode.entries.forEach { m ->
                TvChoiceChip(m.wire.replaceFirstChar { it.uppercase() }, selected = m.wire == intensity) {
                    scope.launch { settings.setLightSyncIntensity(m.wire) }
                }
            }
        }
    }
}

@Composable
private fun TvChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    TvTile(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(100)) {
        Text(
            label,
            color = if (selected) accent else TextMuted,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
