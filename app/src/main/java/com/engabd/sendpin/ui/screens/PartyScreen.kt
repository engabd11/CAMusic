package com.engabd.sendpin.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.party.PartyManager
import com.engabd.sendpin.party.SkipMode
import com.engabd.sendpin.ui.design.AmbientRain
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.CircleIconButton
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.TitleGap
import com.engabd.sendpin.ui.design.navBarInset
import com.engabd.sendpin.ui.screens.settings.CardDivider
import com.engabd.sendpin.ui.screens.settings.DropdownPicker
import com.engabd.sendpin.ui.screens.settings.Note
import com.engabd.sendpin.ui.screens.settings.OledButton
import com.engabd.sendpin.ui.screens.settings.SettingsCard
import com.engabd.sendpin.ui.screens.settings.ToggleRow
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

private val IDLE_HOURS = listOf(1, 2, 4, 8, 12)

/**
 * The host's side of a listening party: start it, show the code, see who is here, say
 * yes or no to requests, and end it.
 *
 * Everything the guests can do is decided in `com.engabd.sendpin.party`; this screen
 * only starts and stops the party and flips its two switches.
 */
@Composable
fun PartyScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val accent = LocalAccent.current
    val manager = remember { (context.applicationContext as SendpinApp).partyManager }
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    val host by manager.host.collectAsStateWithLifecycle()
    val message by manager.message.collectAsStateWithLifecycle()
    val idleHours by settings.partyIdleHours.collectAsState(initial = 4)

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(accent, 420.dp, (-60).dp, (-56).dp, 0.4f)
        AmbientRain(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
                    Text("Listening party", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text(
                        if (state is PartyManager.State.Running) "Guests can add songs from their own phones"
                        else "Friends add songs from their browsers, no app needed",
                        color = TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    )
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarInset() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val running = state as? PartyManager.State.Running
                if (running == null) {
                    item(key = "start") {
                        SettingsCard(
                            title = "Start a party",
                            lead = "Guests on your Wi-Fi scan a code and add songs to your queue from their own browsers. " +
                                "Nothing leaves your network and nobody needs an account.",
                        ) {
                            message?.let { Note(it) }
                            Text("Ends on its own after", color = TextMuted, fontFamily = AppFont, fontSize = 12.sp)
                            DropdownPicker(
                                options = IDLE_HOURS.map { if (it == 1) "1 hour with no guests" else "$it hours with no guests" },
                                selectedIndex = IDLE_HOURS.indexOf(idleHours).coerceAtLeast(0),
                                accent = accent,
                            ) { i -> scope.launch { settings.setPartyIdleHours(IDLE_HOURS[i]) } }
                            OledButton("Start party", accent) {
                                manager.clearMessage()
                                scope.launch { manager.start() }
                            }
                        }
                    }
                } else {
                    item(key = "code") {
                        SettingsCard(title = "Scan to join") {
                            QrCode(running.url)
                            SelectionContainer {
                                Text(running.url, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
                            }
                            OledButton("Share link", accent, outline = true) {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, running.url)
                                context.startActivity(Intent.createChooser(send, "Share party link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            Note("Guests need to be on the same Wi-Fi. The link stops working the moment you end the party.")
                        }
                    }

                    if (host.requests.isNotEmpty()) {
                        item(key = "requests") {
                            SettingsCard(title = "Waiting for you") {
                                host.requests.forEachIndexed { i, r ->
                                    if (i > 0) CardDivider()
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(r.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            listOf(r.artist, "from ${r.guestName}").filter { it.isNotBlank() }.joinToString(" - "),
                                            color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OledButton("Approve", accent, modifier = Modifier.weight(1f)) { manager.approve(r.id) }
                                            OledButton("Decline", accent, outline = true, modifier = Modifier.weight(1f)) { manager.decline(r.id) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "guests") {
                        SettingsCard(title = if (host.guests.isEmpty()) "Guests" else "Guests (${host.guests.size})") {
                            if (host.guests.isEmpty()) {
                                Text("Nobody has joined yet.", color = TextMuted, fontFamily = AppFont, fontSize = 13.sp)
                            } else {
                                host.guests.forEach { g ->
                                    Text(g.name, color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp)
                                }
                            }
                            host.skipNeeded?.let { need ->
                                Text(
                                    "Skip votes: ${host.skipVotes} of $need",
                                    color = TextFaint, fontFamily = AppFont, fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    item(key = "rules") {
                        SettingsCard(title = "House rules") {
                            ToggleRow(
                                title = "Approve songs first",
                                subtitle = "Guests' songs wait for your yes before they join the queue",
                                checked = host.settings.approveFirst,
                                accent = accent,
                            ) { on -> manager.updateSettings { it.copy(approveFirst = on) } }
                            CardDivider()
                            Text("Votes to skip", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            DropdownPicker(
                                options = SkipMode.entries.map { it.label },
                                selectedIndex = host.settings.skipMode.ordinal,
                                accent = accent,
                            ) { i -> manager.updateSettings { it.copy(skipMode = SkipMode.entries[i]) } }
                        }
                    }

                    item(key = "end") {
                        OledButton("End party", accent, danger = true) { manager.end() }
                    }
                }
            }
        }
    }
}

/**
 * A QR code, drawn square by square onto white with a clear border. White and black
 * whatever the theme: phone cameras read dark-on-light codes far more reliably.
 */
@Composable
private fun QrCode(text: String) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    } ?: return
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(14.dp)
                .semantics { contentDescription = "QR code for the party link" },
        ) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) {
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}
