package com.engabd.sendpin.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.LibraryShelves
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.tv.design.TvButton
import com.engabd.sendpin.tv.design.TvError
import com.engabd.sendpin.tv.design.TvTile
import com.engabd.sendpin.ui.design.AlbumArt
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.screens.formatDuration
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Reuses [LibraryViewModel] exactly as the phone's `LibraryScreen.kt` does —
 * same `shelves`/`node`/`depth`/`open`/`back` — with a 10-foot layout instead of
 * the phone's touch grid. Root shows the library's categories and its shelves;
 * opening a browsable item pushes [LibraryViewModel]'s own browse stack, matching
 * phone behaviour, so Back (the remote's actual Back key, wired below) walks out
 * the same way it walked in.
 *
 * The root used to show the shelves alone, so a library without any (a new one,
 * or one whose server keeps no play history) offered nothing to open at all: no
 * way to the albums, the artists or the genres. The categories now come first.
 */
@Composable
fun TvLibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val node by viewModel.node.collectAsStateWithLifecycle()
    val depth by viewModel.depth.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val nav = rememberTvBrowseNav(depth)

    BackHandler(enabled = depth > 0) { nav.back(viewModel) }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        TvPageTitle(if (depth > 0) node.title else "Library", busy = loading)
        error?.let { TvLoadError(it) { viewModel.refresh() } }

        when {
            !hasServer -> Text("No library connected yet. Set one up in Settings.", color = TextMuted)
            !ready -> Text("Connecting…", color = TextMuted)
            depth > 0 -> TvNodeContent(node, depth, viewModel, nav)
            else -> TvLibraryRoot(categories = node.items, shelves = shelves, nav = nav, onOpen = { nav.open(viewModel, it) })
        }
    }
}

/** A page title, with a quiet "Loading…" beside it while the page is being fetched. */
@Composable
internal fun TvPageTitle(title: String, busy: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = TextPrimary, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (busy) {
            Spacer(Modifier.width(16.dp))
            Text("Loading…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(20.dp))
}

/** What went wrong loading the page, with a way to try again. */
@Composable
internal fun TvLoadError(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            "Couldn't load everything: $message",
            color = TvError, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        TvButton(onClick = onRetry) { Text("Retry") }
    }
}

/**
 * Remembers where the D-pad was while the user browses, so Back puts it back.
 *
 * Opening an album replaces the tile that was focused, and Back used to leave the
 * focus wherever Compose put it (the top of the rail). Each open records the tile
 * it came from; each Back asks that tile to take the focus again once it is back
 * on screen. The scroll positions are kept per level for the same reason: a tile
 * that has scrolled out of view cannot take the focus.
 */
internal class TvBrowseNav {
    /** The tile opened at each level, deepest last. */
    val opened = mutableStateListOf<String>()
    /** The tile that should take the focus as soon as it is composed again. */
    var refocus by mutableStateOf<String?>(null)
    val rootList = LazyListState()
    val rowStates = mutableStateMapOf<String, LazyListState>()
    val gridStates = mutableStateMapOf<Int, LazyGridState>()
    val listStates = mutableStateMapOf<Int, LazyListState>()

    fun open(viewModel: LibraryViewModel, item: MaItem) {
        if (item.browsable || item.provider == com.engabd.sendpin.ma.CATEGORY_PROVIDER) {
            val from = viewModel.depth.value
            trimTo(from)
            // Levels entered some other way (a search launched mid-browse) have no
            // tile to return to.
            while (opened.size < from) opened.add("")
            opened.add(tvKey(item))
            gridStates.remove(from + 1)
            listStates.remove(from + 1)
        }
        viewModel.open(item)
    }

    fun back(viewModel: LibraryViewModel) {
        if (viewModel.back()) refocus = opened.removeLastOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The levels the view model no longer has (a library switch, a jump to the top). */
    fun trimTo(depth: Int) {
        while (opened.size > depth) opened.removeAt(opened.lastIndex)
    }
}

@Composable
internal fun rememberTvBrowseNav(depth: Int): TvBrowseNav {
    val nav = remember { TvBrowseNav() }
    LaunchedEffect(depth) { nav.trimTo(depth) }
    return nav
}

internal fun tvKey(item: MaItem) = item.itemId + item.provider

/** Takes the focus when [nav] says this tile is the one Back returned to. */
@Composable
private fun Modifier.tvRefocus(nav: TvBrowseNav, key: String): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(nav.refocus == key) {
        if (nav.refocus == key) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            nav.refocus = null
        }
    }
    return focusRequester(requester)
}

@Composable
private fun TvLibraryRoot(
    categories: List<MaItem>,
    shelves: LibraryShelves,
    nav: TvBrowseNav,
    onOpen: (MaItem) -> Unit,
) {
    val rows = listOf(
        "Recently played" to shelves.recent,
        "Favourite albums" to shelves.favoriteAlbums,
        "Favourite artists" to shelves.favoriteArtists,
        "Recently added" to shelves.recentlyAdded,
        "In progress" to shelves.inProgress,
        "Recommended" to shelves.recommendations,
        "Frequently played" to shelves.frequent,
    ).filter { it.second.isNotEmpty() }

    LazyColumn(
        state = nav.rootList,
        // Every list, row and grid on these screens remembers its focused child, so
        // Left to the rail and Right again lands on the same tile, not the nearest.
        modifier = Modifier.focusRestorer(),
        // Room for a focused tile to grow without its edge being clipped.
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (categories.isNotEmpty()) {
            item(key = "browse") {
                Column {
                    SectionLabel("Browse")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(10.dp),
                        state = nav.rowStates.getOrPut("browse") { LazyListState() },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(categories, key = ::tvKey) { item -> TvCategoryTile(item, nav, onOpen) }
                    }
                }
            }
        }
        items(rows, key = { it.first }) { (title, items) ->
            Column {
                SectionLabel(title)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(10.dp),
                    state = nav.rowStates.getOrPut(title) { LazyListState() },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = ::tvKey) { item -> TvLibraryTile(item, nav, onOpen) }
                }
            }
        }
    }
}

