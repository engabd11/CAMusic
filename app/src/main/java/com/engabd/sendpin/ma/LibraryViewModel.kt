package com.engabd.sendpin.ma

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.subsonic.SubsonicClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * On-device library. Two switchable backends:
 *  - **Music Assistant** (`/ws` API): browse/search and play to a target player,
 *    defaulting to THIS phone (its MA queue id == our Sendspin client id).
 *  - **Navidrome / OpenSubsonic** (direct): browse/search and play *locally* on the
 *    phone (standalone when MA is down), and download original files for offline.
 *
 * Backs the OLED library UI: a root shelf of categories plus recently played,
 * then a browse stack, with search, downloads and connection state alongside.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    enum class Backend { MA, SUBSONIC }
    data class Node(val title: String, val items: List<MaItem>)

    private val settings = AppSettings(app)
    val myPlayerId: String = PlayerIdentity.getPlayerId(app)

    // The MA player to play to (blank = this phone). Kept in sync with the Speakers picker.
    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    private var subsonic: SubsonicClient? = null

    val localPlayer = LocalPlayer()
    val downloadManager = DownloadManager(app)
    val downloads: StateFlow<List<DownloadedTrack>> get() = downloadManager.downloads

    private val _backend = MutableStateFlow(Backend.MA); val backend: StateFlow<Backend> = _backend
    private val _ready = MutableStateFlow(false); val ready: StateFlow<Boolean> = _ready
    /** Settings have been read, so a blank server URL now means "really not set up". */
    private val _booted = MutableStateFlow(false); val booted: StateFlow<Boolean> = _booted
    private val _connecting = MutableStateFlow(false); val connecting: StateFlow<Boolean> = _connecting
    private val _connError = MutableStateFlow<String?>(null); val connError: StateFlow<String?> = _connError

    private val _maUrl = MutableStateFlow(""); val maUrl: StateFlow<String> = _maUrl
    private val _maUser = MutableStateFlow(""); val maUser: StateFlow<String> = _maUser
    private val _maPass = MutableStateFlow(""); val maPass: StateFlow<String> = _maPass
    private val _navUrl = MutableStateFlow(""); val navUrl: StateFlow<String> = _navUrl
    private val _navUser = MutableStateFlow(""); val navUser: StateFlow<String> = _navUser
    private val _navPass = MutableStateFlow(""); val navPass: StateFlow<String> = _navPass

    /** A server address is on file, so we're connecting rather than unconfigured. */
    val hasServer: StateFlow<Boolean> = combine(_backend, _maUrl, _navUrl) { b, ma, nav ->
        (if (b == Backend.SUBSONIC) nav else ma).isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _node = MutableStateFlow(Node("Library", emptyList())); val node: StateFlow<Node> = _node
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _search = MutableStateFlow<MaSearchResults?>(null); val search: StateFlow<MaSearchResults?> = _search
    private val _recent = MutableStateFlow<List<MaItem>>(emptyList()); val recent: StateFlow<List<MaItem>> = _recent
    private val _recentlyAdded = MutableStateFlow<List<MaItem>>(emptyList()); val recentlyAdded: StateFlow<List<MaItem>> = _recentlyAdded
    private val _recommendations = MutableStateFlow<List<MaItem>>(emptyList()); val recommendations: StateFlow<List<MaItem>> = _recommendations
    private val _inProgress = MutableStateFlow<List<MaItem>>(emptyList()); val inProgress: StateFlow<List<MaItem>> = _inProgress
    val downloadJobs: StateFlow<List<DownloadJob>> get() = downloadManager.jobs
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8); val toast: SharedFlow<String> = _toast.asSharedFlow()
    private val stack = ArrayDeque<Node>()

    /** How deep the browser is; 0 = the root shelf (categories + recently played). */
    private val _depth = MutableStateFlow(0); val depth: StateFlow<Int> = _depth

    init {
        viewModelScope.launch {
            _maUrl.value = settings.maBaseUrl.first(); _maUser.value = settings.maUsername.first(); _maPass.value = settings.maPassword.first()
            _navUrl.value = settings.navUrl.first(); _navUser.value = settings.navUsername.first(); _navPass.value = settings.navPassword.first()
            _backend.value = if (settings.backend.first() == "subsonic") Backend.SUBSONIC else Backend.MA
            _booted.value = true
            if (currentUrl().isNotBlank()) connect()
        }
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        viewModelScope.launch {
            maApi.state.collect { st ->
                if (_backend.value != Backend.MA) return@collect
                _ready.value = st == MaApiClient.State.CONNECTED
                if (st == MaApiClient.State.CONNECTED) showRoot()
                if (st == MaApiClient.State.ERROR) _connError.value = "Connection failed"
            }
        }
    }

    fun setBackend(b: Backend) {
        if (_backend.value == b) return
        _backend.value = b
        _ready.value = false
        _search.value = null
        stack.clear()
        viewModelScope.launch { settings.setBackend(if (b == Backend.SUBSONIC) "subsonic" else "ma") }
        if (currentUrl().isNotBlank()) connect()
    }

    fun setMaUrl(v: String) { _maUrl.value = v }
    fun setMaUser(v: String) { _maUser.value = v }
    fun setMaPass(v: String) { _maPass.value = v }
    fun setNavUrl(v: String) { _navUrl.value = v }
    fun setNavUser(v: String) { _navUser.value = v }
    fun setNavPass(v: String) { _navPass.value = v }

    private fun currentUrl() = if (_backend.value == Backend.MA) _maUrl.value else _navUrl.value

    fun connect() {
        _connError.value = null
        viewModelScope.launch {
            settings.setMa(_maUrl.value, _maUser.value, _maPass.value)
            settings.setNavidrome(_navUrl.value, _navUser.value, _navPass.value)
        }
        if (_backend.value == Backend.MA) {
            val url = _maUrl.value.trim(); if (url.isBlank()) return
            _connecting.value = true
            maApi.connect(url, token = null, username = _maUser.value.ifBlank { null }, password = _maPass.value.ifBlank { null })
            viewModelScope.launch {
                maApi.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
                _connecting.value = false
            }
        } else {
            val url = _navUrl.value.trim(); if (url.isBlank()) return
            _connecting.value = true
            val sc = SubsonicClient(url, _navUser.value, _navPass.value); subsonic = sc
            viewModelScope.launch {
                val ok = sc.ping()
                _connecting.value = false
                if (ok) { _ready.value = true; showRoot() }
                else { _ready.value = false; _connError.value = "Couldn't reach Navidrome (check URL + login)" }
            }
        }
    }

    // --- browse / play ----------------------------------------------------

    fun open(item: MaItem) {
        when {
            item.provider == CATEGORY -> openCategory(item.itemId, item.name)
            item.provider == DOWNLOAD -> item.uri?.let { localPlayer.play(it, item.name); _toast.tryEmit("Playing ${item.name}") }
            item.browsable -> pushNode(item.name) { childrenOf(item) }
            item.playable -> play(item)
        }
    }

    fun back(): Boolean {
        if (_search.value != null) { _search.value = null; return true }
        if (stack.isNotEmpty()) { _node.value = stack.removeLast(); _depth.value = stack.size; return true }
        return false
    }

    fun play(item: MaItem, option: String = "replace") {
        viewModelScope.launch {
            try {
                when (item.provider) {
                    "subsonic" -> subsonic?.let { localPlayer.play(it.streamUrl(item.itemId), item.name) }
                    DOWNLOAD -> item.uri?.let { localPlayer.play(it, item.name) }
                    else -> item.uri?.let { maRepo.playMedia(playTarget(), listOf(it), option) }
                }
                _toast.tryEmit(if (option == "add") "Added to queue" else "Playing ${item.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    fun playAll(items: List<MaItem>) {
        val tracks = items.filter { it.playable }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (_backend.value == Backend.MA) {
                    maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }, "replace")
                    _toast.tryEmit("Queued ${tracks.size} tracks")
                } else {
                    play(tracks.first())   // local player has no queue yet
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    fun download(item: MaItem) {
        val sc = subsonic ?: return
        if (item.mediaType != "track") return
        viewModelScope.launch {
            _toast.tryEmit("Downloading ${item.name}…")
            val ok = downloadManager.download(item, sc.downloadUrl(item.itemId))
            _toast.tryEmit(if (ok) "Downloaded ${item.name}" else "Download failed")
        }
    }

    fun deleteDownload(id: String) = downloadManager.delete(id)

    /** Drop a failed download row once the user has acknowledged it. */
    fun dismissDownload(id: String) = downloadManager.dismissJob(id)

    fun doSearch(query: String) {
        if (query.isBlank()) { _search.value = null; return }
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            try {
                _search.value = if (_backend.value == Backend.MA) maRepo.search(query) else subsonic?.search(query)
            } catch (e: Exception) { _error.value = e.message }
            _loading.value = false
        }
    }

    fun clearSearch() { _search.value = null }

    // --- internals --------------------------------------------------------

    private suspend fun childrenOf(item: MaItem): List<MaItem> =
        if (item.provider == "subsonic") subsonic?.children(item) ?: emptyList() else maRepo.children(item)

    private fun openCategory(id: String, title: String) {
        if (id == "downloads") {
            val items = downloads.value.map { downloadItem(it) }
            stack.addLast(_node.value)
            _node.value = Node("Downloads", items)
            _depth.value = stack.size
            return
        }
        pushNode(title) {
            if (_backend.value == Backend.MA) when (id) {
                "artists" -> maRepo.artists(); "albums" -> maRepo.albums()
                "tracks" -> maRepo.tracks(); "playlists" -> maRepo.playlists(); else -> emptyList()
            } else when (id) {
                "artists" -> subsonic?.artists().orEmpty()
                "newest" -> subsonic?.albumList("newest").orEmpty()
                "playlists" -> subsonic?.playlists().orEmpty()
                else -> emptyList()
            }
        }
    }

    private fun pushNode(title: String, loader: suspend () -> List<MaItem>) {
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            try {
                val items = loader()
                rememberFavorites(items)
                stack.addLast(_node.value)
                _node.value = Node(title, items)
                _depth.value = stack.size
            } catch (e: Exception) { _error.value = e.message ?: "Failed to load" }
            _loading.value = false
        }
    }

    private fun showRoot() {
        stack.clear()
        _search.value = null
        _node.value = Node("Library", rootItems())
        _depth.value = 0
        loadRecent()
        loadRecentlyAdded()
        loadRecommendations()
        loadInProgress()
    }

    /** Best-effort — the shelf is hidden rather than erroring if a server lacks it. */
    private fun loadRecent() {
        viewModelScope.launch {
            _recent.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyPlayed()
                else subsonic?.albumList("recent", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
        }
    }

    /** Load the "Recently Added" shelf (MA only). */
    private fun loadRecentlyAdded() {
        if (_backend.value != Backend.MA) return
        viewModelScope.launch {
            _recentlyAdded.value = try { maRepo.recentlyAdded() } catch (_: Exception) { emptyList() }
            rememberFavorites(_recentlyAdded.value)
        }
    }

    /** Load the "For You" recommendations shelf (MA only). */
    private fun loadRecommendations() {
        if (_backend.value != Backend.MA) return
        viewModelScope.launch {
            _recommendations.value = try { maRepo.recommendations() } catch (_: Exception) { emptyList() }
            rememberFavorites(_recommendations.value)
        }
    }

    /** Load in-progress audiobooks/podcasts (MA only). */
    private fun loadInProgress() {
        if (_backend.value != Backend.MA) return
        viewModelScope.launch {
            _inProgress.value = try { maRepo.inProgress() } catch (_: Exception) { emptyList() }
            rememberFavorites(_inProgress.value)
        }
    }

    /**
     * Which items are favourited. Seeded from the server (MA sets `favorite` on
     * every media item it returns) and then updated optimistically, so the heart
     * is right the moment a shelf loads instead of only after the user taps it.
     */
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    /** Fold whatever a load returned into the known-favourites set. */
    private fun rememberFavorites(items: List<MaItem>) {
        if (items.isEmpty()) return
        _favorites.update { known ->
            val fav = items.filter { it.favorite }.map { it.itemId }
            val unfav = items.filterNot { it.favorite }.map { it.itemId }.toSet()
            (known - unfav) + fav
        }
    }

    fun toggleFavorite(item: MaItem) {
        val isFav = item.itemId in _favorites.value
        // Flip locally first: the command is a round-trip and the heart should
        // not sit still while it happens. Rolled back if the server refuses.
        _favorites.update { if (isFav) it - item.itemId else it + item.itemId }
        viewModelScope.launch {
            try {
                if (isFav) {
                    maRepo.removeFavorite(item)
                    _toast.tryEmit("Removed from favorites")
                } else {
                    maRepo.addFavorite(item)
                    _toast.tryEmit("Added to favorites")
                }
            } catch (e: Exception) {
                _favorites.update { if (isFav) it + item.itemId else it - item.itemId }
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    fun isFavorite(item: MaItem): Boolean = item.itemId in _favorites.value

    // --- track preview -----------------------------------------------------

    /** The track currently being auditioned, if any. */
    private val _previewing = MutableStateFlow<String?>(null)
    val previewing: StateFlow<String?> = _previewing

    private var previewPlayer: android.media.MediaPlayer? = null

    /**
     * Audition a track without touching the queue: MA hands back a short preview
     * URL, which plays locally on the phone. Tapping the same track again stops it,
     * as does starting another one.
     */
    fun togglePreview(item: MaItem) {
        if (_previewing.value == item.itemId) { stopPreview(); return }
        stopPreview()
        if (_backend.value != Backend.MA) return
        _previewing.value = item.itemId
        viewModelScope.launch {
            val url = try { maRepo.trackPreview(item.itemId, item.provider) } catch (_: Exception) { null }
            if (url.isNullOrBlank()) {
                _previewing.value = null
                _toast.tryEmit("No preview available for this track")
                return@launch
            }
            // The user may have stopped it while the URL was in flight.
            if (_previewing.value != item.itemId) return@launch
            try {
                previewPlayer = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(url)
                    setOnCompletionListener { stopPreview() }
                    setOnErrorListener { _, _, _ -> stopPreview(); true }
                    setOnPreparedListener { start() }
                    prepareAsync()
                }
            } catch (_: Exception) {
                _previewing.value = null
                _toast.tryEmit("Couldn't play the preview")
            }
        }
    }

    fun stopPreview() {
        _previewing.value = null
        previewPlayer?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        previewPlayer = null
    }


    private fun rootItems(): List<MaItem> = buildList {
        if (_backend.value == Backend.MA) {
            add(category("artists", "Artists")); add(category("albums", "Albums"))
            add(category("tracks", "Tracks")); add(category("playlists", "Playlists"))
        } else {
            add(category("artists", "Artists")); add(category("newest", "Recently Added"))
            add(category("playlists", "Playlists"))
        }
        add(category("downloads", "Downloads"))
    }

    private fun category(id: String, name: String) =
        MaItem(id, CATEGORY, name, null, "category", null, null, null)

    private fun downloadItem(d: DownloadedTrack) =
        MaItem(d.id, DOWNLOAD, d.title, d.filePath, "track", d.artist, d.image, null)

    override fun onCleared() {
        super.onCleared()
        // Shared MaApiClient — don't disconnect it when one ViewModel is destroyed.
        localPlayer.stop()
        stopPreview()
    }

    private companion object {
        const val CATEGORY = "__cat__"
        const val DOWNLOAD = "__dl__"
    }
}
