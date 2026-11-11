package com.engabd.sendpin.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.Platform
import com.engabd.sendpin.ma.Announcer
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.screens.settings.SegmentedToggleRow
import com.engabd.sendpin.ui.screens.settings.SliderRow
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel.SpeakerUi
import kotlin.math.roundToInt

/**
 * The compact card both Speakers-screen dialogs use, centred over the dimmed screen.
 *
 * Drawn inside the Speakers screen's own Box rather than as a Material dialog, so the
 * page behind keeps its look, and deliberately not draggable: the announce sheet's
 * drag-to-dismiss fought the hold-to-talk press and threw the sheet up mid-sentence.
 */
@Composable
internal fun BoxScope.SpeakersDialog(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.matchParentSize().background(Ink.a(0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .clip(shape).background(Ink2).border(1.dp, Hairline, shape)
                // Swallow taps so a miss near a control doesn't fall through to the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    subtitle?.let {
                        Text(it, color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }
            content()
        }
    }
}

/**
 * "Play announcement" on one speaker, typed (spoken by the phone's text-to-speech) or
 * held-to-talk on the microphone, shaped like Music Assistant's own dialog. "Add
 * speakers" sends the same clip to more. See [Announcer].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.AnnounceDialog(
    target: SpeakerUi,
    players: List<SpeakerUi>,
    onClose: () -> Unit,
    viewModel: SpeakersViewModel,
) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    val announcer = viewModel.announcer
    val status by announcer.status.collectAsStateWithLifecycle()
    val busy by announcer.busy.collectAsStateWithLifecycle()
    val recording by announcer.recording.collectAsStateWithLifecycle()

    // The tv build removes RECORD_AUDIO from its manifest, so it gets typed announcements only.
    val canRecord = remember { !Platform.isTelevision(context) }
    var speak by remember { mutableStateOf(canRecord) }
    var text by remember { mutableStateOf("") }
    var selected by remember(target.id) { mutableStateOf(setOf(target.id)) }
    var addOpen by remember { mutableStateOf(false) }
    var chime by remember { mutableStateOf(true) }
    var customVolume by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var micDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micDenied = !it }
    // Leaving mid-recording discards it rather than sending half a sentence.
    DisposableEffect(Unit) { onDispose { announcer.stopRecording(deliver = false) } }

    val chosen = (listOf(target) + players.filter { it.id != target.id }).filter { it.id in selected }
    fun request() = Announcer.Request(
        chosen.associate { it.id to it.name },
        chime,
        // Off sends no volume at all, so MA uses its own announcement volume.
        if (customVolume) (volume * 100).roundToInt() else null,
    )
    val ready = chosen.isNotEmpty() && !busy
    val others = players.filter { it.id != target.id }

    SpeakersDialog(
        title = "Play announcement",
        subtitle = if (chosen.size > 1) "${target.name} and ${chosen.size - 1} more" else target.name,
        onClose = onClose,
    ) {
        if (canRecord) {
            SegmentedToggleRow(listOf("Type", "Speak"), if (speak) 1 else 0) { if (!recording) speak = it == 1 }
        }
        ToggleRow(title = "Play chime first", checked = chime, accent = accent) { chime = it }
        ToggleRow(title = "Custom volume", checked = customVolume, accent = accent) { customVolume = it }
        if (customVolume) {
            SliderRow(
                volume,
                format = { "${(it * 100).roundToInt()}" },
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Announcement volume" },
            ) { volume = it }
        }

        if (speak) {
            HoldToTalk(
                recording = recording,
                enabled = ready,
                onStart = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        micDenied = false
                        announcer.startRecording(request())
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = { announcer.stopRecording() },
            )
            if (micDenied) {
                InfoNote(
                    "Microphone access was refused. Allow it for CAMusic in Android settings, or type the announcement instead.",
                    tone = InfoWarn,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OledField(text, { text = it }, label = "Announcement", placeholder = "Dinner is ready", accent = accent, modifier = Modifier.weight(1f))
                val canSend = ready && text.isNotBlank()
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (canSend) accent else Glass)
                        .clickable(enabled = canSend) { announcer.speak(text, request()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send announcement", tint = if (canSend) Ink else TextMuted, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (others.isNotEmpty()) {
            Row(
                Modifier.clip(RoundedCornerShape(100)).clickable { addOpen = !addOpen }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Add, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                Text(
                    if (addOpen) "Hide other speakers" else "Add speakers",
                    color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                )
            }
            if (addOpen) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { p ->
                        ToggleChip(p.name, p.id in selected) {
                            if (!recording) selected = if (p.id in selected) selected - p.id else selected + p.id
                        }
                    }
                }
            }
        }

        status?.let { InfoNote(it.text, tone = if (it.failed) InfoWarn else null) }
    }
}

/**
 * Hold to record, let go to send, like a walkie-talkie. The press is kept until the
 * finger lifts, wherever it drifts, and every move is consumed so the dialog's scroll
 * cannot take it over. A screen reader cannot hold a press, so for one a double tap
 * starts the recording and a second one sends it.
 */
@Composable
private fun HoldToTalk(recording: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val accent = LocalAccent.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(if (recording) accent else if (enabled) accent.a(0.14f) else Glass)
                .border(1.dp, if (recording) accent else if (enabled) accent.a(0.4f) else Hairline, CircleShape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        start()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        stop()
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = if (recording) "Recording. Activate again to send" else "Hold to talk, records a spoken announcement"
                    if (enabled) onClick { if (recording) stop() else start(); true } else disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, null, tint = if (recording) Ink else if (enabled) accent else TextMuted, modifier = Modifier.size(40.dp))
        }
        Text(
            if (recording) "Recording, let go to send" else "Hold to talk",
            color = if (recording) accent else TextSecondary,
            fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}
