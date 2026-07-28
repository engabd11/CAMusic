package com.engabd.sendpin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import com.engabd.sendpin.ui.design.*
import com.engabd.sendpin.ui.theme.*

/**
 * The library in the same OLED language as Now Playing: a single album-derived
 * bloom over true black, glass rows, and covers doing the talking. Everything
 * with artwork gets a cover tile rather than a list row — a wall of art is the
 * point of a music library.
 */
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val ready by viewModel.ready.collectAsState()
    val booted by viewModel.booted.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val hasServer by viewModel.hasServer.collectAsState()
    val connError by viewModel.connError.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val node by viewModel.node.collectAsState()
    val depth by viewModel.depth.collectAsState()
    val search by viewModel.search.collectAsState()
    val palette = LocalPalette.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = depth > 0 || search != null) { viewModel.back() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Bloom(palette.swatch(0), 520.dp, (-120).dp, (-260).dp, 0.30f)

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Header(
                title = if (depth > 0 && search == null) node.title else "Library",
                showBack = depth > 0 || search != null,
                backend = backend,
                onBack = { viewModel.back() },
                onBackend = viewModel::setBackend,
                onSearch = viewModel::doSearch,
                onClearSearch = viewModel::clearSearch,
            )
            // Only offer the connect form once we know there's nothing to connect
            // to. Showing it while a saved server is still handshaking made every
            // visit to this tab flash what looks like the onboarding screen.
            when {
                ready -> Browse(viewModel)
                booted && !connecting && (!hasServer || connError != null) -> ConnectForm(viewModel, backend)
                else -> ConnectingState()
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = navBarInset() + 8.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(containerColor = Ink3, contentColor = TextPrimary, shape = RoundedCornerShape(14.dp)) {
                Text(data.visuals.message, fontFamily = AppFont, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    showBack: Boolean,
    backend: Backend,
    onBack: () -> Unit,
    onBackend: (Backend) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
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
            Text(
                title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        SegmentedToggle(
            options = listOf("Music Assistant", "Navidrome"),
            selectedIndex = if (backend == Backend.SUBSONIC) 1 else 0,
        ) { onBackend(if (it == 1) Backend.SUBSONIC else Backend.MA) }
        Spacer(Modifier.height(12.dp))
        SearchField(onSearch, onClearSearch)
    }
}

@Composable
private fun SearchField(onSearch: (String) -> Unit, onClear: () -> Unit) {
    var query by remember { mutableStateOf("") }
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
                onValueChange = { query = it; if (it.isBlank()) onClear() },
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
                    .clickable { query = ""; onClear(); focus.clearFocus() },
            )
        }
    }
}

// --- browse ---------------------------------------------------------------

private const val COLS = 6              // 6-col base: covers span 2, categories 3, rows 6
private fun full() = GridItemSpan(COLS)

