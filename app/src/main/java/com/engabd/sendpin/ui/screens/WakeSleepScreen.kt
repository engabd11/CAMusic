package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.ui.design.AmbientRain
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.CircleIconButton
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.Pill
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.SliderRow
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.accentTextFieldColors
import com.engabd.sendpin.ui.viewmodel.RoomsState
import com.engabd.sendpin.ui.viewmodel.WakeSleepViewModel
import com.engabd.sendpin.wake.AlarmMath
import com.engabd.sendpin.wake.WakeAlarm
import com.engabd.sendpin.wake.WakeSource
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Locale

/**
 * Wake & Sleep: sunrise alarms with the lights and music, and the bedtime wind-down.
 *
 * Honest about what it needs: the lights are a Hue Bridge on the same network as this
 * phone at the moment the alarm runs. Music works anywhere the library does.
 */
@Composable
fun WakeSleepScreen(onBack: () -> Unit, viewModel: WakeSleepViewModel = viewModel()) {
    val accent = LocalAccent.current
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<WakeAlarm?>(null) }
    var isNew by remember { mutableStateOf(false) }

    BackHandler(enabled = editing != null) { editing = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)
        AmbientRain(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") {
                    if (editing != null) editing = null else onBack()
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Wake & Sleep", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        if (editing != null) (if (isNew) "New alarm" else "Edit alarm") else nextSummary(alarms),
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
            }

            val current = editing
            if (current != null) {
                AlarmEditor(
                    initial = current,
                    viewModel = viewModel,
                    isNew = isNew,
                    onSave = { viewModel.save(it); editing = null },
                    onDelete = { viewModel.delete(it.id); editing = null },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarInset() + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "alarms") {
                        SettingsCard(
                            title = "Sunrise alarms",
                            lead = "The room brightens from deep red-orange to bright daylight over the ramp you choose, and the music fades in just before the alarm.",
                        ) {
                            if (alarms.isEmpty()) {
                                Text("No alarms yet. Add one to wake gently.", color = TextMuted, fontFamily = AppFont)
                            }
                            alarms.sortedBy { it.hour * 60 + it.minute }.forEach { a ->
                                AlarmRow(a, onOpen = { editing = a; isNew = false }, onToggle = { viewModel.setEnabled(a, it) })
                            }
                            OledButton("Add alarm", accent, modifier = Modifier.fillMaxWidth()) {
                                editing = viewModel.blank(); isNew = true
                            }
                        }
                    }
                    item(key = "winddown") { WindDownCard(viewModel) }
                    item(key = "honesty") {
                        Note(
                            "The lights need a Hue Bridge on the same network as this phone when the alarm runs. " +
                                "If it can't be reached, the music still plays and the alarm tells you the lights were skipped. " +
                                "While Light Sync is streaming to the lamps, the sunrise steps aside so the two don't fight.",
                        )
                    }
                }
            }
        }
    }
}

private fun nextSummary(alarms: List<WakeAlarm>): String {
    val now = System.currentTimeMillis()
    val next = AlarmMath.next(alarms, now, ZoneId.systemDefault()) ?: return "No alarm set"
    val (a, at) = next
    return "Next: ${timeText(a)} in ${AlarmMath.untilText(now, at)}"
}

private fun timeText(a: WakeAlarm) = String.format(Locale.getDefault(), "%02d:%02d", a.hour, a.minute)

@Composable
private fun AlarmRow(alarm: WakeAlarm, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val parts = buildList {
        add(AlarmMath.daysText(alarm.days))
        if (alarm.label.isNotBlank()) add(alarm.label)
        if (alarm.hasMusic) add(alarm.source.name.ifBlank { sourceTitle(alarm.source.kind) })
        if (alarm.hasLights) add("${alarm.roomName.ifBlank { "Lights" }}, ${alarm.rampMinutes} min ramp")
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                timeText(alarm),
                color = if (alarm.enabled) TextPrimary else TextMuted,
                fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
            )
            Text(parts.joinToString("  ·  "), color = TextFaint, fontFamily = AppFont, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Pill(if (alarm.enabled) "On" else "Off", alarm.enabled) { onToggle(!alarm.enabled) }
    }
}