private fun categoryIcon(id: String): ImageVector = when (id) {
    "artists" -> Icons.Default.Person
    "albums" -> Icons.Default.Album
    "tracks" -> Icons.Default.MusicNote
    "playlists" -> Icons.AutoMirrored.Filled.QueueMusic
    "genres" -> Icons.Default.Category
    "starred" -> Icons.Default.Favorite
    "newest" -> Icons.Default.NewReleases
    "random" -> Icons.Default.Shuffle
    "radios" -> Icons.Default.Radio
    "podcasts" -> Icons.Default.Podcasts
    else -> Icons.Default.MusicNote
}

@Composable
private fun TvCategoryTile(item: MaItem, nav: TvBrowseNav, onOpen: (MaItem) -> Unit) {
    val accent = LocalAccent.current
    TvTile(
        onClick = { onOpen(item) },
        modifier = Modifier.width(150.dp).tvRefocus(nav, tvKey(item)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(26.dp))
            Text(
                item.name, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A browsed page: an album or playlist as a track list with Play and Shuffle, a
 * category as a grid, and a page with sections (an artist) as one row per section.
 *
 * Every page used to be a grid of cover tiles, so an album was its own cover
 * repeated once per track with no way to play it from the top.
 */
@Composable
internal fun TvNodeContent(node: LibraryViewModel.Node, level: Int, viewModel: LibraryViewModel, nav: TvBrowseNav) {
    val onOpen: (MaItem) -> Unit = { nav.open(viewModel, it) }
    val playable = node.items.filter { it.playable }
    when {
        node.items.isEmpty() && node.sections.isEmpty() -> Text("Nothing here.", color = TextMuted)
        node.sections.isNotEmpty() -> {
            LazyColumn(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(10.dp),
                state = nav.listStates.getOrPut(level) { LazyListState() },
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                node.sections.forEach { (title, items) ->
                    item(key = "section:$title") {
                        Column {
                            SectionLabel(title)
                            Spacer(Modifier.height(10.dp))
                            if (items.all { it.mediaType == "track" }) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items.forEachIndexed { i, track -> TvTrackRow(track, i + 1, nav, onOpen) }
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier.focusRestorer(),
                                    contentPadding = PaddingValues(10.dp),
                                    state = nav.rowStates.getOrPut("$level:$title") { LazyListState() },
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(items, key = ::tvKey) { item -> TvLibraryTile(item, nav, onOpen) }
                                }
                            }
                        }
                    }
                }
            }
        }
        node.items.all { it.mediaType == "track" } -> {
            LazyColumn(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(10.dp),
                state = nav.listStates.getOrPut(level) { LazyListState() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (playable.isNotEmpty()) {
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            TvButton(onClick = { viewModel.playAll(playable) }) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Play")
                            }
                            TvButton(onClick = { viewModel.playAll(playable.shuffled()) }) {
                                Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Shuffle")
                            }
                        }
                    }
                }
                items(node.items.size, key = { tvKey(node.items[it]) + ":" + it }) { i ->
                    TvTrackRow(node.items[i], node.items[i].trackNumber ?: (i + 1), nav, onOpen)
                }
            }
        }
        else -> TvItemGrid(node.items, nav, nav.gridStates.getOrPut(level) { LazyGridState() }, onOpen)
    }
}

@Composable
private fun TvItemGrid(items: List<MaItem>, nav: TvBrowseNav, state: LazyGridState, onOpen: (MaItem) -> Unit) {
    LazyVerticalGrid(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(10.dp),
        state = state,
        columns = GridCells.Adaptive(minSize = 180.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = ::tvKey) { item -> TvLibraryTile(item, nav, onOpen, fullWidth = true) }
    }
}

/** One song in a list: its number, title, artist and length. OK plays it. */
@Composable
internal fun TvTrackRow(track: MaItem, number: Int, nav: TvBrowseNav?, onOpen: (MaItem) -> Unit) {
    TvTile(
        onClick = { onOpen(track) },
        modifier = Modifier.fillMaxWidth().then(if (nav != null) Modifier.tvRefocus(nav, tvKey(track)) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.toString(), color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(36.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.name, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                track.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            track.duration?.takeIf { it > 0 }?.let {
                Spacer(Modifier.width(16.dp))
                Text(formatDuration(it), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun TvLibraryTile(item: MaItem, nav: TvBrowseNav?, onOpen: (MaItem) -> Unit, fullWidth: Boolean = false) {
    val accent = LocalAccent.current
    TvTile(
        onClick = { onOpen(item) },
        modifier = (if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(160.dp))
            .then(if (nav != null) Modifier.tvRefocus(nav, tvKey(item)) else Modifier),
    ) {
        Column(Modifier.padding(10.dp)) {
            AlbumArt(
                url = item.image,
                glow = accent,
                placeholder = Icons.Default.MusicNote,
                modifier = Modifier.aspectRatio(1f).fillMaxWidth(),
                radius = 8.dp,
            )
            Spacer(Modifier.height(8.dp))
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
