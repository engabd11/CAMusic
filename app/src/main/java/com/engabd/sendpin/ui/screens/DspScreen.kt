package com.engabd.sendpin.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.AudioChannel
import com.engabd.sendpin.ma.DspFilter
import com.engabd.sendpin.ma.EqBand
import com.engabd.sendpin.ma.EqBandType
import com.engabd.sendpin.ma.DspPreset
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.DspViewModel

/**
 * The DSP / Equalizer screen — a per-player parametric EQ, tone control, and
 * gain stage, powered by Music Assistant's server-side DSP pipeline.
 *
 * Reachable from Now Playing via the EQ button. Changes are applied live by MA
 * — no playback restart needed. Works for any MA player (this phone or a remote
 * speaker), following the same "Play here" target the rest of the app uses.
 *
 * Layout: a warning at the top, then expandable sections for the master
 * toggle, parametric EQ, tone control, gain, and presets. Each section
 * collapses to a summary line and expands to show its controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DspScreen(
    onBack: () -> Unit,
    viewModel: DspViewModel = viewModel(),
) {
    val config by viewModel.config.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val error by viewModel.error.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val playerName by viewModel.playerName.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val eqExpanded by viewModel.eqExpanded.collectAsState()
    val toneExpanded by viewModel.toneExpanded.collectAsState()
    val gainExpanded by viewModel.gainExpanded.collectAsState()
    val presetsExpanded by viewModel.presetsExpanded.collectAsState()
    val showSavePreset by viewModel.showSavePreset.collectAsState()
    val presetName by viewModel.presetName.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) { viewModel.load() }

    val accent = LocalAccent.current

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(bottom = systemNavInset()),
        ) {
            // ── Top bar ─────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary, modifier = Modifier.size(22.dp))
                }
                Text(
                    "DSP & Equalizer",
                    color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp, modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // Save button — only when there's something to save.
                if (config != null) {
                    SaveButton(saving = saving) { viewModel.save() }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                return@Column
            }

            if (!connected) {
                NotConnectedNotice()
                return@Column
            }

            error?.let {
                ErrorNotice(it) { viewModel.load() }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                // ── Player name ──────────────────────────────────────────
                Text(
                    "Configuring: $playerName",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                )

                // ── Warning ──────────────────────────────────────────────
                WarningBanner()

                // ── Master enable ────────────────────────────────────────
                config?.let { cfg ->
                    MasterToggle(enabled = cfg.enabled, accent = accent) {
                        viewModel.toggleEnabled()
                    }
                }

                // ── Parametric EQ ────────────────────────────────────────
                config?.let { cfg ->
                    val eq = (cfg.filters.firstOrNull { it is DspFilter.Eq } as? DspFilter.Eq)?.filter
                    val eqEnabled = eq?.enabled == true
                    ExpandableSection(
                        title = "Parametric EQ",
                        subtitle = eq?.let { "${it.bands.size} bands" } ?: "Off",
                        enabled = eqEnabled,
                        expanded = eqExpanded,
                        accent = accent,
                        onToggleExpand = { viewModel.toggleEqExpanded() },
                        onEnable = { viewModel.toggleEq() },
                    ) {
                        EqSection(
                            eq = eq,
                            accent = accent,
                            onPreamp = { viewModel.setEqPreamp(it) },
                            onAddBand = { viewModel.addBand() },
                            onRemoveBand = { viewModel.removeBand(it) },
                            onToggleBand = { viewModel.toggleBand(it) },
                            onBandFreq = { i, v -> viewModel.setBandFreq(i, v) },
                            onBandGain = { i, v -> viewModel.setBandGain(i, v) },
                            onBandQ = { i, v -> viewModel.setBandQ(i, v) },
                            onBandType = { i, v -> viewModel.setBandType(i, v) },
                            onBandChannel = { i, v -> viewModel.setBandChannel(i, v) },
                        )
                    }
                }

                // ── Tone control ─────────────────────────────────────────
                config?.let { cfg ->
                    val tone = (cfg.filters.firstOrNull { it is DspFilter.Tone } as? DspFilter.Tone)?.filter
                    val toneEnabled = tone?.enabled == true
                    ExpandableSection(
                        title = "Tone Control",
                        subtitle = tone?.let { "Bass ${fmtDb(it.bassLevel)} · Mid ${fmtDb(it.midLevel)} · Treble ${fmtDb(it.trebleLevel)}" } ?: "Off",
                        enabled = toneEnabled,
                        expanded = toneExpanded,
                        accent = accent,
                        onToggleExpand = { viewModel.toggleToneExpanded() },
                        onEnable = { viewModel.toggleTone() },
                    ) {
                        ToneSection(
                            tone = tone,
                            accent = accent,
                            onBass = { viewModel.setBass(it) },
                            onMid = { viewModel.setMid(it) },
                            onTreble = { viewModel.setTreble(it) },
                        )
                    }
                }

                // ── Gain ─────────────────────────────────────────────────
                config?.let { cfg ->
                    val hasGain = cfg.inputGain != 0f || cfg.outputGain != 0f
                    ExpandableSection(
                        title = "Gain",
                        subtitle = "In ${fmtDb(cfg.inputGain)} · Out ${fmtDb(cfg.outputGain)}",
                        enabled = hasGain,
                        expanded = gainExpanded,
                        accent = accent,
                        onToggleExpand = { viewModel.toggleGainExpanded() },
                        onEnable = null,
                    ) {
                        GainSection(
                            inputGain = cfg.inputGain,
                            outputGain = cfg.outputGain,
                            accent = accent,
                            onInput = { viewModel.setInputGain(it) },
                            onOutput = { viewModel.setOutputGain(it) },
                        )
                    }
                }

                // ── Presets ──────────────────────────────────────────────
                ExpandableSection(
                    title = "Presets",
                    subtitle = "${presets.size} saved",
                    enabled = false,
                    expanded = presetsExpanded,
                    accent = accent,
                    onToggleExpand = { viewModel.togglePresetsExpanded() },
                    onEnable = null,
                ) {
                    PresetSection(
                        presets = presets,
                        accent = accent,
                        presetName = presetName,
                        showSaveDialog = showSavePreset,
                        onPresetNameChange = { viewModel.setPresetName(it) },
                        onShowSaveDialog = { viewModel.showSavePresetDialog() },
                        onHideSaveDialog = { viewModel.hideSavePresetDialog() },
                        onSavePreset = { viewModel.saveAsPreset() },
                        onApplyPreset = { viewModel.applyPreset(it) },
                        onDeletePreset = { viewModel.deletePreset(it) },
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─── Warning banner ──────────────────────────────────────────────────────

@Composable
private fun WarningBanner() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WarnAmber.a(0.10f))
            .border(1.dp, WarnAmber.a(0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Warning, null, tint = WarnAmber, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(
            "DSP is applied server-side by Music Assistant. " +
                "Extreme gain settings may cause clipping or distortion. " +
                "Changes take effect immediately on the selected player.",
            color = Color(0xFFF2C574), style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ─── Master toggle ───────────────────────────────────────────────────────

@Composable
private fun MasterToggle(enabled: Boolean, accent: Color, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) accent.a(0.08f) else Glass)
            .border(1.dp, if (enabled) accent.a(0.3f) else Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "DSP",
            color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (enabled) "On" else "Off",
            color = if (enabled) accent else TextMuted, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Switch(checked = enabled, onCheckedChange = { onToggle() }, colors = SwitchDefaults.colors(
            checkedThumbColor = accent, checkedTrackColor = accent.a(0.3f),
            uncheckedThumbColor = TextMuted, uncheckedTrackColor = Glass,
        ))
    }
}

// ─── Expandable section ──────────────────────────────────────────────────

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    enabled: Boolean,
    expanded: Boolean,
    accent: Color,
    onToggleExpand: () -> Unit,
    onEnable: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass)
            .border(1.dp, if (enabled) accent.a(0.2f) else Hairline, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Enable toggle (if the section has one).
            if (onEnable != null) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (enabled) accent else Color.Transparent)
                        .border(1.dp, if (enabled) accent else TextMuted, CircleShape)
                        .clickable(onClick = onEnable),
                    contentAlignment = Alignment.Center,
                ) {
                    if (enabled) Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(12.dp))
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (enabled) TextPrimary else TextSecondary,
                    fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text(
                    subtitle,
                    color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
                )
            }

            Icon(
                Icons.Default.ExpandMore,
                if (expanded) "Collapse" else "Expand",
                tint = TextMuted,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                content()
            }
        }
    }
}

// ─── Parametric EQ section ───────────────────────────────────────────────

@Composable
private fun EqSection(
    eq: com.engabd.sendpin.ma.ParametricEQFilter?,
    accent: Color,
    onPreamp: (Float) -> Unit,
    onAddBand: () -> Unit,
    onRemoveBand: (Int) -> Unit,
    onToggleBand: (Int) -> Unit,
    onBandFreq: (Int, Float) -> Unit,
    onBandGain: (Int, Float) -> Unit,
    onBandQ: (Int, Float) -> Unit,
    onBandType: (Int, EqBandType) -> Unit,
    onBandChannel: (Int, AudioChannel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Preamp
        GainSlider(
            label = "Preamp",
            value = eq?.preamp ?: 0f,
            accent = accent,
            onChange = onPreamp,
        )

        // Bands
        eq?.bands?.forEachIndexed { i, band ->
            BandRow(
                index = i,
                band = band,
                accent = accent,
                onToggle = { onToggleBand(i) },
                onRemove = { onRemoveBand(i) },
                onFreq = { onBandFreq(i, it) },
                onGain = { onBandGain(i, it) },
                onQ = { onBandQ(i, it) },
                onType = { onBandType(i, it) },
                onChannel = { onBandChannel(i, it) },
            )
        }

        // Add band button
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accent.a(0.08f))
                .border(1.dp, accent.a(0.25f), RoundedCornerShape(10.dp))
                .clickable(onClick = onAddBand)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Add, "Add band", tint = accent, modifier = Modifier.size(16.dp))
            Text("Add band", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BandRow(
    index: Int,
    band: EqBand,
    accent: Color,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onFreq: (Float) -> Unit,
    onGain: (Float) -> Unit,
    onQ: (Float) -> Unit,
    onType: (EqBandType) -> Unit,
    onChannel: (AudioChannel) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (band.enabled) accent.a(0.05f) else Color(0x06FFFFFF))
            .border(1.dp, if (band.enabled) accent.a(0.15f) else HairlineSoft, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (band.enabled) accent else Color.Transparent)
                    .border(1.dp, if (band.enabled) accent else TextMuted, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (band.enabled) Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(10.dp))
            }
            Text(
                "Band ${index + 1}",
                color = if (band.enabled) TextPrimary else TextMuted,
                fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            // Type selector
            BandTypeDropdown(band.bandType, accent) { onType(it) }
            // Channel selector
            ChannelDropdown(band.channel, accent) { onChannel(it) }
            // Remove
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(4.dp)) {
                Icon(Icons.Default.Close, "Remove band", tint = TextFaint, modifier = Modifier.size(14.dp))
            }
        }

        // Frequency slider — 20 Hz to 20 kHz, logarithmic feel via 0..1 mapping.
        LabelSlider(
            label = "Freq",
            displayValue = fmtFreq(band.frequency),
            value = freqToSlider(band.frequency),
            accent = accent,
            onChange = { onFreq(sliderToFreq(it)) },
        )

        // Gain slider — -12 to +12 dB.
        LabelSlider(
            label = "Gain",
            displayValue = fmtDb(band.gain),
            value = gainToSlider(band.gain),
            accent = accent,
            onChange = { onGain(sliderToGain(it)) },
        )

        // Q slider — 0.1 to 10.0.
        LabelSlider(
            label = "Q",
            displayValue = "%.2f".format(band.q),
            value = qToSlider(band.q),
            accent = accent,
            onChange = { onQ(sliderToQ(it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandTypeDropdown(type: EqBandType, accent: Color, onSelect: (EqBandType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Glass)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                type.wire.replace("_", " "),
                color = TextSecondary, fontFamily = AppFont, fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(Icons.Default.ExpandMore, null, tint = TextFaint, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EqBandType.entries.filter { it != EqBandType.UNKNOWN }.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.wire.replace("_", " "), fontFamily = AppFont, fontSize = 13.sp) },
                    onClick = { onSelect(t); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDropdown(channel: AudioChannel, accent: Color, onSelect: (AudioChannel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Glass)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(channel.wire, color = TextSecondary, fontFamily = AppFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.ExpandMore, null, tint = TextFaint, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AudioChannel.entries.forEach { ch ->
                DropdownMenuItem(
                    text = { Text(ch.wire, fontFamily = AppFont, fontSize = 13.sp) },
                    onClick = { onSelect(ch); expanded = false },
                )
            }
        }
    }
}

// ─── Tone control section ────────────────────────────────────────────────

@Composable
private fun ToneSection(
    tone: com.engabd.sendpin.ma.ToneControlFilter?,
    accent: Color,
    onBass: (Float) -> Unit,
    onMid: (Float) -> Unit,
    onTreble: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LabelSlider(
            label = "Bass",
            displayValue = fmtDb(tone?.bassLevel ?: 0f),
            value = gainToSlider(tone?.bassLevel ?: 0f),
            accent = accent,
            onChange = { onBass(sliderToGain(it)) },
        )
        LabelSlider(
            label = "Mid",
            displayValue = fmtDb(tone?.midLevel ?: 0f),
            value = gainToSlider(tone?.midLevel ?: 0f),
            accent = accent,
            onChange = { onMid(sliderToGain(it)) },
        )
        LabelSlider(
            label = "Treble",
            displayValue = fmtDb(tone?.trebleLevel ?: 0f),
            value = gainToSlider(tone?.trebleLevel ?: 0f),
            accent = accent,
            onChange = { onTreble(sliderToGain(it)) },
        )
    }
}

// ─── Gain section ────────────────────────────────────────────────────────

@Composable
private fun GainSection(
    inputGain: Float,
    outputGain: Float,
    accent: Color,
    onInput: (Float) -> Unit,
    onOutput: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GainSlider("Input gain", inputGain, accent, onInput)
        GainSlider("Output gain", outputGain, accent, onOutput)
        Text(
            "The output limiter prevents clipping after positive gain — enabled by default in MA.",
            color = TextFaint, fontFamily = AppFont, fontSize = 11.sp,
        )
    }
}

// ─── Presets section ─────────────────────────────────────────────────────

@Composable
private fun PresetSection(
    presets: List<DspPreset>,
    accent: Color,
    presetName: String,
    showSaveDialog: Boolean,
    onPresetNameChange: (String) -> Unit,
    onShowSaveDialog: () -> Unit,
    onHideSaveDialog: () -> Unit,
    onSavePreset: () -> Unit,
    onApplyPreset: (DspPreset) -> Unit,
    onDeletePreset: (DspPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showSaveDialog) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Glass).border(1.dp, accent.a(0.25f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = onPresetNameChange,
                    placeholder = { Text("Preset name", color = TextFaint, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = accentTextFieldColors(accent),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFont, fontSize = 13.sp),
                )
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(accent).clickable(onClick = onSavePreset)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("Save", color = Ink, style = MaterialTheme.typography.labelLarge) }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHideSaveDialog)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) { Text("Cancel", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp) }
            }
        } else {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.a(0.08f))
                    .border(1.dp, accent.a(0.25f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onShowSaveDialog)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Save, "Save as preset", tint = accent, modifier = Modifier.size(16.dp))
                Text("Save current as preset", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (presets.isEmpty()) {
            Text("No presets saved yet.", color = TextFaint, fontFamily = AppFont, fontSize = 12.sp)
        } else {
            presets.forEach { preset ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Glass)
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        preset.name,
                        color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(accent.a(0.12f))
                            .clickable { onApplyPreset(preset) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Apply", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    Box(
                        Modifier.clip(CircleShape).clickable { onDeletePreset(preset) }.padding(4.dp),
                    ) { Icon(Icons.Default.Delete, "Delete", tint = TextFaint, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}

// ─── Shared components ───────────────────────────────────────────────────

@Composable
private fun GainSlider(label: String, value: Float, accent: Color, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelLarge)
            Text(fmtDb(value), color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        HSlider(
            value = gainToSlider(value),
            onChange = { onChange(sliderToGain(it)) },
            accented = true,
        )
    }
}

@Composable
private fun LabelSlider(label: String, displayValue: String, value: Float, accent: Color, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelLarge)
            Text(displayValue, color = TextPrimary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        HSlider(value = value, onChange = onChange, accented = true)
    }
}

@Composable
private fun SaveButton(saving: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .clip(RoundedCornerShape(100))
            .background(if (saving) accent.a(0.3f) else accent)
            .clickable(enabled = !saving, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (saving) {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else {
            Text("Save", color = Ink, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NotConnectedNotice() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CloudOff, null, tint = TextFaint, modifier = Modifier.size(32.dp))
            Text("Not connected to Music Assistant", color = TextMuted, fontFamily = AppFont, fontSize = 14.sp)
            Text("DSP requires an MA connection.", color = TextFaint, fontFamily = AppFont, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp))
            .background(ErrorRed.a(0.10f)).border(1.dp, ErrorRed.a(0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(16.dp))
        Text(message, color = Color(0xFFE88080), fontFamily = AppFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("Retry", color = accent, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(onClick = onRetry))
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

private fun fmtDb(gain: Float): String = "%+.1f dB".format(gain)

private fun fmtFreq(hz: Float): String = when {
    hz >= 1000f -> "%.1f kHz".format(hz / 1000f)
    else -> "%.0f Hz".format(hz)
}

/** 20 Hz → 0, 20 kHz → 1, logarithmic. */
private fun freqToSlider(hz: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    val clamped = hz.toDouble().coerceIn(minHz, maxHz)
    return (Math.log(clamped / minHz) / Math.log(maxHz / minHz)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToFreq(s: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    return (minHz * Math.pow(maxHz / minHz, s.toDouble())).toFloat()
}

/** -12 dB → 0, 0 dB → 0.5, +12 dB → 1. */
private fun gainToSlider(db: Float): Float = ((db + 12f) / 24f).coerceIn(0f, 1f)
private fun sliderToGain(s: Float): Float = (s * 24f - 12f)

/** 0.1 → 0, 1.0 → 0.5, 10.0 → 1, logarithmic. */
private fun qToSlider(q: Float): Float {
    val minQ = 0.1
    val maxQ = 10.0
    val clamped = q.toDouble().coerceIn(minQ, maxQ)
    return (Math.log(clamped / minQ) / Math.log(maxQ / minQ)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToQ(s: Float): Float {
    val minQ = 0.1
    val maxQ = 10.0
    return (minQ * Math.pow(maxQ / minQ, s.toDouble())).toFloat()
}