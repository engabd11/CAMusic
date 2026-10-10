package com.engabd.sendpin.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.AutoEqParser
import com.engabd.sendpin.audio.EqPreset
import com.engabd.sendpin.audio.LocalDsp
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

// The slider mappings both equalisers share: this phone's and Music Assistant's.

internal fun fmtFreq(hz: Float): String = when {
    hz >= 1000f -> "%.1f kHz".format(hz / 1000f)
    else -> "%.0f Hz".format(hz)
}

/** 20 Hz → 0, 20 kHz → 1, logarithmic. */
internal fun freqToSlider(hz: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    val clamped = hz.toDouble().coerceIn(minHz, maxHz)
    return (Math.log(clamped / minHz) / Math.log(maxHz / minHz)).toFloat().coerceIn(0f, 1f)
}

internal fun sliderToFreq(s: Float): Float {
    val minHz = 20.0
    val maxHz = 20_000.0
    return (minHz * Math.pow(maxHz / minHz, s.toDouble())).toFloat()
}

/** 0.1 → 0, 1.0 → 0.5, 10.0 → 1, logarithmic. */
internal fun qToSlider(q: Float): Float {
    val minQ = 0.1
    val maxQ = 10.0
    val clamped = q.toDouble().coerceIn(minQ, maxQ)
    return (Math.log(clamped / minQ) / Math.log(maxQ / minQ)).toFloat().coerceIn(0f, 1f)
}

internal fun sliderToQ(s: Float): Float {
    val minQ = 0.1
    val maxQ = 10.0
    return (minQ * Math.pow(maxQ / minQ, s.toDouble())).toFloat()
}

/** [range] dB either way to 0..1 and back, snapped to half a decibel. */
internal fun dbToSlider(db: Float, low: Float, high: Float): Float = ((db.coerceIn(low, high) - low) / (high - low)).coerceIn(0f, 1f)

internal fun sliderToDb(s: Float, low: Float, high: Float): Float =
    (Math.round((low + s.coerceIn(0f, 1f) * (high - low)) * 2f) / 2f).coerceIn(low, high)

internal fun typeLabel(type: LocalDsp.Band.Type): String = when (type) {
    LocalDsp.Band.Type.PEAKING -> "Peak"
    LocalDsp.Band.Type.LOW_SHELF -> "Low shelf"
    LocalDsp.Band.Type.HIGH_SHELF -> "High shelf"
    LocalDsp.Band.Type.HIGH_PASS -> "High-pass"
    LocalDsp.Band.Type.LOW_PASS -> "Low-pass"
}

@Composable
internal fun EqLabelSlider(label: String, displayValue: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelLarge)
            Text(displayValue, color = if (enabled) TextPrimary else TextFaint, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        HSlider(value = value, onChange = { if (enabled) onChange(it) }, accented = enabled)
    }
}

/**
 * One band of a parametric curve: what it is at a glance, its gain always to hand,
 * and its type, frequency and Q a tap away. An AutoEQ correction is ten of these.
 */
@Composable
internal fun ParametricBandRow(
    band: LocalDsp.Band,
    accent: Color,
    enabled: Boolean,
    onChange: (LocalDsp.Band) -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val on = band.enabled && enabled
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) accent.a(0.05f) else Color(0x06FFFFFF))
            .border(1.dp, if (on) accent.a(0.15f) else HairlineSoft, RoundedCornerShape(12.dp))
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
                    .clickable(enabled = enabled) { onChange(band.copy(enabled = !band.enabled)) },
            )
            Text(
                "${typeLabel(band.type)} · ${fmtFreq(band.frequency)}" +
                    if (band.type == LocalDsp.Band.Type.HIGH_PASS || band.type == LocalDsp.Band.Type.LOW_PASS) ""
                    else " · ${"%+.1f".format(band.gainDb)} dB",
                color = if (on) TextPrimary else TextMuted,
                fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.weight(1f).clickable { open = !open },
            )
            Text(
                if (open) "Less" else "Edit",
                color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(100)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onRemove).padding(4.dp)) {
                Icon(Icons.Default.Close, "Remove band", tint = TextFaint, modifier = Modifier.size(14.dp))
            }
        }
        if (band.type != LocalDsp.Band.Type.HIGH_PASS && band.type != LocalDsp.Band.Type.LOW_PASS) {
            EqLabelSlider("Gain", "%+.1f dB".format(band.gainDb), dbToSlider(band.gainDb, -12f, 12f), on) {
                onChange(band.copy(gainDb = sliderToDb(it, -12f, 12f)))
            }
        }
        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LocalDsp.Band.Type.entries.forEach { type ->
                    val chosen = type == band.type
                    Text(
                        typeLabel(type),
                        color = if (chosen) accent else TextSecondary,
                        fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .background(if (chosen) accent.a(0.12f) else Glass)
                            .border(1.dp, if (chosen) accent.a(0.4f) else Hairline, RoundedCornerShape(100))
                            .clickable(enabled = enabled) { onChange(band.copy(type = type)) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            EqLabelSlider("Frequency", fmtFreq(band.frequency), freqToSlider(band.frequency), on) {
                onChange(band.copy(frequency = sliderToFreq(it)))
            }
            EqLabelSlider("Q", "%.2f".format(band.q), qToSlider(band.q), on) {
                onChange(band.copy(q = sliderToQ(it)))
            }
        }
    }
}

