package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ha.LightArea
import com.engabd.sendpin.ha.LightSyncRepository
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.LightSyncViewModel
import kotlin.math.roundToInt

private val ModeFallback = listOf("auto", "subtle", "medium", "high", "intense", "extreme")
private val EffectFallback = listOf("music", "movies", "fireworks")

private val SwatchColour = mapOf(
    "sunset" to 0xFFE0803C, "ocean" to 0xFF3CA0E0, "forest" to 0xFF3ECF7A,
    "lavender" to 0xFFB56AE0, "ember" to 0xFFE05656, "rainbow" to 0xFFE0C256,
)

@Composable
fun LightSyncScreen(onBack: () -> Unit = {}, viewModel: LightSyncViewModel = viewModel()) {
    val accent = LocalAccent.current
    val connected by viewModel.connected.collectAsState()
    val areas by viewModel.areas.collectAsState()
    val area by viewModel.selectedArea.collectAsState()
    val error by viewModel.error.collectAsState()
    val prefillUrl by viewModel.haUrl.collectAsState()
    val prefillToken by viewModel.haToken.collectAsState()

    val enabled = area?.enabled == true

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(if (enabled) accent else TextFaint, 440.dp, (-40).dp, (-70).dp, if (enabled) 0.42f else 0.16f)

        Column(Modifier.fillMaxSize()) {
            // Header.
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Light Sync", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Hue follows the music", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                StatusPill(connected && enabled)
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 100.dp)) {

                if (!connected) {
                    ConnectCard(
                        prefillUrl = prefillUrl,
                        prefillToken = prefillToken,
                        error = error,
                        onConnect = viewModel::connect,
                    )
                    return@Column
                }

                if (areas.isEmpty()) {
                    EmptyAreas(error) { viewModel.refresh() }
                    return@Column
                }

                val a = area ?: return@Column

                // Master toggle.
                GlassCard(radius = 18.dp, fill = if (enabled) accent.a(0.10f) else Glass) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) accent.a(0.18f) else Glass).border(1.dp, if (enabled) accent.a(0.4f) else Hairline, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lightbulb, null, tint = if (enabled) accent else TextMuted, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Sync lights to music", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(if (enabled) "Reacting to the beat" else "Lights are steady", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        AccentSwitch(enabled) { viewModel.setEnabled(it) }
                    }
                }

                if (areas.size > 1) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Area")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        areas.forEach { ar -> Pill(ar.name, ar.id == a.id) { viewModel.selectArea(ar.id) } }
                    }
                }

                val modeOptions = a.modeOptions.ifEmpty { ModeFallback }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Intensity")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modeOptions.forEach { opt -> Pill(opt.label(), opt == a.mode) { viewModel.setMode(opt) } }
                }

                val effectOptions = a.effectOptions.ifEmpty { EffectFallback }
                Spacer(Modifier.height(22.dp))
                SectionLabel("Effect")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    effectOptions.forEach { opt -> EffectTile(opt, effectIcon(opt), opt == a.effect) { viewModel.setEffect(opt) } }
                }

                Spacer(Modifier.height(22.dp))
                val albumSelected = a.colour?.startsWith("album") == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Colour", modifier = Modifier.weight(1f))
                    ToggleChip("Follow album", albumSelected) { viewModel.setColour(LightSyncRepository.ALBUM_COLOUR) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlbumSwatch(selected = albumSelected) { viewModel.setColour(LightSyncRepository.ALBUM_COLOUR) }
                    LightSyncRepository.PALETTE_SWATCHES.forEach { name ->
                        ColourDot(Color(SwatchColour[name] ?: 0xFF888888), selected = a.colour == name) { viewModel.setColour(name) }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Brightness ceiling")
                Spacer(Modifier.height(10.dp))
                val bSlider = ((a.brightnessPct - 5) / 95f).coerceIn(0f, 1f)
                LabeledSlider(Icons.Default.BrightnessHigh, bSlider, { viewModel.setBrightness((5 + it * 95).roundToInt()) }, "${a.brightnessPct}%")

                Spacer(Modifier.height(22.dp))
                SectionLabel("Timing")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Light offset", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OffsetStep("−") { viewModel.changeTiming(-5) }
                    Text("${a.timingMs} ms", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.widthIn(min = 56.dp))
                    OffsetStep("+") { viewModel.changeTiming(5) }
                }
                Text(
                    "Positive delays the lights; negative pushes them ahead of the audio.",
                    color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun String.label(): String = replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun effectIcon(name: String): ImageVector = when (name) {
    "movies" -> Icons.Default.Movie
    "fireworks" -> Icons.Default.Celebration
    else -> Icons.Default.GraphicEq
}

@Composable
private fun ConnectCard(prefillUrl: String, prefillToken: String, error: String?, onConnect: (String, String) -> Unit) {
    val accent = LocalAccent.current
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var token by remember(prefillToken) { mutableStateOf(prefillToken) }

    GlassCard(radius = 18.dp) {
        Column(Modifier.padding(18.dp)) {
            Text("Connect Home Assistant", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Light sync is driven by the Hue Synco integration in Home Assistant. Add your HA URL and a long-lived access token (Profile → Security).",
                color = TextMuted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            HaField(url, { url = it }, "HA URL", "http://192.168.0.10:8123", accent)
            Spacer(Modifier.height(12.dp))
            HaField(token, { token = it }, "Long-lived access token", "eyJ…", accent)
            error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = ErrorRed, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (url.isBlank() || token.isBlank()) Glass else accent)
                    .clickable(enabled = url.isNotBlank() && token.isNotBlank()) { onConnect(url, token) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Connect", color = if (url.isBlank() || token.isBlank()) TextMuted else Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun HaField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, accent: Color) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
            unfocusedBorderColor = Hairline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        ),
    )
}

@Composable
private fun EmptyAreas(error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lightbulb, null, tint = TextFaint, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text("No sync areas found", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(error ?: "Set up a Hue Synco entertainment area in Home Assistant.", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100)).clickable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 11.dp)) {
            Text("Retry", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusPill(live: Boolean) {
    val accent = LocalAccent.current
    val c = if (live) accent else TextMuted
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(c.a(0.12f)).border(1.dp, c.a(0.35f), RoundedCornerShape(100)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(if (live) "Live" else "Off", color = c, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun EffectTile(name: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(15.dp))
            .background(if (selected) accent.a(0.14f) else Glass)
            .border(1.dp, if (selected) accent.a(0.5f) else Hairline, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) accent else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(9.dp))
        Text(name.label(), color = if (selected) accent else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun AlbumSwatch(selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(accent)
            .border(if (selected) 3.dp else 0.dp, TextPrimary, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.AutoAwesome, "Album colour", tint = Ink, modifier = Modifier.size(17.dp)) }
}

@Composable
private fun ColourDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 0.dp, TextPrimary, CircleShape).clickable(onClick = onClick),
    )
}

@Composable
private fun LabeledSlider(icon: ImageVector, value: Float, onChange: (Float) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        HSlider(value, onChange, modifier = Modifier.weight(1f))
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 44.dp))
    }
}

@Composable
private fun OffsetStep(label: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

@Composable
private fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink, checkedTrackColor = accent, checkedBorderColor = accent,
            uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass, uncheckedBorderColor = Hairline,
        ),
    )
}

@Composable
private fun CircleBtn(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass).border(1.dp, Hairline, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, cd, tint = TextSecondary, modifier = Modifier.size(17.dp))
    }
}
