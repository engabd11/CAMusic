package com.engabd.sendpin.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.crash.CrashReport
import com.engabd.sendpin.crash.CrashReporter
import com.engabd.sendpin.crash.DebugBundle
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.OutputStreamWriter

/**
 * How the app looks, one card per composable.
 *
 * Split the way AudioSettings was: the section used to be a single Column with six
 * flow collectors hoisted to its top, rendered inside one item of the host list, so
 * nothing on it ever recycled and any one of the six recomposed all of it. Each card
 * now collects only what it needs and is emitted as its own lazy item -- see
 * SettingsScreen.
 */
@Composable
internal fun ThemeCard(settings: AppSettings, scope: CoroutineScope) {
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeChoice.OLED.key)
    val theme = ThemeChoice.from(themeKey)
    SettingsCard(
        title = "Theme",
        lead = theme.description,
        info = "OLED black is not a darker grey: it is the panel switched off, which on an " +
            "OLED screen means those pixels draw no power and the album art appears to float " +
            "on nothing.\n\nSoft dark is kinder on an LCD, where true black is really dark " +
            "grey and the edge of a card can look smeared.\n\nFollow system takes the phone's " +
            "own day and night schedule.\n\nTip: if the app looks washed out in daylight, that " +
            "is the screen rather than the theme — light mode is a warm page rather than a " +
            "white one, for the same reason.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                ToggleChip(choice.label, choice == theme) {
                    scope.launch { settings.setTheme(choice.key) }
                }
            }
        }
    }
}

@Composable
internal fun AccentCard(settings: AppSettings, scope: CoroutineScope) {
    val accentKey by settings.accentSource.collectAsStateWithLifecycle(initialValue = AccentChoice.ALBUM.key)
    val fixedHex by settings.fixedAccent.collectAsStateWithLifecycle(initialValue = "")
    val accentChoice = AccentChoice.from(accentKey)
    SettingsCard(
        title = "Accent colour",
        lead = accentChoice.description,
        info = "The accent is the one colour used everywhere something is active, selected or " +
            "playing — and on the Lights tab it is roughly what the room is about to do." +
            "\n\nAlbum art re-reads it from every cover, so the whole app takes on the record. " +
            "Wallpaper follows Material You, so the app matches the rest of the phone. Fixed " +
            "never moves.\n\nTip: album art is the one this app was drawn for. Fixed is worth " +
            "it if a particular cover produces a colour you would rather not look at for an " +
            "hour.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentChoice.entries.forEach { choice ->
                ToggleChip(choice.label, choice == accentChoice) {
                    scope.launch { settings.setAccentSource(choice.key) }
                }
            }
        }
        // The swatches only mean anything once the accent has stopped following
        // the artwork.
        if (accentChoice == AccentChoice.FIXED) {
            val picked = parseAccent(fixedHex)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FallbackPalette.forEach { swatch ->
                    SwatchDot(swatch, swatch == picked) {
                        scope.launch { settings.setFixedAccent(swatch.toAccentHex()) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NowPlayingLayoutCard(settings: AppSettings, scope: CoroutineScope) {
    val layout by settings.nowPlayingLayout.collectAsStateWithLifecycle(initialValue = "tab")
    SettingsCard(
        title = "Now Playing",
        lead = "Two ways to reach what's playing.",
        info = "Both show the same controls. They differ in how you get there and in what you " +
            "can see at the same time.\n\nTab replaces the screen you were on, so the " +
            "player gets the whole display and the library is one tap away.\n\nOverlay " +
            "slides over whatever you were doing and collapses to a bar at the bottom, so " +
            "you can keep browsing with the player still up.\n\nTip: overlay suits browsing " +
            "while something plays, tab suits sitting with one record. Switch freely, " +
            "nothing is stored per layout.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Tab", layout == "tab") {
                scope.launch { settings.setNowPlayingLayout("tab") }
            }
            ToggleChip("Overlay", layout == "overlay") {
                scope.launch { settings.setNowPlayingLayout("overlay") }
            }
        }
        Note(
            if (layout == "overlay")
                "The cover slides over the app; swipe down to minimise it into a bar above " +
                    "the tabs, so you can browse while it plays."
            else
                "The classic full-screen player, as its own bottom tab.",
        )
    }
}

@Composable
internal fun SeekBarCard(settings: AppSettings, scope: CoroutineScope) {
    val seekBarStyle by settings.seekBarStyle.collectAsStateWithLifecycle(initialValue = "line")
    SettingsCard(
        title = "Seek bar",
        lead = "How the progress line in Now Playing is drawn.",
        info = "All four scrub the same way and show the same position. What differs is how " +
            "much the bar draws attention to itself while a track plays.\n\nLine is still. " +
            "Wave moves with the music. M3 Pill is the Material 3 expressive shape, with " +
            "larger touch targets. Audiophile Glow lights the played stretch in the accent " +
            "colour.\n\nTip: Reduced motion settles Wave and the Glow halo, so those two read " +
            "as still shapes rather than frozen ones.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Line", seekBarStyle == "line") {
                scope.launch { settings.setSeekBarStyle("line") }
            }
            ToggleChip("Wave", seekBarStyle == "wave") {
                scope.launch { settings.setSeekBarStyle("wave") }
            }
            ToggleChip("M3 Pill", seekBarStyle == "pill") {
                scope.launch { settings.setSeekBarStyle("pill") }
            }
            ToggleChip("Audiophile Glow", seekBarStyle == "glow") {
                scope.launch { settings.setSeekBarStyle("glow") }
            }
        }
        Note(
            when (seekBarStyle) {
                "wave" -> "The played portion wobbles gently while the track plays, like a water surface."
                "pill" -> "A bold, modern Material 3 expressive segmented pill with elevated touch targets."
                "glow" -> "A beam of light: the played stretch glows in the accent with a breathing halo around the playhead."
                else -> "A straight progress line."
            },
        )
    }
}

