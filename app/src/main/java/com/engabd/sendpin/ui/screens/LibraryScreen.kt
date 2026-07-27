package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.LibraryViewModel.Backend
import com.engabd.sendpin.ma.MaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val ready by viewModel.ready.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.toast.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BackendToggle(backend, viewModel::setBackend)
            if (!ready) ConnectForm(viewModel, backend) else BrowseUi(viewModel)
        }
    }
}

@Composable
private fun BackendToggle(backend: Backend, onSelect: (Backend) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = backend == Backend.MA, onClick = { onSelect(Backend.MA) }, label = { Text("Music Assistant") })
        FilterChip(selected = backend == Backend.SUBSONIC, onClick = { onSelect(Backend.SUBSONIC) }, label = { Text("Navidrome") })
    }
}

@Composable
private fun ConnectForm(viewModel: LibraryViewModel, backend: Backend) {
    val connecting by viewModel.connecting.collectAsState()
    val connError by viewModel.connError.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (backend == Backend.MA) {
            val url by viewModel.maUrl.collectAsState()
            val user by viewModel.maUser.collectAsState()
            val pass by viewModel.maPass.collectAsState()
            Text("Connect to Music Assistant", style = MaterialTheme.typography.titleMedium)
            Field("Server URL", url, viewModel::setMaUrl, "http://192.168.0.10:8095")
            Field("Username", user, viewModel::setMaUser)
            Field("Password", pass, viewModel::setMaPass)
        } else {
            val url by viewModel.navUrl.collectAsState()
            val user by viewModel.navUser.collectAsState()
            val pass by viewModel.navPass.collectAsState()
            Text("Connect to Navidrome / OpenSubsonic", style = MaterialTheme.typography.titleMedium)
            Text(
                "Direct mode plays on this phone and can download for offline — works even when Music Assistant is down.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field("Server URL", url, viewModel::setNavUrl, "http://192.168.0.10:4533")
            Field("Username", user, viewModel::setNavUser)
            Field("Password", pass, viewModel::setNavPass)
        }
        Button(onClick = { viewModel.connect() }, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
            if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
        }
        connError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "") {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BrowseUi(viewModel: LibraryViewModel) {
    val node by viewModel.node.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val search by viewModel.search.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; if (it.isBlank()) viewModel.clearSearch() },
            label = { Text("Search") }, singleLine = true,
            trailingIcon = { TextButton(onClick = { viewModel.doSearch(query) }, enabled = query.isNotBlank()) { Text("Go") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (search != null) "Search: $query" else node.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val tracks = node.items.filter { it.playable && it.mediaType == "track" }
            if (search == null && tracks.isNotEmpty()) {
                TextButton(onClick = { viewModel.playAll(tracks) }) { Text("Play all") }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            val s = search
            if (s != null) {
                section("Artists", s.artists, viewModel)
                section("Albums", s.albums, viewModel)
                section("Tracks", s.tracks, viewModel)
                section("Playlists", s.playlists, viewModel)
            } else {
                items(node.items) { entry -> ItemRow(entry, viewModel) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, list: List<MaItem>, viewModel: LibraryViewModel,
) {
    if (list.isEmpty()) return
    item {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    items(list) { entry -> ItemRow(entry, viewModel) }
}

@Composable
private fun ItemRow(item: MaItem, viewModel: LibraryViewModel) {
    val isCategory = item.provider == "__cat__"
    val isDownload = item.provider == "__dl__"
    val isSubsonicTrack = item.provider == "subsonic" && item.mediaType == "track"

    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            if (item.image != null) AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.size(44.dp))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isSubsonicTrack -> IconButton(onClick = { viewModel.download(item) }) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                    isDownload -> IconButton(onClick = { viewModel.deleteDownload(item.itemId) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete download")
                    }
                    item.playable -> IconButton(onClick = { viewModel.play(item, "add") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add to queue")
                    }
                }
                when {
                    isCategory || item.browsable -> Icon(Icons.Default.ChevronRight, contentDescription = null)
                    item.playable -> Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    else -> {}
                }
            }
        },
        modifier = Modifier.clickable { viewModel.open(item) },
    )
}
