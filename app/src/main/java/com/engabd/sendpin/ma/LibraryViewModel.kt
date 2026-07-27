package com.engabd.sendpin.ma

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * On-device Music Assistant library: browse Artists/Albums/Tracks/Playlists,
 * search, and play to a target player (defaults to *this phone*, whose MA queue
 * id equals its Sendspin client id). Connects to the MA `/ws` API with the stored
 * server + login. Plain state for a plain UI — the polished design lands later.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val api = MaApiClient()
    private val repo = MaRepository(api)

    /** This phone's MA player id (== the Sendspin client id we register with). */
    val myPlayerId: String = PlayerIdentity.getPlayerId(app)

    data class Node(val title: String, val items: List<MaItem>)

    val connState: StateFlow<MaApiClient.State> = api.state

    private val _baseUrl = MutableStateFlow(""); val baseUrl: StateFlow<String> = _baseUrl
    private val _username = MutableStateFlow(""); val username: StateFlow<String> = _username
    private val _password = MutableStateFlow(""); val password: StateFlow<String> = _password

    private val _node = MutableStateFlow(Node("Library", rootItems())); val node: StateFlow<Node> = _node
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _search = MutableStateFlow<MaSearchResults?>(null); val search: StateFlow<MaSearchResults?> = _search
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8); val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val stack = ArrayDeque<Node>()

    init {
        viewModelScope.launch {
            _baseUrl.value = settings.maBaseUrl.first()
            _username.value = settings.maUsername.first()
            _password.value = settings.maPassword.first()
            if (_baseUrl.value.isNotBlank()) connect()
        }
    }

    fun setBaseUrl(v: String) { _baseUrl.value = v }
    fun setUsername(v: String) { _username.value = v }
    fun setPassword(v: String) { _password.value = v }

    fun connect() {
        val url = _baseUrl.value.trim()
        if (url.isBlank()) return
        viewModelScope.launch { settings.setMa(url, _username.value, _password.value) }
        api.connect(url, token = null, username = _username.value.ifBlank { null }, password = _password.value.ifBlank { null })
        viewModelScope.launch {
            val st = api.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
            if (st == MaApiClient.State.CONNECTED) {
                stack.clear()
                _node.value = Node("Library", rootItems())
            }
        }
    }

    fun disconnect() = api.disconnect()

    /** Tap handling: open a category, browse a container, or play a track. */
    fun open(item: MaItem) {
        when {
            item.provider == CATEGORY -> openCategory(item.itemId, item.name)
            item.browsable -> pushNode(item.name) { repo.children(item) }
            item.playable -> play(item)
        }
    }

    fun back(): Boolean {
        if (_search.value != null) { _search.value = null; return true }
        if (stack.isNotEmpty()) { _node.value = stack.removeLast(); return true }
        return false
    }

    fun play(item: MaItem, option: String = "replace") {
        val uri = item.uri ?: return
        viewModelScope.launch {
            try {
                repo.playMedia(myPlayerId, listOf(uri), option)
                _toast.tryEmit(if (option == "add") "Added to queue" else "Playing ${item.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    fun playAll(items: List<MaItem>) {
        val uris = items.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.playMedia(myPlayerId, uris, "replace")
                _toast.tryEmit("Queued ${uris.size} tracks")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    fun doSearch(query: String) {
        if (query.isBlank()) { _search.value = null; return }
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            try { _search.value = repo.search(query) } catch (e: Exception) { _error.value = e.message }
            _loading.value = false
        }
    }

    fun clearSearch() { _search.value = null }

    // --- internals --------------------------------------------------------

    private fun openCategory(id: String, title: String) = pushNode(title) {
        when (id) {
            "artists" -> repo.artists()
            "albums" -> repo.albums()
            "tracks" -> repo.tracks()
            "playlists" -> repo.playlists()
            else -> emptyList()
        }
    }

    private fun pushNode(title: String, loader: suspend () -> List<MaItem>) {
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            try {
                val items = loader()
                stack.addLast(_node.value)
                _node.value = Node(title, items)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load"
            }
            _loading.value = false
        }
    }

    private fun rootItems(): List<MaItem> = listOf(
        category("artists", "Artists"),
        category("albums", "Albums"),
        category("tracks", "Tracks"),
        category("playlists", "Playlists"),
    )

    private fun category(id: String, name: String) =
        MaItem(id, CATEGORY, name, null, "category", null, null, null)

    override fun onCleared() {
        super.onCleared()
        api.disconnect()
    }

    private companion object {
        const val CATEGORY = "__cat__"
    }
}
