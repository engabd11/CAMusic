package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

private data class SpeakerModel(
    val name: String, val icon: ImageVector, val meta: String,
    val joined: Boolean, val vol: Float, val offset: Int,
)

@Composable
fun SpeakersScreen(onBack: () -> Unit = {}) {
    val accent = LocalAccent.current
    val players = remember {
        mutableStateListOf(
            SpeakerModel("Home sendspin", Icons.Default.Speaker, "Living room · lossless", true, 0.27f, 0),
            SpeakerModel("JBL Go 3", Icons.Default.Bluetooth, "On the go · Bluetooth", true, 0.27f, 12),
            SpeakerModel("PC sendspin cli", Icons.Default.Computer, "Study · wired", true, 0.23f, 0),
            SpeakerModel("Kitchen sendspin", Icons.Default.Speaker, "Kitchen · idle", false, 0.18f, 0),
            SpeakerModel("Lounge Room", Icons.Default.Speaker, "Lounge · idle", false, 0.20f, 0),
        )
    }
    var master by remember { mutableStateOf(0.27f) }
    val joinedCount = players.count { it.joined }
    val allJoined = joinedCount == players.size

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)

        Column(Modifier.fillMaxSize()) {
            // Header.
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Speakers", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("$joinedCount of ${players.size} players grouped", color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Pill(if (allJoined) "Ungroup" else "Group all", allJoined, {
                    val target = !allJoined
                    for (i in players.indices) players[i] = players[i].copy(joined = target)
                })
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp)) {
                item { GroupHeroCard(master) { master = it } ; Spacer(Modifier.height(22.dp)) }

                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionLabel("In this group")
                        Text("$joinedCount active", color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                itemsIndexed(players.filter { it.joined }, players) { i, p ->
                    JoinedCard(p, onUnjoin = { players[i] = p.copy(joined = false) },
                        onVol = { players[i] = p.copy(vol = it) },
                        onOffset = { players[i] = p.copy(offset = (p.offset + it)) })
                    Spacer(Modifier.height(12.dp))
                }

                item { Spacer(Modifier.height(10.dp)); SectionLabel("Available on this network"); Spacer(Modifier.height(10.dp)) }
                itemsIndexed(players.filter { !it.joined }, players) { i, p ->
                    FreeCard(p, onJoin = { players[i] = p.copy(joined = true) })
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(14.dp))
                                .clickable { for (i in players.indices) players[i] = players[i].copy(joined = i == 0) }.padding(14.dp),
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

// LazyListScope helper that indexes into the shared list so edits map back.
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    subset: List<T>, backing: List<T>, block: @Composable (Int, T) -> Unit,
) {
    items(subset.size) { idx ->
        val value = subset[idx]
        val realIndex = backing.indexOf(value)
        if (realIndex >= 0) block(realIndex, value)
    }
}

@Composable
private fun GroupHeroCard(master: Float, onMaster: (Float) -> Unit) {
    val accent = LocalAccent.current
    GlassCard(radius = 22.dp) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.a(0.14f)).border(1.dp, accent.a(0.35f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Speaker, null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Home sendspin", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("synced", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
private fun JoinedCard(p: SpeakerModel, onUnjoin: () -> Unit, onVol: (Float) -> Unit, onOffset: (Int) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.a(0.08f)).border(1.dp, accent.a(0.28f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(p.icon, true)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.meta, color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100)).clickable(onClick = onUnjoin).padding(horizontal = 13.dp, vertical = 7.dp)) {
                    Text("Unjoin", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.VolumeUp, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                HSlider(p.vol, onVol, modifier = Modifier.weight(1f))
                Text("${(p.vol * 100).toInt()}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync offset", color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                StepBtn("−") { onOffset(-5) }
                Text("${p.offset} ms", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 54.dp))
                StepBtn("+") { onOffset(5) }
            }
        }
    }
}

@Composable
private fun FreeCard(p: SpeakerModel, onJoin: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Glass).border(1.dp, HairlineSoft, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIcon(p.icon, false)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.meta, color = TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(100)).background(accent.a(0.16f)).border(1.dp, accent.a(0.55f), RoundedCornerShape(100)).clickable(onClick = onJoin).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Join", color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlayerIcon(icon: ImageVector, joined: Boolean) {
    val accent = LocalAccent.current
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
