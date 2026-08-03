package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.LibraryViewModel.Backend
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

/**
 * The library in the same OLED language as Now Playing: a single album-derived
 * bloom over true black, glass rows, and covers doing the talking. Everything
 * with artwork gets a cover tile rather than a list row — a wall of art is the
 * point of a music library.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    onAlbumClick: (MaItem) -> Unit = { viewModel.open(it) },
    onArtistClick: (MaItem) -> Unit = { viewModel.open(it) },
    onPlaylistClick: (MaItem) -> Unit = { viewModel.open(it) },
) {
    val ready by viewModel.ready.collectAsState()
    val booted by viewModel.booted.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val hasServer by viewModel.hasServer.collectAsState()
    val connError by viewModel.connError.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val node by viewModel.node.collectAsState()
    val depth by viewModel.depth.collectAsState()
    val search by viewModel.search.collectAsState()
    val searchOpen by viewModel.searchOpen.collectAsState()
    val query by viewModel.query.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val showCreatePlaylist by viewModel.showCreatePlaylist.collectAsState()
    val addingToPlaylist by viewModel.addingToPlaylist.collectAsState()
    val playlistChoices by viewModel.playlistChoices.collectAsState()
    val palette = LocalPalette.current
    val snackbar = remember { SnackbarHostState() }
    // Long-press target. Hoisted to the screen so the sheet is a sibling of the
    // grid rather than a child of a row that scrolls out from under it.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = depth > 0 || searchOpen) { viewModel.back() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(palette.swatch(0), 520.dp, (-120).dp, (-260).dp, 0.30f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Header(
                title = if (depth > 0 && !searchOpen) node.title else "Library",
                showBack = depth > 0 || searchOpen,
                backend = backend,
                query = query,
                onQuery = viewModel::setQuery,
                onBack = { viewModel.back() },
                onSearch = viewModel::doSearch,
                onSonicSearch = viewModel::sonicSearch,
                onClearSearch = viewModel::clearSearch,
                refreshing = refreshing,
                // Only offer it once there is a library to re-read; on the connect
                // form it would just be a button that does nothing.
                onRefresh = if (ready) viewModel::refresh else null,
            )
            // Only offer the connect form once we know there's nothing to connect
            // to. Showing it while a saved server is still handshaking made every
            // visit to this tab flash what looks like the onboarding screen.
            when {
                ready -> Browse(
                    viewModel, onAlbumClick, onArtistClick, onPlaylistClick,
                    onLongPress = { actionsFor = it },
                )
                booted && !connecting && (!hasServer || connError != null) -> ConnectForm(viewModel, backend)
                else -> ConnectingState()
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = navBarInset() + 8.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(containerColor = Ink3, contentColor = TextPrimary, shape = RoundedCornerShape(14.dp)) {
                Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Long-press: play or queue anything without losing what's playing, and take
        // a whole album offline in one gesture.
        actionsFor?.let { picked ->
            MediaActionsSheet(
                item = picked,
                onClose = { actionsFor = null },
                onPlayNow = { viewModel.play(picked, "replace") },
                onPlayNext = { viewModel.play(picked, "next") },
                onAddToQueue = { viewModel.play(picked, "add") },
                // Downloads are a Navidrome feature; the ViewModel says so itself for
                // an MA item, so the row is only offered where it can work.
                onDownload = if (picked.provider == SubsonicClient.PROVIDER) {
                    { viewModel.download(picked) }
                } else null,
                // A playlist has nothing to be filed into but itself, and an artist
                // resolves to albums rather than tracks — so neither is offered.
                onAddToPlaylist = if (picked.mediaType == "track" || picked.mediaType == "album") {
                    { viewModel.openAddToPlaylist(picked) }
                } else null,
                // Only playlists can be deleted; the ViewModel routes to whichever
                // backend owns the one picked.
                onDelete = if (picked.mediaType == "playlist") {
                    { viewModel.deletePlaylist(picked) }
                } else null,
            )
        }

        // Create playlist. The dialog is driven from the ViewModel so the library
        // can reopen it after a failed attempt without the screen holding state.
        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onDismiss = viewModel::closeCreatePlaylist,
                onCreate = viewModel::createPlaylist,
            )
        }

        addingToPlaylist?.let { pending ->
            PlaylistPickerSheet(
                itemName = pending.name,
                playlists = playlistChoices,
                onClose = viewModel::closeAddToPlaylist,
                onPick = viewModel::addToPlaylist,
            )
        }
    }
}

@Composable
private fun Header(
    title: String,
    showBack: Boolean,
    backend: Backend,
    query: String,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onSonicSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack),
                )
                Spacer(Modifier.width(12.dp))
            }
            // Title and backend tag share the slack inside their own row, so the tag
            // keeps hugging the title and Refresh still lands in the corner. Giving
            // the title and a spacer a weight each in the outer row would have capped
            // a long node title at half the width to make room for whitespace.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                // Which backend is on is a *setting* now, not a control that lives
                // here — two places to change it meant the greyed-out Speakers and
                // Lights tabs could disagree with what the library was browsing.
                BackendTag(if (backend == Backend.SUBSONIC) "Navidrome" else "Music Assistant")
            }
            onRefresh?.let {
                Spacer(Modifier.width(10.dp))
                RefreshButton(refreshing = refreshing, onClick = it)
            }
        }
        Spacer(Modifier.height(12.dp))
        SearchField(query, onQuery, onSearch, onClearSearch)
        // Sonic search: the same box, read as a description of a *sound* rather
        // than a name. Finding music belongs in the library, not behind the
        // player's queue button.
        if (query.isNotBlank() && backend == Backend.MA) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(Icons.Default.GraphicEq, "Sounds like this") { onSonicSearch(query) }
            }
        }
    }
}

/**
 * Re-read the library from the server.
 *
 * Spins while the fetch is in flight and ignores taps until it lands, so a user who
 * doesn't see an instant change can't stack up half a dozen identical requests.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "refreshAngle",
    )
    Icon(
        Icons.Default.Refresh,
        contentDescription = "Refresh library",
        tint = if (refreshing) LocalAccent.current else TextSecondary,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = !refreshing, onClick = onClick)
            .padding(6.dp)
            .graphicsLayer { rotationZ = if (refreshing) angle else 0f },
    )
}

/** A quiet read-only badge naming the library backend Settings has selected. */
@Composable
private fun BackendTag(label: String) {
    Text(
        label, color = TextFaint, fontFamily = AppFont, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A compact labelled action chip. */
@Composable
private fun SmallChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(100)).background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(100))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, tint = LocalAccent.current, modifier = Modifier.size(14.dp))
        Text(label, color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Color.White.a(0.045f))
            .border(1.dp, Hairline, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search artists, albums, tracks…", color = TextFaint,
                    fontFamily = AppFont, fontSize = 14.sp, maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { onQuery(it); if (it.isBlank()) onClear() },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query); focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close, "Clear", tint = TextMuted,
                modifier = Modifier.size(16.dp).clip(CircleShape)
                    .clickable { onClear(); focus.clearFocus() },
            )
        }
    }
}

