package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import com.engabd.sendpin.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.first

@Composable
fun OnboardingScreen(
    viewModel: PlayerViewModel = viewModel(),
    onSkip: () -> Unit = {},
) {
    val accent = LocalAccent.current
    val servers by viewModel.discoveredServers.collectAsStateWithLifecycle()
    val discovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val status by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val log by viewModel.connectionLog.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        user = viewModel.savedUsername.first()
        pass = viewModel.savedPassword.first()
        name = viewModel.savedPlayerName.first().ifBlank { viewModel.deviceName }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 460.dp, 40.dp, (-80).dp, 0.34f)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp).padding(top = 64.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo mark.
            Box(
                Modifier.size(78.dp).shadow(30.dp, RoundedCornerShape(24.dp), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(24.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_mushroom),
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("CAMusic", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Play your library in perfect sync - pick a Music Assistant server on your network to begin.",
                color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
            )

            Spacer(Modifier.height(30.dp))

            // Sign-in (MA usually requires auth on its main port — same login you use in the MA app).
            Row(Modifier.fillMaxWidth()) { SectionLabel("Sign in") }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Player name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                colors = accentTextFieldColors(accent),
            )
            Text(
                "Leave blank only if your server has no authentication.",
                color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            // Discovered servers.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("On this network", modifier = Modifier.weight(1f))
                Box(Modifier.size(30.dp).clip(CircleShape).background(Glass).border(1.dp, Hairline, CircleShape).clickable { viewModel.startDiscovery() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Refresh, "Scan again", tint = TextSecondary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(12.dp))

            if (discovering && servers.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    Text("Scanning for servers…", color = TextMuted, fontSize = 13.sp)
                }
            }
            if (!discovering && servers.isEmpty()) {
                Text("No servers found yet - try scanning again or connect manually below.", color = TextFaint, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            servers.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(15.dp)).background(Glass)
                        .border(1.dp, HairlineSoft, RoundedCornerShape(15.dp)).clickable { viewModel.connectToServer(s.webSocketUrl, user, pass, name) }.padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.14f)).border(1.dp, accent.a(0.35f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Text("MA", color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                        Text(s.name, color = TextPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.host}:${s.port}", color = TextMuted, fontSize = 12.sp)
                    }
                    Text("Connect", color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(Hairline))
                Text("  or connect manually  ", color = TextFaint, fontSize = 11.sp)
                Box(Modifier.weight(1f).height(1.dp).background(Hairline))
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = manual, onValueChange = { manual = it },
                label = { Text("WebSocket URL") },
                placeholder = { Text("ws://192.168.0.100:8095/ws") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = accentTextFieldColors(accent),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (manual.isBlank()) Glass else accent).clickable(enabled = manual.isNotBlank()) { viewModel.connectToServer(manual, user, pass, name) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connect", color = if (manual.isBlank()) TextMuted else Ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }

            if (status != "Disconnected") {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = accent, modifier = Modifier.size(15.dp))
                    Text(status, color = TextSecondary, fontSize = 13.sp)
                }
            }

            // Handshake trace — diagnostic (shows why a connection stalls).
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass)
                        .border(1.dp, HairlineSoft, RoundedCornerShape(12.dp)).padding(12.dp),
                ) {
                    Text("CONNECTION LOG", color = TextFaint, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(6.dp))
                    log.takeLast(12).forEach { line ->
                        Text(line, color = TextSecondary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                "Explore offline",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clip(RoundedCornerShape(100)).clickable(onClick = onSkip).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