@Composable
private fun Browse(viewModel: LibraryViewModel) {
    val node by viewModel.node.collectAsState()
    val depth by viewModel.depth.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val search by viewModel.search.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    val jobs by viewModel.downloadJobs.collectAsState()

    val s = search
    val isDownloads = node.items.any { it.provider == "__dl__" } || node.title == "Downloads"

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLS),
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = navBarInset() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) {
            item(span = { full() }) { ErrorState(error!!) { viewModel.connect() } }
            return@LazyVerticalGrid
        }
        if (loading) {
            items(6, span = { full() }) { SkeletonRow() }
            return@LazyVerticalGrid
        }

        if (s != null) {
            searchSection("Artists", s.artists, viewModel)
            searchSection("Albums", s.albums, viewModel)
            searchSection("Tracks", s.tracks, viewModel)
            searchSection("Playlists", s.playlists, viewModel)
            if (s.artists.isEmpty() && s.albums.isEmpty() && s.tracks.isEmpty() && s.playlists.isEmpty()) {
                item(span = { full() }) { EmptyState() }
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
            if (inProgress.isNotEmpty()) {
                item(span = { full() }) { Shelf("Continue listening") }
                items(inProgress, span = { GridItemSpan(2) }) { it2 ->
                    CoverTile(it2) { viewModel.open(it2) }
                }
            }
            if (recentlyAdded.isNotEmpty()) {
                item(span = { full() }) { Shelf("Recently added") }
                items(recentlyAdded, span = { GridItemSpan(2) }) { it2 ->
                    CoverTile(it2) { viewModel.open(it2) }
                }
            }
            if (recommendations.isNotEmpty()) {
                item(span = { full() }) { Shelf("For you") }
                items(recommendations, span = { GridItemSpan(2) }) { it2 ->
                    CoverTile(it2) { viewModel.open(it2) }
                }
            }
            if (recent.isNotEmpty()) {
                item(span = { full() }) { Shelf("Recently played") }
                items(recent, span = { GridItemSpan(2) }) { it2 ->
                    CoverTile(it2) { viewModel.open(it2) }
                }
            }
            return@LazyVerticalGrid
        }

        if (node.items.isEmpty()) {
            item(span = { full() }) { EmptyState() }
            return@LazyVerticalGrid
        }

        // A shelf of albums earns cover tiles; anything else reads better as rows.
        val artful = node.items.count { it.image != null && it.mediaType in setOf("album", "playlist") }
        if (artful >= node.items.size / 2 && artful > 0) {
            items(node.items, span = { GridItemSpan(2) }) { entry ->
                CoverTile(entry) { viewModel.open(entry) }
            }
        } else {
            val tracks = node.items.filter { it.playable && it.mediaType == "track" }
            if (tracks.size > 1) {
                item(span = { full() }) { PlayAllBar(tracks.size) { viewModel.playAll(tracks) } }
            }
            items(node.items, span = { full() }) { entry ->
                ItemRow(entry, viewModel)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.searchSection(
    title: String, list: List<MaItem>, viewModel: LibraryViewModel,
) {
    if (list.isEmpty()) return
    item(span = { full() }) { Shelf(title) }
    items(list, span = { full() }) { entry -> ItemRow(entry, viewModel) }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.downloadsSection(
    items: List<MaItem>, jobs: List<DownloadJob>, viewModel: LibraryViewModel,
) {
    if (jobs.isNotEmpty()) {
        item(span = { full() }) { Shelf("In progress") }
        items(jobs, span = { full() }, key = { "j_" + it.id }) { job -> DownloadJobRow(job, viewModel) }
    }
    if (items.isEmpty() && jobs.isEmpty()) {
        item(span = { full() }) { EmptyState("Nothing downloaded", "Downloaded tracks play with the server off.") }
        return
    }
    if (items.isNotEmpty()) {
        item(span = { full() }) { Shelf("On this device") }
        items(items, span = { full() }) { entry -> ItemRow(entry, viewModel) }
    }
}

// --- pieces ---------------------------------------------------------------

@Composable
private fun Shelf(text: String) {
    Box(Modifier.padding(top = 12.dp, bottom = 2.dp)) { SectionLabel(text) }
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

@Composable
private fun CoverTile(item: MaItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
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
            val art = rememberArtRequest(item.image)
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

@Composable
private fun RowCard(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.a(0.03f))
            .border(1.dp, HairlineSoft, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ItemRow(item: MaItem, viewModel: LibraryViewModel) {
    val accent = LocalAccent.current
    val isCategory = item.provider == "__cat__"
    val isDownload = item.provider == "__dl__"
    val isSubsonicTrack = item.provider == "subsonic" && item.mediaType == "track"

    RowCard(onClick = { viewModel.open(item) }) {
        when {
            isCategory -> Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(accent.a(0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(categoryIcon(item.itemId), null, tint = accent, modifier = Modifier.size(20.dp)) }

            item.image != null -> {
                val art = rememberArtRequest(item.image)
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

        Column(Modifier.weight(1f)) {
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

        when {
            isSubsonicTrack -> Icon(
                Icons.Default.Download, "Download", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.download(item) },
            )
            isDownload -> Icon(
                Icons.Default.Delete, "Delete download", tint = TextMuted,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.deleteDownload(item.itemId) },
            )
            item.playable -> Icon(
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
        Column(Modifier.weight(1f)) {
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
private fun PlayAllBar(count: Int, onPlayAll: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.a(0.10f))
            .border(1.dp, accent.a(0.28f), RoundedCornerShape(14.dp))
            .clickable(onClick = onPlayAll)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(18.dp))
        Text(
            "Play all $count tracks", color = accent, fontFamily = AppFont,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
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
private fun EmptyState(
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
            body, color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
            modifier = Modifier.widthIn(max = 240.dp),
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
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
            message, color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
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
                "Direct mode plays on this phone and can download for offline — it works even when Music Assistant is down.",
                color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
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
            Text(it, color = ErrorRed, fontFamily = AppFont, fontSize = 13.sp)
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
