package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
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
 * Scenes (a saved grouping with each speaker's volume, recalled in one tap) and Music
 * Assistant's permanent group speakers. Both are "which speakers play together", one
 * kept on this phone, the other on the server.
 *
 * Drawn inside the Speakers screen's own Box, like [SpeakerPickerSheet].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.SpeakerScenesSheet(
    players: List<SpeakerUi>,
    onClose: () -> Unit,
    viewModel: SpeakersViewModel,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)
    val accent = LocalAccent.current
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val status by viewModel.sceneStatus.collectAsStateWithLifecycle()

    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SpeakerScene?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf("") }
    var groupMembers by remember { mutableStateOf(emptySet<String>()) }
    // A group player cannot hold another, and the Light listener is not a speaker.
    val groupable = players.filter { !it.isGroup && !it.isListener }

    Box(
        Modifier.matchParentSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClose() },
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding()
            .dismissOnDragDown(onClose)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Ink2)
            .border(1.dp, Hairline, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = systemNavInset() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(100)).background(Hairline))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Scenes", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                SmallIconButton(Icons.Default.Close, "Close", onClick = onClose)
            }
            InfoNote(
                "A scene is which speakers are grouped and how loud each one is. Tap one to " +
                    "recall it; the music carries on, and speakers that are off are skipped.",
            )

            if (scenes.isEmpty()) {
                Text("No scenes yet.", color = TextMuted, fontSize = 13.sp)
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
                            .clickable(role = Role.Button, onClickLabel = "Recall") { viewModel.recallScene(scene) }
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
            OledButton("Save the speakers as a scene…", accent = accent, outline = true) { saving = true }
            status?.let { InfoNote(it) }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Group speakers")
            InfoNote(
                "A group speaker is a permanent group Music Assistant keeps as a speaker of its " +
                    "own, so it shows in every app, Home Assistant included. Needs an admin login.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groupable.forEach { p ->
                    ToggleChip(p.name, p.id in groupMembers) {
                        groupMembers = if (p.id in groupMembers) groupMembers - p.id else groupMembers + p.id
                    }
                }
            }
            OledField(groupName, { groupName = it }, label = "Group speaker name", placeholder = "Downstairs", accent = accent)
            OledButton(
                "Create group speaker", accent = accent,
                enabled = groupName.isNotBlank() && groupMembers.size >= 2,
            ) {
                viewModel.createGroupSpeaker(groupName.trim(), groupable.map { it.id }.filter { it in groupMembers })
                groupName = ""; groupMembers = emptySet()
            }
        }
    }

    if (saving) {
        NamePromptDialog(
            title = "Save a scene",
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
            title = "Rename scene",
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
