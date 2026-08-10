package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel
import com.engabd.sendpin.ui.viewmodel.SpeakersViewModel.SpeakerUi

@Composable
fun SpeakersScreen(onBack: () -> Unit = {}, viewModel: SpeakersViewModel = viewModel()) {
    val accent = LocalAccent.current
    val joined by viewModel.joined.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val groupVolume by viewModel.groupVolume.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val canGroup by viewModel.leaderCanGroup.collectAsStateWithLifecycle()
    val somethingPlaying by viewModel.leaderIsPlaying.collectAsStateWithLifecycle()

    val total = joined.size + available.size
    val allJoined = available.isEmpty() && joined.isNotEmpty()

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Header.
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Speakers", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        if (!connected) "Connecting to Music Assistant…" else "${joined.size} of $total players grouped",
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
                // Grouping needs a leader that supports it; Ungroup always works.
                if (allJoined || canGroup) {
                    Pill(if (allJoined) "Ungroup" else "Group all", allJoined) {
                        if (allJoined) viewModel.ungroupAll() else viewModel.groupAll()
                    }
                }
            }

            if (error != null) {
                ErrorRow(error!!)
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp)) {
                item {
                    val targetName = joined.firstOrNull { it.isTarget }?.name ?: "This group"
                    GroupHeroCard(groupVolume, joined.size, targetName) { viewModel.setGroupVolume(it) }
                    Spacer(Modifier.height(22.dp))
                }

                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionLabel("In this group")
                        Text("${joined.size} active", color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                items(joined, key = { it.id }) { p ->
                    JoinedCard(
                        p,
                        onSelect = { viewModel.selectPlayer(p.id) },
                        onUnjoin = { viewModel.unjoin(p.id) },
                        onVol = { viewModel.setPlayerVolume(p.id, it) },
                        onOffset = { viewModel.changeOffset(p.id, it) },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (available.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        SectionLabel("Available on this network")
                        Spacer(Modifier.height(10.dp))
                        // Sendspin players (this phone included) can't lead a group, so
                        // say so up front rather than letting Join fail on the first tap.
                        if (!canGroup) {
                            NoteRow("This player can't lead a group. Tap a speaker's name to play there, then Join the rest to it.")
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    items(available, key = { it.id }) { p ->
                        FreeCard(
                            p,
                            canJoin = canGroup,
                            canMoveMusic = somethingPlaying,
                            onPlayHere = { viewModel.selectPlayer(p.id) },
                            onJoin = { viewModel.join(p.id) },
                            onMoveMusic = { viewModel.transferQueueTo(p.id) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }

                item {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(14.dp))
                                .clickable { viewModel.playHereOnly() }.padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Play here only", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(accent).clickable(onClick = onBack).padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Done", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(msg: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(WarnAmber.a(0.12f)).border(1.dp, WarnAmber.a(0.3f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = WarnAmber, modifier = Modifier.size(16.dp))
        Text(msg, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun GroupHeroCard(master: Float, activeCount: Int, title: String, onMaster: (Float) -> Unit) {
    val accent = LocalAccent.current
    GlassCard(radius = 22.dp) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.a(0.14f)).border(1.dp, accent.a(0.35f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Speaker, null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text(title, color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$activeCount ${if (activeCount == 1) "player" else "players"} synced", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100)).padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                    Text("LIVE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("Group volume")
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Icon(Icons.Default.VolumeUp, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                HSlider(master, onMaster, modifier = Modifier.weight(1f), trackHeight = 5.dp, knob = 15.dp)
                Text("${(master * 100).toInt()}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun JoinedCard(p: SpeakerUi, onSelect: () -> Unit, onUnjoin: () -> Unit, onVol: (Float) -> Unit, onOffset: (Int) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.a(0.08f)).border(1.dp, accent.a(0.28f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(p.isSelf, true)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f).clickable(onClick = onSelect)) {
                    Text(p.name, color = TextPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p.isTarget) "Playing here" else p.meta, color = if (p.isTarget) accent else TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
                // In the group, so the switch is on. The leader is the group — it
                // cannot leave itself — so its switch is disabled rather than absent,
                // which keeps the column of switches aligned and says why.
                GroupSwitch(
                    checked = true,
                    enabled = !p.isTarget,
                    onCheckedChange = { onUnjoin() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.VolumeUp, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                HSlider(p.volume, onVol, modifier = Modifier.weight(1f))
                Text("${(p.volume * 100).toInt()}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            // Sync offset only for members (the active/leader player is the reference clock).
            if (!p.isTarget) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sync offset", color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    StepBtn("−") { onOffset(-5) }
                    Text(if (p.offsetKnown) "${p.offsetMs} ms" else "-", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 54.dp))
                    StepBtn("+") { onOffset(5) }
                }
            }
        }
    }
}

@Composable
private fun FreeCard(
    p: SpeakerUi,
    canJoin: Boolean,
    canMoveMusic: Boolean,
    onPlayHere: () -> Unit,
    onJoin: () -> Unit,
    onMoveMusic: () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Glass).border(1.dp, HairlineSoft, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIcon(p.isSelf, false)
        Spacer(Modifier.width(11.dp))
        // Tap the name to make this the active play-to player.
        Column(Modifier.weight(1f).clickable(onClick = onPlayHere)) {
            Text(p.name, color = TextSecondary, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Tap to play here · ${p.meta}", color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Distinct from "tap to play here", which only redirects what you play
        // *next*: this carries the queue and the playhead across, so the track
        // keeps going on the new speaker. Only offered when there is something
        // playing to move.
        if (canMoveMusic) {
            CircleIconButton(Icons.Default.MusicNote, "Move music here", onMoveMusic)
            Spacer(Modifier.width(8.dp))
        }
        if (canJoin) {
            GroupSwitch(checked = false, enabled = true, onCheckedChange = { onJoin() })
        }
    }
}

/**
 * In the group, or not — as a switch rather than a Join/Unjoin button.
 *
 * Group membership is a state, not an action, and a button had to name the *next*
 * state to be useful ("Join" when out, "Unjoin" when in) which meant the label
 * contradicted the row it sat on. A switch shows the state it is in and needs no
 * label at all, which is also how every speaker-grouping UI on the platform behaves.
 *
 * Tinted from the album accent like the rest of the screen rather than left on the
 * M3 primary, so it belongs to the artwork the way the sliders and pills do.
 */
@Composable
private fun GroupSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink,
            checkedTrackColor = accent,
            checkedBorderColor = accent,
            uncheckedThumbColor = TextMuted,
            uncheckedTrackColor = Glass,
            uncheckedBorderColor = Hairline,
            // The leader's switch: on, and not for turning off. Dimmed rather than
            // greyed to neutral, so it still reads as part of the group.
            disabledCheckedThumbColor = Ink,
            disabledCheckedTrackColor = accent.a(0.45f),
            disabledCheckedBorderColor = accent.a(0.45f),
        ),
    )
}

/** A quiet inline explanation — not an error, just why a control isn't there. */
@Composable
private fun NoteRow(msg: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(12.dp)).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.Info, null, tint = TextFaint, modifier = Modifier.size(15.dp))
        Text(msg, color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun PlayerIcon(isSelf: Boolean, joined: Boolean) {
    val accent = LocalAccent.current
    val icon = if (isSelf) Icons.Default.Smartphone else Icons.Default.Speaker
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
            .background(if (joined) accent.a(0.14f) else Glass)
            .border(1.dp, if (joined) accent.a(0.35f) else Hairline, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (joined) accent else TextMuted, modifier = Modifier.size(17.dp)) }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass).border(1.dp, Hairline, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, cd, tint = TextSecondary, modifier = Modifier.size(17.dp))
    }
}
