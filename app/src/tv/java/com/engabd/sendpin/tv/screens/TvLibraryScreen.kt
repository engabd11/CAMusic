package com.engabd.sendpin.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.LibraryShelves
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Reuses [LibraryViewModel] exactly as the phone's `LibraryScreen.kt` does —
 * same `shelves`/`node`/`depth`/`open`/`back` — with a 10-foot grid/row layout
 * instead of the phone's touch grid. Root shows the shelves; opening a
 * browsable item pushes [LibraryViewModel]'s own browse stack, matching phone
 * behaviour, so Back (the remote's actual Back key, wired below) walks out the
 * same way it walked in.
 */
@Composable
fun TvLibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val node by viewModel.node.collectAsStateWithLifecycle()
    val depth by viewModel.depth.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()

    BackHandler(enabled = depth > 0) { viewModel.back() }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text(
            if (depth > 0) node.title else "Library",
            color = TextPrimary, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineSmall,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))

        when {
            !hasServer -> Text("No library connected yet. Set one up in Settings.", color = TextMuted)
            !ready -> Text("Connecting…", color = TextMuted)
            depth > 0 -> TvItemGrid(node.items, onOpen = viewModel::open)
            else -> TvLibraryShelves(shelves, onOpen = viewModel::open)
        }
    }
}

@Composable
private fun TvLibraryShelves(shelves: LibraryShelves, onOpen: (MaItem) -> Unit) {
    val rows = listOf(
        "Recently played" to shelves.recent,
        "Favourite albums" to shelves.favoriteAlbums,
        "Favourite artists" to shelves.favoriteArtists,
        "Recently added" to shelves.recentlyAdded,
        "In progress" to shelves.inProgress,
        "Recommended" to shelves.recommendations,
        "Frequently played" to shelves.frequent,
    ).filter { it.second.isNotEmpty() }

    if (rows.isEmpty()) {
        Text("Nothing here yet.", color = TextMuted)
        return
    }

    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        items(rows) { (title, items) ->
            Column {
                SectionLabel(title)
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(items, key = { it.itemId + it.provider }) { item -> TvLibraryTile(item, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun TvItemGrid(items: List<MaItem>, onOpen: (MaItem) -> Unit) {
    if (items.isEmpty()) {
        Text("Nothing here.", color = TextMuted)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = { it.itemId + it.provider }) { item -> TvLibraryTile(item, onOpen, fullWidth = true) }
    }
}

@Composable
private fun TvLibraryTile(item: MaItem, onOpen: (MaItem) -> Unit, fullWidth: Boolean = false) {
    val accent = LocalAccent.current
    TvTile(
        onClick = { onOpen(item) },
        modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(160.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            AlbumArt(
                url = item.image,
                glow = accent,
                placeholder = Icons.Default.MusicNote,
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(),
                radius = 8.dp,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            Text(
                item.name, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