// --- browse ---------------------------------------------------------------

private const val COLS = 6              // 6-col base: covers span 2, categories 3, rows 6
private fun full() = GridItemSpan(COLS)

@Composable
private fun Browse(
    viewModel: LibraryViewModel,
    onAlbumClick: (MaItem) -> Unit,
    onArtistClick: (MaItem) -> Unit,
    onPlaylistClick: (MaItem) -> Unit,
    // A parameter rather than composition state: the sections below are LazyGridScope
    // extensions, which are not composable and can't read a CompositionLocal.
    onLongPress: (MaItem) -> Unit,
) {
    val node by viewModel.node.collectAsState()
    val depth by viewModel.depth.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val search by viewModel.search.collectAsState()
    val searchOpen by viewModel.searchOpen.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val favoriteAlbums by viewModel.favoriteAlbums.collectAsState()
    val favoriteArtists by viewModel.favoriteArtists.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val frequent by viewModel.frequent.collectAsState()
    val jobs by viewModel.downloadJobs.collectAsState()
    val offline by viewModel.offline.collectAsState()

    val s = if (searchOpen) search else null
    val isDownloads = node.items.any { it.provider == "__dl__" } || node.title == "Downloads"

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLS),
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = navBarInset() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Running on downloads alone is a working state, not a failure — say so
        // once, at the top, and let the rest of the screen behave normally.
        if (offline) {
            item(span = { full() }) { OfflineNotice { viewModel.connect() } }
        }
        if (error != null) {
            item(span = { full() }) { SearchErrorState(error!!) { viewModel.connect() } }
            return@LazyVerticalGrid
        }
        if (loading) {
            items(6, span = { full() }) { SkeletonRow() }
            return@LazyVerticalGrid
        }

        if (s != null) {
            searchSection("Artists", s.artists, viewModel, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress)
            searchSection("Albums", s.albums, viewModel, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress)
            searchSection("Tracks", s.tracks, viewModel, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress)
            searchSection("Playlists", s.playlists, viewModel, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress)
            if (s.artists.isEmpty() && s.albums.isEmpty() && s.tracks.isEmpty() && s.playlists.isEmpty()) {
                item(span = { full() }) { SearchEmptyState() }
            }
            return@LazyVerticalGrid
        }

        if (isDownloads) {
            downloadsSection(node.items, jobs, viewModel)
            return@LazyVerticalGrid
        }

        if (depth == 0) {
            // Root shelf: the category grid, then dynamic shelves of content.
            items(node.items, span = { GridItemSpan(3) }) { cat ->
                CategoryCard(cat) { viewModel.open(cat) }
            }
            val openItem: (MaItem) -> Unit = { item ->
                when (item.mediaType) {
                    "album" -> onAlbumClick(item)
                    "artist" -> onArtistClick(item)
                    "playlist" -> onPlaylistClick(item)
                    else -> viewModel.open(item)
                }
            }
            // Favourites lead: what you chose to keep is a better front page than
            // whatever the scanner saw last. The rest keep their old order behind them.
            shelf("Favourite albums", favoriteAlbums, openItem, onLongPress)
            shelf("Favourite artists", favoriteArtists, openItem, onLongPress)
            shelf("Continue listening", inProgress, openItem, onLongPress)
            shelf("Recently added", recentlyAdded, openItem, onLongPress)
            shelf("For you", recommendations, openItem, onLongPress)
            shelf("Recently played", recent, openItem, onLongPress)
            // Navidrome only — MA has no equivalent of getAlbumList2(frequent), so
            // the list is empty on that backend and the shelf hides itself.
            shelf("Played most", frequent, openItem, onLongPress)
            return@LazyVerticalGrid
        }

        // Creating a playlist belongs where playlists are, not on the root — and
        // it has to come before the empty-state return, or a library with no
        // playlists yet would be the one place you can't make one.
        val inPlaylists = !searchOpen && node.title == "Playlists"
        if (inPlaylists) {
            item(span = { full() }) { NewPlaylistRow(viewModel::openCreatePlaylist) }
        }

        if (node.items.isEmpty()) {
            item(span = { full() }) { if (inPlaylists) Unit else SearchEmptyState() }
            return@LazyVerticalGrid
        }

        // A shelf of albums earns cover tiles; anything else reads better as rows.
        val artful = node.items.count { it.image != null && it.mediaType in setOf("album", "playlist") }
        if (artful >= node.items.size / 2 && artful > 0) {
            items(node.items, span = { GridItemSpan(2) }) { entry ->
                CoverTile(entry, onLongPress = { onLongPress(entry) }) {
                    when (entry.mediaType) {
                        "album" -> onAlbumClick(entry)
                        "artist" -> onArtistClick(entry)
                        "playlist" -> onPlaylistClick(entry)
                        else -> viewModel.open(entry)
                    }
                }
            }
        } else {
            val tracks = node.items.filter { it.playable && it.mediaType == "track" }
            if (tracks.size > 1) {
                // Downloading a Navidrome album one row at a time was never a real
                // answer, so the whole list is one tap from disk.
                val downloadable = tracks.filter { it.provider == "subsonic" }
                item(span = { full() }) {
                    PlayAllBar(
                        count = tracks.size,
                        onPlayAll = { viewModel.playAll(tracks) },
                        onDownloadAll = if (downloadable.isEmpty()) null
                        else ({ viewModel.downloadAll(downloadable) }),
                    )
                }
            }
            items(node.items, span = { full() }) { entry ->
                val click: (() -> Unit)? = when (entry.mediaType) {
                    "album" -> { { onAlbumClick(entry) } }
                    "artist" -> { { onArtistClick(entry) } }
                    "playlist" -> { { onPlaylistClick(entry) } }
                    else -> null
                }
                ItemRow(entry, viewModel, click, onLongPress)
            }
        }
    }
}

