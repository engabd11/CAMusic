package com.engabd.sendpin.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.HSlider
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

/** Version, licence, and where the code is. */
@Composable
internal fun AboutSection(accent: Color) {
    val context = LocalContext.current
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
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/engabd11/sendspin-nowdroid"))
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
}
