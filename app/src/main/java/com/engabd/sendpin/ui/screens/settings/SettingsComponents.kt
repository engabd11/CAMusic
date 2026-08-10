package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.theme.*

/**
 * The pieces every Settings section is built from.
 *
 * They used to live at the bottom of a 1600-line `SettingsScreen.kt` alongside eight
 * inline section bodies, which is why the sections drifted: two of them grew their own
 * spelling of a status line, three of them explained a backend restriction in
 * different words, and the reader had to scroll past all of it to find anything.
 *
 * The rule the whole screen now follows: **a card says what it is for before it says
 * what you can change.** A settings page that opens on a toggle assumes the reader
 * already knows what the toggle does, and for everything here except the theme
 * picker, they don't.
 */

// ── Structure ─────────────────────────────────────────────────────────────

/**
 * One card: a title, the sentence explaining what it is for, then its controls.
 *
 * [lead] is not decoration. Half of what is configurable here is invisible until it
 * goes wrong — a stream format, a gain mode, a background connection — and a title
 * alone leaves the reader to guess from the control what the control does.
 */
@Composable
internal fun SettingsCard(
    title: String,
    lead: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = TextPrimary, fontFamily = AppFont, style = MaterialTheme.typography.titleLarge)
            if (lead != null) {
                Text(lead, color = TextMuted, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

/** The quiet explanatory line under a control. */
@Composable
internal fun Note(text: String, warn: Boolean = false) {
    Text(
        text,
        color = if (warn) WarnAmber else TextFaint,
        fontFamily = AppFont,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A label above a control that is not the card's own title. */
@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = TextSecondary, fontFamily = AppFont, style = MaterialTheme.typography.labelLarge)
}

/** A hairline between two groups inside one card. */
@Composable
internal fun CardDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
}

/**
 * A row that goes somewhere: icon, name, what is behind it, chevron.
 *
 * Used by the settings index and by anything that points at another page. A
 * one-word category is a guess until you have opened it once, which is why the
 * subtitle is not optional.
 */
@Composable
internal fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlassCard(radius = 16.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (enabled) accent.a(0.14f) else Glass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = if (enabled) accent else TextFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    title,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(subtitle, color = TextFaint, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
            }
            if (enabled) Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Status ────────────────────────────────────────────────────────────────

/** How a connection is doing, in the four states worth colouring differently. */
internal enum class Health { GOOD, WORKING, WARN, BAD, IDLE }

/** A coloured dot and a line of text. The one status shape the whole screen uses. */
@Composable
internal fun StatusLine(text: String, health: Health, accent: Color) {
    val tint = when (health) {
        Health.GOOD -> accent
        Health.WORKING -> TextMuted
        Health.WARN -> WarnAmber
        Health.BAD -> ErrorRed
        Health.IDLE -> TextFaint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Text(text, color = tint, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
    }
}

/** A label and a monospaced value, for the read-only readouts. */
@Composable
internal fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/** A boxed group of [StatusRow]s. */
@Composable
internal fun StatusPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink3).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

// ── Controls ──────────────────────────────────────────────────────────────

@Composable
internal fun OledField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    accent: Color,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
            disabledBorderColor = Hairline, disabledTextColor = TextMuted, disabledLabelColor = TextMuted,
        ),
        trailingIcon = trailingIcon,
    )
}

/** A password field with the show/hide eye, since three screens wanted the same one. */
@Composable
internal fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    accent: Color,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    placeholder: String = "",
) {
    OledField(
        value, onChange, label, placeholder, accent,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Box(Modifier.size(20.dp).clip(CircleShape).clickable { onVisibilityChange(!visible) }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (visible) "Hide" else "Show",
                    tint = TextMuted, modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
internal fun OledButton(
    text: String, accent: Color, enabled: Boolean = true, outline: Boolean = false,
    danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val fill = when {
        !enabled -> Glass
        danger -> ErrorRed.a(0.14f)
        outline -> Glass
        else -> accent
    }
    val border = when {
        danger && enabled -> ErrorRed.a(0.55f)
        outline -> Hairline
        else -> Color.Transparent
    }
    val label = when {
        !enabled -> TextMuted
        danger -> ErrorRed
        outline -> TextMuted
        else -> Ink
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = label, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink3)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextMuted,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(subtitle, color = TextFaint, fontSize = 11.sp)
        }
        Box(
            Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(100))
                .background(if (checked) accent else Glass)
                .border(1.dp, if (checked) accent.a(0.5f) else Hairline, RoundedCornerShape(100))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Ink else TextMuted)) }
    }
}

/** One pickable accent, ticked when it is the chosen one. */
@Composable
internal fun SwatchDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            // Drawn in the page's own ink rather than a fixed white, so the selected
            // swatch reads on a light theme as well as a black one.
            .border(if (selected) 2.dp else 1.dp, if (selected) TextPrimary else Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, "Selected", tint = Ink, modifier = Modifier.size(18.dp))
    }
}

// ── Shared value sets ─────────────────────────────────────────────────────

/**
 * What this phone tells Music Assistant it can decode. "Auto" offers all three;
 * naming one narrows the advertised list to it alone, which is what actually forces
 * the server's hand — see `FormatNegotiator.supportedFormats`.
 */
internal val CodecValues = listOf("auto", "flac", "pcm", "opus")
internal val CodecLabels = listOf("Auto", "FLAC", "PCM", "Opus")

/**
 * What a self-hosted library should send for a direct stream. "raw" hands back the
 * stored file untouched; the lossy options carry their bitrate in the same token so
 * the pair cannot drift apart.
 */
internal val StreamFormatValues = listOf("raw", "flac", "mp3-320", "mp3-192", "opus-128")
internal val StreamFormatLabels = listOf("Original", "FLAC", "MP3 320", "MP3 192", "Opus 128")

internal val ReplayGainValues = listOf(ReplayGain.OFF, ReplayGain.TRACK, ReplayGain.ALBUM)
internal val ReplayGainLabels = listOf("Off", "Track", "Album")

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
