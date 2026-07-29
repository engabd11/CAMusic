package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load

/**
 * Lyrics as part of the player, not a sheet over it: this takes the album art's
 * place in the layout and scrolls itself as the song moves, so the words are
 * simply what you're looking at while the track plays.
 *
 * [positionMs] is the interpolated playhead the screen is already tracking for the
 * scrubber, which is what keeps the highlight moving between server polls.
 */
@Composable
fun LyricsPane(
    viewModel: NowPlayingViewModel,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val load by viewModel.lyrics.collectAsState()
    val accent = LocalAccent.current

    LaunchedEffect(Unit) { if (load is Load.Idle) viewModel.loadLyrics() }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val l = load) {
            is Load.Loading, Load.Idle ->
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)

            is Load.Failed -> Notice(Icons.Default.CloudOff, l.message)

            is Load.Ready -> {
                val lyrics: MaLyrics? = l.value
                if (lyrics == null || lyrics.lines.none { it.text.isNotBlank() }) {
                    Notice(Icons.Default.Lyrics, "No lyrics for this track.")
                } else if (!lyrics.synced && lyrics.text.length < 20 && !lyrics.text.contains(" ")) {
                    // A single short word is usually an error response, not lyrics.
                    Notice(Icons.Default.Lyrics, "No lyrics for this track.")
                } else {
                    SyncedLyrics(lyrics, positionMs, accent)
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: MaLyrics,
    positionMs: Long,
    accent: androidx.compose.ui.graphics.Color,
) {
    val lines = lyrics.lines
    // The line in force right now: the last one whose timestamp has passed.
    val active = if (!lyrics.synced) -1
    else lines.indexOfLast { it.atMs <= positionMs }.coerceAtLeast(0)

    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        // Hold the sung line a third of the way down rather than pinned to the top,
        // so the next few lines are always readable ahead of the vocal.
        if (active >= 0) listState.animateScrollToItem(maxOf(0, active - 2))
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            if (line.text.isBlank()) {
                Spacer(Modifier.height(12.dp))
            } else {
                val isActive = i == active && lyrics.synced
                Text(
                    line.text,
                    color = when {
                        !lyrics.synced -> TextSecondary
                        isActive -> accent
                        i < active -> TextFaint
                        else -> TextMuted
                    },
                    fontFamily = AppFont,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = if (isActive) 20.sp else 16.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = TextFaint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            message, color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
            lineHeight = 18.sp, textAlign = TextAlign.Center,
        )
    }
}
