package com.engabd.sendpin.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.Platform
import com.engabd.sendpin.ma.Announcer
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.OledField
import com.engabd.sendpin.ui.screens.settings.SliderRow
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel.SpeakerUi

/**
 * "Announce": say something on chosen Music Assistant speakers, typed (spoken by the
 * phone's text-to-speech) or held-to-talk on the microphone. See [Announcer].
 *
 * Drawn inside the Speakers screen's own Box like [SpeakerPickerSheet], for the same
 * reason: a Material scrim would flatten the page behind it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.AnnounceSheet(
    players: List<SpeakerUi>,
    onClose: () -> Unit,
    viewModel: SpeakersViewModel,
) {
    HideBottomChrome()
    BackHandler(onBack = onClose)
    val accent = LocalAccent.current
    val context = LocalContext.current
    val announcer = viewModel.announcer
    val status by announcer.status.collectAsStateWithLifecycle()
    val busy by announcer.busy.collectAsStateWithLifecycle()
    val recording by announcer.recording.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }
    // Starts on the speaker music is going to, which is the likeliest one to talk to.
    var selected by remember { mutableStateOf(players.filter { it.isTarget }.map { it.id }.toSet()) }
    var chime by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var micDenied by remember { mutableStateOf(false) }

    // The tv build removes RECORD_AUDIO from its manifest, so it gets typed announcements only.
    val canRecord = remember { !Platform.isTelevision(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micDenied = !it }
    // Leaving mid-recording discards it rather than sending half a sentence.
    DisposableEffect(Unit) { onDispose { announcer.stopRecording(deliver = false) } }

    fun request() = Announcer.Request(
        players.filter { it.id in selected }.associate { it.id to it.name },
        chime,
        (volume * 100).toInt(),
    )
    val ready = selected.isNotEmpty() && !busy

    // Anything visible above the sheet dismisses it.
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
            .dismissOnDragDown(onClose, enabled = !recording)
            // Swallow taps so a miss near a control doesn't fall through to the scrim.
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
                    "Announce", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Glass).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
            }
            InfoNote(
                "Music Assistant fetches the announcement from this phone, so the server has to be able to " +
                    "reach this phone on your local network. Music resumes afterwards.",
            )

            SectionLabel("Speakers")
            if (players.isEmpty()) {
                Text("No players available.", color = TextMuted, fontSize = 13.sp)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    players.forEach { p ->
                        ToggleChip(p.name, p.id in selected) {
                            selected = if (p.id in selected) selected - p.id else selected + p.id
                        }
                    }
                }
            }

            OledField(text, { text = it }, label = "Type an announcement", placeholder = "Dinner is ready", accent = accent)
            OledButton("Announce", accent = accent, enabled = ready && text.isNotBlank() && !recording) {
                announcer.speak(text, request())
            }
            if (canRecord) {
                TalkButton(
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
                        "Microphone access was refused, so a spoken announcement can't be recorded. " +
                            "Allow the microphone for CAMusic in Android settings, or type the announcement instead.",
                        tone = InfoWarn,
                    )
                }
            }

            ToggleRow(
                title = "Chime first",
                subtitle = "Music Assistant's alert sound before the message",
                checked = chime,
                accent = accent,
            ) { chime = it }
            SectionLabel("Announcement volume")
            SliderRow(
                volume,
                format = { "${(it * 100).toInt()}" },
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Announcement volume" },
            ) { volume = it }

            status?.let { InfoNote(it.text, tone = if (it.failed) InfoWarn else null) }
        }
    }
}

/**
 * Hold to record, let go to send, like a walkie-talkie. A screen reader cannot hold a
 * press, so for one a double tap starts the recording and a second one sends it.
 */
@Composable
private fun TalkButton(recording: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val accent = LocalAccent.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (recording) accent else Glass)
            .border(1.dp, if (recording) accent else Hairline, RoundedCornerShape(13.dp))
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = { start(); tryAwaitRelease(); stop() })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (recording) "Recording. Activate again to send" else "Hold to talk, records a spoken announcement"
                if (enabled) onClick { if (recording) stop() else start(); true } else disabled()
            }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Mic, null, tint = if (recording) Ink else if (enabled) TextSecondary else TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (recording) "Recording, let go to send" else "Hold to talk",
            color = if (recording) Ink else if (enabled) TextSecondary else TextMuted,
            fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
        )
    }
}