@Composable
internal fun ChameleonCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val chameleonBloom by settings.chameleonBloom.collectAsStateWithLifecycle(initialValue = false)
    SettingsCard(
        title = "Chameleon canvas & bloom",
        lead = "The album's colours bleed off the artwork and into the screen around it.",
        info = "Two or three colours are taken from the cover and diffused across the edges of " +
            "Now Playing, so the artwork appears to light the screen it sits on rather than " +
            "being pasted onto it.\n\nDrawn on the GPU, and it settles when nothing is " +
            "moving, so it costs very little while you are simply looking at the player." +
            "\n\nTip: turn it off if you would rather the cover read as one crisp object " +
            "against black. It also settles under Reduced motion.",
    ) {
        ToggleRow(
            title = "Ambient album bloom",
            subtitle = if (chameleonBloom) {
                "On — the screen around the cover takes its colours"
            } else {
                "Off — a plain dark canvas"
            },
            checked = chameleonBloom,
            accent = accent,
        ) { on ->
            scope.launch { settings.setChameleonBloom(on) }
        }
    }
}

@Composable
internal fun MotionCard(settings: AppSettings, scope: CoroutineScope) {
    val motionMode by settings.motionMode.collectAsStateWithLifecycle(initialValue = AppSettings.MOTION_SYSTEM)
    SettingsCard(
        title = "Motion",
        lead = when (motionMode) {
            AppSettings.MOTION_FULL -> "Everything animates, whatever the system setting says."
            AppSettings.MOTION_REDUCED -> "Still, resolved states instead of continuous motion."
            else -> "Follows Android's own \"remove animations\" setting."
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AppSettings.MOTION_SYSTEM to "Follow system",
                AppSettings.MOTION_FULL to "Full",
                AppSettings.MOTION_REDUCED to "Reduced",
            ).forEach { (key, label) ->
                ToggleChip(label, key == motionMode) {
                    scope.launch { settings.setMotionMode(key) }
                }
            }
        }
        Note(
            "Reduced settles motion rather than slowing it.",
            title = "Reduced motion",
            info = "Spinners, shimmers and the wave seek bar come to rest in a still state " +
                "instead of freezing wherever they happened to be. An animation that is " +
                "merely suspended looks broken rather than calm, which is what a plain " +
                "speed reduction gives you.\n\nTransitions that carry something from one " +
                "place to another still run, just without the flourish.\n\nTip: this " +
                "follows the system setting on its own. Set it here only if you want the " +
                "app to differ from the rest of the phone.",
        )
    }
}

