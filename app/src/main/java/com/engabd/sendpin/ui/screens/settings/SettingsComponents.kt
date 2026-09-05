package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.ReplayGain
import com.engabd.sendpin.ui.design.GlassCard
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.InfoChip
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
 *
 * [info] is where the *rest* of that explanation goes. A lead that ran to four or
 * five lines pushed the card's own controls off the screen, so the long form sits
 * behind a chip on the title and the lead keeps one sentence. See [InfoChip].
 */
@Composable
internal fun SettingsCard(
    title: String,
    lead: String? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(radius = 16.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitleWithInfo(title, info, MaterialTheme.typography.titleLarge)
            if (lead != null) {
                Text(lead, color = TextMuted, fontFamily = AppFont, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

/**
 * A title, with the chip that opens its long form sitting on the same line.
 *
 * The chip is 30dp of touch target around a 17dp mark, which is taller than any title
 * line in this app. `heightIn(0.dp)` lets it overhang instead of setting the row
 * height — without it every card with an [InfoChip] stood 8dp taller than every card
 * without one, and the settings index rippled.
 *
 * The text is given most of the row with the chip beside it, rather than both sizing
 * themselves in an unconstrained row: long titles ("Media Providers & Accounts",
 * "Chameleon Canvas & Bloom") otherwise ran underneath the chip and read as two lines
 * printed over each other. Top-aligned, so a title that wraps keeps its second line
 * clear of the chip sitting on the first.
 */
@Composable
private fun TitleWithInfo(title: String, info: String?, style: TextStyle) {
    if (info == null) {
        Text(title, color = TextPrimary, fontFamily = AppFont, style = style)
        return
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title, color = TextPrimary, fontFamily = AppFont, style = style,
            modifier = Modifier.weight(1f, fill = false),
        )
        InfoChip(title, info, Modifier.heightIn(0.dp))
    }
}

/**
 * The quiet explanatory line under a control.
 *
 * [info] carries the long form, if there is one, behind a chip on the end of the
 * line — see [InfoChip]. [title] names what the chip is about, since a note has no
 * title of its own to borrow; it defaults to the note itself, which is right when the
 * note is short and wrong only if you forget to pass one.
 */
@Composable
internal fun Note(
    text: String,
    warn: Boolean = false,
    info: String? = null,
    title: String? = null,
) {
    if (info == null) {
        Text(
            text,
            color = if (warn) WarnAmber else TextFaint,
            fontFamily = AppFont,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            color = if (warn) WarnAmber else TextFaint,
            fontFamily = AppFont,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        InfoChip(title ?: text, info, Modifier.heightIn(0.dp))
    }
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

/**
 * A label and a monospaced value, for the read-only readouts.
 *
 * Both sides are given a share of the row rather than left to size themselves:
 * these rows sit inside narrow cards, and values like "On, but this stream is
 * 16-bit so it makes no difference" ran under the label — the two texts printed
 * over each other — whenever the pair together outgrew the width. The value
 * carries the larger share and wraps rather than clips, since half the point of
 * these lines is a sentence the reader is meant to finish.
 */
@Composable
internal fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            color = TextSecondary,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/**
 * A group of [StatusRow]s.
 *
 * Unboxed: a status panel only ever appears inside a [SettingsCard], which already
 * gives it a surface — a second, nested box around a handful of label/value lines
 * read as a card inside a card rather than as one grouped panel.
 */
@Composable
internal fun StatusPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
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
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = modifier.fillMaxWidth(),
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

/**
 * A picker for a list that is too long, or too unpredictable, to lay out flat.
 *
 * The alternative in this file is [com.engabd.sendpin.ui.design.SegmentedToggle], and
 * it is the right control for three or four fixed options — a stream format, a gain
 * mode. It is the wrong one the moment the options come from the *phone* rather than
 * from the app: a segmented row of every paired Bluetooth device is as wide as the
 * user's history with the device, and the entries past the edge simply cannot be
 * reached. This gives every option the same width and the same reach, however many
 * there are.
 *
 * [subtitles] runs parallel to [options] when a second line is worth showing — a
 * device's address under its name, so two speakers called "Car" can be told apart.
 */
@Composable
internal fun DropdownPicker(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    subtitles: List<String>? = null,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.getOrNull(selectedIndex)

    Box(modifier.fillMaxWidth()) {
        // A hairline outline, not a filled box — the card underneath is already the
        // surface. Kept (unlike ToggleRow's, dropped entirely) because a dropdown
        // needs a visible tap target its own chevron alone doesn't make obvious.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, if (open) accent.a(0.5f) else Hairline, RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                Text(
                    selected ?: placeholder,
                    color = if (selected != null) TextPrimary else TextMuted,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                subtitles?.getOrNull(selectedIndex)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1)
                }
            }
            Icon(
                Icons.Default.ArrowDropDown, if (open) "Close list" else "Open list",
                tint = TextMuted, modifier = Modifier.size(22.dp),
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // Tall enough to show a real list, short enough to leave the page
            // visible behind it. The menu scrolls past that on its own.
            modifier = Modifier.heightIn(max = 340.dp).background(Ink3),
        ) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                            Text(
                                option,
                                color = if (i == selectedIndex) accent else TextPrimary,
                                fontFamily = AppFont,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            subtitles?.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp)
                            }
                        }
                    },
                    trailingIcon = if (i == selectedIndex) {
                        { Icon(Icons.Default.Check, "Selected", tint = accent, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { open = false; onSelect(i) },
                )
            }
        }
    }
}

/**
 * A switch, what it is called, and — in as few words as it takes — what it does.
 *
 * [subtitle] is optional and capped at two lines. It used to be required and
 * uncapped, drawn at a bare 11sp with no line height, so a two-hundred-character
 * explanation beside a fixed-size switch produced a six-line row. The long form goes
 * in [info] now, behind a chip on the title. See [InfoChip].
 *
 * Unboxed, flush with the card's own edge: a [SettingsCard] is already the
 * surface, and a toggle only ever appears inside one. A row of its own background
 * and border read as a card nested in a card, which is what every row in this file
 * used to do — the whole reason [SettingsComponents.kt] exists in one place is so
 * that stops being true everywhere at once.
 */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    accent: Color,
    enabled: Boolean = true,
    info: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            TitleWithInfo(
                title,
                info,
                MaterialTheme.typography.titleLarge.copy(
                    color = if (enabled) TextPrimary else TextMuted,
                ),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = TextFaint,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/**
 * A slider with its value beside it, in the mono face the rest of the readouts use.
 *
 * Three files had written this same Row by hand — the crossfade length, the lyrics
 * offset and every numeric Music Assistant config entry — identically, down to the
 * 10dp gap. [format] is the only thing that ever differed.
 */
@Composable
internal fun SliderRow(
    value: Float,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HSlider(value, onChange, accented = true, modifier = Modifier.weight(1f))
        Text(
            format(value),
            color = TextSecondary,
            fontFamily = MonoFont,
            style = MaterialTheme.typography.labelMedium,
        )
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