/** One titled row of cover tiles, hidden when the shelf has nothing in it. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.shelf(
    title: String,
    list: List<MaItem>,
    onOpen: (MaItem) -> Unit,
    onLongPress: (MaItem) -> Unit,
) {
    if (list.isEmpty()) return
    item(span = { full() }) { Shelf(title) }
    items(list, span = { GridItemSpan(2) }) { entry ->
        CoverTile(entry, onLongPress = { onLongPress(entry) }) { onOpen(entry) }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.searchSection(
    title: String, list: List<MaItem>, viewModel: LibraryViewModel,
    onAlbumClick: (MaItem) -> Unit,
    onArtistClick: (MaItem) -> Unit,
    onPlaylistClick: (MaItem) -> Unit,
    onLongPress: (MaItem) -> Unit,
) {
    if (list.isEmpty()) return
    item(span = { full() }) { Shelf(title) }
    items(list, span = { full() }) { entry ->
        val click: (() -> Unit)? = when (entry.mediaType) {
            "album" -> { { onAlbumClick(entry) } }
            "artist" -> { { onArtistClick(entry) } }
            "playlist" -> { { onPlaylistClick(entry) } }
            else -> null
        }
        ItemRow(entry, viewModel, click, onLongPress)
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.downloadsSection(
    items: List<MaItem>, jobs: List<DownloadJob>, viewModel: LibraryViewModel,
) {
    if (jobs.isNotEmpty()) {
        item(span = { full() }) { Shelf("In progress") }
        items(jobs, span = { full() }, key = { "j_" + it.id }) { job -> DownloadJobRow(job, viewModel) }
    }
    if (items.isEmpty() && jobs.isEmpty()) {
        item(span = { full() }) { SearchEmptyState("Nothing downloaded", "Downloaded tracks play with the server off.") }
        return
    }
    if (items.isNotEmpty()) {
        item(span = { full() }) { Shelf("On this device") }
        if (items.size > 1) {
            item(span = { full() }) { PlayAllBar(items.size, onPlayAll = { viewModel.playAll(items) }) }
        }
        items(items, span = { full() }) { entry -> ItemRow(entry, viewModel) }
    }
}

// --- pieces ---------------------------------------------------------------

@Composable
private fun Shelf(text: String) {
    Box(Modifier.padding(top = 12.dp, bottom = 2.dp)) { SectionLabel(text) }
}

/** The "New playlist" affordance at the top of the Playlists list. */
@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Glass)
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(20.dp))
        Text(
            "New playlist",
            color = TextPrimary, fontFamily = AppFont,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

/**
 * Name a new playlist. It is created empty — both backends make one that way,
 * and tracks go in afterwards — so the caption says so rather than leaving the
 * user waiting for a picker that isn't coming.
 */
@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val accent = LocalAccent.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink2,
        title = {
            Text(
                "New playlist", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.ExtraBold, fontSize = 17.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name", color = TextFaint, fontFamily = AppFont) },
                    colors = accentTextFieldColors(accent),
                )
                Text(
                    "Created empty - add tracks to it afterwards.",
                    color = TextMuted, fontFamily = AppFont, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create", color = if (name.isBlank()) TextFaint else accent, fontFamily = AppFont, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted, fontFamily = AppFont)
            }
        },
    )
}

