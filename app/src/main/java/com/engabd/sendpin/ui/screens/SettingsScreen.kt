package com.engabd.sendpin.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val currentFormat by viewModel.currentFormat.collectAsState()
    val context = LocalContext.current

    var manualUrl by remember { mutableStateOf("") }
    var prefer96k by remember { mutableStateOf(true) }
    var preferFlac by remember { mutableStateOf(true) }

    // Home Assistant (drives the light-sync integration).
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var haSaved by remember { mutableStateOf(false) }
    var haTokenVisible by remember { mutableStateOf(false) }
    var playerName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        haUrl = settings.haUrl.first()
        haToken = settings.haToken.first()
        playerName = settings.playerName.first()
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink, titleContentColor = TextPrimary),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Server Selection
            item {
                Text(
                    "Music Assistant Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Discovered servers
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Discovered Servers", style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { viewModel.startDiscovery() }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (isDiscovering) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Searching...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (discoveredServers.isEmpty() && !isDiscovering) {
                            Text(
                                "No servers found on the network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        discoveredServers.forEach { server ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.connectToServer(server.webSocketUrl)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (serverUrl == server.webSocketUrl && connected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(server.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${server.host}:${server.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (serverUrl == server.webSocketUrl && connected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Connected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Manual URL Entry
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Manual Connection", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualUrl,
                            onValueChange = { manualUrl = it },
                            label = { Text("WebSocket URL") },
                            placeholder = { Text("ws://192.168.0.100:8095/ws") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.connectToServer(manualUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = manualUrl.isNotBlank()
                        ) {
                            Text(if (connected) "Reconnect" else "Connect")
                        }
                        if (connected) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            // Player (name + enable/disable), like the MA app.
            item {
                Text(
                    "Player",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = playerName,
                            onValueChange = { playerName = it },
                            label = { Text("Player name") },
                            placeholder = { Text("e.g. Abdullah's phone") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Shown in Music Assistant. Applies when the player (re)connects.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (connected) {
                                OutlinedButton(onClick = { viewModel.disablePlayer() }, modifier = Modifier.weight(1f)) {
                                    Text("Disable player")
                                }
                                Button(
                                    onClick = { scope.launch { settings.setPlayerName(playerName.trim()); viewModel.enablePlayer() } },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Apply name") }
                            } else {
                                Button(
                                    onClick = { scope.launch { settings.setPlayerName(playerName.trim()); viewModel.enablePlayer() } },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Enable player") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.logout() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Log out")
                        }
                    }
                }
            }

            // Audio Format Preferences
            item {
                Text(
                    "Audio Format",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Prefer 96kHz when available")
                                Text(
                                    "Use hi-res 96/24 format instead of 48/24",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = prefer96k, onCheckedChange = { prefer96k = it })
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Prefer FLAC over PCM")
                                Text(
                                    "FLAC is bandwidth-efficient lossless — same bit-perfect quality",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = preferFlac, onCheckedChange = { preferFlac = it })
                        }
                    }
                }
            }

            // Current Status
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current Status", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        LabeledRow("Connection", if (connected) "Connected" else "Disconnected")
                        LabeledRow("Format", currentFormat)
                        LabeledRow("Player ID", viewModel.playerId.take(12) + "...")
                        LabeledRow("Device", viewModel.playerName)
                    }
                }
            }

            // Home Assistant (light sync)
            item {
                Text(
                    "Home Assistant (light sync)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Drives the Hue Synco light-sync integration. Use a long-lived access token (Profile → Security).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = haUrl, onValueChange = { haUrl = it; haSaved = false },
                            label = { Text("HA URL") }, placeholder = { Text("http://192.168.0.10:8123") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = haToken, onValueChange = { haToken = it; haSaved = false },
                            label = { Text("Long-lived access token") }, placeholder = { Text("eyJ…") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (haTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { haTokenVisible = !haTokenVisible }) {
                                    Icon(
                                        if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (haTokenVisible) "Hide token" else "Show token",
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch { settings.setHomeAssistant(haUrl.trim(), haToken.trim()); haSaved = true }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = haUrl.isNotBlank() && haToken.isNotBlank()
                        ) { Text(if (haSaved) "Saved" else "Save") }
                    }
                }
            }

            // About
            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sendspin", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "v0.1.0 — a Sendspin player & Navidrome client for Music Assistant\n\n" +
                            "Plays FLAC / Opus 16-bit as an MA player, with Navidrome-direct " +
                            "browse + offline downloads and Hue light-sync controls. " +
                            "Bit-perfect 24-bit output is a later phase.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/sendspin-nowdroid"))
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub Repository")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
