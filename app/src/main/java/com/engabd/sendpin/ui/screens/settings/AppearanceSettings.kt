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

    var layout by remember { mutableStateOf("tab") }
    LaunchedEffect(Unit) { layout = settings.nowPlayingLayout.first() }

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
            lead = "Two ways to reach what's playing. Both show the same controls; they differ " +
                "in how you get there and what you can see at the same time.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Tab", layout == "tab") {
                    layout = "tab"; scope.launch { settings.setNowPlayingLayout("tab") }
                }
                ToggleChip("Overlay", layout == "overlay") {
                    layout = "overlay"; scope.launch { settings.setNowPlayingLayout("overlay") }
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
            title = "Lyrics timing",
            lead = "Nudge synced lyrics if they run ahead of or behind the vocal. Providers " +
                "stamp the same track differently, so this is a matter of taste rather than a " +
                "setting with a right answer.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HSlider(
                    value = (offset + AppSettings.MAX_LYRICS_OFFSET_MS) /
                        (2f * AppSettings.MAX_LYRICS_OFFSET_MS),
                    onChange = {},
                    onCommit = { f ->
                        val ms = (f * 2f * AppSettings.MAX_LYRICS_OFFSET_MS -
                            AppSettings.MAX_LYRICS_OFFSET_MS).toInt()
                        // Snap to 50 ms — finer than that is below what anyone can
                        // hear against a line of sung text.
                        scope.launch { settings.setLyricsOffsetMs((ms / 50) * 50) }
                    },
                    accented = true,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (offset == 0) "0 ms" else "%+d ms".format(offset),
                    color = TextSecondary, fontFamily = MonoFont,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
            lead = "A Music Assistant player and controller for Android, with direct playback " +
                "from self-hosted libraries, offline downloads, and Hue light sync driven by " +
                "the music itself.",
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

        NavRow(
            Icons.Default.BarChart,
            "Listening stats",
            "This week's top artists, total time, and format breakdown",
            accent,
            onClick = onOpenStats,
        )

        SettingsCard(
            title = "Crash reporting",
            lead = "Crashes are stored on this device only. Share them to GitHub manually, or " +
                "enable automatic upload with a personal access token.",
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

        BackupSection(settings, accent, scope)
    }
}

/**
 * Export every setting — including saved servers — to an encrypted JSON file, or
 * restore one. The password never leaves this device: it exists only to derive
 * [com.engabd.sendpin.data.PortableCrypto]'s key, which is why losing it means
 * losing the backup — there is nothing to reset it with.
 */
@Composable
private fun BackupSection(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    var exportPrompt by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Held between the picker's launch and its callback — CreateDocument hands off
    // to a separate activity, and the composition (with its remember state) is
    // what survives that round trip, not any local variable in the click handler.
    var exportPassword by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val blob = settings.exportSettings(password)
                if (blob == null) {
                    "Export failed"
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { it.write(blob) }
                    }
                    "Exported"
                }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
    }

    SettingsCard(
        title = "Backup",
        lead = "Export every setting — including saved servers — to an encrypted file, or " +
            "restore one on a new install. The password is yours alone: there is no way to " +
            "recover a backup without it.",
    ) {
        OledButton(text = "Export settings", accent = accent, outline = true) { exportPrompt = true }
        Spacer(Modifier.height(8.dp))
        OledButton(text = "Import settings", accent = accent, outline = true) {
            openDoc.launch(arrayOf("application/json", "*/*"))
        }
        status?.let { Spacer(Modifier.height(6.dp)); Note(it, warn = it.startsWith("Export failed") || it.startsWith("Import failed")) }
    }

    if (exportPrompt) {
        PasswordPromptDialog(
            title = "Export settings",
            note = "This password encrypts the file. Choose one you'll remember — it can't be reset.",
            confirmLabel = "Export",
            onDismiss = { exportPrompt = false },
            onConfirm = { password ->
                exportPrompt = false
                exportPassword = password
                createDoc.launch("camusic-settings-backup.json")
            },
        )
    }

    importUri?.let { uri ->
        PasswordPromptDialog(
            title = "Import settings",
            note = "This replaces your saved servers and other settings on this device.",
            confirmLabel = "Import",
            onDismiss = { importUri = null },
            onConfirm = { password ->
                importUri = null
                scope.launch {
                    status = try {
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input)).readText()
                        }
                        if (text == null) {
                            "Import failed: couldn't read that file"
                        } else if (settings.importSettings(text, password)) {
                            "Imported — restart the app for everything to take effect"
                        } else {
                            "Import failed: wrong password, or not a CAMusic backup"
                        }
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    }
                }
            },
        )
    }
}

@Composable
private fun PasswordPromptDialog(
    title: String,
    note: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val accent = LocalAccent.current
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = { Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(
                    value = password,
                    onChange = { password = it },
                    label = "Password",
                    accent = accent,
                    visible = visible,
                    onVisibilityChange = { visible = it },
                )
                Note(note)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank()) {
                Text(confirmLabel, color = if (password.isBlank()) TextFaint else accent, fontFamily = AppFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}