@Composable
private fun CategoryCard(item: MaItem, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.a(0.035f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.12f))
                .border(1.dp, accent.a(0.22f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(18.dp)) }
        Text(
            item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(16.dp))
    }
}

private fun categoryIcon(id: String): ImageVector = when (id) {
    "artists" -> Icons.Default.Person
    "albums" -> Icons.Default.Album
    "tracks" -> Icons.Default.MusicNote
    "playlists" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "downloads" -> Icons.Default.Download
    "newest" -> Icons.Default.Schedule
    else -> Icons.AutoMirrored.Filled.QueueMusic
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(item: MaItem, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        if (onLongPress != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(14.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Ink3)
                .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val art = rememberArtRequest(item.image, pixels = 200)
            if (art != null) {
                AsyncImage(
                    model = art, contentDescription = item.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Album, null, tint = TextFaint, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let {
            Text(
                it, color = Color.White.a(0.38f), fontFamily = AppFont, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowCard(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.a(0.03f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .then(
                when {
                    onLongClick != null ->
                        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ItemRow(
    item: MaItem,
    viewModel: LibraryViewModel,
    onClick: (() -> Unit)? = null,
    onLongPress: ((MaItem) -> Unit)? = null,
) {
    val accent = LocalAccent.current
    val isCategory = item.provider == "__cat__"
    val isDownload = item.provider == "__dl__"
    val isSubsonic = item.provider == "subsonic"
    val isSubsonicTrack = isSubsonic && item.mediaType == "track"
    // Artists and genres resolve to a lot of files; offering to download one by
    // accident from a list row would be a nasty surprise, so only the things a
    // user thinks of as "a download" get the button.
    val downloadable = isSubsonic && item.mediaType in setOf("track", "album", "playlist")
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val onDisk = item.itemId in downloadedIds

    // A category card opens a list and a download row is already on the phone —
    // neither has anything the actions sheet could offer.
    val longPressable = !isCategory && !isDownload &&
        item.mediaType in setOf("track", "album", "artist", "playlist", "radio")

    RowCard(
        onClick = {
            if (onClick != null) onClick()
            else viewModel.open(item)
        },
        onLongClick = if (longPressable && onLongPress != null) {
            { onLongPress(item) }
        } else null,
    ) {
        when {
            isCategory -> Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(20.dp)) }

            item.image != null -> {
                val art = rememberArtRequest(item.image, pixels = 120)
                AsyncImage(
                    model = art, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Ink3),
                )
            }

            item.mediaType == "artist" ->
                GradientAvatar(item.name, item.name.hashCode(), size = 46.dp)

            else -> Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Glass),
                contentAlignment = Alignment.Center,
            ) { Icon(mediaIcon(item.mediaType), null, tint = TextMuted, modifier = Modifier.size(18.dp)) }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(
                    it, color = Color.White.a(0.38f), fontFamily = AppFont, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // MA tracks can be auditioned in place; Navidrome has no preview endpoint.
        val isMaTrack = !isCategory && !isDownload && !isSubsonic && item.mediaType == "track"
        if (isMaTrack) {
            val previewing by viewModel.previewing.collectAsState()
            Icon(
                if (previewing == item.itemId) Icons.Default.StopCircle else Icons.Default.Headphones,
                if (previewing == item.itemId) "Stop preview" else "Preview",
                tint = if (previewing == item.itemId) accent else TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.togglePreview(item) },
            )
        }
        // Favourites are a Navidrome star as much as an MA favourite, so the heart
        // belongs on both backends rather than only one — and on albums and artists
        // as much as tracks. `music/favorites/add_item` takes any media item's uri
        // and Subsonic's `star` has an `albumId`/`artistId` for exactly this; the
        // heart was gated to MA *tracks* for no reason either server imposed.
        //
        // Subsonic's `star` has no playlist parameter, so that one really is
        // backend-specific.
        val favouritable = !isCategory && !isDownload && when {
            isSubsonic -> item.mediaType in setOf("track", "album", "artist")
            else -> item.mediaType in setOf("track", "album", "artist", "playlist")
        }
        if (favouritable) {
            val favorites by viewModel.favorites.collectAsState()
            val isFav = item.itemId in favorites
            Icon(
                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFav) "Remove from favourites" else "Add to favourites",
                tint = if (isFav) accent else TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.toggleFavorite(item) },
            )
        }

        when {
            // A track already on disk says so instead of offering to fetch it again.
            downloadable && onDisk -> Icon(
                Icons.Default.DownloadDone, "Downloaded", tint = accent,
                modifier = Modifier.size(20.dp),
            )
            downloadable -> Icon(
                Icons.Default.Download,
                if (item.mediaType == "track") "Download" else "Download all",
                tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.download(item) },
            )
            isDownload -> Icon(
                Icons.Default.Delete, "Delete download", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.deleteDownload(item.itemId) },
            )
        }

        if (item.playable || isDownload) {
            Icon(
                Icons.Default.Add, "Add to queue", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.play(item, "add") },
            )
        }

        when {
            isCategory || item.browsable -> Icon(Icons.Default.ChevronRight, null, tint = TextFaint, modifier = Modifier.size(16.dp))
            item.playable -> Icon(Icons.Default.PlayArrow, "Play", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

private fun mediaIcon(mediaType: String): ImageVector = when (mediaType) {
    "artist" -> Icons.Default.Person
    "album" -> Icons.Default.Album
    "playlist" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "radio" -> Icons.Default.Radio
    else -> Icons.Default.MusicNote
}

@Composable
private fun DownloadJobRow(job: DownloadJob, viewModel: LibraryViewModel) {
    val accent = LocalAccent.current
    RowCard {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Glass),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                job.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (job.failed) "Failed · tap to dismiss" else "Downloading · ${(job.fraction * 100).toInt()}%",
                color = if (job.failed) ErrorRed else accent,
                fontFamily = AppFont, fontSize = 12.sp, maxLines = 1,
            )
        }
        if (job.failed) {
            Icon(
                Icons.Default.Refresh, "Dismiss", tint = ErrorRed,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { viewModel.dismissDownload(job.id) },
            )
        } else {
            RingProgress(job.fraction)
        }
    }
}

@Composable
private fun PlayAllBar(count: Int, onPlayAll: () -> Unit, onDownloadAll: (() -> Unit)? = null) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.a(0.10f))
            .border(1.dp, accent.a(0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlayAll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                "Play all $count tracks", color = accent, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
        onDownloadAll?.let {
            Icon(
                Icons.Default.Download, "Download all", tint = accent,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = it),
            )
        }
    }
}

/**
 * The server is out of reach and the phone is running on its downloads. This is
 * the feature working, not an error, so it reads as a state rather than a fault —
 * with the one action that matters if the server is actually back.
 */
@Composable
private fun OfflineNotice(onRetry: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.a(0.04f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.CloudOff, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TitleGap)) {
            Text(
                "Offline", color = TextPrimary, fontFamily = AppFont,
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text(
                "Navidrome isn't reachable - playing your downloads.",
                color = TextMuted, fontFamily = AppFont, fontSize = 11.sp,
            )
        }
        Text(
            "Retry", color = accent, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(100)).clickable(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// --- states ---------------------------------------------------------------

/** Placeholder rows while a saved server finishes handshaking. */
@Composable
private fun ConnectingState() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { SkeletonRow() }
    }
}

@Composable
private fun SkeletonRow() {
    RowCard {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.a(0.06f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.a(0.07f)))
            Box(Modifier.fillMaxWidth(0.38f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.a(0.05f)))
        }
    }
}

