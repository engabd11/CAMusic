package com.engabd.sendpin.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.LinkButtonNotPressed
import com.engabd.sendpin.tv.design.TvButton
import com.engabd.sendpin.tv.design.TvError
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.tv.design.tvDpadExitField
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bridge discovery, pairing and unpairing for the TV flavor — the same three
 * steps the phone's `LightSyncSettings.kt` `DirectBridgeSetup` walks through
 * (mDNS scan → pick a bridge → press its link button), against the very same
 * process-scoped `HueBridgeClient` off [SendpinApp.directLightSync].
 *
 * This screen is what makes [TvLightSyncScreen] reachable at all: everything
 * there reads `AppSettings.hueBridgeIp`, and until something writes it the whole
 * tab can only ever show its "no bridge configured" empty state.
 *
 * Manual address entry is kept despite being the most painful thing to type on a
 * remote, for the reason the phone keeps it: it is the spec's required last
 * resort, and multicast filtering — the thing that breaks mDNS — is more common
 * on the guest/IoT VLANs a TV tends to sit on, not less.
 */
@Composable
fun TvHueBridgeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SendpinApp
    val bridge = app.directLightSync.bridgeClient
    val settings = remember { AppSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Read straight off DataStore rather than mirrored into local state and kept in
    // step by hand after each pair/unpair: the pairing write is what decides this,
    // and a `LaunchedEffect`-seeded copy would also show the discovery UI for one
    // frame on entry to a screen whose bridge is already paired.
    val bridgeIp by settings.hueBridgeIp.collectAsStateWithLifecycle(initialValue = "")
    val appKey by settings.hueAppKey.collectAsStateWithLifecycle(initialValue = "")
    val paired = bridgeIp.isNotBlank() && appKey.isNotBlank()

    var scanning by remember { mutableStateOf(false) }
    /** Set once a scan has finished, so the "nothing found" hint only appears after one. */
    var tried by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    /** Which host is mid-pair, so only that row says so rather than all of them. */
    var pairingHost by remember { mutableStateOf<String?>(null) }
    var pairError by remember { mutableStateOf<String?>(null) }

    // Collected rather than snapshotted after the scan window closes, so bridges
    // appear as mDNS answers for them — usually in the first second or two of the
    // eight this waits. `addManual` pushes into the same flow, so a typed address
    // shows up in the same list without a second code path.
    val discovered by bridge.discovered.collectAsStateWithLifecycle()

    // Leaving mid-scan otherwise leaves an NsdManager discovery running for the life
    // of the process — this screen is the only thing that ever started it.
    DisposableEffect(Unit) { onDispose { bridge.stopDiscovery() } }

    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Hue Bridge", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        if (paired) {
            Text("Paired with the bridge at $bridgeIp.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            Text("Pick which entertainment area to light up on the Light Sync tab.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(onClick = onBack) { Text("Back") }
                TvButton(
                    onClick = {
                        scope.launch {
                            settings.setHueBridge("", "", "")
                            settings.setHueConfigId("")
                        }
                    },
                ) { Text("Unpair") }
            }
            return@Column
        }

        Text(
            "CAMusic talks to the bridge directly on your network. You will be asked to press " +
                "the link button on the bridge itself.",
            color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(620.dp),
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    pairError = null
                    bridge.startDiscovery()
                    scope.launch {
                        // Philips' order: mDNS first, then the cloud endpoint, then a
                        // manual address. mDNS is the primary and usually answers
                        // within a second or two.
                        delay(8_000)
                        bridge.stopDiscovery()
                        // Nothing on mDNS — some networks block multicast entirely.
                        // The cloud endpoint knows this network's bridges if any has
                        // ever been online, and is cached against its rate limit.
                        if (bridge.discovered.value.isEmpty()) bridge.addCloudDiscovered()
                        scanning = false
                        tried = true
                    }
                },
            ) { Text(if (scanning) "Scanning…" else "Discover bridges") }
            TvButton(onClick = onBack) { Text("Back") }
        }

        if (discovered.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Bridges found")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discovered.forEach { found ->
                    val busy = pairingHost != null
                    TvTile(
                        modifier = Modifier.width(620.dp),
                        onClick = onClick@{
                            // One pairing attempt at a time: the bridge's link-button
                            // window is ~30s and a second request inside it races the
                            // first for the same key.
                            if (busy) return@onClick
                            pairingHost = found.host
                            pairError = null
                            scope.launch {
                                try {
                                    val (key, clientKey) = bridge.pair(found.host)
                                    val appId = bridge.fetchApplicationId(found.host, key) ?: ""
                                    // The bridge id comes off the mDNS TXT record and is
                                    // what the certificate's Common Name is checked
                                    // against on every later connection.
                                    settings.setHueBridge(found.host, key, clientKey, appId, found.bridgeId)
                                } catch (_: LinkButtonNotPressed) {
                                    pairError = "Press the link button on the bridge, then select it again"
                                } catch (e: Exception) {
                                    pairError = e.message ?: "Pairing failed"
                                }
                                pairingHost = null
                            }
                        },
                    ) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(found.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(found.host, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (pairingHost == found.host) {
                                Text("Pairing…", color = accent, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        if (!scanning && discovered.isEmpty() && tried) {
            Spacer(Modifier.height(16.dp))
            Text(
                "No bridge found. Some networks block the discovery protocol, so enter the bridge's " +
                    "address instead. It is on the Hue app's Settings → My Hue System screen, or in " +
                    "your router's device list.",
                color = TextMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(620.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("Or enter the address")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("Bridge address") },
            placeholder = { Text("192.168.1.20", color = TextMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            // Same pair TvServerSetupScreen's fields use: tvDpadExitField so D-pad
            // down leaves the field, and an explicit IME show on focus because a
            // TV does not raise one for a focused field on its own.
            modifier = Modifier.width(480.dp).tvDpadExitField()
                .onFocusChanged { if (it.isFocused) keyboardController?.show() },
        )
        if (manualIp.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = { bridge.addManual(manualIp); manualIp = "" }) {
                Text("Use this address")
            }
        }

        pairError?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = TvError, style = MaterialTheme.typography.bodySmall)
        }
    }
}
