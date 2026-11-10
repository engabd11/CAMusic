package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.ma.SpeakerScene
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel.SpeakerUi

/**
 * Custom groups (a saved grouping with each speaker's volume, applied in one tap; a
 * [SpeakerScene] in code) and, under "More", Music Assistant's permanent group
 * speakers. Both are "which speakers play together", one kept on this phone, the
 * other on the server. The same centred card as [AnnounceDialog].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.CustomGroupsDialog(
    players: List<SpeakerUi>,
    onClose: () -> Unit,
    viewModel: SpeakersViewModel,
) {
    val accent = LocalAccent.current
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val status by viewModel.sceneStatus.collectAsStateWithLifecycle()
    val canCreate by viewModel.groupProviders.collectAsStateWithLifecycle()

    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SpeakerScene?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var groupMembers by remember { mutableStateOf(emptySet<String>()) }
    // A group player cannot hold another, and the Light listener is not a speaker.
    val groupable = players.filter { !it.isGroup && !it.isListener }
    // Asked only when the section opens: most visits never need it.
    LaunchedEffect(more) { if (more) viewModel.loadGroupProviders() }

    SpeakersDialog(title = "Custom groups", subtitle = null, onClose = onClose) {
        if (scenes.isEmpty()) {
            Text("No custom groups yet.", color = TextMuted, fontSize = 13.sp)
        }
        scenes.forEach { scene ->
            val confirming = deleting == scene.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass)
                    .border(1.dp, Hairline, RoundedCornerShape(14.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = "Apply") { viewModel.recallScene(scene) }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(
                        scene.name.ifBlank { "Untitled" }, color = TextPrimary,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        scene.members.joinToString(", ") { it.name },
                        color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                SmallIconButton(Icons.Default.Edit, "Rename ${scene.name}") { renaming = scene }
                Spacer(Modifier.width(6.dp))
                // Tap twice, like the other deletes in this app.
                SmallIconButton(
                    Icons.Default.DeleteOutline,
                    if (confirming) "Tap again to delete ${scene.name}" else "Delete ${scene.name}",
                    tint = if (confirming) ErrorRed else null,
                ) {
                    if (confirming) { deleting = null; viewModel.deleteScene(scene) } else deleting = scene.id
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        OledButton("Save current speakers as a group", accent = accent) { saving = true }
        status?.let { InfoNote(it) }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { more = !more }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "More: group speakers in Music Assistant", color = TextMuted, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            Icon(if (more) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        }
        if (more) {
            Text(
                "A permanent group Music Assistant shows as its own speaker, in every app. Needs an admin login.",
                color = TextFaint, fontSize = 11.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groupable.forEach { p ->
                    ToggleChip(p.name, p.id in groupMembers) {
                        groupMembers = if (p.id in groupMembers) groupMembers - p.id else groupMembers + p.id
                    }
                }
            }
            val provider = viewModel.groupProviderFor(groupMembers, canCreate)
            // Say what is missing rather than offer a button that will fail.
            if (groupMembers.size >= 2 && canCreate != null && provider == null) {
                InfoNote(
                    "Music Assistant can't group these permanently: they don't sync together, and its Universal Group provider isn't added.",
                    tone = InfoWarn,
                )
            }
            OledField(groupName, { groupName = it }, label = "Group speaker name", placeholder = "Downstairs", accent = accent)
            OledButton(
                "Create group speaker", accent = accent, outline = true,
                enabled = groupName.isNotBlank() && groupMembers.size >= 2 && provider != null,
            ) {
                viewModel.createGroupSpeaker(groupName.trim(), groupable.map { it.id }.filter { it in groupMembers })
                groupName = ""; groupMembers = emptySet()
            }
        }
    }

    if (saving) {
        NamePromptDialog(
            title = "Save as a custom group",
            note = "The speakers grouped now, and each one's volume.",
            initial = "",
            confirmLabel = "Save",
            accent = accent,
            onDismiss = { saving = false },
            onConfirm = { name -> saving = false; viewModel.saveScene(name) },
        )
    }
    renaming?.let { scene ->
        NamePromptDialog(
            title = "Rename custom group",
            note = "Only the name changes.",
            initial = scene.name,
            confirmLabel = "Rename",
            accent = accent,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; viewModel.renameScene(scene, name) },
        )
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, cd: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, tint = tint ?: TextSecondary, modifier = Modifier.size(16.dp)) }
}
