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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel

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

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
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
                            "v0.1.0 — Hi-res audio player for Music Assistant\n\n" +
                            "Built by Cyborg Automation AU\n" +
                            "Open source under MIT License\n\n" +
                            "Bit-perfect 24-bit audio via AAudio I24 Exclusive mode. " +
                            "Supports FLAC 96/24, PCM 96/24, and automatic format negotiation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/sendspin-android"))
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
