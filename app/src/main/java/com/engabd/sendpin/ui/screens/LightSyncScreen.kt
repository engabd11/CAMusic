package com.engabd.sendpin.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

private val Intensities = listOf("Auto", "Subtle", "Medium", "High", "Intense", "Extreme")
private val AutoRanges = listOf("Subtle → Medium", "Medium → High", "High → Extreme", "Full range")
private data class EffectDef(val name: String, val icon: ImageVector)
private val Effects = listOf(
    EffectDef("Pulse", Icons.Default.GraphicEq),
    EffectDef("Wash", Icons.Default.Waves),
    EffectDef("Strobe", Icons.Default.FlashOn),
    EffectDef("Fireworks", Icons.Default.Celebration),
    EffectDef("Breathe", Icons.Default.Air),
)
private val Swatches = listOf(
    0xFFD9A85C, 0xFF3ECF7A, 0xFF56B6E0, 0xFFB56AE0, 0xFFE0566A, 0xFFE0C256,
)

@Composable
fun LightSyncScreen(onBack: () -> Unit = {}) {
    val accent = LocalAccent.current
    var enabled by remember { mutableStateOf(true) }
    var area by remember { mutableStateOf("Living room") }
    var player by remember { mutableStateOf("Home sendspin") }
    var intensity by remember { mutableStateOf(0) }        // index into Intensities
    var autoRange by remember { mutableStateOf(1) }
    var effect by remember { mutableStateOf(0) }
    var followAlbum by remember { mutableStateOf(true) }
    var swatch by remember { mutableStateOf(-1) }          // -1 = album colour
    var brightness by remember { mutableStateOf(0.72f) }
    var floor by remember { mutableStateOf(0.12f) }
    var transition by remember { mutableStateOf(0.4f) }
    var offset by remember { mutableStateOf(0) }
    var advanced by remember { mutableStateOf(false) }

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
                StatusPill(enabled)
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 100.dp)) {
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
                        AccentSwitch(enabled) { enabled = it }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Target")
                Spacer(Modifier.height(10.dp))
                PickerRow(Icons.Default.Category, "Area", area) { area = if (area == "Living room") "Bedroom" else "Living room" }
                Spacer(Modifier.height(10.dp))
                PickerRow(Icons.Default.Speaker, "Player", player) { player = if (player == "Home sendspin") "PC sendspin cli" else "Home sendspin" }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Intensity")
                Spacer(Modifier.height(10.dp))
                FlowChips(Intensities, intensity) { intensity = it }

                AnimatedVisibility(intensity == 0) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        SectionLabel("Auto range")
                        Spacer(Modifier.height(9.dp))
                        FlowChips(AutoRanges, autoRange) { autoRange = it }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Effect")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Effects.forEachIndexed { i, e -> EffectTile(e, i == effect) { effect = i } }
                }

                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Colour", modifier = Modifier.weight(1f))
                    ToggleChip("Follow album", followAlbum) { followAlbum = !followAlbum; if (followAlbum) swatch = -1 }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlbumSwatch(selected = swatch == -1 && followAlbum) { swatch = -1; followAlbum = true }
                    Swatches.forEachIndexed { i, c ->
                        ColourDot(Color(c), selected = swatch == i) { swatch = i; followAlbum = false }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Brightness ceiling")
                Spacer(Modifier.height(10.dp))
                LabeledSlider(Icons.Default.BrightnessHigh, brightness, { brightness = it }, "${(brightness * 100).toInt()}%")

                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Advanced", modifier = Modifier.weight(1f))
                    Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                }
                AnimatedVisibility(advanced) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Text("Brightness floor", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LabeledSlider(Icons.Default.BrightnessLow, floor, { floor = it }, "${(floor * 100).toInt()}%")
                        Spacer(Modifier.height(14.dp))
                        Text("Transition smoothing", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LabeledSlider(Icons.Default.Timeline, transition, { transition = it }, "${(transition * 500).toInt()}ms")
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Light offset", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            OffsetStep("−") { offset -= 5 }
                            Text("$offset ms", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.widthIn(min = 56.dp))
                            OffsetStep("+") { offset += 5 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(enabled: Boolean) {
    val accent = LocalAccent.current
    val c = if (enabled) accent else TextMuted
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(c.a(0.12f)).border(1.dp, c.a(0.35f), RoundedCornerShape(100)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(if (enabled) "Live" else "Off", color = c, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass).border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.UnfoldMore, null, tint = TextFaint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FlowChips(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // A horizontal scroll keeps the chips on one line and predictable across widths.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, label -> Pill(label, i == selected, { onSelect(i) }) }
    }
}

@Composable
private fun EffectTile(e: EffectDef, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(15.dp))
            .background(if (selected) accent.a(0.14f) else Glass)
            .border(1.dp, if (selected) accent.a(0.5f) else Hairline, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(e.icon, null, tint = if (selected) accent else TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(9.dp))
        Text(e.name, color = if (selected) accent else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
