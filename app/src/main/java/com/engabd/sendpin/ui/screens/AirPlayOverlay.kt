package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.theme.inkOn
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.ErrorRed
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.GlassStrong
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.AirPlayViewModel

/**
 * The AirPlay control in Now Playing's top-left corner.
 *
 * A glass disc with the AirPlay glyph when nothing is connected; the glyph on the
 * accent with the receiver's name beside it once a session is up, so the corner
 * says where the sound is going without opening anything. Absent entirely when the
 * native sender is not available on this build.
 */
@Composable
internal fun AirPlayButton(viewModel: AirPlayViewModel, onTap: () -> Unit) {
    if (!viewModel.available) return
    val accent = LocalAccent.current
    val active by viewModel.active.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val name by viewModel.deviceName.collectAsStateWithLifecycle()
    Row(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (active) accent else GlassStrong)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Airplay, "AirPlay",
            tint = if (active) Ink else inkOn(0.85f),
            modifier = Modifier.size(14.dp),
        )
        if (connected && !name.isNullOrBlank()) {
            Text(
                name!!, color = Ink, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
    }
}

/**
 * The receiver picker: what is on the network, what is playing where, and the PIN
 * a receiver shows the first time. Same shape as the quality and device cards —
 * a dimmed page with the card in the middle, dismissed by a tap outside.
 */
@Composable
internal fun BoxScope.AirPlayOverlay(
    visible: Boolean,
    viewModel: AirPlayViewModel,
    /** This phone is decoding — the only case in which a receiver hears anything. */
    localSession: Boolean,
    onDismiss: () -> Unit,
) {
    if (visible) BackHandler { onDismiss() }
    // Browse only while the card is up: mDNS on a phone is not free.
    DisposableEffect(visible) {
        if (visible) viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()),
        exit = fadeOut(Motion.effects()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = 0.72f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), 0.92f),
        exit = fadeOut(Motion.effects()) + scaleOut(Motion.effects(), 0.96f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
            AirPlayCard(viewModel, localSession, onDismiss)
        }
    }
}

@Composable
private fun AirPlayCard(viewModel: AirPlayViewModel, localSession: Boolean, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val waitingForPin by viewModel.waitingForPin.collectAsStateWithLifecycle()
    val pinDevice by viewModel.pinDeviceName.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.padding(24.dp)) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(shape)
                .background(Ink2)
                .border(1.dp, Hairline, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Airplay, null, tint = accent, modifier = Modifier.size(20.dp))
                Text("AirPlay", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }

            if (waitingForPin) {
                PinEntry(pinDevice ?: deviceName ?: "the receiver") { viewModel.submitPin(it) }
                return@Column
            }

            if (!localSession) {
                Text(
                    "AirPlay streams what this phone decodes itself — Navidrome, Jellyfin, files on " +
                        "the phone, the streaming accounts. A Music Assistant queue plays to its own " +
                        "speakers; group those from the Speakers tab instead.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }

            if (connected && deviceName != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(deviceName!!, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Playing here", color = TextMuted, fontFamily = AppFont, fontSize = 11.sp)
                    }
                    Text(
                        "Disconnect", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(100.dp)).clickable { viewModel.disconnect() }.padding(8.dp),
                    )
                }
            }

            val others = devices.filter { !(connected && it.name == deviceName) }
            if (others.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
                    Text(
                        if (devices.isEmpty()) "Looking for receivers on this network…" else "No other receivers",
                        color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                    )
                }
            }
            others.forEach { d ->
                val busy = connecting == d.name
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass)
                        .clickable(enabled = !busy) { viewModel.connect(d) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (d.name.contains("TV", ignoreCase = true)) Icons.Default.Tv else Icons.Default.Speaker,
                        null, tint = TextSecondary, modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (d.airplay2) "AirPlay 2" else "AirPlay") + " · ${d.host}",
                            color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
                        )
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                }
            }

            error?.let { Text(it, color = ErrorRed, fontFamily = AppFont, fontSize = 12.sp) }

            Spacer(Modifier.height(2.dp))
            Text(
                "Done", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.End).clip(CircleShape).clickable(onClick = onDismiss).padding(8.dp),
            )
        }
    }
}

/** The four digits an Apple TV shows on screen the first time this phone connects. */
@Composable
private fun PinEntry(deviceName: String, onSubmit: (String) -> Unit) {
    val accent = LocalAccent.current
    var pin by remember { mutableStateOf("") }
    Text(
        "$deviceName is showing a PIN. Enter it here to pair this phone; it is remembered for next time.",
        color = TextSecondary, fontFamily = AppFont, fontSize = 13.sp, lineHeight = 18.sp,
    )
    OutlinedTextField(
        value = pin,
        onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
        label = { Text("PIN") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            "Pair", color = if (pin.length >= 4) accent else TextMuted, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.clip(CircleShape).clickable(enabled = pin.length >= 4) { onSubmit(pin) }.padding(8.dp),
        )
    }
}
