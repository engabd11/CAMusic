package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.hue.GenrePresetRule
import com.engabd.sendpin.hue.ShowPreset
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.theme.*

/**
 * Saved shows: apply one, save the current one, and optionally let the genre of
 * whatever is playing pick between them.
 *
 * A chip per preset rather than a list, because applying one is the common action
 * by a wide margin and everything else — rename, delete, tie it to a genre — is
 * rare enough to live behind a long-press.
 *
 * Split out of `LightSyncScreen.kt` when it grew a built-in show and a live
 * highlight: ten callbacks and three dialogs is a file of its own, and it was the
 * part of that one people were actually reading.
 */
@Composable
internal fun SavedShows(
    presets: List<ShowPreset>,
    rules: List<GenrePresetRule>,
    genreAuto: Boolean,
    /**
     * The preset whose show is the one currently on the room, or null when the
     * tuning matches none of them.
     *
     * Derived by comparing every preset against the live settings — see
     * [ShowPreset.matches] — rather than remembered from the last tap. Remembering
     * the tap was the previous answer and it was wrong three ways: it stayed lit
     * after a slider moved, it was lost the moment the Lights tab was left, and it
     * said nothing at all when a genre rule applied a preset by itself.
     */
    activeId: String?,
    accent: Color,
    onApply: (ShowPreset) -> Unit,
    onSave: (String) -> Unit,
    onRename: (ShowPreset, String) -> Unit,
    onDelete: (ShowPreset) -> Unit,
    onGenreAuto: (Boolean) -> Unit,
    onAddRule: (String, ShowPreset) -> Unit,
    onRemoveRule: (GenrePresetRule) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShowPreset?>(null) }

    SectionLabel("Saved shows")
    Spacer(Modifier.height(2.dp))
    Text(
        "The whole show - intensity, palette, brightness, layers and tunables - under one " +
            "name. Applying one never turns the lights on or off, or moves them to another " +
            "room. Default is the show as it ships, and is always here to come back to.",
        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            PresetChip(
                preset = preset,
                accent = accent,
                active = activeId == preset.id,
                onClick = { onApply(preset) },
                onLongClick = { editing = preset },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    OledButton("Save this show as…", accent = accent, outline = true) { saving = true }
    Spacer(Modifier.height(4.dp))
    Note(
        "Hold a show for its options - delete it, rename it, or tie it to a genre. A " +
            "highlighted show is the one the room is on right now; change any tuning and " +
            "the highlight clears.",
    )

    Spacer(Modifier.height(14.dp))
    FeatureRow(
        title = "Pick a show by genre",
        gist = "Change the room to match what is playing.",
        info = "When a track starts, its genre is matched against the rules you have set and " +
            "that show is applied. First matching rule wins, so the order you add them in " +
            "is the priority order.\n\nMatching is loose in both directions, because no two " +
            "servers agree on genre strings: a rule for \"jazz\" catches \"Vocal Jazz\", and a " +
            "rule for \"Progressive House\" is caught by a track tagged \"house\".\n\nA track " +
            "with no genre, or one nothing matches, leaves the room exactly as it is - " +
            "rather than resetting to a default halfway through a record.\n\nTip: two or " +
            "three broad rules beat a dozen narrow ones. The point is that a quiet album " +
            "does not light the room like a club.",
        checked = genreAuto,
        unavailable = if (rules.isEmpty()) "Add a rule first - hold a show above." else null,
    ) { on -> onGenreAuto(on) }

    if (rules.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        rules.forEach { rule ->
            val name = presets.firstOrNull { it.id == rule.presetId }?.name ?: "(deleted)"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${rule.genre}  →  $name",
                    color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                CircleBtn(Icons.Default.Close, "Remove rule") { onRemoveRule(rule) }
            }
        }
    }

    if (saving) {
        NamePromptDialog(
            title = "Save this show",
            note = "Everything on this screen as it is right now, under a name.",
            initial = "",
            confirmLabel = "Save",
            accent = accent,
            onDismiss = { saving = false },
            onConfirm = { name -> saving = false; onSave(name) },
        )
    }

    editing?.let { preset ->
        PresetActionsDialog(
            preset = preset,
            accent = accent,
            onDismiss = { editing = null },
            onRename = { name -> editing = null; onRename(preset, name) },
            onDelete = { editing = null; onDelete(preset) },
            onAddRule = { genre -> editing = null; onAddRule(genre, preset) },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    preset: ShowPreset,
    accent: Color,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) accent.a(0.16f) else Glass)
            .border(
                1.dp,
                if (active) accent.a(0.45f) else Hairline,
                RoundedCornerShape(14.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            preset.name.ifBlank { "Untitled" },
            color = if (active) accent else TextPrimary,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            preset.summary(),
            color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NamePromptDialog(
    title: String,
    note: String,
    initial: String,
    confirmLabel: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OledField(name, { name = it }, "Name", "e.g. Dinner", accent)
                Note(note)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    confirmLabel,
                    color = if (name.isBlank()) TextFaint else accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}

/**
 * A show's options: delete it, rename it, or tie it to a genre.
 *
 * One dialog for all three because they are all rare — a preset is applied dozens of
 * times for every once it is edited, which is why the chip's tap does the applying
 * and everything here is behind a hold.
 *
 * Delete leads, because it is what a hold is most often for and it used to sit
 * underneath two text fields that had nothing to do with it.
 *
 * The built-in Default ([ShowPreset.DEFAULT_ID]) can be neither renamed nor deleted:
 * it is not a saved show, it is the app's own defaults wearing a chip, and there is
 * nothing to store a new name in. A genre rule may still point at it — going back to
 * the shipped show for a certain kind of record is a reasonable thing to want.
 */
@Composable
private fun PresetActionsDialog(
    preset: ShowPreset,
    accent: Color,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddRule: (String) -> Unit,
) {
    val builtIn = preset.id == ShowPreset.DEFAULT_ID
    var name by remember { mutableStateOf(preset.name) }
    var genre by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                preset.name.ifBlank { "Untitled" },
                color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note(preset.summary())
                if (builtIn) {
                    Note(
                        "The show as it ships. It cannot be renamed or deleted, and it is " +
                            "always the way back from a preset.",
                    )
                } else {
                    OledButton(
                        if (confirmDelete) "Tap again to delete" else "Delete this show",
                        accent = accent, danger = true,
                    ) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                    OledField(name, { name = it }, "Name", "e.g. Dinner", accent)
                }
                OledField(genre, { genre = it }, "Use for genre (optional)", "e.g. jazz", accent)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A genre typed here is the reason the dialog was opened; a name
                    // change alone is the other reason. Both at once is fine.
                    if (genre.isNotBlank()) onAddRule(genre)
                    else if (!builtIn && name.trim() != preset.name) onRename(name.trim())
                    else onDismiss()
                },
                enabled = builtIn || name.isNotBlank(),
            ) {
                Text(
                    "Done",
                    color = if (!builtIn && name.isBlank()) TextFaint else accent,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
    )
}
