package com.engabd.sendpin.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.tv.design.tvDpadExitField
import com.engabd.sendpin.ui.design.SectionLabel
import com.engabd.sendpin.ui.theme.TextMuted

/**
 * Search, as its own tab. The TV had none: finding one song meant browsing to it.
 *
 * Drives the same [LibraryViewModel] search the phone uses (typing searches as it
 * goes; the keyboard's Search key runs it at once), and the same view model as the
 * Library tab, since a second one would load every shelf again for nothing. So it
 * tidies up after itself: leaving the tab walks back to the level the Library tab
 * was on and closes the search, and the Library tab finds itself where it was.
 */
@Composable
fun TvSearchScreen(viewModel: LibraryViewModel = viewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.search.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val node by viewModel.node.collectAsStateWithLifecycle()
    val depth by viewModel.depth.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val nav = rememberTvBrowseNav(depth)
    // The Library tab's level when this tab opened; everything deeper is ours.
    val baseDepth = remember { viewModel.depth.value }

    DisposableEffect(Unit) {
        onDispose {
            while (viewModel.depth.value > baseDepth) {
                if (!viewModel.back()) break
            }
            viewModel.clearSearch()
        }
    }
    BackHandler(enabled = depth > baseDepth || results != null) { nav.back(viewModel) }

    val browsing = depth > baseDepth && !searchOpen

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        TvPageTitle(if (browsing) node.title else "Search", busy = loading || searching)
        if (!hasServer) {
            Text("No library connected yet. Set one up in Settings.", color = TextMuted)
            return@Column
        }
        if (browsing) {
            TvNodeContent(node, depth, viewModel, nav)
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text("Artists, albums or songs") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.doSearch(query)
                keyboard?.hide()
            }),
            modifier = Modifier.width(560.dp).tvDpadExitField()
                .onFocusChanged { if (it.isFocused) keyboard?.show() },
        )
        Spacer(Modifier.height(24.dp))

        val found = results
        when {
            found == null -> Text(
                "Type at least a couple of letters. Results come in as you type.",
                color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            )
            found.artists.isEmpty() && found.albums.isEmpty() && found.tracks.isEmpty() && found.playlists.isEmpty() ->
                Text("Nothing found for “${query.trim()}”.", color = TextMuted)
            else -> TvSearchResults(found, nav, onOpen = { nav.open(viewModel, it) })
        }
    }
}

@Composable
private fun TvSearchResults(
    results: com.engabd.sendpin.ma.MaSearchResults,
    nav: TvBrowseNav,
    onOpen: (MaItem) -> Unit,
) {
    val rows = listOf(
        "Artists" to results.artists,
        "Albums" to results.albums,
        "Playlists" to results.playlists,
    ).filter { it.second.isNotEmpty() }
    LazyColumn(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(rows, key = { "row:" + it.first }) { (title, items) ->
            Column {
                SectionLabel(title)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(10.dp),
                    state = nav.rowStates.getOrPut("search:$title") { LazyListState() },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = ::tvKey) { item -> TvLibraryTile(item, nav, onOpen) }
                }
            }
        }
        if (results.tracks.isNotEmpty()) {
            item(key = "songs") { SectionLabel("Songs") }
            items(results.tracks.size, key = { "song:" + tvKey(results.tracks[it]) + ":" + it }) { i ->
                TvTrackRow(results.tracks[i], i + 1, nav, onOpen)
            }
        }
    }
}
