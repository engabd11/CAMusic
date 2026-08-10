package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ma.LibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Music kept on the phone.
 *
 * The distinction worth leading with, because it is the reason to bother: a download
 * is the *original file*, not a transcode. It plays at the quality it was stored at,
 * costs nothing to stream, and keeps working with the server switched off.
 */
@Composable
internal fun DownloadsSection(
    vm: LibraryViewModel,
    settings: AppSettings,
    accent: Color,
    scope: CoroutineScope,
    onOpenDownloads: () -> Unit,
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    // Measuring means stat-ing every file, so it happens off the main thread and only
    // when the list itself changes.
    var bytes by remember { mutableStateOf(0L) }
    LaunchedEffect(downloads) { bytes = withContext(Dispatchers.IO) { vm.downloadBytes() } }

    var wifiOnly by remember { mutableStateOf(false) }
    var capMb by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        wifiOnly = settings.downloadWifiOnly.first()
        capMb = settings.downloadStorageCapMb.first()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard(
            title = "On this phone",
            lead = "Original files, not transcodes — a downloaded track plays at the quality it " +
                "was stored at, with or without a network.",
        ) {
            StatusPanel {
                StatusRow("Tracks", downloads.size.toString())
                StatusRow("On disk", formatBytes(bytes))
                if (capMb > 0) StatusRow("Limit", "${capMb / 1000} GB")
            }
            Note("Download a track from its long-press menu, or from the player while it is playing.")
        }

        NavRow(
            Icons.Default.Download,
            "Manage downloads",
            "Search, sort by size, retry what failed, and see where the space went",
            accent,
            onClick = onOpenDownloads,
        )

        SettingsCard(
            title = "When to fetch them",
            lead = "Downloading a hi-res album is hundreds of megabytes, which is not something " +
                "to spend a data allowance on by accident.",
        ) {
            ToggleRow(
                "Wi-Fi only",
                "Skip downloads on mobile data rather than spending the allowance on them",
                wifiOnly, accent,
            ) { wifiOnly = it; scope.launch { settings.setDownloadWifiOnly(it) } }
        }

        SettingsCard(
            title = "Storage limit",
            lead = "A ceiling on how much of the phone downloads may take.",
        ) {
            // Fixed steps rather than a free text field: the number only has to be
            // roughly right, and a slider or a keyboard here would be more precision
            // than the setting deserves.
            SegmentedToggleRow(
                labels = listOf("Off", "1 GB", "5 GB", "20 GB"),
                selectedIndex = when (capMb) {
                    1_000 -> 1
                    5_000 -> 2
                    20_000 -> 3
                    else -> 0
                },
            ) { i ->
                val mb = listOf(0, 1_000, 5_000, 20_000)[i]
                capMb = mb
                scope.launch { settings.setDownloadStorageCapMb(mb) }
            }
            Note(
                if (capMb > 0)
                    "The oldest downloads are deleted once the total goes past the limit — never " +
                        "the one currently playing."
                else
                    "No limit — downloads are only removed when you delete them.",
            )
        }

        if (downloads.isNotEmpty()) {
            SettingsCard(
                title = "Delete everything",
                lead = "Removes every downloaded file from this phone. The music itself is " +
                    "untouched on the server.",
            ) {
                OledButton(
                    if (confirming) "Tap again to delete all ${downloads.size}" else "Delete all downloads",
                    accent = accent, danger = true,
                ) {
                    if (confirming) { vm.deleteAllDownloads(); confirming = false } else confirming = true
                }
            }
        }
    }
}
