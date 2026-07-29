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
    private val _recentlyAdded = MutableStateFlow<List<MaItem>>(emptyList()); val recentlyAdded: StateFlow<List<MaItem>> = _recentlyAdded
    private val _recommendations = MutableStateFlow<List<MaItem>>(emptyList()); val recommendations: StateFlow<List<MaItem>> = _recommendations
    private val _inProgress = MutableStateFlow<List<MaItem>>(emptyList()); val inProgress: StateFlow<List<MaItem>> = _inProgress
    val downloadJobs: StateFlow<List<DownloadJob>> get() = downloadManager.jobs
    /** Ids of everything on disk — what puts the "downloaded" tick on a track row. */
    val downloadedIds: StateFlow<Set<String>> = downloadManager.downloads
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8); val toast: SharedFlow<String> = _toast.asSharedFlow()
    private val stack = ArrayDeque<Node>()

    /** How deep the browser is; 0 = the root shelf (categories + shelves). */
    private val _depth = MutableStateFlow(0); val depth: StateFlow<Int> = _depth

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
        viewModelScope.launch {
            localPlayer.started.collect { track ->
                if (_backend.value == Backend.SUBSONIC) subsonic?.scrobble(track.id)
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
        // Stop all playback before switching — neither backend knows about the
        // other, so without this a song playing on the old backend keeps playing
        // under whatever the new backend starts.
        localPlayer.stop()
        stopMaPlayback()

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
                val err = sc.pingError()
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
     * The server is unreachable. If anything has been downloaded, run on that
     * instead of showing a dead end — an offline library is what the downloads are
     * for. With nothing downloaded there is genuinely nothing to show, so the
     * connect form stands.
     */
    private fun goOfflineIfPossible(error: String) {
        _connError.value = "Couldn't reach Navidrome — $error"
        if (downloads.value.isEmpty()) {
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
                        val direct = if (option == "replace") navidromeDirectUrl(item) else null
                        if (direct != null) {
                            // Playing a Navidrome stream directly stops MA — same
                            // reason as playLocal: both can't own the speaker.
                            if (option == "replace") stopMaPlayback()
                            localPlayer.setQueue(listOf(localTrack(item, streamUrl = direct)))
                            _toast.tryEmit("Playing ${item.name} — original file")
                            return@launch
                        }
                        // Handing playback back to Music Assistant ends the local
                        // session, or Now Playing would keep showing (and its
                        // buttons keep driving) a player that is no longer the one
                        // making the sound.
                        if (option == "replace") localPlayer.stop()
                        item.uri?.let { maRepo.playMedia(playTarget(), listOf(it), option) }
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
        // the local player plays on top of it.
        if (option == "replace") {
            localPlayer.stop()
            stopMaPlayback()
        }
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

    private fun localTrack(item: MaItem, streamUrl: String? = null): LocalTrack =
        downloadManager.toLocalTrack(
            item = item,
            streamUrl = streamUrl
                ?: item.takeIf { it.provider == SubsonicClient.PROVIDER }?.let { subsonic?.streamUrl(it.itemId) },
            // A DOWNLOAD item carries its file path as its uri; everything else has
            // to be looked up in the index by id.
            localPathFallback = item.uri?.takeIf { item.provider == DOWNLOAD },
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
    private suspend fun navidromeDirectUrl(item: MaItem): String? {
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
        return sc.streamUrl(match.itemId)
    }

    /** Build (and cache) a Navidrome client from saved settings, if there is one. */
    private suspend fun navidromeClient(): SubsonicClient? {
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) return null
        return SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
            .also { subsonic = it }
    }

    fun playAll(items: List<MaItem>) {
        val tracks = items.filter { it.playable || it.provider == DOWNLOAD }
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (_backend.value == Backend.MA && tracks.none { it.provider == DOWNLOAD }) {
                    localPlayer.stop()
                    maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }, "replace")
                    _toast.tryEmit("Queued ${tracks.size} tracks")
                } else {
                    // Local playback — stop MA first so both don't play at once.
                    stopMaPlayback()
                    localPlayer.setQueue(tracks.map { localTrack(it) }, 0)
                    _toast.tryEmit("Playing ${tracks.size} tracks")
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
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
        viewModelScope.launch {
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
            _node.value = Node(DOWNLOADS_TITLE, downloadItems(downloads.value))
            _depth.value = stack.size
            return
        }
        pushNode(title) {
            if (_backend.value == Backend.MA) when (id) {
                "artists" -> maRepo.artists(); "albums" -> maRepo.albums()
                "tracks" -> maRepo.tracks(); "playlists" -> maRepo.playlists(); else -> emptyList()
            } else {
                val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                when (id) {
                    "artists" -> sc.artists()
                    "albums" -> sc.albumList("alphabeticalByName", size = 500)
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

    /** Best-effort — a shelf is hidden rather than erroring if a server lacks it. */
    private fun loadRecent() {
        if (_offline.value) { _recent.value = emptyList(); return }
        viewModelScope.launch {
            _recent.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyPlayed()
                else subsonic?.albumList("recent", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
        }
    }

    private fun loadRecentlyAdded() {
        if (_offline.value) { _recentlyAdded.value = emptyList(); return }
        viewModelScope.launch {
            _recentlyAdded.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyAdded()
                else subsonic?.albumList("newest", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_recentlyAdded.value)
        }
    }

    /** "For you": MA's recommendations, or a fresh handful of the shelf you forgot. */
    private fun loadRecommendations() {
        if (_offline.value) { _recommendations.value = emptyList(); return }
        viewModelScope.launch {
            _recommendations.value = try {
                if (_backend.value == Backend.MA) maRepo.recommendations()
                else subsonic?.albumList("random", size = 12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_recommendations.value)
        }
    }

    /** Load in-progress audiobooks/podcasts (MA only). */
    private fun loadInProgress() {
        if (_backend.value != Backend.MA) { _inProgress.value = emptyList(); return }
        viewModelScope.launch {
            _inProgress.value = try { maRepo.inProgress() } catch (_: Exception) { emptyList() }
            rememberFavorites(_inProgress.value)
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
        add(category("downloads", "Downloads"))
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
    }
}
