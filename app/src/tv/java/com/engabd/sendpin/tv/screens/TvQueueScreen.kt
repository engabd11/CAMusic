package com.engabd.sendpin.tv.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.MaQueueItem
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel

/**
 * Reuses [NowPlayingViewModel]'s queue panel (`queueItems`/`loadQueue`/
 * `playQueueItem`) — on the phone this is a sheet off Now Playing; TV promotes
 * it to its own rail tab, since a sheet is a touch/drag idiom with no D-pad
 * equivalent.
 */
@Composable
fun TvQueueScreen(viewModel: NowPlayingViewModel = viewModel()) {
    val loadState by viewModel.queueItems.collectAsStateWithLifecycle()
    val current by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadQueue() }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("Queue", color = TextPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))

        when (val load = loadState) {
            is NowPlayingViewModel.Load.Idle, is NowPlayingViewModel.Load.Loading ->
                Text("Loading…", color = TextMuted)
            is NowPlayingViewModel.Load.Failed ->
                Text(load.message, color = TextMuted)
            is NowPlayingViewModel.Load.Ready -> {
                if (load.value.isEmpty()) {
                    Text("Nothing queued.", color = TextMuted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(load.value, key = { it.queueItemId }) { item ->
                            TvQueueRow(
                                item = item,
                                playing = item.queueItemId == current.currentQueueItemId,
                                onClick = { viewModel.playQueueItem(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvQueueRow(item: MaQueueItem, playing: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    TvTile(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(
                url = item.image, glow = accent, placeholder = Icons.Default.MusicNote,
                modifier = Modifier.size(52.dp), radius = 6.dp,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name, color = if (playing) accent else TextPrimary, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item.artist?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (playing) Icon(Icons.Default.MusicNote, "Now playing", tint = accent)
        }
    }
}
