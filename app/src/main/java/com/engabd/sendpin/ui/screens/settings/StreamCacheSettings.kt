package com.engabd.sendpin.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.MediaCache
import com.engabd.sendpin.audio.PreCachePlan
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ui.design.ToggleChip
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The stream cache: fetching the next songs ahead, how big the cache may grow, and
 * emptying it. Separate from downloads, which are kept until you remove them; this
 * is the player's own scratch space, and Android may clear it when storage is short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamCacheCard(settings: AppSettings, accent: Color, scope: CoroutineScope) {
    val context = LocalContext.current
    val ahead by settings.precacheAhead.collectAsStateWithLifecycle(initialValue = 0)
    val wifiOnly by settings.precacheWifiOnly.collectAsStateWithLifecycle(initialValue = true)
    val sizeMb by settings.mediaCacheMb.collectAsStateWithLifecycle(initialValue = PreCachePlan.DEFAULT_SIZE_MB)
    var used by remember { mutableLongStateOf(-1L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh, sizeMb) {
        used = withContext(Dispatchers.IO) { MediaCache.usedBytes(context) }
    }

    SettingsCard(
        title = "Streaming cache",
        lead = "Songs heard recently, and the next ones fetched ahead, kept on the phone.",
        info = "Everything streamed from a library passes through this cache, so a song " +
            "played again, or a seek back, is read from the phone.\n\nFetch ahead goes " +
            "further: while a song plays, the next ones are downloaded into the cache, so " +
            "they start at once and keep playing through a tunnel, a lift, or the server " +
            "going away. It only runs while music is playing, waits a few seconds after " +
            "each change so skipping through a list costs nothing, and stops as soon as " +
            "the queue moves on.\n\nDownloads are separate and are never removed by " +
            "this. Android may empty this cache itself when storage runs short.",
    ) {
        Text("Fetch ahead", color = TextMuted, fontFamily = AppFont, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreCachePlan.AHEAD_CHOICES.forEach { n ->
                val label = when (n) { 0 -> "Off"; 1 -> "Next song"; else -> "Next $n" }
                ToggleChip(label, ahead == n) { scope.launch { settings.setPrecacheAhead(n) } }
            }
        }
        ToggleRow(
            title = "Only on Wi-Fi",
            subtitle = if (wifiOnly) "Mobile data is never used to fetch ahead" else "Fetches on mobile data too",
            checked = wifiOnly,
            accent = accent,
            enabled = ahead > 0,
        ) { on -> scope.launch { settings.setPrecacheWifiOnly(on) } }

        Text("Cache size", color = TextMuted, fontFamily = AppFont, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreCachePlan.SIZE_CHOICES_MB.forEach { mb ->
                ToggleChip(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", sizeMb == mb) {
                    scope.launch { settings.setMediaCacheMb(mb) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(
                if (used < 0) "Working out how much is in the cache…"
                else "${formatMb(used)} in the cache now. The oldest goes first when it is full.",
            )
            OledButton("Clear the cache", accent = accent, outline = true, enabled = used > 0) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        MediaCache.clear(context, SendpinApp.instance.localPlayer.currentSource())
                    }
                    refresh++
                }
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
