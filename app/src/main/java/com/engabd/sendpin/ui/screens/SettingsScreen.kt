package com.engabd.sendpin.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val accent = LocalAccent.current
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val currentFormat by viewModel.currentFormat.collectAsState()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var haSaved by remember { mutableStateOf(false) }
    var haTokenVisible by remember { mutableStateOf(false) }
    var playerName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Only start discovery if it's not already running — avoids re-scanning
        // every time the user switches to the Settings tab.
        if (!isDiscovering && discoveredServers.isEmpty()) {
            viewModel.startDiscovery()
        }
        haUrl = settings.haUrl.first()
        haToken = settings.haToken.first()
        playerName = settings.playerName.first()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings", color = TextPrimary, fontFamily = AppFont,
                    fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
                    letterSpacing = (-0.5).sp,
                )
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // ── Connection ──────────────────────────────────────────────
                item {
                    var manualUrl by remember { mutableStateOf("") }
                    SectionHeader(Icons.Default.Cloud, "Connection", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isDiscovering) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Scanning…", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                            if (!isDiscovering && discoveredServers.isEmpty()) {
                                Text("No servers found on the network", color = TextFaint, fontSize = 13.sp)
                            }
                            discoveredServers.forEach { server ->
                                ServerRow(
                                    name = server.name,
                                    host = "${server.host}:${server.port}",
                                    connected = serverUrl == server.webSocketUrl && connected,
                                    accent = accent,
                                ) { viewModel.connectToServer(server.webSocketUrl) }
                            }
                            OledField(manualUrl, { manualUrl = it }, "WebSocket URL", "ws://192.168.0.100:8095/ws", accent)
                            OledButton(
                                if (connected) "Reconnect" else "Connect",
                                enabled = manualUrl.isNotBlank(),
                                accent = accent,
                            ) { viewModel.connectToServer(manualUrl) }
                            if (connected) {
                                OledButton("Disconnect", accent = accent, outline = true) { viewModel.disconnect() }
                            }
                        }
                    }
                }

                // ── Player ──────────────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Smartphone, "Player", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Player name is read-only while the player is enabled —
                            // changing it live has no effect until a reconnect anyway.
                            OledField(
                                playerName,
                                { if (!connected) playerName = it },
                                "Player name",
                                "e.g. Abdullah's phone",
                                accent,
                                enabled = !connected,
                            )
                            Text(
                                if (connected) "Disconnect or disable the player to change the name."
                                else "Shown in Music Assistant. Applies when the player connects.",
                                color = TextFaint, fontSize = 11.sp,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (connected) {
                                    OledButton("Disable", modifier = Modifier.weight(1f), accent = accent) { viewModel.disablePlayer() }
                                } else {
                                    OledButton("Enable player", modifier = Modifier.weight(1f), accent = accent) {
                                        scope.launch { settings.setPlayerName(playerName.trim()); viewModel.enablePlayer() }
                                    }
                                }
                            }
                            OledButton("Log out", accent = accent) { viewModel.logout() }
                        }
                    }
                }

                // ── Now Playing ─────────────────────────────────────────────
                item {
                    var npLayout by remember { mutableStateOf("tab") }
                    LaunchedEffect(Unit) { npLayout = settings.nowPlayingLayout.first() }
                    SectionHeader(Icons.Default.PlayArrow, "Now Playing", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Layout style",
                                color = TextPrimary, fontFamily = AppFont,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            )
                            Text(
                                "Overlay: the cover slides over the app; swipe down to minimize into a bar. " +
                                    "Tab: the classic full-screen player as a bottom tab.",
                                color = TextFaint, fontFamily = AppFont, fontSize = 11.sp, lineHeight = 15.sp,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ToggleChip("Tab (classic)", npLayout == "tab") {
                                    npLayout = "tab"
                                    scope.launch { settings.setNowPlayingLayout("tab") }
                                }
                                ToggleChip("Overlay", npLayout == "overlay") {
                                    npLayout = "overlay"
                                    scope.launch { settings.setNowPlayingLayout("overlay") }
                                }
                            }
                        }
                    }
                }

                // ── Audio ───────────────────────────────────────────────────
                item {
                    var preferHiRes by remember { mutableStateOf(true) }
                    var preferFlac by remember { mutableStateOf(true) }
                    var preferOriginal by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        preferHiRes = settings.preferHiRes.first()
                        preferFlac = settings.preferFlac.first()
                        preferOriginal = settings.preferOriginal.first()
                    }
                    SectionHeader(Icons.Default.GraphicEq, "Audio", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ToggleRow(
                                "Offer hi-res rates",
                                "Also accept 88.2/96 kHz so hi-res masters aren't downsampled to 48 kHz",
                                preferHiRes, accent,
                            ) { preferHiRes = it; scope.launch { settings.setPreferHiRes(it) } }
                            ToggleRow(
                                "Prefer FLAC over PCM",
                                "Lossless either way — FLAC uses about half the bandwidth",
                                preferFlac, accent,
                            ) { preferFlac = it; scope.launch { settings.setPreferFlac(it) } }
                            ToggleRow(
                                "Play at original quality",
                                "Stream straight from Navidrome when Music Assistant would have " +
                                    "to resample. Plays on this phone only — no queue, no grouping.",
                                preferOriginal, accent,
                            ) { preferOriginal = it; scope.launch { settings.setPreferOriginal(it) } }
                            Text(
                                "Music Assistant may only send a format this phone advertises. " +
                                    "44.1 and 48 kHz are always offered, so CD-rate files stream " +
                                    "untouched. Output is 16-bit. Reconnect to apply.",
                                color = TextFaint, fontFamily = AppFont, fontSize = 11.sp, lineHeight = 15.sp,
                            )
                            // Status readout
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Ink3).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusRow("Connection", if (connected) "Connected" else "Disconnected")
                                StatusRow("Format", currentFormat)
                                StatusRow("Player ID", viewModel.playerId.take(12) + "…")
                                StatusRow("Device", viewModel.playerName)
                            }
                        }
                    }
                }

                // ── Light Sync (HA) ─────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Lightbulb, "Light Sync", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Drives the Hue Synco light-sync integration. Use a long-lived access token (Profile → Security).",
                                color = TextMuted, fontSize = 13.sp,
                            )
                            OledField(haUrl, { haUrl = it; haSaved = false }, "HA URL", "http://192.168.0.10:8123", accent)
                            OledField(
                                haToken, { haToken = it; haSaved = false },
                                "Long-lived access token", "eyJ…", accent,
                                visualTransformation = if (haTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Box(Modifier.size(20.dp).clip(CircleShape).clickable { haTokenVisible = !haTokenVisible }) {
                                        Icon(
                                            if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (haTokenVisible) "Hide" else "Show",
                                            tint = TextMuted, modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                            OledButton(
                                if (haSaved) "Saved" else "Save",
                                enabled = haUrl.isNotBlank() && haToken.isNotBlank(),
                                accent = accent,
                            ) { scope.launch { settings.setHomeAssistant(haUrl.trim(), haToken.trim()); haSaved = true } }
                        }
                    }
                }

                // ── About ────────────────────────────────────────────────────
                item {
                    SectionHeader(Icons.Default.Info, "About", accent)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(radius = 16.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Sendspin — a Music Assistant player & controller for Android.\n\n" +
                                "Plays FLAC / Opus 16-bit as an MA player, with Navidrome-direct " +
                                "browse + offline downloads and Hue light-sync controls.",
                                color = TextMuted, fontSize = 13.sp,
                            )
                            Row(
                                Modifier.clip(RoundedCornerShape(100)).background(Glass)
                                    .border(1.dp, Hairline, RoundedCornerShape(100))
                                    .clickable {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/sendspin-nowdroid")))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Code, null, tint = accent, modifier = Modifier.size(16.dp))
                                Text("GitHub Repository", color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text("v0.1.3 (code 4)", color = TextFaint, fontFamily = MonoFont, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Reusable OLED design components for settings ──────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(accent.a(0.12f))
                .border(1.dp, accent.a(0.3f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp)) }
        Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
private fun ServerRow(name: String, host: String, connected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (connected) accent.a(0.08f) else Glass)
            .border(1.dp, if (connected) accent.a(0.3f) else Hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text(host, color = TextMuted, fontSize = 11.sp)
        }
        if (connected) Icon(Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OledField(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String, accent: Color,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
            unfocusedBorderColor = Hairline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            disabledBorderColor = Hairline, disabledTextColor = TextMuted, disabledLabelColor = TextMuted,
        ),
        trailingIcon = trailingIcon,
    )
}

@Composable
private fun OledButton(
    text: String, accent: Color, enabled: Boolean = true, outline: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (outline || !enabled) Glass else accent)
            .border(1.dp, if (outline) Hairline else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (outline || !enabled) TextMuted else Ink, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink3)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp)).clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = TextFaint, fontSize = 11.sp)
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

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}