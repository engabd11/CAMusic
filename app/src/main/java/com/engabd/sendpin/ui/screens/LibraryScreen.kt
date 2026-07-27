package com.engabd.sendpin.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel()) {
    val connState by viewModel.connState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (connState != MaApiClient.State.CONNECTED) {
                ConnectForm(viewModel, connState)
            } else {
                BrowseUi(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectForm(viewModel: LibraryViewModel, connState: MaApiClient.State) {
    val baseUrl by viewModel.baseUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to Music Assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            "Browsing and search use the Music Assistant API. Enter your server and login.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = viewModel::setBaseUrl,
            label = { Text("Server URL") }, placeholder = { Text("http://192.168.0.10:8095") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username, onValueChange = viewModel::setUsername,
            label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = viewModel::setPassword,
            label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.connect() },
            enabled = baseUrl.isNotBlank() && connState != MaApiClient.State.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (connState == MaApiClient.State.CONNECTING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Connect")
            }
        }
        if (connState == MaApiClient.State.ERROR) {
            Text("Couldn't connect. Check the URL and login.", color = MaterialTheme.colorScheme.error)
        }
    }
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
            label = { Text("Search") },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { viewModel.doSearch(query) }, enabled = query.isNotBlank()) { Text("Go") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (!viewModel.back()) Unit }) {
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
            if (search != null) {
                val s = search!!
                section("Artists", s.artists, viewModel)
                section("Albums", s.albums, viewModel)
                section("Tracks", s.tracks, viewModel)
                section("Playlists", s.playlists, viewModel)
            } else {
                items(node.items) { item -> ItemRow(item, viewModel) }
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
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    items(list) { entry -> ItemRow(entry, viewModel) }
}

@Composable
private fun ItemRow(item: MaItem, viewModel: LibraryViewModel) {
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            if (item.image != null) {
                AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.size(44.dp))
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.playable) {
                    IconButton(onClick = { viewModel.play(item, "add") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add to queue")
                    }
                }
                when {
                    item.browsable || item.provider == "__cat__" ->
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    item.playable ->
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    else -> {}
                }
            }
        },
        modifier = Modifier.clickable { viewModel.open(item) },
    )
}
