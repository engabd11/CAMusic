package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.tv.design.TvButton
import com.engabd.sendpin.ui.design.titleMarquee
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.MeterBar
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

/**
 * Reuses [NowPlayingViewModel] exactly as the phone's `NowPlayingScreen.kt` does —
 * same `State`, same transport calls — only the layout is new: a big cover on the
 * left (10-foot viewing distance wants scale, not the phone's edge-to-edge wash)
 * and transport + track info on the right, with explicit seek-step buttons instead
 * of a drag scrubber, since a D-pad cannot drag one.
 */
@Composable
fun TvNowPlayingScreen(viewModel: NowPlayingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val favouritable by viewModel.favouritableItem.collectAsStateWithLifecycle()
    val accent = LocalAccent.current

    Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            AlbumArt(
                url = state.artworkUrl,
                glow = accent,
                placeholder = Icons.Default.MusicNote,
                modifier = Modifier.aspectRatio(1f).fillMaxSize(),
                radius = 12.dp,
            )
        }

        Column(
            Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.blank) {
                Text("Nothing playing", color = TextMuted, style = MaterialTheme.typography.headlineSmall)
                return@Column
            }

            Text(
                state.title.ifBlank { "Unknown title" },
                color = TextPrimary, fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.headlineMedium,
                // One line here, not two: a marquee scrolls its content as a single
                // row, so a two-line title would be laid out on one line and only the
                // first screenful ever shown. Scrolling one line shows all of it.
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.titleMarquee(),
            )
            Text(
                state.artist.ifBlank { "Unknown artist" },
                color = TextSecondary, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (state.album.isNotBlank()) {
                Text(state.album, color = TextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))
            MeterBar(
                fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(state.positionMs), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                Text(formatMs(state.durationMs), color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TvIconButton(Icons.Default.Shuffle, "Shuffle", active = state.shuffle) { viewModel.toggleShuffle() }
                TvIconButton(Icons.Default.SkipPrevious, "Previous") { viewModel.previous() }
                TvIconButton(Icons.Default.FastRewind, "Seek back 10s") {
                    seekBySeconds(viewModel, -10)
                }
                TvPlayPauseButton(state.isPlaying) { viewModel.playPause() }
                TvIconButton(Icons.Default.FastForward, "Seek forward 10s") {
                    seekBySeconds(viewModel, 10)
                }
                TvIconButton(Icons.Default.SkipNext, "Next") { viewModel.next() }
                TvIconButton(
                    if (state.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", active = state.repeatMode != "off",
                ) { viewModel.cycleRepeat() }
                if (favouritable != null) {
                    TvIconButton(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Favourite", active = favorite,
                    ) { viewModel.toggleFavorite() }
                }
            }
        }
    }
}

/**
 * The remote's skip buttons: [deltaSeconds] from where the music actually is.
 *
 * Measured off [NowPlayingViewModel.positionMs], the projected playhead, and not
 * [NowPlayingViewModel.State.positionMs], which is the last raw reading Music
 * Assistant sent and can be most of a poll old — "back ten seconds" taken from that
 * lands somewhere the listener did not ask for, and two presses in a row both
 * measure from the same stale number. `massdroid_native`'s `seekRelative` reads its
 * own ticker for exactly this reason.
 *
 * The target goes out in milliseconds. It used to be divided back down into a
 * fraction of the duration for a view model that multiplied it straight back up —
 * two conversions that could disagree, on a number this already had exactly right.
 * Clamping is the view model's job now, and it holds the one duration the server
 * will measure the seek against.
 */
private fun seekBySeconds(viewModel: NowPlayingViewModel, deltaSeconds: Int) {
    viewModel.seekTo((viewModel.positionMs.value + deltaSeconds * 1000L).coerceAtLeast(0L))
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun TvPlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    TvButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "Pause" else "Play",
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    TvButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, description, tint = if (active) accent else TextPrimary, modifier = Modifier.size(20.dp))
    }
}
