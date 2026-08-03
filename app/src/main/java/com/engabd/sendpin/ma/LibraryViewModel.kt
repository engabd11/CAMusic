package com.engabd.sendpin.ma

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.subsonic.SubsonicError
import com.engabd.sendpin.subsonic.SubsonicException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * On-device library. Two switchable backends:
 *  - **Music Assistant** (`/ws` API): browse/search and play to a target player,
 *    defaulting to THIS phone (its MA queue id == our Sendspin client id).
 *  - **Navidrome / OpenSubsonic** (direct): browse/search and play *locally* on the
 *    phone through [com.engabd.sendpin.audio.LocalPlayer], with a real queue, and
 *    download original files for offline. This is the standalone path — it works
 *    with Music Assistant and Home Assistant both down, and with no network at all
 *    once tracks are downloaded.
 *
 * Backs the OLED library UI: a root shelf of categories plus dynamic shelves, then
 * a browse stack, with search, downloads and connection state alongside.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    enum class Backend { MA, SUBSONIC }
    data class Node(val title: String, val items: List<MaItem>)

    private val settings = AppSettings(app)
    val myPlayerId: String = PlayerIdentity.getPlayerId(app)

    // The MA player to play to (blank = this phone). Kept in sync with the Speakers picker.
    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    /**
     * Whether to ask MA to keep the music going once this queue runs dry. Read at
     * play time rather than cached: `radio_mode` is a parameter of
     * `player_queues/play_media`, so it only ever means anything here.
     */
    private suspend fun radioMode(): Boolean = settings.radioMode.first()

    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    private var subsonic: SubsonicClient? = null

    /** Process-scoped, so Now Playing and the media notification see the same player. */
    val localPlayer = (app as SendpinApp).localPlayer
    val downloadManager = (app as SendpinApp).downloads
    val downloads: StateFlow<List<DownloadedTrack>> get() = downloadManager.downloads

    private val _backend = MutableStateFlow(Backend.MA); val backend: StateFlow<Backend> = _backend
    private val _ready = MutableStateFlow(false); val ready: StateFlow<Boolean> = _ready
    /** Settings have been read, so a blank server URL now means "really not set up". */
    private val _booted = MutableStateFlow(false); val booted: StateFlow<Boolean> = _booted
    private val _connecting = MutableStateFlow(false); val connecting: StateFlow<Boolean> = _connecting
    private val _connError = MutableStateFlow<String?>(null); val connError: StateFlow<String?> = _connError
    /**
     * The Navidrome server can't be reached but there are downloads, so the library
     * runs on what's on the phone. This is the whole point of downloading, so it is
     * a *mode* rather than an error screen.
     */
    private val _offline = MutableStateFlow(false); val offline: StateFlow<Boolean> = _offline

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
    private val _search = MutableStateFlow<MaSearchResults?>(null)
    /**
     * Results are kept while the user drills into one of them, so [searchOpen] —
     * not the results themselves — decides whether the list is on screen.
     */
    private val _searchOpen = MutableStateFlow(false); val searchOpen: StateFlow<Boolean> = _searchOpen
    val search: StateFlow<MaSearchResults?> = _search
    /** Browse depth the current search was launched from; -1 when not searching. */
    private var searchDepth = -1
    /** The text in the search box, held here so a tab switch doesn't wipe it. */
    private val _query = MutableStateFlow(""); val query: StateFlow<String> = _query
    fun setQuery(v: String) { _query.value = v }
    private val _recent = MutableStateFlow<List<MaItem>>(emptyList()); val recent: StateFlow<List<MaItem>> = _recent
    private val _favoriteAlbums = MutableStateFlow<List<MaItem>>(emptyList()); val favoriteAlbums: StateFlow<List<MaItem>> = _favoriteAlbums
    private val _favoriteArtists = MutableStateFlow<List<MaItem>>(emptyList()); val favoriteArtists: StateFlow<List<MaItem>> = _favoriteArtists
    private val _recentlyAdded = MutableStateFlow<List<MaItem>>(emptyList()); val recentlyAdded: StateFlow<List<MaItem>> = _recentlyAdded
    private val _recommendations = MutableStateFlow<List<MaItem>>(emptyList()); val recommendations: StateFlow<List<MaItem>> = _recommendations
    private val _inProgress = MutableStateFlow<List<MaItem>>(emptyList()); val inProgress: StateFlow<List<MaItem>> = _inProgress

    /**
     * Frequently-played albums — a Navidrome shelf from `getAlbumList2(frequent)`.
     *
     * Declared here with the other shelves rather than beside [loadFrequent], which
     * is where it was and where it read more naturally. [init] collects the API
     * state on `viewModelScope` — `Dispatchers.Main.immediate` — so on the main
     * thread a StateFlow hands over its current value *synchronously during
     * construction*, and `showRoot()` runs before any property below [init] exists.
     * Sitting further down the file, this was still null when the first shelf load
     * reached it, and the app died the moment the library opened.
     */
    private val _frequent = MutableStateFlow<List<MaItem>>(emptyList()); val frequent: StateFlow<List<MaItem>> = _frequent
    val downloadJobs: StateFlow<List<DownloadJob>> get() = downloadManager.jobs
    /** Ids of everything on disk — what puts the "downloaded" tick on a track row. */
    val downloadedIds: StateFlow<Set<String>> = downloadManager.downloads
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8); val toast: SharedFlow<String> = _toast.asSharedFlow()
    private val stack = ArrayDeque<Node>()

    // --- playlist create / delete (both backends) --------------------------

    /**
     * Whether the "Create playlist" dialog is open. The dialog lives in the
     * library because a playlist is a library-level object — it is created
     * empty and filled later by playing into it — and the entry point sits
     * next to the Playlists category.
     */
    private val _showCreatePlaylist = MutableStateFlow(false)
    val showCreatePlaylist: StateFlow<Boolean> = _showCreatePlaylist

    fun openCreatePlaylist() { _showCreatePlaylist.value = true }
    fun closeCreatePlaylist() { _showCreatePlaylist.value = false }

    /**
     * The item waiting to be filed into a playlist, and the playlists to choose
     * from. Adding to a playlist was the one thing neither backend could do at
     * all — a playlist could be created and deleted, and then only filled by
     * playing into it and saving the queue.
     */
    private val _addingToPlaylist = MutableStateFlow<MaItem?>(null)
    val addingToPlaylist: StateFlow<MaItem?> = _addingToPlaylist

    private val _playlistChoices = MutableStateFlow<List<MaItem>>(emptyList())
    val playlistChoices: StateFlow<List<MaItem>> = _playlistChoices

    fun openAddToPlaylist(item: MaItem) {
        _addingToPlaylist.value = item
        viewModelScope.launch {
            _playlistChoices.value = try {
                when (_backend.value) {
                    Backend.MA -> maRepo.playlists()
                    Backend.SUBSONIC -> subsonic?.playlists().orEmpty()
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    fun closeAddToPlaylist() { _addingToPlaylist.value = null }

    /**
     * File [item] into [playlist]. A container resolves to its tracks first, so
     * "add this album to my playlist" means the album's tracks rather than nothing.
     */
    fun addToPlaylist(playlist: MaItem) {
        val item = _addingToPlaylist.value ?: return
        _addingToPlaylist.value = null
        viewModelScope.launch {
            try {
                val tracks =
                    if (item.mediaType == "track") listOf(item) else childrenOf(item)
                        .filter { it.mediaType == "track" }
                if (tracks.isEmpty()) { _toast.tryEmit("Nothing to add"); return@launch }
                when (playlist.provider) {
                    SubsonicClient.PROVIDER -> {
                        val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                        sc.updatePlaylist(playlist.itemId, addSongIds = tracks.map { it.itemId })
                    }
                    // MA identifies playlist members by uri, not by library id.
                    else -> maRepo.addPlaylistTracks(playlist, tracks.mapNotNull { it.uri })
                }
                _toast.tryEmit(
                    if (tracks.size == 1) "Added to \"${playlist.name}\""
                    else "Added ${tracks.size} tracks to \"${playlist.name}\""
                )
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't add to playlist")
            }
        }
    }

    /**
     * Create a playlist on whichever backend is active. A freshly-created
     * playlist has no tracks; the user fills it by playing into it or by
     * using "Add to queue" from the library and then "Save queue as playlist".
     */
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        _showCreatePlaylist.value = false
        viewModelScope.launch {
            try {
                when (_backend.value) {
                    Backend.MA -> {
                        maRepo.createPlaylist(name.trim())
                        _toast.tryEmit("Created \"${name.trim()}\"")
                        refresh()
                    }
                    Backend.SUBSONIC -> {
                        val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                        sc.createPlaylist(name.trim())
                        _toast.tryEmit("Created \"${name.trim()}\"")
                        refresh()
                    }
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't create playlist")
            }
        }
    }

    /**
     * Delete a playlist from whichever backend owns it. The caller passes the
     * [MaItem] as it appears in the library — its `provider` decides which
     * backend's delete command is used.
     */
    fun deletePlaylist(item: MaItem) {
        viewModelScope.launch {
            try {
                when (item.provider) {
                    SubsonicClient.PROVIDER -> {
                        val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                        sc.deletePlaylist(item.itemId)
                    }
                    else -> maRepo.deletePlaylist(item)
                }
                _toast.tryEmit("Deleted \"${item.name}\"")
                refresh()
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't delete playlist")
            }
        }
    }

    /** How deep the browser is; 0 = the root shelf (categories + shelves). */
    private val _depth = MutableStateFlow(0); val depth: StateFlow<Int> = _depth

    // ── Refresh ─────────────────────────────────────────────────────────────
    //
    // Both backends cache hard at their own end and neither pushes an "the library
    // changed" event, so a newly-added album simply isn't there until something asks
    // again. Without a way to ask, the only remedy was to kill the app.

    /**
     * How to re-fetch the node currently on screen. Null at the root shelf, whose
     * refresh is [showRoot] rather than a single loader.
     *
     * Kept as a stack alongside [stack] so walking back up restores the right one:
     * the node a [pushNode] published knows how it was built, but its parent's
     * loader is only recoverable if it was put somewhere first.
     */
    private data class Reload(val title: String, val load: suspend ((List<MaItem>) -> Unit) -> List<MaItem>)
    private var reload: Reload? = null
    private val reloadStack = ArrayDeque<Reload?>()

    private val _refreshing = MutableStateFlow(false); val refreshing: StateFlow<Boolean> = _refreshing
    private var refreshJob: Job? = null

    /**
     * Re-pull whatever the library is showing, from the server.
     *
     * Refreshes what the user is actually looking at rather than always bouncing to
     * the root: a search re-runs, a browsed node re-fetches in place (no push, so
     * Back still goes where it did), and the root reloads every shelf.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                val here = reload
                when {
                    _searchOpen.value && _query.value.isNotBlank() -> runSearch(_query.value)
                    here == null -> reloadRoot()
                    else -> reloadNode(here)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Re-run a node's own loader, replacing it in place rather than pushing a copy. */
    private suspend fun reloadNode(target: Reload) {
        _loading.value = true; _error.value = null
        try {
            fun publish(items: List<MaItem>) {
                rememberFavorites(items)
                _node.value = Node(target.title, items)
            }
            publish(target.load { partial -> if (partial.isNotEmpty()) publish(partial) })
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to refresh"
        }
        _loading.value = false
    }

    /** The root shelf, re-fetched — and waited for, so the spinner means something. */
    private suspend fun reloadRoot() {
        _node.value = Node("Library", rootItems())
        listOfNotNull(
            loadFavoriteAlbums(), loadFavoriteArtists(), loadRecent(),
            loadRecentlyAdded(), loadRecommendations(), loadInProgress(),
            loadFrequent(),
        ).joinAll()
    }

    init {
        viewModelScope.launch {
            _maUrl.value = settings.maBaseUrl.first(); _maUser.value = settings.maUsername.first(); _maPass.value = settings.maPassword.first()
            _navUrl.value = settings.navUrl.first(); _navUser.value = settings.navUsername.first(); _navPass.value = settings.navPassword.first()
            // Backend first, then booted: the settings collector below only acts
            // once booted, so this ordering keeps boot from racing it into two
            // connects for the same backend.
            applyBackend(if (settings.backend.first() == "subsonic") Backend.SUBSONIC else Backend.MA)
            _booted.value = true
        }
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        // This client outlives any one screen, so a format change made in Settings
        // has to reach it rather than waiting for a reconnect.
        viewModelScope.launch { settings.navStreamFormat.collect { subsonic?.streamFormat = it } }
        // The backend belongs to Settings, so follow it rather than owning it.
        // [setBackend] no-ops on an unchanged value, so this doesn't feed back.
        viewModelScope.launch {
            settings.backend.collect { stored ->
                if (!_booted.value) return@collect
                setBackend(if (stored == "subsonic") Backend.SUBSONIC else Backend.MA)
            }
        }
        viewModelScope.launch {
            maApi.state.collect { st ->
                if (_backend.value != Backend.MA) return@collect
                _ready.value = st == MaApiClient.State.CONNECTED
                if (st == MaApiClient.State.CONNECTED) showRoot()
                if (st == MaApiClient.State.ERROR) _connError.value = "Connection failed"
            }
        }
        // A local track that actually started is a play worth reporting, so
        // Navidrome's play counts and its "recently played" shelf stay honest.
        //
        // Two reports per track, which is what the Subsonic spec actually asks for:
        // a "now playing" ping the moment it starts, and a completed play once it has
        // been listened to. Sending the completion at track start — which is what the
        // app used to do — counted every one-second skip as a full play.
        viewModelScope.launch {
            localPlayer.started.collect { track ->
                // Keyed on the track carrying a Navidrome id, not on the selected
                // backend. "Play at original quality" streams straight from Navidrome
                // while the *MA* backend is selected, and the old `Backend.SUBSONIC`
                // gate meant precisely the listening this audience does most —
                // untouched, straight from the source — was the listening that never
                // counted towards play counts or "recently played".
                val sc = subsonic ?: return@collect
                val songId = track.scrobbleId ?: return@collect
                val startedAtMs = System.currentTimeMillis()
                sc.scrobble(songId, submission = false)
                submissionJob?.cancel()
                submissionJob = viewModelScope.launch { submitWhenPlayed(sc, songId, startedAtMs) }
            }
        }
        viewModelScope.launch { localPlayer.errors.collect { _toast.tryEmit(it) } }
        // The Downloads shelf is a live view of the index, not a snapshot — a
        // download landing (or a delete) while the user is standing on it has to
        // show up, or the list quietly lies about what is on the phone.
        viewModelScope.launch {
            downloads.collect { list ->
                if (_node.value.title == DOWNLOADS_TITLE) _node.value = Node(DOWNLOADS_TITLE, downloadItems(list))
            }
        }
    }

    fun setBackend(b: Backend) {
        if (_backend.value == b) return
        // Stop all playback on the way out — neither backend knows about the other,
        // so without this a song playing on the old backend keeps playing under
        // whatever the new one starts.
        //
        // This lives here rather than in [applyBackend] on purpose: applyBackend also
        // runs at boot, and stopping there meant every launch of the app fired a stop
        // at this phone's MA player. Opening the app to see what was playing killed
        // what was playing.
        localPlayer.stop()
        stopMaPlayback()
        applyBackend(b)
        viewModelScope.launch { settings.setBackend(if (b == Backend.SUBSONIC) "subsonic" else "ma") }
    }

    /**
     * Switch the library over to [b] and put it back on its root.
     *
     * Everything derived from the old backend is dropped here — the browse stack,
     * the shelves, the search, the favourites set. Leaving them behind is what made
     * switching look like it did nothing: the previous backend's albums stayed on
     * screen under the new backend's name.
     *
     * Readiness is then re-derived rather than waited for. The MA branch used to
     * rely on `maApi.state` *transitioning* to CONNECTED, so switching back to a
     * socket that was already connected left the library stuck on its skeleton
     * forever, because a StateFlow does not re-emit a value it is already holding.
     */
    private fun applyBackend(b: Backend) {
        _backend.value = b
        _ready.value = false
        _offline.value = false
        _connError.value = null
        _error.value = null
        _favorites.value = emptySet()
        stack.clear()
        _depth.value = 0
        _node.value = Node("Library", emptyList())
        _recent.value = emptyList()
        _favoriteAlbums.value = emptyList()
        _favoriteArtists.value = emptyList()
        _recentlyAdded.value = emptyList()
        _recommendations.value = emptyList()
        _inProgress.value = emptyList()
        clearSearch()

        if (b == Backend.MA) {
            if (maApi.state.value == MaApiClient.State.CONNECTED) {
                _ready.value = true
                showRoot()
            } else if (_maUrl.value.isNotBlank()) {
                connect()
            }
        } else {
            subsonic = null
            // With no server on file the connect form is the right answer, even if
            // there are downloads — the user has not set this backend up yet.
            if (_navUrl.value.isNotBlank()) connect()
        }
    }

    fun setMaUrl(v: String) { _maUrl.value = v }
    fun setMaUser(v: String) { _maUser.value = v }
    fun setMaPass(v: String) { _maPass.value = v }
    fun setNavUrl(v: String) { _navUrl.value = v }
    fun setNavUser(v: String) { _navUser.value = v }
    fun setNavPass(v: String) { _navPass.value = v }

    /**
     * Persist the Navidrome server and, if it is the active backend, reconnect.
     * Settings can edit these while browsing Music Assistant, and saving must not
     * quietly reconnect the *other* backend.
     */
    fun saveNavidrome() {
        viewModelScope.launch {
            settings.setNavidrome(_navUrl.value.trim(), _navUser.value, _navPass.value)
            if (_backend.value == Backend.SUBSONIC) connect()
        }
    }

    fun connect() {
        _connError.value = null
        _offline.value = false
        // Only the backend being connected has its credentials written. Writing both
        // meant a save from one screen could stamp the other backend's settings with
        // whatever this ViewModel happened to be holding.
        if (_backend.value == Backend.MA) {
            viewModelScope.launch { settings.setMa(_maUrl.value, _maUser.value, _maPass.value) }
            val url = _maUrl.value.trim(); if (url.isBlank()) return
            _connecting.value = true
            maApi.connect(url, token = null, username = _maUser.value.ifBlank { null }, password = _maPass.value.ifBlank { null })
            viewModelScope.launch {
                maApi.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
                _connecting.value = false
            }
        } else {
            viewModelScope.launch { settings.setNavidrome(_navUrl.value.trim(), _navUser.value, _navPass.value) }
            val url = _navUrl.value.trim()
            if (url.isBlank()) { subsonic = null; _ready.value = false; return }
            _connecting.value = true
            val sc = SubsonicClient(url, _navUser.value, _navPass.value); subsonic = sc
            viewModelScope.launch {
                sc.streamFormat = settings.navStreamFormat.first()
                val err = sc.pingResult()
                _connecting.value = false
                if (err == null) {
                    _ready.value = true
                    _offline.value = false
                    showRoot()
                } else {
                    _ready.value = false
                    goOfflineIfPossible(err)
                }
            }
        }
    }

    /**
     * The ping failed. If anything has been downloaded, run on that instead of
     * showing a dead end — an offline library is what the downloads are for. With
     * nothing downloaded there is genuinely nothing to show, so the connect form
     * stands.
     *
     * Rejected credentials are the exception: the server answered, so "couldn't
     * reach" would be a lie and dropping to offline would hide the one thing the
     * user can actually fix. Those keep the form up whatever is downloaded.
     */
    private fun goOfflineIfPossible(error: SubsonicException) {
        val auth = SubsonicError.isAuth(error.code)
        _connError.value =
            if (auth) error.message.orEmpty()
            else "Couldn't reach Navidrome — ${error.message}"
        if (auth || downloads.value.isEmpty()) {
            _offline.value = false
            _ready.value = false
            return
        }
        _offline.value = true
        _ready.value = true
        showRoot()
    }

    // --- browse / play ----------------------------------------------------

    /**
     * Stop Music Assistant playback on the target player. Fire-and-forget —
     * if the API is down or the player doesn't respond, there's nothing more
     * to do; the local player will claim audio focus regardless.
     */
    private fun stopMaPlayback() {
        viewModelScope.launch {
            runCatching { maRepo.stop(playTarget()) }
        }
    }

    fun open(item: MaItem) {
        when {
            item.provider == CATEGORY -> openCategory(item.itemId, item.name)
            // Opening a result hides the result list but keeps it in memory, so
            // Back returns to the same matches instead of an empty search box.
            item.browsable -> { _searchOpen.value = false; pushNode(item.name) { childrenOf(item) } }
            item.playable || item.provider == DOWNLOAD -> play(item)
        }
    }

    /**
     * Back unwinds in the order the user built it: browse stack first, then back
     * to the search results that led here, then out of search altogether.
     */
    fun back(): Boolean {
        if (stack.isNotEmpty()) {
            _node.value = stack.removeLast()
            reload = if (reloadStack.isEmpty()) null else reloadStack.removeLast()
            _depth.value = stack.size
            // Returning to the depth the search was launched from puts the results back.
            if (_search.value != null && stack.size == searchDepth) _searchOpen.value = true
            return true
        }
        if (_search.value != null) { clearSearch(); return true }
        return false
    }

    /**
     * Play [item]. [option] is MA's queue option — `replace` starts it, `add`
     * appends, `next` plays it after the current track.
     *
     * On the standalone backend this fills the local queue rather than firing a
     * single URL at a bare MediaPlayer: tapping a track plays the *list it is in*
     * from that point, and tapping an album or playlist plays the whole thing.
     * Anything already downloaded is played from disk, so a queue survives the
     * server going away mid-album.
     */
    fun play(item: MaItem, option: String = "replace") {
        viewModelScope.launch {
            try {
                when {
                    item.provider == DOWNLOAD -> playLocal(downloadContext(item), option)
                    item.provider == SubsonicClient.PROVIDER -> playLocal(subsonicContext(item), option)
                    else -> {
                        val direct = if (option == "replace") navidromeDirect(item) else null
                        if (direct != null) {
                            // Playing a Navidrome stream directly stops MA — same
                            // reason as playLocal: both can't own the speaker.
                            // (`direct` is only ever resolved for "replace".)
                            stopMaPlayback()
                            localPlayer.setQueue(
                                listOf(
                                    localTrack(item, streamUrl = direct.url, scrobbleId = direct.songId)
                                )
                            )
                            _toast.tryEmit("Playing ${item.name} — original file")
                            return@launch
                        }
                        // Handing playback back to Music Assistant ends the local
                        // session, or Now Playing would keep showing (and its
                        // buttons keep driving) a player that is no longer the one
                        // making the sound.
                        if (option == "replace") localPlayer.stop()
                        item.uri?.let {
                            maRepo.playOn(playTarget(), listOf(it), option, radioMode())
                        }
                        _toast.tryEmit(if (option == "replace") "Playing ${item.name}" else "Added to queue")
                    }
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /** Hand a resolved list to the local player under the requested queue option. */
    private fun playLocal(context: PlayContext, option: String) {
        if (context.tracks.isEmpty()) { _toast.tryEmit("Nothing to play"); return }
        // Stop Music Assistant playback before starting the local player —
        // without this, MA keeps streaming on the Sendspin connection while
        // the local player plays on top of it. The local side needs no stop:
        // `setQueue` below replaces the queue, and stopping first would only drop
        // audio focus for long enough to let something else take it.
        if (option == "replace") stopMaPlayback()
        when (option) {
            "add" -> {
                localPlayer.addToQueue(context.tracks)
                _toast.tryEmit(if (context.tracks.size == 1) "Added to queue" else "Added ${context.tracks.size} tracks")
            }
            "next" -> {
                localPlayer.playNext(context.tracks)
                _toast.tryEmit("Playing next")
            }
            else -> {
                localPlayer.setQueue(context.tracks, context.startIndex)
                _toast.tryEmit(context.label)
            }
        }
    }

    private data class PlayContext(val tracks: List<LocalTrack>, val startIndex: Int, val label: String)

    /**
     * What playing a Subsonic item means.
     *
     * A track is played *in the list it was tapped in* — the album, the playlist,
     * the search results — because a track on its own is a queue of one, and that
     * is the thing that made this backend feel broken. A container resolves to all
     * of its tracks.
     */
    private suspend fun subsonicContext(item: MaItem): PlayContext {
        val sc = subsonic
        if (item.mediaType == "track") {
            val siblings = visibleTracks().filter { it.provider == SubsonicClient.PROVIDER }
            val list = siblings.ifEmpty { listOf(item) }
            val start = list.indexOfFirst { it.itemId == item.itemId }.coerceAtLeast(0)
            return PlayContext(list.map { localTrack(it) }, start, "Playing ${item.name}")
        }
        val tracks = sc?.tracksUnder(item) ?: emptyList()
        return PlayContext(tracks.map { localTrack(it) }, 0, "Playing ${item.name}")
    }

    /** Downloads play as one list, in the order the Downloads shelf shows them. */
    private fun downloadContext(item: MaItem): PlayContext {
        val list = visibleTracks().filter { it.provider == DOWNLOAD }.ifEmpty {
            downloads.value.map { downloadItem(it) }
        }
        val start = list.indexOfFirst { it.itemId == item.itemId }.coerceAtLeast(0)
        return PlayContext(list.map { localTrack(it) }, start, "Playing ${item.name}")
    }

    /** The track list the user is actually looking at — the node, or the search hits. */
    private fun visibleTracks(): List<MaItem> {
        val fromSearch = if (_searchOpen.value) _search.value?.tracks.orEmpty() else emptyList()
        val fromNode = _node.value.items.filter { it.mediaType == "track" }
        return fromSearch.ifEmpty { fromNode }
    }

    private fun localTrack(
        item: MaItem,
        streamUrl: String? = null,
        scrobbleId: String? = null,
    ): LocalTrack =
        downloadManager.toLocalTrack(
            item = item,
            streamUrl = streamUrl
                ?: item.takeIf { it.provider == SubsonicClient.PROVIDER }?.let { subsonic?.streamUrl(it.itemId) },
            // A DOWNLOAD item carries its file path as its uri; everything else has
            // to be looked up in the index by id.
            localPathFallback = item.uri?.takeIf { item.provider == DOWNLOAD },
        ).copy(
            // Downloads are Subsonic-only, so their id is a Navidrome id too.
            scrobbleId = scrobbleId
                ?: item.itemId.takeIf {
                    item.provider == SubsonicClient.PROVIDER || item.provider == DOWNLOAD
                },
        )

    /**
     * "Play at original quality": when Music Assistant would have to convert a
     * track to hand it to this phone, fetch the untouched file from Navidrome
     * instead and decode it locally.
     *
     * Returns null — meaning "just use MA" — unless every condition holds:
     *  - the setting is on;
     *  - the target is this phone (routing to a speaker has to go through MA, and
     *    the local player has no multi-room);
     *  - Navidrome is configured and can be matched to this track;
     *  - MA could *not* have streamed it untouched anyway. There is no point
     *    losing the queue for a 44.1/16 file the server would have passed through.
     */
    private suspend fun navidromeDirect(item: MaItem): DirectStream? {
        if (!settings.preferOriginal.first()) return null
        if (_backend.value != Backend.MA) return null
        if (item.mediaType != "track") return null
        if (_targetPlayer.value.isNotBlank() && _targetPlayer.value != myPlayerId) return null

        val fmt = item.audioFormat ?: return null
        val hiRes = settings.preferHiRes.first()
        if (FormatNegotiator.canStreamUntouched(fmt.sampleRate, fmt.bitDepth, hiRes)) return null

        val sc = subsonic ?: navidromeClient() ?: return null
        // MA and Navidrome have different ids for the same file, so match by name.
        val match = try {
            sc.search("${item.name} ${item.subtitle.orEmpty()}".trim()).tracks
                .firstOrNull { it.name.equals(item.name, ignoreCase = true) }
        } catch (_: Exception) { null } ?: return null
        // The matched id travels with the URL: it is the only id Navidrome will
        // accept a scrobble for, and the MaItem's own id is Music Assistant's.
        return DirectStream(url = sc.streamUrl(match.itemId), songId = match.itemId)
    }

    /** A Navidrome stream for a Music Assistant item, with the id Navidrome knows it by. */
    private data class DirectStream(val url: String, val songId: String)

    /** Build (and cache) a Navidrome client from saved settings, if there is one. */
    private suspend fun navidromeClient(): SubsonicClient? {
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) return null
        return SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
            .also { it.streamFormat = settings.navStreamFormat.first(); subsonic = it }
    }

    /**
     * [option] follows Music Assistant's queue options: `replace` (the default — play
     * these instead), `next`, or `add`. The enqueue options deliberately do *not* stop
     * the other player or reset the queue; adding to what's playing is the whole point.
     */
    fun playAll(items: List<MaItem>, option: String = "replace") {
        val tracks = items.filter { it.playable || it.provider == DOWNLOAD }
        if (tracks.isEmpty()) return
        val replacing = option == "replace"
        viewModelScope.launch {
            try {
                if (_backend.value == Backend.MA && tracks.none { it.provider == DOWNLOAD }) {
                    if (replacing) localPlayer.stop()
                    maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, option, radioMode())
                    _toast.tryEmit(queuedMessage(option, tracks.size))
                } else {
                    // Local playback — stop MA first so both don't play at once.
                    if (replacing) stopMaPlayback()
                    val local = tracks.map { localTrack(it) }
                    when (option) {
                        "next" -> localPlayer.playNext(local)
                        "add" -> localPlayer.addToQueue(local)
                        else -> localPlayer.setQueue(local, 0)
                    }
                    _toast.tryEmit(queuedMessage(option, tracks.size))
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    private fun queuedMessage(option: String, n: Int) = when (option) {
        "next" -> if (n == 1) "Playing next" else "$n tracks playing next"
        "add" -> if (n == 1) "Added to queue" else "Added $n tracks to queue"
        else -> "Playing $n tracks"
    }

    // --- downloads ---------------------------------------------------------

    /**
     * Download [item] for offline. A track downloads itself; an album, playlist or
     * artist resolves to its tracks and downloads the lot — downloading an album
     * one track at a time through the UI was not a real answer.
     */
    fun download(item: MaItem) {
        val sc = subsonic
        if (sc == null) { _toast.tryEmit("Connect to Navidrome to download"); return }
        if (item.provider != SubsonicClient.PROVIDER) { _toast.tryEmit("Only Navidrome tracks can be downloaded"); return }
        viewModelScope.launch {
            val tracks = try {
                sc.tracksUnder(item)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't list that")
                return@launch
            }
            runDownload(tracks, sc)
        }
    }

    /** Download a list already on screen — an album's tracks, a playlist, a search. */
    fun downloadAll(items: List<MaItem>) {
        val sc = subsonic
        if (sc == null) { _toast.tryEmit("Connect to Navidrome to download"); return }
        val tracks = items.filter { it.provider == SubsonicClient.PROVIDER && it.mediaType == "track" }
        if (tracks.isEmpty()) { _toast.tryEmit("Nothing here to download"); return }
        viewModelScope.launch { runDownload(tracks, sc) }
    }

    /**
     * One download run, start to finish. Kept in one place (and one coroutine) so a
     * 20-track album is a single sequential job with one summary at the end, rather
     * than 20 jobs racing each other for the same wifi.
     */
    private suspend fun runDownload(tracks: List<MaItem>, sc: SubsonicClient) {
        val pending = tracks.filterNot { downloadManager.isDownloaded(it.itemId) }
        if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return }

        // Wi-Fi-only guard — skip downloads on mobile data when the setting is on.
        if (settings.downloadWifiOnly.first()) {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val isWifi = cm.activeNetwork?.let { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } ?: false
            if (!isWifi) { _toast.tryEmit("Wi-Fi only — connect to Wi-Fi to download"); return }
        }

        _toast.tryEmit(
            if (pending.size == 1) "Downloading ${pending.first().name}…"
            else "Downloading ${pending.size} tracks…"
        )
        val ok = downloadManager.downloadAll(pending, urlFor = { sc.downloadUrl(it.itemId) })
        _toast.tryEmit(
            when {
                ok == pending.size && ok == 1 -> "Downloaded ${pending.first().name}"
                ok == pending.size -> "Downloaded $ok tracks"
                ok == 0 -> "Download failed"
                else -> "Downloaded $ok of ${pending.size} — tap a failed row to dismiss"
            }
        )

        // Storage cap — evict oldest-first until back under the limit. The index is
        // appended to on each download, so the head of the list is the oldest.
        // Bounded by the snapshot rather than looping on live state: a file that has
        // vanished from disk contributes nothing to the total, and re-reading the
        // list each pass would spin on it.
        val capMb = settings.downloadStorageCapMb.first()
        if (capMb > 0) {
            val capBytes = capMb.toLong() * 1_000_000
            val nowPlayingId = localPlayer.current.value?.id
            for (entry in downloadManager.downloads.value) {
                if (downloadManager.bytesUsed() <= capBytes) break
                // Never evict the track being listened to out from under the player.
                if (entry.id == nowPlayingId) continue
                downloadManager.delete(entry.id)
            }
            if (downloadManager.bytesUsed() > capBytes) {
                _toast.tryEmit("Downloads are over the ${capMb / 1000} GB limit")
            }
        }
    }

    fun deleteDownload(id: String) = downloadManager.delete(id)

    /** Drop a failed download row once the user has acknowledged it. */
    fun dismissDownload(id: String) = downloadManager.dismissJob(id)

    fun deleteAllDownloads() {
        downloadManager.deleteAll()
        _toast.tryEmit("Downloads cleared")
    }

    /** Bytes the downloads take up, for the Settings readout. */
    fun downloadBytes(): Long = downloadManager.bytesUsed()

    fun isDownloaded(id: String): Boolean = downloadManager.isDownloaded(id)

    fun doSearch(query: String) {
        if (query.isBlank()) { clearSearch(); return }
        searchDepth = stack.size
        _searchOpen.value = true
        viewModelScope.launch { runSearch(query) }
    }

    /** The body of [doSearch], so [refresh] can await it rather than fire and forget. */
    private suspend fun runSearch(query: String) {
        _loading.value = true; _error.value = null
        try {
            val r = when {
                _backend.value == Backend.MA -> maRepo.search(query)
                _offline.value -> searchDownloads(query)
                else -> subsonic?.search(query)
            }
            r?.let { rememberFavorites(it.artists + it.albums + it.tracks + it.playlists) }
            _search.value = r
        } catch (e: Exception) { _error.value = e.message ?: "Search failed" }
        _loading.value = false
    }

    /** With the server gone, search what's on the phone rather than nothing at all. */
    private fun searchDownloads(query: String) = MaSearchResults(
        artists = emptyList(), albums = emptyList(), playlists = emptyList(),
        tracks = downloads.value
            .filter {
                it.title.contains(query, true) || it.artist.orEmpty().contains(query, true) ||
                    it.album.orEmpty().contains(query, true)
            }
            .map { downloadItem(it) },
    )

    fun clearSearch() {
        _search.value = null
        _searchOpen.value = false
        _query.value = ""
        searchDepth = -1
    }

    // --- sonic similarity --------------------------------------------------

    /**
     * Natural-language search over MA's CLAP embeddings ("late night drive, warm
     * synths"). This is a way of *finding* music, so it lives here in the library
     * next to ordinary search rather than behind the player's queue button.
     */
    fun sonicSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        if (_backend.value != Backend.MA) {
            _toast.tryEmit("Sonic search needs the Music Assistant backend")
            return
        }
        _searchOpen.value = false
        pushNode("Sounds like \"$q\"") {
            maRepo.sonicTextSearch(q, limit = 40).map { it.toItem() }
        }
    }

    /** Acoustically similar tracks to a specific library track. */
    fun similarTo(item: MaItem) {
        if (_backend.value != Backend.MA) {
            _toast.tryEmit("Sonic similarity needs the Music Assistant backend")
            return
        }
        _searchOpen.value = false
        pushNode("Similar to ${item.name}") {
            maRepo.similarTracks(item.itemId, item.provider, limit = 40).map { it.toItem() }
        }
    }

    private fun MaSimilarTrack.toItem() =
        MaItem(itemId, provider, name, uri, "track", artist, image, null)

    // --- internals --------------------------------------------------------

    private suspend fun childrenOf(item: MaItem): List<MaItem> = when (item.provider) {
        SubsonicClient.PROVIDER -> subsonic?.children(item) ?: emptyList()
        else -> maRepo.children(item)
    }

    private fun openCategory(id: String, title: String) {
        if (id == "downloads") {
            stack.addLast(_node.value)
            reloadStack.addLast(reload)
            // Reads the in-memory index rather than the server, but refreshing it is
            // still the right answer to "this list looks stale".
            reload = Reload(DOWNLOADS_TITLE) { downloadItems(downloads.value) }
            _node.value = Node(DOWNLOADS_TITLE, downloadItems(downloads.value))
            _depth.value = stack.size
            return
        }
        pushNode(title) { onPartial ->
            if (_backend.value == Backend.MA) {
                // Paged, and published as the pages land: a large library used to be a
                // single 5000-item request that simply timed out, showing an error
                // instead of a library. Now the first 500 are on screen in a moment and
                // the rest fill in behind them.
                val page: suspend (Int, Int) -> List<MaItem> = when (id) {
                    "artists" -> { o, l -> maRepo.artists(o, l) }
                    "albums" -> { o, l -> maRepo.albums(o, l) }
                    "tracks" -> { o, l -> maRepo.tracks(o, l) }
                    "playlists" -> { o, l -> maRepo.playlists(o, l) }
                    else -> return@pushNode emptyList()
                }
                maRepo.allLibraryItems(page, onPage = onPartial)
            } else {
                val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                when (id) {
                    "artists" -> sc.artists()
                    // Paged, not one 5000-item request: the spec caps `getAlbumList2`
                    // at 500 per call, and a server that enforces it answered the old
                    // request with an error rather than a truncated list. Batches are
                    // published as they land, so the wall of art fills in.
                    "albums" -> allAlbums(sc, onPartial)
                    "newest" -> sc.albumList("newest", size = 200)
                    "playlists" -> sc.playlists()
                    "genres" -> sc.genres()
                    "starred" -> sc.starred().let { it.albums + it.artists + it.tracks }
                    "random" -> sc.randomSongs(100)
                    else -> emptyList()
                }
            }
        }
    }

    /**
     * Every album on a Navidrome server, a page at a time.
     *
     * `getAlbumList2` documents a maximum `size` of 500, so the whole library has to
     * be walked with `offset`. [cap] backstops a server that ignores `offset` and
     * hands back the same page for ever.
     */
    private suspend fun allAlbums(
        sc: SubsonicClient,
        onPartial: (List<MaItem>) -> Unit,
        cap: Int = 20_000,
    ): List<MaItem> {
        val all = mutableListOf<MaItem>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (offset < cap) {
            val page = sc.albumList("alphabeticalByName", size = SUBSONIC_PAGE, offset = offset)
            if (page.isEmpty()) break
            val fresh = page.filterNot { it.itemId in seen }
            fresh.forEach { seen += it.itemId }
            if (fresh.isEmpty()) break          // the server is repeating itself
            all += fresh
            onPartial(all.toList())
            if (page.size < SUBSONIC_PAGE) break
            offset += SUBSONIC_PAGE
        }
        return all
    }

    /**
     * Open a node, showing partial results as they arrive.
     *
     * [loader] is handed an `onPartial` callback it may call with each running batch;
     * a loader that reads in one shot simply ignores it. The node is pushed on the
     * first batch so the screen changes immediately, and updated in place afterwards —
     * a paged library therefore appears at once and fills in, rather than showing a
     * spinner until every page is home.
     */
    private fun pushNode(
        title: String,
        loader: suspend ((List<MaItem>) -> Unit) -> List<MaItem>,
    ) {
        viewModelScope.launch {
            _loading.value = true; _error.value = null
            var pushed = false
            fun publish(items: List<MaItem>) {
                rememberFavorites(items)
                if (!pushed) {
                    stack.addLast(_node.value)
                    // Remember how *this* node was built, and park the parent's own
                    // loader so Back restores it. See [Reload].
                    reloadStack.addLast(reload)
                    reload = Reload(title, loader)
                    pushed = true
                    _depth.value = stack.size
                }
                _node.value = Node(title, items)
            }
            try {
                val items = loader { partial -> if (partial.isNotEmpty()) publish(partial) }
                publish(items)
            } catch (e: Exception) {
                // A partial result is better than an error page: keep what did arrive
                // and say what went wrong alongside it.
                _error.value = e.message ?: "Failed to load"
                if (!pushed) publish(emptyList())
            }
            _loading.value = false
        }
    }

    private fun showRoot() {
        stack.clear()
        reloadStack.clear()
        reload = null            // the root's refresh is [reloadRoot], not a loader
        _search.value = null
        _node.value = Node("Library", rootItems())
        _depth.value = 0
        loadFavoriteAlbums()
        loadFavoriteArtists()
        loadRecent()
        loadRecentlyAdded()
        loadRecommendations()
        loadInProgress()
        loadFrequent()
    }

    /** Best-effort — a shelf is hidden rather than erroring if a server lacks it. */
    private fun loadRecent(): Job? {
        if (_offline.value) { _recent.value = emptyList(); return null }
        return viewModelScope.launch {
            _recent.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyPlayed()
                else subsonic?.albumList("recent", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            // The only loader that used to skip this, which is why "Recently played"
            // was the one shelf whose hearts never came up filled.
            rememberFavorites(_recent.value)
        }
    }

    /** The pending "this track was actually played" report; cancelled by the next track. */
    private var submissionJob: kotlinx.coroutines.Job? = null

    /**
     * Wait until [id] has been listened to, then report the completed play.
     *
     * The conventional threshold, and the one Last.fm and Navidrome both use: half the
     * track, or four minutes, whichever comes first. Bails if the user moved on — a
     * track that was skipped is not a play, which is the whole point of splitting this
     * off from the "now playing" ping.
     */
    private suspend fun submitWhenPlayed(sc: SubsonicClient, id: String, startedAtMs: Long) {
        // Both conditions are watched together rather than one after the other: the
        // position stops emitting the moment playback ends, so waiting on it alone
        // would leave this suspended for ever on a queue that simply ran out.
        val played = combine(
            localPlayer.positionMs,
            localPlayer.durationMs,
            localPlayer.current,
        ) { position, duration, current ->
            when {
                current?.id != id -> false                                  // moved on: not a play
                duration > 0 && position >= minOf(duration / 2, SCROBBLE_MAX_MS) -> true
                else -> null                                                // still listening
            }
        }.first { it != null }
        if (played == true) sc.scrobble(id, submission = true, timeMs = startedAtMs)
    }

    private fun loadFavoriteAlbums(): Job? {
        if (_offline.value) { _favoriteAlbums.value = emptyList(); return null }
        return viewModelScope.launch {
            _favoriteAlbums.value = try {
                if (_backend.value == Backend.MA) maRepo.favoriteAlbums()
                else subsonic?.albumList("starred", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_favoriteAlbums.value)
        }
    }

    private fun loadFavoriteArtists(): Job? {
        if (_offline.value) { _favoriteArtists.value = emptyList(); return null }
        return viewModelScope.launch {
            _favoriteArtists.value = try {
                if (_backend.value == Backend.MA) maRepo.favoriteArtists()
                else subsonic?.starred()?.artists?.take(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_favoriteArtists.value)
        }
    }

    private fun loadRecentlyAdded(): Job? {
        if (_offline.value) { _recentlyAdded.value = emptyList(); return null }
        return viewModelScope.launch {
            _recentlyAdded.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyAdded()
                else subsonic?.albumList("newest", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_recentlyAdded.value)
        }
    }

    /** "For you": MA's recommendations, or a fresh handful of the shelf you forgot. */
    private fun loadRecommendations(): Job? {
        if (_offline.value) { _recommendations.value = emptyList(); return null }
        return viewModelScope.launch {
            _recommendations.value = try {
                if (_backend.value == Backend.MA) maRepo.recommendations()
                else subsonic?.albumList("random", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_recommendations.value)
        }
    }

    /** Load in-progress audiobooks/podcasts (MA only). */
    private fun loadInProgress(): Job? {
        if (_backend.value != Backend.MA) { _inProgress.value = emptyList(); return null }
        return viewModelScope.launch {
            _inProgress.value = try { maRepo.inProgress() } catch (_: Exception) { emptyList() }
            rememberFavorites(_inProgress.value)
        }
    }

    private fun loadFrequent(): Job? {
        if (_backend.value != Backend.SUBSONIC) { _frequent.value = emptyList(); return null }
        return viewModelScope.launch {
            _frequent.value = try { subsonic?.albumList("frequent", size = 12).orEmpty() } catch (_: Exception) { emptyList() }
            rememberFavorites(_frequent.value)
        }
    }

    /**
     * Which items are favourited. Seeded from the server (MA sets `favorite` on
     * every media item it returns, Subsonic sets `starred`) and then updated
     * optimistically, so the heart is right the moment a shelf loads instead of
     * only after the user taps it.
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
                if (item.provider == SubsonicClient.PROVIDER) {
                    val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                    sc.setStarred(item, !isFav)
                } else if (isFav) {
                    maRepo.removeFavorite(item)
                } else {
                    maRepo.addFavorite(item)
                }
                _toast.tryEmit(if (isFav) "Removed from favorites" else "Added to favorites")
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
        when {
            // Offline: the server's categories would all dead-end, so the phone's
            // own library is the only thing offered.
            _offline.value -> Unit
            _backend.value == Backend.MA -> {
                add(category("artists", "Artists")); add(category("albums", "Albums"))
                add(category("tracks", "Tracks")); add(category("playlists", "Playlists"))
            }
            else -> {
                add(category("artists", "Artists")); add(category("albums", "Albums"))
                add(category("playlists", "Playlists")); add(category("genres", "Genres"))
                add(category("starred", "Starred")); add(category("newest", "Recently Added"))
                add(category("random", "Shuffle all"))
            }
        }
        // Downloads only make sense for the Navidrome backend — MA streams
        // everything from its providers, and there is no file to download.
        if (_backend.value == Backend.SUBSONIC || _offline.value) {
            add(category("downloads", "Downloads"))
        }
    }

    private fun category(id: String, name: String) =
        MaItem(id, CATEGORY, name, null, "category", null, null, null)

    /**
     * Downloads grouped by album and in track order, so a downloaded album reads —
     * and plays — as an album rather than in whatever order the files happened to
     * land.
     */
    private fun downloadItems(list: List<DownloadedTrack>): List<MaItem> = list
        .sortedWith(compareBy({ it.album ?: "" }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }))
        .map { downloadItem(it) }

    private fun downloadItem(d: DownloadedTrack) = MaItem(
        itemId = d.id, provider = DOWNLOAD, name = d.title, uri = d.filePath, mediaType = "track",
        subtitle = d.artist, image = d.artUri, duration = (d.durationMs / 1000).toInt().takeIf { it > 0 },
        album = d.album, parentId = d.albumId, trackNumber = d.trackNumber,
    )

    override fun onCleared() {
        super.onCleared()
        // The MaApiClient and the LocalPlayer are process-scoped and shared — a
        // ViewModel going away (a rotation, a tab swap) must not stop the music.
        stopPreview()
    }

    private companion object {
        const val CATEGORY = "__cat__"
        const val DOWNLOAD = "__dl__"
        const val DOWNLOADS_TITLE = "Downloads"

        /** Four minutes in: a play, however long the track. The usual convention. */
        const val SCROBBLE_MAX_MS = 4 * 60 * 1000L

        /** `getAlbumList2`'s documented maximum `size`. */
        const val SUBSONIC_PAGE = 500
    }
}