private fun sourceTitle(kind: WakeSource.Kind) = when (kind) {
    WakeSource.Kind.NONE -> "No music"
    WakeSource.Kind.ITEM -> "Playlist or album"
    WakeSource.Kind.RESUME -> "Resume last"
    WakeSource.Kind.AMBIENCE -> "Ambience scene"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(
    initial: WakeAlarm,
    viewModel: WakeSleepViewModel,
    isNew: Boolean,
    onSave: (WakeAlarm) -> Unit,
    onDelete: (WakeAlarm) -> Unit,
) {
    val accent = LocalAccent.current
    var alarm by remember(initial.id) { mutableStateOf(initial) }
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.clearResults() }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarInset() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "time") {
            SettingsCard(title = "Time") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = time) }
                OutlinedTextField(
                    value = alarm.label,
                    onValueChange = { alarm = alarm.copy(label = it.take(40)) },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    colors = accentTextFieldColors(accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "days") {
            SettingsCard(title = "Repeat", lead = "Leave every day off for a one-off alarm.") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val on = (alarm.days and WakeAlarm.bit(d)) != 0
                        Pill(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, on) {
                            alarm = alarm.copy(days = alarm.days xor WakeAlarm.bit(d))
                        }
                    }
                }
            }
        }
        item(key = "music") {
            SettingsCard(title = "Music") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WakeSource.Kind.entries.forEach { k ->
                        Pill(sourceTitle(k), alarm.source.kind == k) {
                            alarm = alarm.copy(source = WakeSource(kind = k))
                        }
                    }
                }
                when (alarm.source.kind) {
                    WakeSource.Kind.ITEM -> {
                        if (alarm.source.name.isNotBlank()) {
                            Text("Waking to ${alarm.source.name}", color = TextPrimary, fontFamily = AppFont)
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search a playlist or album") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
                            colors = accentTextFieldColors(accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OledButton("Search", accent, outline = true, modifier = Modifier.fillMaxWidth()) { viewModel.search(query) }
                        if (searching) Text("Searching...", color = TextMuted, fontFamily = AppFont)
                        results?.let { list ->
                            if (list.isEmpty() && !searching) {
                                Text("Nothing found. Try another word.", color = TextMuted, fontFamily = AppFont)
                            }
                            list.forEach { pick ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        alarm = alarm.copy(source = WakeSource(WakeSource.Kind.ITEM, pick.mediaId, pick.title))
                                        viewModel.clearResults()
                                    }.padding(vertical = 8.dp),
                                ) {
                                    Text(pick.title, color = TextPrimary, fontFamily = AppFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (pick.subtitle.isNotBlank()) {
                                        Text(pick.subtitle, color = TextFaint, fontFamily = AppFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    WakeSource.Kind.AMBIENCE -> Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AmbienceEffect.entries.forEach { e ->
                            Pill(e.title, alarm.source.ambience == e.wire) {
                                alarm = alarm.copy(source = WakeSource(WakeSource.Kind.AMBIENCE, name = e.title, ambience = e.wire))
                            }
                        }
                    }
                    WakeSource.Kind.RESUME -> Note("Carries on with whatever was playing last.")
                    WakeSource.Kind.NONE -> Note("Lights only.")
                }
                if (alarm.hasMusic) {
                    Text("Fade in over", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WakeAlarm.FADE_CHOICES.forEach { m ->
                            Pill("$m min", alarm.fadeInMinutes == m) { alarm = alarm.copy(fadeInMinutes = m) }
                        }
                    }
                    Text("Volume it settles at", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
                    SliderRow(
                        value = alarm.volume / 100f,
                        format = { "${(it * 100).toInt()}%" },
                    ) { alarm = alarm.copy(volume = (it * 100).toInt().coerceIn(5, 100)) }
                }
            }
        }
        item(key = "lights") {
            SettingsCard(
                title = "Lights",
                lead = "A Hue room or zone brightens on the bridge itself, so your phone doesn't need to stay connected.",
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("No lights", alarm.roomId.isBlank()) { alarm = alarm.copy(roomId = "", roomName = "") }
                    if (alarm.roomId.isNotBlank()) Pill(alarm.roomName.ifBlank { "Chosen room" }, true) {}
                }
                RoomChoices(rooms, viewModel) { g -> alarm = alarm.copy(roomId = g.groupedLightId, roomName = g.name) }
                if (alarm.roomId.isNotBlank()) {
                    Text("Sunrise length", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WakeAlarm.RAMP_CHOICES.forEach { m ->
                            Pill("$m min", alarm.rampMinutes == m) { alarm = alarm.copy(rampMinutes = m) }
                        }
                    }
                }
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OledButton("Save alarm", accent, modifier = Modifier.fillMaxWidth()) {
                    onSave(alarm.copy(hour = time.hour, minute = time.minute, enabled = true))
                }
                if (!isNew) {
                    OledButton("Delete alarm", accent, danger = true, modifier = Modifier.fillMaxWidth()) { onDelete(alarm) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RoomChoices(rooms: RoomsState, viewModel: WakeSleepViewModel, onPick: (com.engabd.sendpin.hue.HueGroup) -> Unit) {
    val accent = LocalAccent.current
    when (rooms) {
        RoomsState.Idle -> OledButton("Find my rooms", accent, outline = true, modifier = Modifier.fillMaxWidth()) { viewModel.loadRooms() }
        RoomsState.Loading -> Text("Asking your bridge...", color = TextMuted, fontFamily = AppFont)
        is RoomsState.Failed -> {
            Note(rooms.message, warn = true)
            OledButton("Try again", accent, outline = true, modifier = Modifier.fillMaxWidth()) { viewModel.loadRooms() }
        }
        is RoomsState.Ready -> {
            if (rooms.groups.isEmpty()) Text("No rooms or zones found on this bridge.", color = TextMuted, fontFamily = AppFont)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.groups.forEach { g -> Pill(g.name, false) { onPick(g) } }
            }
        }
    }
}

@Composable
private fun WindDownCard(viewModel: WakeSleepViewModel) {
    val accent = LocalAccent.current
    val dim by viewModel.windDownDim.collectAsStateWithLifecycle()
    val roomId by viewModel.windDownRoomId.collectAsStateWithLifecycle()
    val roomName by viewModel.windDownRoomName.collectAsStateWithLifecycle()
    val scene by viewModel.windDownAmbience.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()

    SettingsCard(
        title = "Wind down",
        lead = "Applies whenever you start the sleep timer from the player.",
    ) {
        ToggleRow(
            title = "Dim the lights too",
            subtitle = "Warm amber now, then slowly down to 1% over the timer, and off at the end. A room that is already off stays off.",
            checked = dim,
            accent = accent,
            onChange = { viewModel.setWindDownDim(it) },
        )
        if (dim) {
            Text(
                if (roomId.isBlank()) "Choose a room" else "Room: $roomName",
                color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
            )
            RoomChoices(rooms, viewModel) { viewModel.setWindDownRoom(it) }
            Note("Cancelling the timer freezes the fade where it has reached. The colour stays amber until you change it.")
        }
        Text("When the music ends", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Nothing", scene.isBlank()) { viewModel.setWindDownAmbience("") }
            AmbienceEffect.entries.forEach { e ->
                Pill(e.title, scene == e.wire) { viewModel.setWindDownAmbience(e.wire) }
            }
        }
        if (scene.isNotBlank()) {
            Note("The scene starts while the app is still open in the background. If the system has closed it, the scene is skipped.")
        }
    }
}