/** What this is, who wrote it, and which version is installed. */
@Composable
internal fun AboutCard(accent: Color) {
    val context = LocalContext.current
    SettingsCard(
        title = "CAMusic",
        lead = "A Music Assistant player built to give Philips Hue Entertainment its full power.",
        info = "CAMusic is short for Cyborg Automation Music, built by Cyborg Automation AU.\n\n" +
            "The idea started with Hue: Spotify and Samsung's Music Sync can drive the " +
            "Entertainment system properly, but most players settle for a generic colour " +
            "cycle that leaves the rest of what the bridge can do on the table. CAMusic " +
            "renders straight to the bridge at 60 frames a second instead, using the room's " +
            "own layout and the track itself to shape every effect.\n\nIt plays your own " +
            "library with the same directness, and is a full Music Assistant player and " +
            "controller in its own right, so this phone shows up as a speaker in Music " +
            "Assistant's own list.",
    ) {
        StatusPanel {
            StatusRow("Developer", "Cyborg Automation AU")
            StatusRow("Version", BuildConfig.VERSION_NAME)
            StatusRow("Build", BuildConfig.VERSION_CODE.toString())
        }
        Row(
            Modifier.clip(RoundedCornerShape(100)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(100))
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/CAMusic"))
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Code, null, tint = accent, modifier = Modifier.size(16.dp))
            Text(
                "Source on GitHub", color = TextSecondary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
    }
}

/**
 * One file for a bug report, and the last crash it will carry.
 *
 * This page used to hold a GitHub repository, a personal access token and an
 * auto-upload switch, and could open an issue with a bare stack trace in it. All of
 * that is gone: what gets a bug fixed is the log around the failure, and a token on
 * the phone was a secret with nothing to justify it. Now there is a debug file — the
 * app's log, what was playing, the analysis queue, every recorded crash, the settings
 * with secrets left out — and two ways to get it off the phone: save it, or share it.
 * Attaching it to an issue is the user's move.
 */
@Composable
internal fun DiagnosticsCard(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastReport by remember { mutableStateOf<CrashReport?>(null) }
    var crashCount by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }

    fun refresh() {
        val reports = CrashReporter.reports()
        crashCount = reports.size
        lastReport = reports.lastOrNull()
    }
    LaunchedEffect(Unit) { refresh() }

    // "Save" goes through the system file picker, so the file lands wherever the user
    // can find it again — Downloads, Drive, a folder — with no storage permission and
    // no guessing about which app opens .txt. The bundle is built once the place is
    // chosen, so it describes the moment of saving, not the moment the page opened.
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            building = true
            status = try {
                val text = DebugBundle.build(context)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { it.write(text) }
                    }
                }
                "Saved"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            building = false
        }
    }

    SettingsCard(
        title = "Diagnostics",
        lead = "One file with everything needed to look into a problem.",
        info = "The debug file holds this app's recent log, what is playing and from where, " +
            "the track-analysis queue, every crash recorded on this phone, and a copy of your " +
            "settings with passwords, tokens and server logins left out. Nothing is sent " +
            "anywhere by itself.\n\nTo report a problem: make the file straight after it " +
            "happens, then attach it to a new issue at github.com/engabd11/CAMusic with a " +
            "line about what you were doing.\n\nTip: the log only covers the current run of " +
            "the app, so if it crashed, make the file as soon as it reopens.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OledButton(
                text = if (building) "Building…" else "Save debug file",
                accent = accent,
                enabled = !building,
                modifier = Modifier.weight(1f),
            ) { saveDoc.launch(DebugBundle.fileName()) }
            OledButton(
                text = "Share",
                accent = accent,
                outline = true,
                enabled = !building,
                modifier = Modifier.weight(1f),
            ) {
                scope.launch {
                    building = true
                    status = try {
                        val file = DebugBundle.write(context)
                        context.startActivity(DebugBundle.shareIntent(context, file))
                        null
                    } catch (e: Exception) {
                        "Could not share: ${e.message}"
                    }
                    building = false
                }
            }
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Note(it, warn = it.startsWith("Could not"))
        }

        CardDivider()
        val report = lastReport
        if (report == null) {
            Note("No crashes recorded on this phone.")
        } else {
            Text(
                "Last crash: ${report.exceptionClass}",
                color = TextSecondary,
                fontFamily = AppFont,
                style = MaterialTheme.typography.bodySmall,
            )
            Note(
                report.time + " · " + report.versionName +
                    if (crashCount > 1) " · $crashCount recorded, all go in the file" else "",
            )
            Spacer(Modifier.height(6.dp))
            OledButton(text = "Clear recorded crashes", accent = accent, outline = true) {
                CrashReporter.clear()
                lastReport = null
                crashCount = 0
            }
        }
    }
}
