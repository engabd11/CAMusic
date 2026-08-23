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

/** How the app looks, and how the player behaves. */
@Composable
internal fun AppearanceSection(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val themeKey by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeChoice.OLED.key)
    val accentKey by settings.accentSource.collectAsStateWithLifecycle(initialValue = AccentChoice.ALBUM.key)
    val fixedHex by settings.fixedAccent.collectAsStateWithLifecycle(initialValue = "")
    val offset by settings.lyricsOffsetMs.collectAsStateWithLifecycle(initialValue = 0)
    val theme = ThemeChoice.from(themeKey)
    val accentChoice = AccentChoice.from(accentKey)

    // `collectAsStateWithLifecycle`, not a one-shot `first()`. These two were read once
    // when the screen composed, so changing the seek-bar style or the layout anywhere
    // else left this screen showing the old answer until it was rebuilt — and every
    // other row on it was already live.
    val layout by settings.nowPlayingLayout.collectAsStateWithLifecycle(initialValue = "tab")
    val seekBarStyle by settings.seekBarStyle.collectAsStateWithLifecycle(initialValue = "line")
    val motionMode by settings.motionMode.collectAsStateWithLifecycle(initialValue = AppSettings.MOTION_SYSTEM)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(title = "Theme", lead = theme.description) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    ToggleChip(choice.label, choice == theme) {
                        scope.launch { settings.setTheme(choice.key) }
                    }
                }
            }
        }

        SettingsCard(title = "Accent colour", lead = accentChoice.description) {
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

        SettingsCard(
            title = "Now Playing",
            lead = "Two ways to reach what's playing.",
            info = "Both show the same controls. They differ in how you get there and in what " +
                "you can see at the same time — a tab replaces the screen you were on, an " +
                "overlay slides over it and collapses to a bar you can leave up.",
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

        SettingsCard(
            title = "Seek bar",
            lead = "How the progress line in Now Playing is drawn.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Line", seekBarStyle == "line") {
                    scope.launch { settings.setSeekBarStyle("line") }
                }
                ToggleChip("Wave", seekBarStyle == "wave") {
                    scope.launch { settings.setSeekBarStyle("wave") }
                }
            }
            Note(
                if (seekBarStyle == "wave")
                    "The played portion wobbles gently while the track plays, like a water surface."
                else
                    "A straight progress line.",
            )
        }

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
                    "instead of freezing wherever they happened to be — an infinite animation " +
                    "that is merely suspended looks broken rather than calm.",
            )
        }

        SettingsCard(
            title = "Lyrics timing",
            lead = "Nudge synced lyrics against the vocal.",
            info = "Providers stamp the same track differently, so there is no right answer " +
                "here — only what looks in time to you. Adjustments snap to 50ms, which is " +
                "finer than anyone can pick out against a sung line.",
        ) {
            SliderRow(
                value = (offset + AppSettings.MAX_LYRICS_OFFSET_MS) /
                    (2f * AppSettings.MAX_LYRICS_OFFSET_MS),
                format = { if (offset == 0) "0 ms" else "%+d ms".format(offset) },
                onChange = { f ->
                    val ms = (f * 2f * AppSettings.MAX_LYRICS_OFFSET_MS -
                        AppSettings.MAX_LYRICS_OFFSET_MS).toInt()
                    scope.launch { settings.setLyricsOffsetMs((ms / 50) * 50) }
                },
            )
            Note("Later ← → earlier")
        }
    }
}

/** Version, licence, where the code is, and how crashes are reported. */
@Composable
internal fun AboutSection(accent: Color, onOpenStats: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf("engabd11/CAMusic") }
    var token by remember { mutableStateOf("") }
    var autoUpload by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var lastReport by remember { mutableStateOf<CrashReport?>(null) }
    var uploadResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repo = settings.crashGitHubRepo.first()
        token = settings.crashGitHubToken.first()
        autoUpload = settings.crashAutoUpload.first()
        lastReport = CrashReporter.lastUnreported()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "CAMusic",
            lead = "A Music Assistant player and controller for Android.",
            info = "Direct playback from self-hosted libraries — Navidrome, Subsonic, " +
                "Jellyfin, or files on this phone — offline downloads, and Hue light sync " +
                "driven by the music itself rather than by a preset.",
        ) {
            StatusPanel {
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

        SettingsCard(
            title = "Crash reporting",
            lead = "Crashes stay on this device unless you send them.",
            info = "Share one to GitHub by hand, or enable automatic upload with a personal " +
                "access token. Nothing leaves the phone until you do one of those two things.",
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
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Upload crashes automatically",
                subtitle = "Needs a token above",
                checked = autoUpload,
                accent = accent,
                enabled = token.isNotBlank(),
            ) { autoUpload = it }
            Spacer(Modifier.height(8.dp))
            OledButton(
                text = "Save crash-report settings",
                accent = accent,
                outline = true,
            ) {
                scope.launch {
                    settings.setCrashGitHubRepo(repo)
                    if (token.isNotBlank()) settings.setCrashGitHubToken(token)
                    settings.setCrashAutoUpload(autoUpload)
                }
            }
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
}