@Composable
private fun SearchEmptyState(
    title: String = "No results",
    body: String = "Try a different search, or check your spelling.",
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Glass)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Search, null, tint = TextFaint, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White.a(0.75f), fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            body, color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

@Composable
private fun SearchErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(ErrorRed.a(0.10f))
                .border(1.dp, ErrorRed.a(0.28f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Couldn't load", color = Color.White.a(0.8f), fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            message, color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.clip(RoundedCornerShape(100)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(100))
                .clickable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 11.dp),
        ) { Text("Retry", color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

// --- connect --------------------------------------------------------------

@Composable
private fun ConnectForm(viewModel: LibraryViewModel, backend: Backend) {
    val connecting by viewModel.connecting.collectAsState()
    val connError by viewModel.connError.collectAsState()
    val accent = LocalAccent.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = navBarInset()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (backend == Backend.MA) {
            val url by viewModel.maUrl.collectAsState()
            val user by viewModel.maUser.collectAsState()
            val pass by viewModel.maPass.collectAsState()
            SectionLabel("Music Assistant server")
            GlassField("Server URL", url, viewModel::setMaUrl, "http://192.168.0.10:8095")
            GlassField("Username", user, viewModel::setMaUser)
            GlassField("Password", pass, viewModel::setMaPass, secret = true)
        } else {
            val url by viewModel.navUrl.collectAsState()
            val user by viewModel.navUser.collectAsState()
            val pass by viewModel.navPass.collectAsState()
            SectionLabel("Navidrome / OpenSubsonic")
            Text(
                "Direct mode plays on this phone and can download for offline - it works even when Music Assistant is down.",
                color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            )
            GlassField("Server URL", url, viewModel::setNavUrl, "http://192.168.0.10:4533")
            GlassField("Username", user, viewModel::setNavUser)
            GlassField("Password", pass, viewModel::setNavPass, secret = true)
        }

        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(15.dp), ambientColor = accent, spotColor = accent)
                .clip(RoundedCornerShape(15.dp))
                .background(accent)
                .clickable(enabled = !connecting) { viewModel.connect() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
            else Text("Connect", color = Ink, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        connError?.let {
            Text(it, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GlassField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.a(0.035f))
                .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, color = TextFaint, fontFamily = AppFont, fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontFamily = AppFont, fontSize = 14.sp),
                cursorBrush = SolidColor(LocalAccent.current),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
