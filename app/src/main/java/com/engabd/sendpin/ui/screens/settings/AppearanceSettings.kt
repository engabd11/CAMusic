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
import com.engabd.sendpin.crash.CrashReporter
import com.engabd.sendpin.crash.CrashReport
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
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
 * Where a crash goes, and how far it gets without being asked.
 *
 * Its own page rather than a second card under "About": one of these is a version
 * number and a licence, the other asks for a GitHub personal access token.
 */
@Composable
internal fun DiagnosticsCard(accent: Color) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val scope = rememberCoroutineScope()

    // The repo and token are text fields behind a Save button, so they hold what is
    // being typed. The switch is a switch: it persists on tap, like every other
    // ToggleRow in Settings. It used to only set local state, so flipping it and
    // leaving without pressing Save silently did nothing at all.
    var repo by remember { mutableStateOf("engabd11/CAMusic") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var lastReport by remember { mutableStateOf<CrashReport?>(null) }
    var uploadResult by remember { mutableStateOf<String?>(null) }

    val autoUpload by settings.crashAutoUpload.collectAsStateWithLifecycle(initialValue = false)
    // The *stored* token, not the typed one. `SendpinApp` reads the stored value on
    // the crash path and quietly does nothing without it, so gating the switch on
    // half-typed text would let it read "on" while nothing could ever be uploaded.
    val savedToken by settings.crashGitHubToken.collectAsStateWithLifecycle(initialValue = "")

    LaunchedEffect(Unit) {
        repo = settings.crashGitHubRepo.first()
        token = settings.crashGitHubToken.first()
        lastReport = CrashReporter.lastUnreported()
    }

        SettingsCard(
            title = "Crash reporting",
            lead = "Crashes stay on this device unless you send them.",
            info = "Crashes are written to this device and stay there. Share one to GitHub by " +
                "hand, or enable automatic upload with a personal access token. Nothing leaves " +
                "the phone until you do one of those two things.\n\nTip: a report is far more " +
                "use with a line about what you were doing when it happened. The automatic " +
                "upload cannot know that, so one manual share with a sentence attached beats " +
                "ten silent ones.",
        ) {
            OledField(
                value = repo,
                onChange = { repo = it },
                label = "GitHub repository",
                placeholder = "owner/repo",
                accent = accent,
            )
            SecretField(
                value = token,
                onChange = { token = it },
                label = "GitHub token (optional)",
                accent = accent,
                visible = showToken,
                onVisibilityChange = { showToken = it },
            )
            Note("With a token, the app can open an issue automatically. Without one, you still get a one-tap share link.")
            Spacer(Modifier.height(8.dp))
            OledButton(
                text = "Save repository and token",
                accent = accent,
                outline = true,
            ) {
                scope.launch {
                    settings.setCrashGitHubRepo(repo)
                    if (token.isNotBlank()) settings.setCrashGitHubToken(token)
                }
            }
            Spacer(Modifier.height(8.dp))
            // Below the button rather than above it, because it depends on what the
            // button saved: the switch is only live once a token is actually stored.
            ToggleRow(
                title = "Upload crashes automatically",
                subtitle = if (savedToken.isBlank()) "Save a token above first"
                    else "Opens an issue on the repository above, without asking",
                checked = autoUpload && savedToken.isNotBlank(),
                accent = accent,
                enabled = savedToken.isNotBlank(),
            ) { on -> scope.launch { settings.setCrashAutoUpload(on) } }
            if (lastReport != null) {
                CardDivider()
                val report: CrashReport = lastReport ?: return@SettingsCard
                Text(
                    "Last crash: ${report.exceptionClass}",
                    color = TextSecondary,
                    fontFamily = AppFont,
                    style = MaterialTheme.typography.bodySmall,
                )
                Note(report.time)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OledButton(
                        text = "Share to GitHub",
                        accent = accent,
                        modifier = Modifier.weight(1f),
                    ) {
                        val url = CrashReporter.githubIssueUrl(repo, report)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        CrashReporter.markLastReported()
                        lastReport = null
                    }
                    if (token.isNotBlank()) {
                        OledButton(
                            text = "Upload now",
                            accent = accent,
                            modifier = Modifier.weight(1f),
                        ) {
                            scope.launch {
                                uploadResult = CrashReporter.postToGitHub(repo, token, report)
                                    .fold(
                                        onSuccess = { "Issue #$it created" },
                                        onFailure = { "Upload failed: ${it.message}" },
                                    )
                                CrashReporter.markLastReported()
                                lastReport = null
                            }
                        }
                    }
                }
                uploadResult?.let {
                    Spacer(Modifier.height(6.dp))
                    Note(it, warn = it.startsWith("Upload failed"))
                }
            }
        }
}