/** A curve chip: a preset, a saved curve, or an action beside them. */
@Composable
internal fun EqChip(label: String, accent: Color, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (highlighted) accent else TextSecondary, fontFamily = AppFont,
        fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, if (highlighted) accent.a(0.5f) else Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** Name the curve on screen, to keep it. */
@Composable
internal fun SaveEqDialog(accent: Color, existing: List<EqPreset>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val replaces = existing.any { it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text("Save this curve", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OledField(name, { name = it.take(40) }, "Name", "e.g. Commute, Studio monitors", accent)
                Note(if (replaces) "Replaces the curve already saved under this name." else "Saved curves sit beside the presets, a tap away.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Save", color = if (name.isBlank()) TextFaint else accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}

/** A saved curve's options: use it, or delete it. */
@Composable
internal fun ManageEqDialog(preset: EqPreset, accent: Color, onDismiss: () -> Unit, onUse: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(preset.name, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note(
                    (if (preset.config.parametric) "Parametric, " else "Ten bands, ") +
                        "${preset.config.bands.count { it.enabled }} active",
                )
                OledButton("Use this curve", accent = accent) { onUse() }
                OledButton("Delete", accent = accent, danger = true) { onDelete() }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) } },
    )
}

/**
 * Import an AutoEQ correction: choose the `ParametricEQ.txt`, or paste it. The curve
 * is put to use straight away and kept as a saved curve under [onImport]'s name.
 */
@Composable
internal fun ImportAutoEqDialog(accent: Color, onDismiss: () -> Unit, onImport: (name: String, AutoEqParser.Result) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { readUpTo(it, MAX_FILE_BYTES) }
        }.getOrNull()?.let { text = it; error = null }
        if (name.isBlank()) name = displayName(context, uri)?.let(::nameFromFile).orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text("Import from AutoEQ", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Note(
                    "AutoEQ publishes a correction for thousands of headphones. Find yours at " +
                        "autoeq.app, download its ParametricEQ.txt, and choose it here, or paste what is in it.",
                )
                OledButton("Choose the file", accent = accent, outline = true) {
                    pick.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_FILE_BYTES); error = null },
                    label = { Text("Or paste it here") },
                    placeholder = { Text("Preamp: -6.2 dB\nFilter 1: ON PK Fc 105 Hz Gain -2.4 dB Q 0.70") },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OledField(name, { name = it.take(40) }, "Name", "e.g. Sennheiser HD 600", accent)
                error?.let { Note(it, warn = true) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = AutoEqParser.parse(text)
                    if (result == null) error = "No filters this equaliser can use were found in that text."
                    else onImport(name.trim().ifBlank { "AutoEQ" }, result)
                },
                enabled = text.isNotBlank(),
            ) { Text("Import", color = if (text.isBlank()) TextFaint else accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}

/** A ParametricEQ.txt is a few hundred bytes; anything past this is not one. */
private const val MAX_FILE_BYTES = 64 * 1024

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

/** "Sennheiser HD 600 ParametricEQ.txt" → "Sennheiser HD 600". */
internal fun nameFromFile(file: String): String =
    file.substringBeforeLast('.').removeSuffix("ParametricEQ").removeSuffix(" ParametricEQ").trim()
        .removeSuffix("-").removeSuffix("_").trim().take(40)

/** Up to [limit] bytes of [input] as text. */
private fun readUpTo(input: java.io.InputStream, limit: Int): String {
    val buf = ByteArray(limit)
    var n = 0
    while (n < limit) {
        val read = input.read(buf, n, limit - n)
        if (read < 0) break
        n += read
    }
    return String(buf, 0, n, Charsets.UTF_8)
}
