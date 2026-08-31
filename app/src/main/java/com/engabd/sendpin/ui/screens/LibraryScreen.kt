package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.engabd.sendpin.ui.design.sharedArt
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.LibraryViewModel.Backend
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.subsonic.SavedQueue
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind

/**
 * The library in the same OLED language as Now Playing: a single album-derived
 * bloom over true black, glass rows, and covers doing the talking. Everything
 * with artwork gets a cover tile rather than a list row — a wall of art is the
 * point of a music library.
 *
 * ## Where the rest of it is
 *
 * This file was 1,457 lines, and the three documents about it growing each quoted a
 * larger number than the last. It is now the screen's *structure* — the state
 * plumbing, the grid, and the `LazyGridScope` extensions that fill it — and the
 * parts that change for their own reasons are beside it:
 *
 * - **`LibraryHeader.kt`** — the title row, the search field and the controls beside
 *   them. Everything above the grid.
 * - **`LibraryTiles.kt`** — what one item looks like: category cards, cover tiles,
 *   row cards and list rows. The most-edited part, and the most-repeated on screen.
 * - **`LibraryStates.kt`** — the library when it is not a grid of music: connecting,
 *   offline, empty, failed, or asking for credentials.
 * - **`LibraryPieces.kt`** — the small shared bits: the section label, the
 *   new-playlist row, the create-playlist dialog.
 *
 * Same package, so nothing about the call sites changed. What did change is that a
 * declaration used across the new boundary had to widen from `private` to
 * `internal` — `private` is file-scoped in Kotlin, so it is not optional. Only the
 * declarations that actually cross are widened; the rest stayed private, which is
 * what keeps this file's own helpers from becoming package surface.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    gridCols: Int = 6,
    onAlbumClick: (MaItem) -> Unit = { viewModel.open(it) },
    onArtistClick: (MaItem) -> Unit = { viewModel.open(it) },
    onPlaylistClick: (MaItem) -> Unit = { viewModel.open(it) },
    /**
     * Open the storage manager. Offered only while the Downloads library is the active
     * one — bytes on disk, retries and delete-all are not things a `MusicSource` knows
     * about, so that screen stays where it is and this is a second way into it.
     */
    onManageDownloads: (() -> Unit)? = null,
) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val booted by viewModel.booted.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val connError by viewModel.connError.collectAsStateWithLifecycle()
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val node by viewModel.node.collectAsStateWithLifecycle()
    val depth by viewModel.depth.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val showCreatePlaylist by viewModel.showCreatePlaylist.collectAsStateWithLifecycle()
    val addingToPlaylist by viewModel.addingToPlaylist.collectAsStateWithLifecycle()
    val playlistChoices by viewModel.playlistChoices.collectAsStateWithLifecycle()
    val activeServerConfig by viewModel.activeServerConfig.collectAsStateWithLifecycle()
    val allServers by viewModel.allServers.collectAsStateWithLifecycle()
    // Only for the switcher's Downloads subtitle. The set is already collected for
    // the row badges deeper in, but that is inside [Browse] and this is not.
    val downloadCount by viewModel.downloadedIds
        .collectAsStateWithLifecycle()
        .let { ids -> remember { derivedStateOf { ids.value.size } } }
    val palette = LocalPalette.current
    val snackbar = remember { SnackbarHostState() }
    // Long-press target. Hoisted to the screen so the sheet is a sibling of the
    // grid rather than a child of a row that scrolls out from under it.
    var actionsFor by remember { mutableStateOf<MaItem?>(null) }
    // Library switch overlay state
    var showLibrarySwitch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }

    /**
     * Whether the search field currently holds focus — i.e. whether the keyboard is up
     * because of it.
     *
     * Back means two different things while typing, and the screen was only handling
     * one of them. Putting the keyboard away is a back press that reaches the app
     * (the IME's dismiss chevron sends one, and under the predictive-back dispatcher
     * it is not always swallowed on the way), and this screen answered it with
     * [LibraryViewModel.back], which drops the search — including `_query`, so the
     * text that had just been typed vanished with the keyboard.
     *
     * One handler decides, rather than two racing on registration order: with the
     * field focused, back only takes the focus away, which is what puts the keyboard
     * down and leaves the query and its results exactly where they were. Only once
     * the keyboard is gone does back mean "leave this search" or "go up a level".
     */
    var searchFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    BackHandler(enabled = searchFocused || depth > 0 || searchOpen) {
        if (searchFocused) focus.clearFocus() else viewModel.back()
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(palette.swatch(0), 520.dp, (-120).dp, (-260).dp, 0.30f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Header(
                title = if (depth > 0 && !searchOpen) node.title else "Library",
                showBack = depth > 0 || searchOpen,
                backend = backend,
                activeServerConfig = activeServerConfig,
                query = query,
                onQuery = viewModel::setQuery,
                onBack = { viewModel.back() },
                onSearch = viewModel::doSearch,
                onSonicSearch = viewModel::sonicSearch,
                onClearSearch = viewModel::clearSearch,
                onSearchFocus = { searchFocused = it },
                refreshing = refreshing,
                // Only offer it once there is a library to re-read; on the connect
                // form it would just be a button that does nothing.
                onRefresh = if (ready) viewModel::refresh else null,
                searching = searching,
                onLibraryBadgeClick = { showLibrarySwitch = true },
            )
            // Only offer the connect form once we know there's nothing to connect
            // to. Showing it while a saved server is still handshaking made every
            // visit to this tab flash what looks like the onboarding screen.
            when {
                ready -> Browse(
                    viewModel, gridCols, onAlbumClick, onArtistClick, onPlaylistClick,
                    onLongPress = { actionsFor = it },
                    onManageDownloads = onManageDownloads,
                )
                booted && !connecting && (!hasServer || connError != null) -> ConnectForm(viewModel, backend, activeServerConfig)
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
                // Only Music Assistant can generate a queue, and only from something
                // with a URI to seed it with.
                onStartRadio = if (backend == LibraryViewModel.Backend.MA && picked.uri != null) {
                    { viewModel.startRadio(picked) }
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

        // Library switch overlay — triggered by tapping the library badge.
        if (showLibrarySwitch) {
            LibrarySwitchOverlay(
                servers = allServers,
                activeId = activeServerConfig?.id,
                subtitleFor = { config ->
                    if (config.kind == ServerKind.DOWNLOADS) {
                        if (downloadCount == 0) "Nothing downloaded yet"
                        else "$downloadCount ${if (downloadCount == 1) "track" else "tracks"} on this phone"
                    } else {
                        config.kind.label
                    }
                },
                onDismiss = { showLibrarySwitch = false },
                onSelect = { config ->
                    viewModel.selectServer(config)
                    showLibrarySwitch = false
                },
            )
        }
    }
}
/**
 * The library's own left and right margin.
 *
 * Named because two things have to agree on it: the grid's `contentPadding`, and the
 * distance each shelf carousel bleeds back out past that padding so its tiles scroll
 * under the margin instead of stopping at it. See [Modifier.bleed].
 */
internal val LibraryEdge = 20.dp

private const val DEFAULT_COLS = 6         // 6-col base: covers span 2, categories 3, rows 6
private fun full(cols: Int = DEFAULT_COLS) = GridItemSpan(cols)

// Media-type sets, hoisted out of the per-item lambdas that test against them. Written
// inline as `mediaType in setOf(...)` these built a fresh Set for every item, on every
// pass over the list — once per row for the membership tests, and once per *element*
// for the `artful` count that decides tiles-or-rows.
/**
 * The lazy key for [item] sitting at [index] of the list [section] draws.
 *
 * Keyed by index as well as by item, because a duplicate key is a hard crash in a lazy
 * list — the moment two items claiming one are composed together, the state holder
 * throws. `provider|itemId` is not unique enough to bet on: a library item's own
 * provider is always `library` and Music Assistant numbers library items *per media
 * type*, so album 5 and track 5 are both `library|5`. Any list mixing types can
 * collide that way, and `music/recommendations` — folders flattened into one "For you"
 * shelf — could repeat an item outright.
 *
 * The id stays in the key, so a slot whose item really changed still recomposes; the
 * index goes in front of it, because that is the part nothing on the server can make
 * collide. [section] separates lists that are on screen together — the same album is
 * a favourite *and* recently added, and the seven root shelves share one grid.
 *
 * The detail screens have always keyed this way. This screen had not, and it crashed
 * on the way down the root shelves.
 */
internal fun itemKey(section: String, index: Int, item: MaItem) =
    "$section:$index:${item.provider}|${item.itemId}"

private val ArtfulTypes = setOf("album", "playlist", "podcast", "audiobook")
internal val DownloadableTypes = setOf("track", "album", "playlist")
internal val LongPressableTypes =
    setOf("track", "album", "artist", "playlist", "radio", "podcast", "podcast_episode", "audiobook")
internal val SubsonicActionTypes = setOf("track", "album", "artist")
internal val MaActionTypes =
    setOf("track", "album", "artist", "playlist", "podcast", "podcast_episode", "audiobook")

@Composable
private fun Browse(
    viewModel: LibraryViewModel,
    gridCols: Int,
    onAlbumClick: (MaItem) -> Unit,
    onArtistClick: (MaItem) -> Unit,
    onPlaylistClick: (MaItem) -> Unit,
    // A parameter rather than composition state: the sections below are LazyGridScope
    // extensions, which are not composable and can't read a CompositionLocal.
    onLongPress: (MaItem) -> Unit,
    onManageDownloads: (() -> Unit)? = null,
) {
    val node by viewModel.node.collectAsStateWithLifecycle()
    val depth by viewModel.depth.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val savedQueue by viewModel.savedQueue.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val jobs by viewModel.downloadJobs.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()

    // Collected once for the whole grid. These three used to be read *inside* ItemRow
    // and its children, so every visible row stood up three flow collectors of its
    // own, each torn down and rebuilt as rows recycled during a scroll. The rows now
    // take plain booleans, which also lets them skip when nothing about them changed.
    val downloadedIds by viewModel.downloadedIds.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val previewingId by viewModel.previewing.collectAsStateWithLifecycle()
    val rows = RowState(downloadedIds, favorites, previewingId)

    val s = if (searchOpen) search else null

    // Everything derived from the node's item list is computed once here, keyed on the
    // list itself. It cannot live inside the LazyVerticalGrid content lambda below:
    // that lambda is a LazyGridScope receiver, not a composable, so it has no
    // `remember` — every scan written inside it is redone in full each time the grid
    // rebuilds, which is on any of this screen's state changes.
    // The automatic offline fallback still injects a flat "Downloads" category, and
    // that one has no artists or albums to browse. Narrowed to exactly that case:
    // Downloads is a real library now, and its own browse nodes carry `__dl__` items
    // too — sniffing for the provider would have forced the flat rendering on the
    // whole of it.
    val activeConfig by viewModel.activeServerConfig.collectAsStateWithLifecycle()
    val isDownloads = remember(node, offline) { offline && node.title == "Downloads" }
    val isDownloadsLibrary = activeConfig?.kind == ServerKind.DOWNLOADS
    // A shelf of albums earns cover tiles; anything else reads better as rows.
    val artful = remember(node.items) {
        val n = node.items.count { it.image != null && it.mediaType in ArtfulTypes }
        n > 0 && n >= node.items.size / 2
    }
    val tracks = remember(node.items) {
        node.items.filter { it.playable && it.mediaType == "track" }
    }
    // Downloading an album one row at a time was never a real answer, so the whole
    // list is one tap from disk. Any library this phone plays itself qualifies —
    // hardcoding "subsonic" hid the bar on Jellyfin, and Downloads is excluded for
    // the obvious reason.
    val downloadable = remember(tracks) {
        tracks.filter {
            MusicSources.isLocalProvider(it.provider) && it.provider != MusicSources.DOWNLOAD_PROVIDER
        }
    }
    // A list that holds more than one kind of thing, split into its kinds. Empty for
    // the ordinary node, which holds one — see [typedGroups].
    val groups = remember(node.items) { typedGroups(node.items) }
    // The front page's shelves, as data. Seven near-identical calls became a list you
    // can reorder or add to in one line, and the empty ones are filtered out here
    // rather than by each shelf checking itself.
    val shelfRows = remember(shelves) {
        listOf(
            // Unfinished business leads. Favourites used to, on the argument that what
            // you chose to keep beats whatever the scanner saw last — true, but a
            // favourite is still there tomorrow and a half-finished chapter is what
            // you opened the app for. It carries the last songs played as well as the
            // podcasts and audiobooks now, so it answers that for a music library too
            // — see [LibraryShelves.inProgress].
            ShelfSpec("Continue listening", shelves.inProgress, maxRows = 1),
            ShelfSpec("Favourite albums", shelves.favoriteAlbums),
            ShelfSpec("Favourite artists", shelves.favoriteArtists, circular = true),
            ShelfSpec("Recently added", shelves.recentlyAdded),
            ShelfSpec("For you", shelves.recommendations),
            ShelfSpec("Recently played", shelves.recent),
            // Navidrome only — MA has no equivalent of getAlbumList2(frequent), so
            // the list is empty on that backend and the shelf drops out here.
            ShelfSpec("Played most", shelves.frequent),
            // Then whatever Music Assistant's own Discover rows turned out to be:
            // "Random artists", "Forgotten Albums", "Never / Rarely Played", and any
            // row a provider adds. Named by the server, so nothing here has to know
            // what they are — see [LibraryViewModel.loadDiscoverRows]. They come last
            // because the shelves above are answers to questions the listener asked
            // (what I favourited, what I was in the middle of) and these are the
            // server's suggestions.
            *shelves.discover.map {
                ShelfSpec(it.title, it.items, circular = it.circular)
            }.toTypedArray(),
        ).filter { it.items.isNotEmpty() }
            // The title is the shelf's lazy key, and the Discover rows are named by the
            // server — so a row called "Favourite albums" would be a duplicate key and
            // a crash, not a cosmetic clash. The fixed shelves are listed first, so
            // first-wins keeps them and drops the server's twin.
            .distinctBy { it.title }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCols),
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = LibraryEdge, end = LibraryEdge, top = 4.dp, bottom = navBarInset() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Running on downloads alone is a working state, not a failure — say so
        // once, at the top, and let the rest of the screen behave normally.
        if (offline) {
            item(span = { full(gridCols) }) { OfflineNotice { viewModel.connect() } }
        }
        // Something another device left mid-track. An offer, not an interruption:
        // it sits above the shelves and goes away when dismissed or superseded.
        savedQueue?.let { saved ->
            item(span = { full(gridCols) }, key = "resume") {
                ResumeCard(
                    saved = saved,
                    onResume = { viewModel.resumeSavedQueue() },
                    onDismiss = { viewModel.dismissSavedQueue() },
                )
            }
        }
        if (error != null) {
            item(span = { full(gridCols) }) { SearchErrorState(error!!) { viewModel.connect() } }
            return@LazyVerticalGrid
        }
        if (loading) {
            items(6, span = { full(gridCols) }, contentType = { "skeleton" }) { SkeletonRow() }
            return@LazyVerticalGrid
        }

        // The first query of a session has no previous results to hold on to, and
        // falling through to the browse shelves under a search header reads as the
        // search having done nothing. Every query after this one keeps its old results
        // on screen instead — see the spinner in the search field.
        if (searchOpen && s == null && searching) {
            items(6, span = { full(gridCols) }, contentType = { "skeleton" }) { SkeletonRow() }
            return@LazyVerticalGrid
        }

        if (s != null) {
            searchSection("Artists", s.artists, viewModel, rows, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress, gridCols)
            searchSection("Albums", s.albums, viewModel, rows, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress, gridCols)
            searchSection("Tracks", s.tracks, viewModel, rows, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress, gridCols)
            searchSection("Playlists", s.playlists, viewModel, rows, onAlbumClick, onArtistClick, onPlaylistClick, onLongPress, gridCols)
            if (s.artists.isEmpty() && s.albums.isEmpty() && s.tracks.isEmpty() && s.playlists.isEmpty()) {
                item(span = { full(gridCols) }) { SearchEmptyState() }
            }
            return@LazyVerticalGrid
        }

        if (isDownloads) {
            downloadsSection(node.items, jobs, viewModel, rows, gridCols)
            return@LazyVerticalGrid
        }

        // Files still arriving, at the top of the Downloads library where they are
        // the one thing on screen about to change. This is all that survives of the
        // bespoke flat rendering the library replaced.
        if (isDownloadsLibrary) {
            downloadJobsSection(jobs, viewModel, gridCols)
            if (depth == 0 && onManageDownloads != null) {
                item(key = "dl_manage", span = { full(gridCols) }, contentType = { "manage" }) {
                    ManageDownloadsRow(onManageDownloads)
                }
            }
        }

        if (depth == 0) {
            // Root shelf: the category grid, then dynamic shelves of content.
            itemsIndexed(
                node.items,
                key = { i, cat -> itemKey("cat", i, cat) },
                contentType = { _, _ -> "category" },
                span = { _, _ -> GridItemSpan(3) },
            ) { _, cat ->
                // Categories come and go with the backend — switching to Music
                // Assistant adds Radio and Podcasts, switching away removes them —
                // and without this they popped in and out. Same modifier the shelves
                // below already use.
                CategoryCard(cat, Modifier.animateItem()) { viewModel.open(cat) }
            }
            val openItem: (MaItem) -> Unit = { item ->
                when (item.mediaType) {
                    "album" -> onAlbumClick(item)
                    "artist" -> onArtistClick(item)
                    "playlist" -> onPlaylistClick(item)
                    else -> viewModel.open(item)
                }
            }
            shelfRows.forEach { spec -> shelfCarousel(spec, openItem, onLongPress, gridCols) }
            return@LazyVerticalGrid
        }

        // Creating a playlist belongs where playlists are, not on the root — and
        // it has to come before the empty-state return, or a library with no
        // playlists yet would be the one place you can't make one.
        val inPlaylists = !searchOpen && node.title == "Playlists"
        if (inPlaylists) {
            item(span = { full(gridCols) }) { NewPlaylistRow(viewModel::openCreatePlaylist) }
        }

        if (node.items.isEmpty()) {
            item(span = { full(gridCols) }) { if (inPlaylists) Unit else SearchEmptyState() }
            return@LazyVerticalGrid
        }

        // A mixed list — Starred, chiefly — reads as sections rather than as one run
        // of rows. See [typedGroups].
        if (groups.isNotEmpty()) {
            groups.forEach { (title, list) ->
                typedSection(
                    title = title,
                    list = list,
                    viewModel = viewModel,
                    rows = rows,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    onLongPress = onLongPress,
                    gridCols = gridCols,
                )
            }
            return@LazyVerticalGrid
        }

        if (artful) {
            itemsIndexed(
                node.items,
                key = { i, entry -> itemKey("t", i, entry) },
                contentType = { _, _ -> "cover" },
                span = { _, _ -> GridItemSpan(2) },
            ) { _, entry ->
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
            if (tracks.size > 1) {
                item(key = "playall", span = { full(gridCols) }, contentType = { "playall" }) {
                    PlayAllBar(
                        count = tracks.size,
                        onPlayAll = { viewModel.playAll(tracks) },
                        onDownloadAll = if (downloadable.isEmpty()) null
                        else ({ viewModel.downloadAll(downloadable) }),
                    )
                }
            }
            itemsIndexed(
                node.items,
                key = { i, entry -> itemKey("r", i, entry) },
                contentType = { _, _ -> "row" },
                span = { _, _ -> full(gridCols) },
            ) { _, entry ->
                val click: (() -> Unit)? = when (entry.mediaType) {
                    "album" -> { { onAlbumClick(entry) } }
                    "artist" -> { { onArtistClick(entry) } }
                    "playlist" -> { { onPlaylistClick(entry) } }
                    else -> null
                }
                ItemRow(entry, viewModel, rows.of(entry), click, onLongPress, swipeToQueue = true)
            }
        }
    }
}

/**
 * The row-level state the library's list rows need, collected once for the whole grid
 * and narrowed to a single row by [of].
 *
 * `@Immutable` because the `Set`s read as unstable — they are replaced wholesale by
 * the view model, never mutated in place.
 */
@Immutable
private data class RowState(
    val downloadedIds: Set<String>,
    val favorites: Set<String>,
    val previewingId: String?,
) {
    /** The three flags as they apply to one item. */
    fun of(item: MaItem) = RowFlags(
        onDisk = item.itemId in downloadedIds,
        isFavorite = item.itemId in favorites,
        isPreviewing = previewingId == item.itemId,
    )
}

/** [RowState] resolved for a single row — three booleans, so the row can skip. */
@Immutable
internal data class RowFlags(
    val onDisk: Boolean,
    val isFavorite: Boolean,
    val isPreviewing: Boolean,
)

/** One shelf on the library's front page: its label, its contents, its tile shape. */
@Immutable
internal data class ShelfSpec(
    val title: String,
    val items: List<MaItem>,
    /** Artists are people, not sleeves: round tiles, centred names, initials fallback. */
    val circular: Boolean = false,
    /**
     * The shelf's own ceiling on rows, before [shelfCarousel]'s single-item rule
     * still collapses it to one. Continue listening reads better as one long row —
     * it is a short list of unfinished things, not a browsable wall — where every
     * other shelf keeps the two-row default.
     */
    val maxRows: Int = 2,
)

/**
 * One shelf, as a two-row carousel that scrolls sideways.
 *
 * Shelves used to wrap into the page's own grid, three tiles across, so a twelve-item
 * shelf was four rows deep and two shelves filled the screen. Reading down the front
 * page meant scrolling past everything you were not looking for. Two fixed rows that
 * run off the right edge instead: the page scrolls between *sections*, and each
 * section scrolls within itself.
 *
 * The whole carousel is a single full-span grid item. A `LazyHorizontalGrid` inside a
 * vertically-scrolling parent has to be given a height outright — an unbounded one
 * throws — which is why [ShelfTile] is pinned rather than sizing to its text. Compose
 * splits the axes between the two scrollers on its own, so there is no nested-scroll
 * glue here and no snapping: a shelf is not a pager.
 *
 * [ShelfSpec.title] seeds the item keys as well as labelling the shelf. Each carousel
 * now has its own key space, so it is no longer strictly load-bearing — but the same
 * album really does turn up in several shelves at once, and this screen has crashed
 * on a duplicate lazy key before. See [itemKey].
 */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.shelfCarousel(
    spec: ShelfSpec,
    onOpen: (MaItem) -> Unit,
    onLongPress: (MaItem) -> Unit,
    gridCols: Int,
) {
    item(key = "shelf_${spec.title}", span = { full(gridCols) }, contentType = { "shelf" }) {
        // One row for a shelf with a single item — a second, empty row under it is
        // 158dp of nothing, and it reads as a shelf that failed to load.
        val rowCount = spec.maxRows.coerceAtMost(if (spec.items.size < 2) 1 else 2)
        val tileHeight = shelfTileHeight()
        Column(Modifier.padding(top = 12.dp)) {
            LibraryShelf(spec.title, spec.items.size)
            Spacer(Modifier.height(10.dp))
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rowCount),
                modifier = Modifier
                    // Back out past the grid's own margin, with the margin re-applied
                    // as content padding, so tiles scroll *under* the edge of the
                    // screen rather than stopping short of it.
                    .bleed(LibraryEdge)
                    .height(tileHeight * rowCount + ShelfRowGap * (rowCount - 1)),
                contentPadding = PaddingValues(horizontal = LibraryEdge),
                horizontalArrangement = Arrangement.spacedBy(ShelfTileGap),
                verticalArrangement = Arrangement.spacedBy(ShelfRowGap),
            ) {
                itemsIndexed(
                    spec.items,
                    key = { i, entry -> itemKey(spec.title, i, entry) },
                    contentType = { _, _ -> "cover" },
                ) { _, entry ->
                    // Shelves refill in place as the server answers — favourites
                    // arrive, then recently-added, then the rest. Keyed items make
                    // that a move rather than a redraw, and animateItem is what turns
                    // the move into something you can follow.
                    Box(Modifier.animateItem()) {
                        ShelfTile(
                            entry,
                            circular = spec.circular,
                            onLongPress = { onLongPress(entry) },
                        ) { onOpen(entry) }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.searchSection(
    title: String, list: List<MaItem>, viewModel: LibraryViewModel,
    rows: RowState,
    onAlbumClick: (MaItem) -> Unit,
    onArtistClick: (MaItem) -> Unit,
    onPlaylistClick: (MaItem) -> Unit,
    onLongPress: (MaItem) -> Unit,
    gridCols: Int,
) {
    if (list.isEmpty()) return
    item(key = "shdr_$title", span = { full(gridCols) }, contentType = { "header" }) { LibraryShelf(title) }
    itemsIndexed(
        list,
        key = { i, entry -> itemKey("s_$title", i, entry) },
        contentType = { _, _ -> "row" },
        span = { _, _ -> full(gridCols) },
    ) { _, entry ->
        val click: (() -> Unit)? = when (entry.mediaType) {
            "album" -> { { onAlbumClick(entry) } }
            "artist" -> { { onArtistClick(entry) } }
            "playlist" -> { { onPlaylistClick(entry) } }
            else -> null
        }
        ItemRow(entry, viewModel, rows.of(entry), click, onLongPress, swipeToQueue = true)
    }
}

/** Media types that get a section of their own, in the order they are shown. */
private val TypeSections = listOf(
    "artist" to "Artists",
    "album" to "Albums",
    "playlist" to "Playlists",
    "podcast" to "Podcasts",
    "audiobook" to "Audiobooks",
    "radio" to "Radio stations",
    "track" to "Tracks",
)

/**
 * The sections a mixed list should be shown as, or empty when it isn't one.
 *
 * Starred is the node this exists for. It is the only browse node that genuinely
 * holds several kinds of thing at once — the server answers with the artists, the
 * albums, the playlists and the tracks the user has starred — and it arrived as one
 * undifferentiated run of rows, in which an album and a song looked alike and only
 * the subtitle hinted otherwise. Worse, the "Play all N tracks" bar above it counted
 * the tracks, which is what it plays, while the list underneath was mostly albums —
 * so the number read as wrong against what was on screen. Split into sections, every
 * count is a count of what is under it.
 *
 * Empty for the ordinary node — an album's tracks, an artist's albums — which holds
 * exactly one kind and is better as the flat list it already is. Empty too for a mix
 * containing a type with no section of its own, because a heading called "Other" says
 * less than the plain row it would replace.
 */
internal fun typedGroups(items: List<MaItem>): List<Pair<String, List<MaItem>>> {
    if (items.size < 2) return emptyList()
    val byType = items.groupBy { it.mediaType }
    if (byType.size < 2) return emptyList()
    if (byType.keys.any { type -> TypeSections.none { it.first == type } }) return emptyList()
    return TypeSections.mapNotNull { (type, title) ->
        byType[type]?.takeIf { it.isNotEmpty() }?.let { title to it }
    }
}

/**
 * One kind of thing, under its own heading.
 *
 * Covers where the section is covers — albums and playlists are a wall of art in
 * their own right — and rows everywhere else, which is the same rule the flat list
 * uses, applied per section instead of to the mixture. The play-all bar rides with
 * the tracks, because tracks are the only thing in a mixed list it can act on and
 * putting it at the top of the whole node is what made its count read as wrong.
 */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.typedSection(
    title: String,
    list: List<MaItem>,
    viewModel: LibraryViewModel,
    rows: RowState,
    onAlbumClick: (MaItem) -> Unit,
    onArtistClick: (MaItem) -> Unit,
    onPlaylistClick: (MaItem) -> Unit,
    onLongPress: (MaItem) -> Unit,
    gridCols: Int,
) {
    if (list.isEmpty()) return
    val section = "g_$title"
    item(key = "ghdr_$title", span = { full(gridCols) }, contentType = { "header" }) {
        LibraryShelf("$title (${list.size})")
    }

    val playable = list.filter { it.playable && it.mediaType == "track" }
    if (playable.size > 1) {
        val downloadable = playable.filter { it.provider == "subsonic" }
        item(key = "gplayall_$title", span = { full(gridCols) }, contentType = { "playall" }) {
            PlayAllBar(
                count = playable.size,
                onPlayAll = { viewModel.playAll(playable) },
                onDownloadAll = if (downloadable.isEmpty()) null
                else ({ viewModel.downloadAll(downloadable) }),
            )
        }
    }

    // Every item in a section shares a type, so the type decides — and art is
    // required, because a wall of empty tiles says less than a list of names.
    val asCovers = list.first().mediaType in ArtfulTypes &&
        list.count { it.image != null } >= list.size / 2
    if (asCovers) {
        itemsIndexed(
            list,
            key = { i, entry -> itemKey(section, i, entry) },
            contentType = { _, _ -> "cover" },
            span = { _, _ -> GridItemSpan(2) },
        ) { _, entry ->
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
        itemsIndexed(
            list,
            key = { i, entry -> itemKey(section, i, entry) },
            contentType = { _, _ -> "row" },
            span = { _, _ -> full(gridCols) },
        ) { _, entry ->
            val click: (() -> Unit)? = when (entry.mediaType) {
                "album" -> { { onAlbumClick(entry) } }
                "artist" -> { { onArtistClick(entry) } }
                "playlist" -> { { onPlaylistClick(entry) } }
                else -> null
            }
            ItemRow(entry, viewModel, rows.of(entry), click, onLongPress, swipeToQueue = true)
        }
    }
}

/**
 * The way into the storage manager from the library it manages.
 *
 * Until now that screen was reachable only from Settings, which is a strange place to
 * put "delete the album you are looking at".
 */
@Composable
private fun ManageDownloadsRow(onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Storage, null, tint = accent, modifier = Modifier.size(18.dp))
        Text(
            "Manage storage",
            color = TextPrimary, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
    }
}

/** Downloads still arriving, shared by the offline category and the Downloads library. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.downloadJobsSection(
    jobs: List<DownloadJob>,
    viewModel: LibraryViewModel,
    gridCols: Int,
) {
    if (jobs.isEmpty()) return
    item(key = "hdr_inprogress", span = { full(gridCols) }, contentType = { "header" }) { LibraryShelf("In progress") }
    items(
        jobs,
        key = { "j_" + it.id },
        contentType = { "job" },
        span = { full(gridCols) },
    ) { job ->
        // Jobs appear and vanish as downloads finish, so this list is the one that
        // most obviously popped without an animation.
        Box(Modifier.animateItem()) { DownloadJobRow(job, viewModel) }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.downloadsSection(
    items: List<MaItem>, jobs: List<DownloadJob>, viewModel: LibraryViewModel,
    rows: RowState,
    gridCols: Int,
) {
    downloadJobsSection(jobs, viewModel, gridCols)
    if (items.isEmpty() && jobs.isEmpty()) {
        item(span = { full(gridCols) }) { SearchEmptyState("Nothing downloaded", "Downloaded tracks play with the server off.") }
        return
    }
    if (items.isNotEmpty()) {
        item(key = "hdr_ondevice", span = { full(gridCols) }, contentType = { "header" }) { LibraryShelf("On this device") }
        if (items.size > 1) {
            item(key = "dl_playall", span = { full(gridCols) }, contentType = { "playall" }) {
                var buildingSet by remember { mutableStateOf(false) }
                PlayAllBar(
                    items.size,
                    onPlayAll = { viewModel.playAll(items) },
                    // Only on a flat list of tracks. An album is already in the order
                    // its maker chose, and reordering one is not a feature.
                    onBuildSet = { buildingSet = true },
                )
                if (buildingSet) {
                    val (scanned, total) = remember(items) { viewModel.scanCoverage(items) }
                    SetBuilderDialog(
                        scanned = scanned,
                        total = total,
                        onDismiss = { buildingSet = false },
                        onBuild = { curve, minutes ->
                            buildingSet = false
                            viewModel.buildSet(items, curve, minutes)
                        },
                    )
                }
            }
        }
        itemsIndexed(
            items,
            key = { i, entry -> itemKey("d", i, entry) },
            contentType = { _, _ -> "row" },
            span = { _, _ -> full(gridCols) },
        ) { _, entry -> ItemRow(entry, viewModel, rows.of(entry), swipeToQueue = true) }
    }
}
