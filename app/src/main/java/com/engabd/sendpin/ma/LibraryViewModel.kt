package com.engabd.sendpin.ma

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AlbumContinuation
import com.engabd.sendpin.audio.Camelot
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.LibraryAlbumWalk
import com.engabd.sendpin.audio.LibraryRadioSource
import com.engabd.sendpin.audio.LocalRadio
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RadioSource
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.audio.SetBuilder
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.download.DownloadJob
import com.engabd.sendpin.download.DownloadedTrack
import com.engabd.sendpin.library.AuthStyle
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.JellyfinSource
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.library.ServerConfig
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.library.SourceAuthException
import com.engabd.sendpin.library.SourceError
import com.engabd.sendpin.library.SubsonicSource
import com.engabd.sendpin.subsonic.SavedQueue
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.subsonic.SubsonicError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The library root's dynamic shelves, carried as one value rather than seven flows.
 *
 * `@Immutable` for the same reason [MaItem] is: `List<T>` reads as unstable, and an
 * unstable parameter would stop the composable holding these from ever skipping.
 * Every list here is replaced wholesale by its loader, never mutated in place.
 */
@Immutable
data class LibraryShelves(
    val favoriteAlbums: List<MaItem> = emptyList(),
    val favoriteArtists: List<MaItem> = emptyList(),
    /**
     * "Continue listening" — what you were in the middle of, of any kind.
     *
     * Music Assistant's `music/in_progress_items` answers that for the two media
     * types it tracks a position on, podcasts and audiobooks, and for a long time
     * this shelf was only ever those. A listener whose library is music saw a
     * "Continue listening" shelf that never once mentioned music.
     *
     * So the songs are folded in here too, behind the genuinely-unfinished items,
     * and taken out of [recent] as they go. See [ContinueListening].
     */
    val inProgress: List<MaItem> = emptyList(),
    val recentlyAdded: List<MaItem> = emptyList(),
    val recommendations: List<MaItem> = emptyList(),
    /**
     * "Recently played", less the songs [inProgress] has taken — see
     * [ContinueListening] for why the line is drawn there.
     *
     * What is left is the albums, artists, playlists and radio stations, which is a
     * different question — "where was I listening" rather than "what was I listening
     * to" — and reads as its own shelf rather than a copy of the one above it. A
     * library that only ever plays single tracks empties this shelf out entirely, and
     * an empty shelf is dropped from the page.
     */
    val recent: List<MaItem> = emptyList(),
    val frequent: List<MaItem> = emptyList(),
    /** Music Assistant's own Discover rows — see [LibraryViewModel.loadDiscoverRows]. */
    val discover: List<DiscoverShelf> = emptyList(),
)

/**
 * One shelf the *server* named, as opposed to the seven this app hard-codes above.
 *
 * The fixed shelves each exist because someone wrote a loader for one specific Music
 * Assistant command. That does not scale: MA's built-in recommendations provider alone
 * offers sixteen rows, and every music provider can add more. Carrying the row's own
 * name and icon means a row the server grows appears here with no change to the app.
 */
@Immutable
data class DiscoverShelf(
    val key: String,
    val title: String,
    /** MA's `mdi-*` icon name, mapped to a Material icon at the tile. */
    val icon: String?,
    val items: List<MaItem>,
    /** Artists read better as circles; everything else as squares. */
    val circular: Boolean,
)

/**
 * The provider tag a root-shelf category card carries.
 *
 * Not a library at all — a sentinel saying "this row opens a list rather than plays
 * something". File-level rather than inside the view model's private companion
 * because the library screen has to recognise one too, and the alternative was the
 * literal `"__cat__"` written out in both places.
 */
internal const val CATEGORY_PROVIDER = "__cat__"

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
@OptIn(kotlinx.coroutines.FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    enum class Backend { MA, SUBSONIC }
    data class Node(val title: String, val items: List<MaItem>)

    private val settings = AppSettings(app)
    /**
     * Read live, not captured: a rename mints a new player id mid-session and a
     * captured one then aims every play command at a player MA no longer has — see
     * [PlayerIdentity.getPlayerId].
     */
    val myPlayerId: String get() = PlayerIdentity.getPlayerId(getApplication<Application>())

    // The MA player to play to (blank = this phone). Kept in sync with the Speakers picker.
    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    /**
     * Whether to ask MA to keep the music going once this queue runs dry. Read at
     * play time rather than cached: `radio_mode` is a parameter of
     * `player_queues/play_media`, so it only ever means anything here.
     *
     * **Not applied to a plain tap any more.** In MA 2.10 `radio_mode` is translated
     * into a `radio_playlist://` dynamic source seeded from the item, which is a
     * *generated* queue — so with the setting on, tapping a song from Recently added
     * played something adjacent to it rather than the song itself, intermittently and
     * with no visible cause. Every detail screen already hardcoded `false`; only the
     * shelf and list taps read the setting, which is why the two behaved differently
     * for the same track. A tap now plays what was tapped, and radio is reached
     * deliberately through [startRadio] — the "Start radio from this" action.
     *
     * The setting itself still means what it says. On the local/Subsonic backend
     * [startLocalRadio] tops the queue up two tracks before the end. On Music Assistant
     * [applyDontStopTheMusic] hands the same job to the server's own
     * `player_queues/dont_stop_the_music`, which is MA's feature for "keep going when
     * this runs dry" — as opposed to `radio_mode`, which is "don't play what I asked
     * for, play something like it".
     */
    private suspend fun radioMode(): Boolean = settings.radioMode.first()

    /**
     * Mirror the Radio Mode setting onto whatever MA queue this phone is playing to.
     *
     * Best-effort and fire-and-forget: it is a preference about what happens minutes
     * from now, so it must never delay or fail a play. Applied after the play command
     * rather than before, because the queue it names may not exist until then.
     */
    private fun applyDontStopTheMusic() {
        viewModelScope.launch {
            runCatching { maRepo.setDontStopTheMusic(maRepo.activeQueueId(playTarget()), radioMode()) }
        }
    }

    /**
     * Play [item] and let Music Assistant carry on with things like it.
     *
     * The explicit form of what the Radio Mode setting used to do to every tap. Sends
     * `radio_mode = true` regardless of the setting, because the user asked for it here
     * and now rather than in Settings a fortnight ago.
     */
    fun startRadio(item: MaItem) {
        val uri = item.uri ?: run { _toast.tryEmit("Can't start radio from that"); return }
        viewModelScope.launch {
            try {
                localPlayer.stop()
                maRepo.playOn(playTarget(), listOf(uri), "replace", radioMode = true)
                _toast.tryEmit("Radio from ${item.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't start radio")
            }
        }
    }

    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)

    /**
     * The library this phone browses and plays itself, or null on Music Assistant.
     *
     * Was a `SubsonicClient?`, with `when (backend)` around every call — which made
     * Navidrome not one library among several but a hard-coded half of the app. It is
     * now a [MusicSource]: Jellyfin arrived through that interface without any of the
     * call sites below knowing, and the next one will too.
     *
     * Backed by the process-scoped holder so the three detail view models see the
     * same live connection instead of each building a client of their own.
     */
    private val sourceHolder = (app as SendpinApp).musicSource
    private var source: MusicSource?
        get() = sourceHolder.value
        set(value) { sourceHolder.value = value }

    /**
     * What the connected library can actually do, for the rows that offer actions.
     *
     * The library's list rows decided what to offer by comparing the item's provider
     * against the string `"subsonic"`, which was the same test as "is this the only
     * self-hosted library that exists" back when it was. It stopped being that the
     * day Jellyfin arrived: a Jellyfin track got no download button — on rows or in
     * the long-press sheet — while the very same screen's "Download all" bar offered
     * to fetch the whole list, because that one had already been widened. A Plex row
     * got a favourite heart that calls a `setStarred` Plex has no endpoint for.
     *
     * So the rows ask [Capability] instead. Published as a flow rather than read
     * during composition for the reason `AlbumDetailViewModel.canDownload` gives:
     * the source is null for a moment around a reconnect, and a getter read at that
     * instant drops the action and never brings it back.
     */
    val sourceCapabilities: StateFlow<Set<Capability>> =
        sourceHolder.map { it?.capabilities ?: emptySet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The active server's stored config, so a connect knows what it is connecting to. */
    private var activeConfig: ServerConfig? = null
        set(value) {
            field = value
            _activeServerConfig.value = value
        }

    /**
     * Exposed so the connect form can label itself for the actual provider (Jellyfin,
     * Navidrome, etc.) rather than presenting every non-MA backend as Navidrome.
     */
    private val _activeServerConfig = MutableStateFlow<ServerConfig?>(null)
    val activeServerConfig: StateFlow<ServerConfig?> = _activeServerConfig

    /**
     * What to call the active library in a message.
     *
     * Every one of these strings used to say "Navidrome" because there was only ever
     * one thing it could be. Told a Jellyfin user their Jellyfin server wasn't
     * connected under someone else's product name, which is the kind of small lie
     * that makes an app feel like it was built for someone else.
     */
    private fun libraryName(): String =
        activeConfig?.displayName ?: source?.kind?.label ?: "the library"

    /**
     * The in-flight Navidrome connect, so a newer attempt can supersede it.
     *
     * Each `connect()` used to launch a ping and forget about it. Correcting a typo in
     * the server address then raced the two attempts: the wrong address sat blocked on
     * a connect timeout while the corrected one answered, went ready and showed the
     * library — and then the first one finally failed and stamped its own error over
     * the top, leaving the app reporting a host the user had already fixed until it was
     * restarted.
     */
    private var navConnectJob: Job? = null

    /** Process-scoped, so Now Playing and the media notification see the same player. */
    val localPlayer = (app as SendpinApp).localPlayer
    val downloadManager = (app as SendpinApp).downloads
    /** Offline per-track analysis (bpm, key) — for Harmonic DJ mode's ranking bonus. */
    private val trackScans = (app as SendpinApp).trackScans
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

    /**
     * A server address is on file, so we're connecting rather than unconfigured.
     *
     * Local files are "on file" the moment that library is active even though there
     * is no address to check — [_navUrl] never holds one for them — or the connect
     * form would show up asking for a server URL that doesn't apply.
     */
    val hasServer: StateFlow<Boolean> = combine(_backend, _maUrl, _navUrl, _activeServerConfig) { b, ma, nav, active ->
        if (b == Backend.SUBSONIC) active?.kind?.needsAddress == false || nav.isNotBlank() else ma.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The three settings a tap has to know, held rather than read.
     *
     * Each of these used to be a `settings.<x>.first()` on the play path. A DataStore
     * read is a suspending call that can touch disk, and "play at original quality" did
     * two of them before it had even decided whether it applied — so every tap in the
     * library paid for settings that are almost always at their defaults. Collected
     * eagerly here instead: the value is in memory by the time any row is tappable, and
     * a change still lands immediately.
     */
    private val preferOriginalNow = settings.preferOriginal
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val preferHiResNow = settings.preferHiRes
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /**
     * A search is in flight for a query the user has already typed past.
     *
     * Distinct from [loading], which drives skeletons: results now arrive while the
     * field is still being typed into, and blanking the list on every keystroke to
     * show placeholders would flicker worse than the old submit-only search did. The
     * previous results stay put under a slim progress line until the new ones land.
     */
    private val _searching = MutableStateFlow(false); val searching: StateFlow<Boolean> = _searching

    /**
     * The last few queries and what they returned, so backspacing is instant.
     *
     * Small and deliberately unbounded in age rather than time: a search session is
     * seconds long, and the point is that walking back through "beatl" → "beat" → "bea"
     * doesn't re-ask the server three times for answers it just gave.
     */
    private val searchCache = object : LinkedHashMap<String, MaSearchResults>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MaSearchResults>) = size > SEARCH_CACHE_SIZE
    }

    /** The query whose results are on screen, so an identical run can be skipped. */
    private var lastSearched: String? = null
    private val _recent = MutableStateFlow<List<MaItem>>(emptyList()); val recent: StateFlow<List<MaItem>> = _recent
    private val _favoriteAlbums = MutableStateFlow<List<MaItem>>(emptyList()); val favoriteAlbums: StateFlow<List<MaItem>> = _favoriteAlbums
    private val _favoriteArtists = MutableStateFlow<List<MaItem>>(emptyList()); val favoriteArtists: StateFlow<List<MaItem>> = _favoriteArtists
    private val _recentlyAdded = MutableStateFlow<List<MaItem>>(emptyList()); val recentlyAdded: StateFlow<List<MaItem>> = _recentlyAdded
    private val _recommendations = MutableStateFlow<List<MaItem>>(emptyList()); val recommendations: StateFlow<List<MaItem>> = _recommendations
    private val _inProgress = MutableStateFlow<List<MaItem>>(emptyList()); val inProgress: StateFlow<List<MaItem>> = _inProgress

    /**
     * Music Assistant's Discover rows, each already filled. Declared above the
     * [shelves] combine that reads it — see the note on [_frequent] for why that
     * matters here specifically.
     */
    private val _discover = MutableStateFlow<List<DiscoverShelf>>(emptyList())
    val discover: StateFlow<List<DiscoverShelf>> = _discover

    /**
     * A reachability answer for the Navidrome server that does *not* depend on it
     * being the active backend.
     *
     * The existing status line could only ever say "switch the library to Navidrome
     * to use it" while Music Assistant was selected, so there was no way to find out
     * whether the credentials were even right. And Navidrome is used from the MA
     * backend too — "play at original quality" streams from it, and every download
     * comes from it — so "is it connected" is a real question in both modes.
     */
    private val _navStatus = MutableStateFlow<String?>(null)
    val navStatus: StateFlow<String?> = _navStatus

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

    /**
     * The seven root shelves as one value.
     *
     * The library grid used to read all seven flows separately, which meant seven
     * independent ways to invalidate the whole `LazyVerticalGrid` content lambda —
     * and the shelves load one after another on connect, so opening the library
     * rebuilt the grid seven times in a row. Combined, that is one rebuild.
     *
     * Declared below every flow it reads: [init] collects on `viewModelScope`
     * (`Dispatchers.Main.immediate`), so construction runs shelf loads synchronously
     * and a property declared above its dependencies sees them as null — the same
     * trap [_frequent] documents above.
     */
    val shelves: StateFlow<LibraryShelves> = combine(
        _favoriteAlbums, _favoriteArtists, _inProgress, _recentlyAdded,
        _recommendations, _recent, _frequent, _discover,
    ) { s ->
        @Suppress("UNCHECKED_CAST")
        val split = ContinueListening.split(
            inProgress = s[2] as List<MaItem>,
            recentlyPlayed = s[5] as List<MaItem>,
        )
        @Suppress("UNCHECKED_CAST")
        LibraryShelves(
            favoriteAlbums = s[0] as List<MaItem>, favoriteArtists = s[1] as List<MaItem>,
            inProgress = split.continueListening, recentlyAdded = s[3] as List<MaItem>,
            recommendations = s[4] as List<MaItem>, recent = split.recentlyPlayed,
            frequent = s[6] as List<MaItem>, discover = s[7] as List<DiscoverShelf>,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryShelves())

    /** All configured servers, for the library switch overlay. */
    val allServers: StateFlow<List<ServerConfig>> = settings.servers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val downloadJobs: StateFlow<List<DownloadJob>> get() = downloadManager.jobs
    /** Ids of everything on disk — what puts the "downloaded" tick on a track row. */
    val downloadedIds: StateFlow<Set<String>> = downloadManager.downloads
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8); val toast: SharedFlow<String> = _toast.asSharedFlow()

    /**
     * Replies to actions started from the Now Playing screen, which has no snackbar.
     *
     * A separate channel rather than a second collector on [toast]: in the overlay
     * layout the player is drawn *over* the Library tab, so both screens are composed
     * at once and every library action would be announced twice — a snackbar under a
     * Toast saying the same thing.
     */
    private val _playerToast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val playerToast: SharedFlow<String> = _playerToast.asSharedFlow()
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

    /**
     * What the playlist about to be created should be filled with, or null for the
     * empty one the Playlists category makes.
     *
     * "Create a playlist" and "put this in a new playlist" are the same dialog and
     * two different intentions, and only the second has anything to seed with — so
     * the item rides here rather than the dialog growing a second entry point.
     */
    private var newPlaylistSeed: MaItem? = null

    fun openCreatePlaylist() { newPlaylistSeed = null; _showCreatePlaylist.value = true }
    fun closeCreatePlaylist() { newPlaylistSeed = null; _showCreatePlaylist.value = false }

    /**
     * "New playlist…" from the add-to-playlist sheet: make one and put [item] in it.
     *
     * The sheet used to offer nothing but the playlists that already existed, and
     * told a library with none to "create one from the Playlists list first" — which
     * meant leaving the album you were looking at, walking to another category, and
     * coming back. Filing something into a playlist is where wanting a new one
     * actually happens.
     */
    fun createPlaylistFor(item: MaItem) {
        newPlaylistSeed = item
        _addingToPlaylist.value = null
        _showCreatePlaylist.value = true
    }

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
                    Backend.SUBSONIC -> source?.playlists().orEmpty()
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
                val tracks = tracksOf(item)
                if (tracks.isEmpty()) { _toast.tryEmit("Nothing to add"); return@launch }
                when {
                    MusicSources.isLocalProvider(playlist.provider) -> {
                        val sc = source ?: throw IllegalStateException("${libraryName()} isn't connected")
                        sc.addToPlaylist(playlist.itemId, tracks.map { it.itemId })
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
     * Create a playlist on whichever backend is active, seeded with whatever
     * [createPlaylistFor] left behind.
     *
     * Created empty from the Playlists category, and created *with the tracks in it*
     * when it came from the add-to-playlist sheet — one round trip rather than a
     * create followed by an add, which is also the only shape Emby's create endpoint
     * has ever offered.
     */
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        val title = name.trim()
        val seed = newPlaylistSeed
        newPlaylistSeed = null
        _showCreatePlaylist.value = false
        viewModelScope.launch {
            try {
                val tracks = seed?.let { tracksOf(it) }.orEmpty()
                if (seed != null && tracks.isEmpty()) {
                    _toast.tryEmit("Nothing to add")
                    return@launch
                }
                when (_backend.value) {
                    Backend.MA -> {
                        val created = maRepo.createPlaylist(title)
                        // MA identifies playlist members by uri, not by library id.
                        if (created != null && tracks.isNotEmpty()) {
                            maRepo.addPlaylistTracks(created, tracks.mapNotNull { it.uri })
                        }
                    }
                    Backend.SUBSONIC -> {
                        val sc = source ?: throw IllegalStateException("${libraryName()} isn't connected")
                        sc.createPlaylist(title, tracks.map { it.itemId })
                    }
                }
                _toast.tryEmit(
                    when {
                        tracks.isEmpty() -> "Created \"$title\""
                        tracks.size == 1 -> "Created \"$title\" with 1 track"
                        else -> "Created \"$title\" with ${tracks.size} tracks"
                    }
                )
                refresh()
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't create playlist")
            }
        }
    }

    /**
     * The tracks [item] stands for: itself if it is one, its contents if it is a
     * container. Shared by "add to playlist" and "new playlist with this in it",
     * which mean the same thing about an album.
     */
    private suspend fun tracksOf(item: MaItem): List<MaItem> =
        if (item.mediaType == "track") listOf(item)
        else childrenOf(item).filter { it.mediaType == "track" }

    /**
     * Delete a playlist from whichever backend owns it. The caller passes the
     * [MaItem] as it appears in the library — its `provider` decides which
     * backend's delete command is used.
     */
    fun deletePlaylist(item: MaItem) {
        viewModelScope.launch {
            try {
                when {
                    MusicSources.isLocalProvider(item.provider) -> {
                        val sc = source ?: throw IllegalStateException("${libraryName()} isn't connected")
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
            loadRecentlyAdded(), loadRecommendations(), loadDiscoverRows(),
            loadInProgress(), loadFrequent(),
        ).joinAll()
    }

    init {
        viewModelScope.launch {
            _maUrl.value = settings.maBaseUrl.first(); _maUser.value = settings.maUsername.first(); _maPass.value = settings.maPassword.first()
            _navUrl.value = settings.navUrl.first(); _navUser.value = settings.navUsername.first(); _navPass.value = settings.navPassword.first()
            activeConfig = settings.activeServer.first()
            // The connect form's fields are seeded from the *active server* rather
            // than only from the legacy Navidrome keys. Those keys are only written
            // for Subsonic-speaking servers, so a Jellyfin-only install came back from
            // a restart with a blank address and never reconnected.
            // A source is built from the stored config at the same time, so play
            // actions arriving before the probe finishes still get a valid stream URL.
            // The connect below authenticates/probes it and replaces it if needed.
            activeConfig?.takeIf { it.kind.playsLocally }?.let { seedLocalFieldsFrom(it) }
            // Backend first, then booted: the settings collector below only acts
            // once booted, so this ordering keeps boot from racing it into two
            // connects for the same backend.
            applyBackend(if (settings.backend.first() == "subsonic") Backend.SUBSONIC else Backend.MA)
            _booted.value = true
        }
        // Which server is active is the Settings screen's to decide and this screen's
        // to obey. Without this, "Browse this library" moved the Active pill and
        // nothing else: the app stayed connected to whatever it had already opened,
        // because the connect path only ever read the one stored Navidrome address.
        //
        // Keyed on the id rather than the whole config, so editing a server's *other*
        // fields (its name, its stream format) doesn't tear the connection down.
        viewModelScope.launch {
            settings.activeServerId.distinctUntilChanged().collect { id ->
                if (!_booted.value || id.isBlank()) return@collect
                if (activeConfig?.id == id && (source != null || _backend.value == Backend.MA)) return@collect
                val config = settings.activeServer.first() ?: return@collect
                switchTo(config)
            }
        }
        startLiveSearch()
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        // This client outlives any one screen, so a format change made in Settings
        // has to reach it rather than waiting for a reconnect.
        // Per server, not global. The stored `navStreamFormat` is a mirror kept for
        // downloads and "play at original quality" — pushing it onto whatever source
        // happened to be connected meant editing server B's stream quality changed
        // server A's playback.
        viewModelScope.launch {
            settings.activeServer
                .map { it?.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw" }
                .distinctUntilChanged()
                .collect { source?.streamFormat = it }
        }
        // The backend belongs to Settings, so follow it rather than owning it.
        // [setBackend] no-ops on an unchanged value, so this doesn't feed back.
        viewModelScope.launch {
            settings.backend.collect { stored ->
                if (!_booted.value) return@collect
                val want = if (stored == "subsonic") Backend.SUBSONIC else Backend.MA
                setBackend(want)
                // Backstop. [setBackend] early-returns when it thinks it is already on
                // this backend, and [applyBackend] is the only thing that empties the
                // browse stack — so any path that leaves those two disagreeing strands
                // the *previous* library's albums on screen under the new library's
                // name, which is what "switching backends keeps the old view" looks
                // like. Cheap to assert here, and it cannot be wrong: a backend change
                // has no reading in which a half-browsed stack from the other server
                // is still valid.
                if (_backend.value == want && _depth.value > 0) {
                    stack.clear()
                    reloadStack.clear()
                    _depth.value = 0
                    showRoot()
                }
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
        startLocalRadio()
        startQueueSync()
        // The fade belongs to the player, and the setting can change under a queue
        // that is already running.
        viewModelScope.launch { settings.navFadeSeconds.collect { localPlayer.fadeSeconds = it } }
        viewModelScope.launch { settings.beatMatchedCrossfade.collect { localPlayer.beatMatchedFade = it } }
        // A local track that actually started is a play worth reporting, so
        // Navidrome's play counts and its "recently played" shelf stay honest.
        //
        // Two reports per track, which is what the Subsonic spec actually asks for:
        // a "now playing" ping the moment it starts, and a completed play once it has
        // been listened to. Sending the completion at track start — which is what the
        // app used to do — counted every one-second skip as a full play.
        viewModelScope.launch {
            localPlayer.started.collect { track ->
                // Keyed on the track carrying a library id, not on the selected
                // backend. "Play at original quality" streams straight from Navidrome
                // while the *MA* backend is selected, and the old `Backend.SUBSONIC`
                // gate meant precisely the listening this audience does most —
                // untouched, straight from the source — was the listening that never
                // counted towards play counts or "recently played".
                val songId = track.scrobbleId ?: return@collect
                val sink = scrobbleSink(track.scrobbleProvider) ?: return@collect
                val startedAtMs = System.currentTimeMillis()
                runCatching { sink.scrobble(songId, completed = false) }
                submissionJob?.cancel()
                submissionJob = viewModelScope.launch { submitWhenPlayed(sink, songId, startedAtMs) }
                progressJob?.cancel()
                progressJob = viewModelScope.launch { reportProgressWhile(sink, songId) }
            }
        }
        viewModelScope.launch { localPlayer.errors.collect { _toast.tryEmit(it) } }

        // Wire the prepare-playback hook so MPD queues the right track before
        // ExoPlayer opens its constant HTTP stream URL. A no-op for every other
        // provider — MusicSource.preparePlayback defaults to Unit.
        localPlayer.onPreparePlayback = prepare@{ track ->
            val src = source ?: return@prepare
            val itemId = track.scrobbleId ?: return@prepare
            if (src.providerId == track.scrobbleProvider) {
                runCatching { src.preparePlayback(itemId) }
            }
        }
        // The Downloads shelf is a live view of the index, not a snapshot — a
        // download landing (or a delete) while the user is standing on it has to
        // show up, or the list quietly lies about what is on the phone.
        viewModelScope.launch {
            downloads.collect { list ->
                if (_node.value.title == DOWNLOADS_TITLE) _node.value = Node(DOWNLOADS_TITLE, downloadItems(list))
            }
        }
    }

    /**
     * The music libraries on the connected Jellyfin server, for the settings picker.
     *
     * Empty for every other backend, and empty when nothing is connected — the picker
     * only appears when there is a genuine choice to make, so "no answer" and "one
     * library" are both handled by simply not rendering it.
     */
    suspend fun jellyfinLibraries(): List<MaItem> = try {
        (source as? JellyfinSource)?.jellyfin?.musicLibraries().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Point the library at [config] and connect to it.
     *
     * The entry point for "the user picked a different server". [setBackend] handles
     * the Music-Assistant-or-not half of that — stopping whatever the old library was
     * playing, clearing the browse stack — and this adds the half it cannot know
     * about: *which* local library, when there is more than one.
     */
    /**
     * The library-switch overlay's entry point for "the user picked a different server".
     *
     * Persists through [AppSettings.setActiveServer] rather than calling [switchTo]
     * directly, so [resolveActiveConfig] sees the new server as soon as the connect it
     * triggers runs. Calling [switchTo] here first and settings after left a window
     * where `settings.activeServer` still held the *previous* server while `connect()`
     * was already underway — [resolveActiveConfig] prefers that stored value over the
     * latched one, so it rebuilt the connection with the new server's address but the
     * old server's kind and credentials. That is what made picking Navidrome sometimes
     * connect as the old backend, or land on an empty library.
     */
    fun selectServer(config: ServerConfig) {
        viewModelScope.launch { settings.setActiveServer(config.id) }
    }

    fun switchTo(config: ServerConfig) {
        val want = if (config.kind.playsLocally) Backend.SUBSONIC else Backend.MA
        val changedServer = activeConfig?.id != config.id
        activeConfig = config
        if (config.kind.playsLocally) {
            // Keep the legacy form fields in step: they are what the connect form
            // shows and what `connect()` reads back. A source is built at the same
            // time, unprobed, so the library can render and play while the probe runs.
            seedLocalFieldsFrom(config)
        } else {
            _maUrl.value = config.url
            _maUser.value = config.username
            _maPass.value = config.password
        }
        if (_backend.value != want) {
            setBackend(want)
        } else if (changedServer) {
            // Same *kind* of backend, different server — `setBackend` would no-op, and
            // the old server's albums would sit on screen under the new one's name.
            localPlayer.stop()
            source = null
            applyBackend(want)
            // `applyBackend` short-circuits on an already-open Music Assistant socket,
            // which is right when the address hasn't changed and wrong here: two MA
            // servers would swap labels without the connection ever moving.
            if (want == Backend.MA) connect()
        }
    }

    /**
     * Build or refresh [source] from [config] without probing the network.
     *
     * Call this whenever [activeConfig] lands so that play actions and stream URL
     * generation can succeed even while the probe is still in flight. The source
     * is recreated if the kind or address changed; otherwise only the stream
     * format is refreshed.
     */
    private fun ensureSourceFor(config: ServerConfig) {
        val existing = source
        val sameKindAndAddress = existing != null &&
            existing.kind == config.kind &&
            existing.serverUrl.startsWith(config.url.trim().trimEnd('/')) &&
            // For a kind whose address is a constant — "This device" against an empty
            // url — the address test above is vacuously true, so changing the picked
            // music folder kept the old source and its already-taken lazy scan. The
            // library simply did not change.
            existing.let { it.kind.auth != AuthStyle.NONE || sourceOptions == localOptionsOf(config) }
        if (sameKindAndAddress) {
            existing.streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            return
        }
        val next = MusicSources.create(getApplication(), config)
        if (next != null) {
            next.streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
            source = next
            sourceOptions = localOptionsOf(config)
        }
    }

    /**
     * The options that define what a device-local source *reads*, so a change to them
     * can be told from a change to nothing. Only the folder list qualifies today.
     */
    private fun localOptionsOf(config: ServerConfig): String? =
        config.option(com.engabd.sendpin.local.LocalMediaSource.OPT_FOLDER_URIS)

    /** What [localOptionsOf] returned for the live [source]. */
    private var sourceOptions: String? = null

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
        // Cached hits belong to the library that answered them. Keeping them across a
        // switch is how the previous backend's albums end up under the new one's name.
        searchCache.clear()
        lastSearched = null
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
            // The local source is not merely unused on Music Assistant — it is
            // published in a process-scoped holder that the detail screens, the
            // download chip and the scrobble sink all read. Left live, it answered
            // for a library nobody was browsing.
            source = null
            if (maApi.state.value == MaApiClient.State.CONNECTED) {
                _ready.value = true
                showRoot()
            } else if (_maUrl.value.isNotBlank()) {
                connect()
            }
        } else {
            source = null
            // With no server on file the connect form is the right answer, even if
            // there are downloads — the user has not set this backend up yet.
            //
            // "On file" means the *stored active server*, not the legacy address
            // fields. Those mirror the first Subsonic-speaking server in the list and
            // nothing else, by design — they back downloads and "play at original
            // quality", which go to Navidrome from either backend — so on a Jellyfin
            // library they are blank. Reading them alone is what made the first switch
            // to Jellyfin land on an empty, permanently-connecting library that a
            // restart fixed: boot seeds these fields from the active server (see
            // `init`), and nothing else did. The two settings collectors that react to
            // a library switch can arrive in either order, and the one that gets here
            // first was reading fields the other had not filled in yet.
            if (_navUrl.value.isNotBlank()) connect()
            else viewModelScope.launch {
                val stored = settings.activeServer.first()?.takeIf { it.kind.playsLocally }
                if (stored != null && _navUrl.value.isBlank() && _backend.value == Backend.SUBSONIC) {
                    seedLocalFieldsFrom(stored)
                    connect()
                }
            }
        }
    }

    /**
     * Point the connect form — and the live [source] — at [config].
     *
     * The fields are what [connect] reads back, so they have to be in step with the
     * active server before a connect is worth attempting. The source is built here
     * too, unprobed, so a play started before the probe lands still has a valid
     * stream URL to work with.
     */
    private fun seedLocalFieldsFrom(config: ServerConfig) {
        activeConfig = config
        _navUrl.value = config.url
        _navUser.value = config.username
        _navPass.value = config.password
        ensureSourceFor(config)
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
            val url = _navUrl.value.trim()
            // Local files have no server address — a folder picker, not a host, is
            // what points this source at its music (and even that is optional; with
            // none picked it scans all device audio). Bailing out here on a blank
            // URL treated "This device" as "not set up" and dropped straight to the
            // connect form, which then asked for a server URL, username and password
            // that a local library has no use for.
            val addressless = activeConfig?.kind?.needsAddress == false
            if (url.isBlank() && !addressless) { source = null; _ready.value = false; return }
            _connecting.value = true
            // Abandon whatever address was being tried before this one.
            navConnectJob?.cancel()
            navConnectJob = viewModelScope.launch {
                val config = resolveActiveConfig(url)
                // The legacy Navidrome keys are written **only for a server that
                // speaks Subsonic**. They are not "the active library" — they back
                // downloads and "play at original quality", both of which go to
                // Navidrome from either backend — so stamping a Jellyfin address into
                // them pointed a `SubsonicClient` at a Jellyfin host and took every
                // download with it.
                if (config.kind == ServerKind.NAVIDROME || config.kind == ServerKind.SUBSONIC) {
                    settings.setNavidrome(url, _navUser.value, _navPass.value)
                }
                val next = MusicSources.create(getApplication(), config)
                if (next == null) {
                    // A kind with no adapter yet — the settings picker greys those
                    // out, so this is only reachable by editing the stored list.
                    _connecting.value = false
                    _ready.value = false
                    _connError.value = "${config.kind.label} isn't supported yet"
                    return@launch
                }
                source = next
                activeConfig = config
                next.streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT)
                    ?: settings.navStreamFormat.first()
                // Signing in, and asking what the server can do. Both can fail, and a
                // failure here is a connection failure like any other.
                val err = try {
                    activeConfig = MusicSources.prepare(next, config)
                    next.probe()
                } catch (e: SourceAuthException) {
                    SourceError(e.message ?: "Sign-in was refused", isAuth = true)
                } catch (e: Exception) {
                    // Not an auth failure. Calling everything one meant a Jellyfin
                    // server that was merely unreachable kept the connect form up with
                    // "check your password" — and suppressed the fallback to
                    // downloads, which is the whole point of having them.
                    SourceError(e.message ?: "Couldn't reach the server", isAuth = false)
                }
                // Belt as well as braces. `cancel()` above only takes effect at a
                // suspension point, and a ping blocked on a TCP connect to an address
                // with nothing on it may not reach one until the socket times out — so
                // a superseded attempt can still arrive here with an answer nobody
                // asked for. Whether it is stale is not a matter of timing: it is
                // whether the source it used is still the current one.
                if (source !== next) return@launch
                _connecting.value = false
                if (err == null) {
                    _ready.value = true
                    _offline.value = false
                    // Anything `prepare` learned — a Jellyfin token, its user and
                    // library ids — is worth keeping, or the next launch signs in
                    // again and leaves another device session behind in the server's
                    // dashboard.
                    persistActiveConfig()
                    showRoot()
                    // Asked once per connect: the answer only changes when another
                    // device puts something down, and this is when that becomes
                    // knowable.
                    loadSavedQueue()
                } else {
                    _ready.value = false
                    goOfflineIfPossible(err)
                }
            }
        }
    }

    /**
     * Which server this connect is for.
     *
     * [activeConfig] is the answer whenever the Settings screen has told us — it is
     * the only thing that knows the *kind*, and building a Jellyfin session out of
     * Navidrome's stored address is exactly the failure this replaced.
     *
     * [url] is what the connect form is holding, which can be a keystroke ahead of
     * what is stored, so "Save & connect" tries what was typed. Falling all the way
     * back to a fresh Navidrome config covers an install that predates the server
     * list, where the credentials fields have only ever meant one thing.
     */
    private suspend fun resolveActiveConfig(url: String): ServerConfig {
        // The store first, the latched value second. `activeConfig` is set optimistically
        // by [switchTo] and can be a step behind when two settings collectors race a
        // library switch — and being a step behind here means building, say, a Jellyfin
        // session against Navidrome's address.
        val stored = settings.activeServer.first()?.takeIf { it.kind.playsLocally }
            ?: activeConfig?.takeIf { it.kind.playsLocally }
        return stored?.copy(
            url = url,
            // Blank form fields are not an edit — they mean the form was never seeded
            // or was cleared. Overwriting the stored credentials with blanks broke
            // Jellyfin connects when the active server was switched in Settings.
            username = _navUser.value.takeIf { it.isNotBlank() } ?: stored.username,
            password = _navPass.value.takeIf { it.isNotBlank() } ?: stored.password,
        ) ?: ServerConfig(
            kind = ServerKind.NAVIDROME,
            url = url,
            username = _navUser.value,
            password = _navPass.value,
        )
    }

    /** Write [activeConfig] back into the stored list, in place. */
    private suspend fun persistActiveConfig() {
        val config = activeConfig ?: return
        val list = settings.servers.first()
        val merged =
            if (list.any { it.id == config.id }) list.map { if (it.id == config.id) config else it }
            else list + config
        settings.saveServers(merged)
        settings.setActiveServer(config.id)
    }

    /**
     * Ask the Navidrome server whether it is there and whether it accepts these
     * credentials, whichever backend the library is currently showing.
     */
    fun checkNavidrome() {
        viewModelScope.launch {
            val url = settings.navUrl.first().trim()
            if (url.isBlank()) { _navStatus.value = "Not set up"; return@launch }
            _navStatus.value = "Checking…"
            val sc = navidromeClient()
            if (sc == null) { _navStatus.value = "Not set up"; return@launch }
            val err = sc.pingResult()
            _navStatus.value = when {
                err == null -> "Connected"
                SubsonicError.isAuth(err.code) -> err.message ?: "Rejected"
                else -> "Unreachable - ${err.message}"
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
    private fun goOfflineIfPossible(error: SourceError) {
        val auth = error.isAuth
        val name = activeConfig?.displayName ?: "the server"
        _connError.value =
            if (auth) error.message
            else "Couldn't reach $name - ${error.message}"
        if (auth || downloads.value.isEmpty()) {
            _offline.value = false
            _ready.value = false
            return
        }
        _offline.value = true
        _ready.value = true
        // Answers from the server the app just lost are not answers about what is on
        // the phone, and offline search reads a different source entirely.
        searchCache.clear()
        lastSearched = null
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
            item.provider == CATEGORY_PROVIDER -> openCategory(item.itemId, item.name)
            // Opening a result hides the result list but keeps it in memory, so
            // Back returns to the same matches instead of an empty search box.
            item.browsable -> { _searchOpen.value = false; pushNode(item.name) { childrenOf(item) } }
            item.playable || item.provider == DOWNLOAD -> play(item)
            // Anything the library rendered but neither list claims. This used to be
            // an absent `else`, so an unrecognised media type was a tap that did
            // nothing, logged nothing and looked exactly like a frozen screen. Saying
            // so is worth more than the silence, and it is how the next unsupported
            // type will get noticed instead of shipping.
            else -> _toast.tryEmit("Can't open ${item.mediaType.ifBlank { "that" }} items yet")
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
     * single URL at a bare ExoPlayer: tapping a track plays the *list it is in*
     * from that point, and tapping an album or playlist plays the whole thing.
     * Anything already downloaded is played from disk, so a queue survives the
     * server going away mid-album.
     */
    fun play(item: MaItem, option: String = "replace") {
        viewModelScope.launch {
            try {
                when {
                    item.provider == DOWNLOAD -> playLocal(downloadContext(item), option)
                    MusicSources.isLocalProvider(item.provider) -> playLocal(
                        localContext(item),
                        option,
                    )
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
                            _toast.tryEmit("Playing ${item.name} - original file")
                            return@launch
                        }
                        // Handing playback back to Music Assistant ends the local
                        // session, or Now Playing would keep showing (and its
                        // buttons keep driving) a player that is no longer the one
                        // making the sound.
                        if (option == "replace") localPlayer.stop()
                        val uri = item.uri
                        if (uri == null) {
                            // Reported success unconditionally before, even though the
                            // `?.let` had sent nothing at all.
                            _toast.tryEmit("Can't play ${item.name}")
                            return@launch
                        }
                        maRepo.playOn(playTarget(), listOf(uri), option, radioMode = false)
                        if (option == "replace") applyDontStopTheMusic()
                        _toast.tryEmit(if (option == "replace") "Playing ${item.name}" else "Added to queue")
                    }
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /**
     * Queue a single track without disturbing what's playing or pulling in the rest
     * of the list it lives in.
     *
     * Deliberately not [play] with `option = "next"`/`"add"`: on the local/download
     * path that goes through [localContext], which resolves a track to *every
     * sibling in the visible list* (right for "tap plays this list from here", wrong
     * for "queue just this one"). This builds a single [LocalTrack] directly instead.
     */
    fun enqueueTrack(item: MaItem, option: String) {
        viewModelScope.launch {
            try {
                if (item.provider == DOWNLOAD || MusicSources.isLocalProvider(item.provider)) {
                    val track = localTrack(item)
                    if (option == "next") localPlayer.playNext(listOf(track)) else localPlayer.addToQueue(listOf(track))
                } else {
                    val uri = item.uri ?: run { _toast.tryEmit("Couldn't queue that"); return@launch }
                    maRepo.playOn(playTarget(), listOf(uri), option, radioMode = false)
                }
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't queue that")
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
     * What playing a locally-played item means.
     *
     * A track is played *in the list it was tapped in* — the album, the playlist,
     * the search results — because a track on its own is a queue of one. A container
     * resolves to all of its tracks.
     */
    private suspend fun localContext(item: MaItem): PlayContext {
        val sc = source
        if (item.mediaType == "track") {
            val siblings = visibleTracks().filter { it.provider == item.provider }
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
    ): LocalTrack {
        // Every track that enters a local queue comes through here, which makes this
        // the one place guaranteed to see the library item behind it. A LocalTrack
        // carries no genre and no album id, so without this the radio would have
        // nothing but a title to seed from.
        rememberSeeds(listOf(item))
        val resolvedStream = streamUrl
            ?: item.takeIf { it.provider == source?.providerId }?.let { source?.streamUrl(it.itemId) }
            ?: streamUrlFor(item)
        return downloadManager.toLocalTrack(
            item = item,
            streamUrl = resolvedStream,
            // A DOWNLOAD item carries its file path as its uri; everything else has
            // to be looked up in the index by id.
            localPathFallback = item.uri?.takeIf { item.provider == DOWNLOAD },
        ).let { track ->
            // `toLocalTrack` fills in the library id and the library it belongs to for
            // every caller — it used to be done here, so the three detail screens built
            // tracks that carried neither, and a song started from an album page was
            // silently unscrobbleable and undownloadable from the player.
            //
            // The one case it cannot know about is this one: a Music Assistant item
            // streamed straight from Navidrome by "play at original quality", where
            // the caller holds the Navidrome id and the item does not.
            if (scrobbleId == null) track
            else track.copy(scrobbleId = scrobbleId, scrobbleProvider = SubsonicClient.PROVIDER)
        }
    }

    /**
     * Build a stream URL from the active config when [source] is missing or doesn't
     * match the item's provider. This is the fallback that keeps Jellyfin songs
     * playable while the library is still probing, and when the source holder was
     * cleared by a backend switch.
     */
    private fun streamUrlFor(item: MaItem): String? {
        if (!MusicSources.isLocalProvider(item.provider)) return null
        val config = activeConfig?.takeIf { it.kind.playsLocally } ?: return null
        val src = MusicSources.create(getApplication(), config)?.apply {
            streamFormat = config.option(ServerConfig.OPT_STREAM_FORMAT) ?: "raw"
        } ?: return null
        return src.takeIf { it.providerId == item.provider }?.streamUrl(item.itemId)
    }

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
        // Ordered cheapest-first, and every one of these is now a memory read. The
        // Navidrome search below is a full HTTP round trip on the tap path, so the
        // point of these guards is that it is reached only when it can actually help.
        if (!preferOriginalNow.value) return null
        if (_backend.value != Backend.MA) return null
        if (item.mediaType != "track") return null
        if (_targetPlayer.value.isNotBlank() && _targetPlayer.value != myPlayerId) return null

        val fmt = item.audioFormat ?: return null
        val hiRes = preferHiResNow.value
        if (FormatNegotiator.canStreamUntouched(fmt.sampleRate, fmt.bitDepth, hiRes)) return null

        val sc = navidromeClient() ?: return null
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

    /**
     * The Navidrome server itself, whichever library is active.
     *
     * Distinct from [source] on purpose. Two features reach past the active library
     * straight to Navidrome — every download comes from it, and "play at original
     * quality" streams from it while *Music Assistant* is the library — so this is
     * "the Subsonic server on file", not "the library being browsed". Conflating the
     * two is why this used to overwrite the active client as a side effect of asking
     * whether Navidrome was reachable.
     *
     * Cached rather than rebuilt per call, so the connection pool is reused, and
     * rebuilt when the saved address changes.
     */
    private var navClient: SubsonicClient? = null
    private var navClientKey: String = ""
    private var navSource: SubsonicSource? = null

    /** The Navidrome server as a [MusicSource], for the paths that speak that language. */
    private suspend fun navidromeSource(): MusicSource? {
        val client = navidromeClient() ?: return null
        return navSource?.takeIf { it.subsonic === client }
            ?: SubsonicSource(client).also { navSource = it }
    }

    /**
     * Where to report a play against [provider].
     *
     * An id is only meaningful to the library it came from — a Jellyfin guid sent to
     * Navidrome names nothing there — so this refuses rather than guessing when the
     * active library is not the one that produced the track.
     *
     * The two exceptions are the two cases where the id outlives its library on
     * screen: a Navidrome id from "play at original quality", which is reported while
     * *Music Assistant* is the library, and a download, whose provider tag records
     * that it came off disk rather than which server it came from originally.
     */
    private suspend fun scrobbleSink(provider: String?): MusicSource? = when (provider) {
        null -> null
        SubsonicClient.PROVIDER -> source?.takeIf { it.providerId == provider } ?: navidromeSource()
        else -> source?.takeIf { it.providerId == provider }
    }

    private suspend fun navidromeClient(): SubsonicClient? {
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) return null
        val user = settings.navUsername.first()
        val pass = settings.navPassword.first()
        val key = "$url|$user|$pass"
        navClient?.takeIf { navClientKey == key }?.let {
            it.streamFormat = settings.navStreamFormat.first()
            return it
        }
        return SubsonicClient(url, user, pass)
            .also {
                it.streamFormat = settings.navStreamFormat.first()
                navClient = it
                navClientKey = key
            }
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
                    maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }.distinct(), option, radioMode = false)
                    if (replacing) applyDontStopTheMusic()
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

    // ── Set builder ──────────────────────────────────────────────────────

    /**
     * How much of [items] has been scanned, as (scanned, total).
     *
     * The set builder can only order tracks it knows the shape of, so the sheet
     * says so up front rather than quietly building a worse set out of half a
     * library. `peek` rather than `cached`, because this runs for every visible
     * item and the answer only has to be good enough to put a number on screen.
     */
    fun scanCoverage(items: List<MaItem>): Pair<Int, Int> {
        val playable = items.filter { it.playable || it.provider == DOWNLOAD }
        val scanned = playable.count { trackScans.peek(localTrack(it)) != null }
        return scanned to playable.size
    }

    /**
     * Build a set out of [items] and play it.
     *
     * Only scanned tracks take part: an unscanned one has no tempo, key or energy,
     * so it cannot be placed on the curve and including it would be padding rather
     * than programming. The seed is whatever is playing, when that is one of them,
     * so a set built mid-song carries on from where the listener already is.
     *
     * Local libraries only. Music Assistant tops up its own queue server-side with
     * no key or tempo data to weigh, exactly as Harmonic DJ mode already documents.
     */
    fun buildSet(items: List<MaItem>, curve: SetBuilder.Curve, minutes: Int) {
        viewModelScope.launch {
            val playable = items.filter { it.playable || it.provider == DOWNLOAD }
            val byId = playable.associateBy { it.itemId }
            val candidates = playable.mapNotNull { item ->
                val scan = trackScans.cached(localTrack(item)) ?: return@mapNotNull null
                SetBuilder.Candidate.of(item.itemId, scan)
            }
            if (candidates.size < 2) {
                _toast.tryEmit(
                    "Not enough scanned tracks yet - run a sweep under Settings, " +
                        "Light Sync, Track analysis",
                )
                return@launch
            }

            val playingId = localPlayer.current.value?.id
            val ordered = SetBuilder.build(
                candidates = candidates,
                curve = curve,
                targetS = minutes * 60f,
                seed = candidates.firstOrNull { it.id == playingId },
            )
            val tracks = ordered.mapNotNull { byId[it.id] }
            if (tracks.isEmpty()) {
                _toast.tryEmit("Couldn't build a set from those tracks")
                return@launch
            }

            stopMaPlayback()
            localPlayer.setQueue(tracks.map { localTrack(it) }, 0)
            val mins = (SetBuilder.durationOf(ordered) / 60f).roundToInt()
            _toast.tryEmit("${curve.label}: ${tracks.size} tracks, about $mins minutes")
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
    fun download(item: MaItem, replyTo: MutableSharedFlow<String> = _toast) {
        viewModelScope.launch {
            // Resolved from the *item's* library rather than the active one. A track
            // being played through "play at original quality" is a Navidrome file
            // while Music Assistant is the library, and it is downloadable — asking
            // the active source about it either refused or, worse, handed a Navidrome
            // id to a server that had never seen it.
            val sc = sourceFor(item.provider)
            if (sc == null) {
                // Music Assistant streams are the server's to serve, not ours to keep.
                replyTo.tryEmit(
                    if (MusicSources.isLocalProvider(item.provider)) "Connect to that library to download"
                    else "Only tracks from your own library can be downloaded"
                )
                return@launch
            }
            // A library this phone plays is not automatically one that hands files
            // over: a folder on the phone is already the file, and has no download to
            // offer. The list rows ask the same question before showing the button;
            // this is the backstop for every other way in, the player's chip included.
            if (!sc.has(Capability.DOWNLOAD)) {
                replyTo.tryEmit("Those files are already on this phone")
                return@launch
            }
            val tracks = try {
                sc.tracksUnder(item)
            } catch (e: Exception) {
                replyTo.tryEmit(e.message ?: "Couldn't list that")
                return@launch
            }
            runDownload(tracks, sc, replyTo)
        }
    }

    /**
     * The live source that can answer for [provider], or null.
     *
     * The active library where it matches, and the Navidrome server otherwise —
     * because two features deliberately reach past the active library to it: every
     * download, and "play at original quality", which streams from Navidrome while
     * Music Assistant is what is being browsed.
     */
    private suspend fun sourceFor(provider: String): MusicSource? = when {
        !MusicSources.isLocalProvider(provider) -> null
        source?.providerId == provider -> source
        // Navidrome is reachable whichever library is active — it backs every download
        // and "play at original quality". No other provider gets that treatment,
        // because guessing which server an id belongs to is how a Navidrome id ends up
        // being offered to Jellyfin.
        provider == SubsonicClient.PROVIDER -> navidromeSource()
        else -> null
    }

    /** Download a list already on screen — an album's tracks, a playlist, a search. */
    fun downloadAll(items: List<MaItem>) {
        val sc = source
        if (sc == null) { _toast.tryEmit("Connect to a library to download"); return }
        if (!sc.has(Capability.DOWNLOAD)) { _toast.tryEmit("Those files are already on this phone"); return }
        val tracks = items.filter { it.provider == sc.providerId && it.mediaType == "track" }
        if (tracks.isEmpty()) { _toast.tryEmit("Nothing here to download"); return }
        viewModelScope.launch { runDownload(tracks, sc) }
    }

    /**
     * One download run, start to finish. Kept in one place (and one coroutine) so a
     * 20-track album is a single sequential job with one summary at the end, rather
     * than 20 jobs racing each other for the same wifi.
     */
    private suspend fun runDownload(
        tracks: List<MaItem>,
        sc: MusicSource,
        /** Where the running commentary goes — see [playerToast] for why it varies. */
        replyTo: MutableSharedFlow<String> = _toast,
    ) {
        val pending = tracks.filterNot { downloadManager.isDownloaded(it.itemId) }
        if (pending.isEmpty()) { replyTo.tryEmit("Already downloaded"); return }

        // Wi-Fi-only and storage cap are enforced inside downloadAll now,
        // so the check happens per-file at the moment it starts, not just once
        // at the queue level. Keeping a pre-check here for the toast: telling
        // the user "Wi-Fi only" before kicking off 20 silent failures is better
        // UX than 20 failed rows.
        val wifiOnly = settings.downloadWifiOnly.first()
        val storageCap = settings.downloadStorageCapMb.first()
        if (wifiOnly) {
            val cm = getApplication<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val isWifi = cm.activeNetwork?.let { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } ?: false
            if (!isWifi) { replyTo.tryEmit("Wi-Fi only - connect to Wi-Fi to download"); return }
        }

        replyTo.tryEmit(
            if (pending.size == 1) "Downloading ${pending.first().name}…"
            else "Downloading ${pending.size} tracks…"
        )
        val ok = downloadManager.downloadAll(
            pending,
            urlFor = { sc.downloadUrl(it.itemId) },
            wifiOnly = wifiOnly,
            storageCapMb = storageCap,
        )
        replyTo.tryEmit(
            when {
                ok == pending.size && ok == 1 -> "Downloaded ${pending.first().name}"
                ok == pending.size -> "Downloaded $ok tracks"
                ok == 0 -> "Download failed"
                else -> "Downloaded $ok of ${pending.size} - tap a failed row to dismiss"
            }
        )

        // Storage cap — evict oldest-first until back under the limit. The index is
        // appended to on each download, so the head of the list is the oldest.
        // Bounded by the snapshot rather than looping on live state: a file that has
        // vanished from disk contributes nothing to the total, and re-reading the
        // list each pass would spin on it.
        val capMb = settings.downloadStorageCapMb.first()
        val capBytes = AppSettings.storageCapBytes(capMb)
        if (capBytes != null) {
            val nowPlayingId = localPlayer.current.value?.id
            for (entry in downloadManager.downloads.value) {
                if (downloadManager.bytesUsed() <= capBytes) break
                // Never evict the track being listened to out from under the player.
                if (entry.id == nowPlayingId) continue
                downloadManager.delete(entry.id)
            }
            if (downloadManager.bytesUsed() > capBytes) {
                replyTo.tryEmit("Downloads are over the ${capMb / 1000} GB limit")
            }
        }
    }

    // --- the track that is playing right now -------------------------------

    /**
     * Whether the track on the local player can be, is being, or has been downloaded.
     *
     * Its own state rather than something the player screen works out, because the
     * answer needs three things that live here — the download index, the jobs list and
     * whether a Navidrome connection exists at all — and none of them belong to
     * Now Playing.
     */
    enum class TrackDownload {
        /** Nothing playing locally, or nothing behind it a library could serve. */
        UNAVAILABLE,
        READY,
        IN_FLIGHT,
        DONE,
    }

    /**
     * The **library** song id behind a locally-playing track, or null when there
     * isn't one.
     *
     * `scrobbleId` is already exactly this: it is set for a track from any library
     * this phone plays itself, for a download (which carries the id of the server it
     * came from), and for a Music Assistant item being played through a Navidrome
     * stream by "play at original quality" — where the item's own id belongs to MA
     * and would name a song Navidrome has never heard of. Reusing it means the
     * download chip is offered in precisely the cases a download can succeed.
     *
     * It was called `navId` back when Navidrome was the only library that could
     * answer for one. [LocalTrack.scrobbleProvider] says which library it belongs
     * to, and that is what [download] resolves a source from.
     */
    private val LocalTrack.libraryId: String? get() = scrobbleId

    val currentTrackDownload: StateFlow<TrackDownload> =
        combine(localPlayer.current, downloadedIds, downloadJobs) { track, done, jobs ->
            val id = track?.libraryId ?: return@combine TrackDownload.UNAVAILABLE
            when {
                // Keyed on the index rather than on `track.offline`, which is fixed
                // when the track is built: deleting the copy left the chip saying
                // "Downloaded" for the rest of the song.
                id in done -> TrackDownload.DONE
                jobs.any { it.id == id && !it.failed } -> TrackDownload.IN_FLIGHT
                else -> TrackDownload.READY
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TrackDownload.UNAVAILABLE)

    /**
     * Download whatever is playing on this phone, from the player rather than from
     * the library.
     *
     * Goes through [download] rather than around it, so the Wi-Fi-only rule, the
     * storage cap, the progress rows and every toast are the ones the library already
     * uses. The item is synthesised from the track because the local player holds a
     * [LocalTrack] and not the library row it came from — including its format, so a
     * track downloaded from here keeps its quality badge offline.
     */
    fun downloadCurrentTrack() {
        val t = localPlayer.current.value
        if (t == null) { _playerToast.tryEmit("Nothing playing"); return }
        if (t.offline) { _playerToast.tryEmit("Already on this phone"); return }
        val id = t.libraryId
        // A Music Assistant stream is the server's to serve, not ours to keep, and a
        // track with no library behind it has no file to ask anyone for. Whether the
        // library it *does* have can hand one over is [download]'s to answer.
        if (id == null) { _playerToast.tryEmit("Only tracks from your own library can be downloaded"); return }
        download(
            MaItem(
                itemId = id,
                // The library the id belongs to, not whichever one is active. On the
                // play-original path the track is a Navidrome file while Music
                // Assistant is the library, and tagging it with the active provider
                // sent a Navidrome id to a server that had never heard of it.
                provider = t.scrobbleProvider ?: SubsonicClient.PROVIDER,
                name = t.title,
                uri = id,
                mediaType = "track",
                subtitle = t.artist,
                image = t.artUrl,
                duration = (t.durationMs / 1000).toInt().takeIf { it > 0 },
                album = t.album,
                composer = t.composer,
                audioFormat = t.sourceQuality?.let {
                    MaAudioFormat(
                        codec = it.codec,
                        sampleRate = it.sampleRateHz,
                        bitDepth = it.bitDepth,
                        bitRate = it.bitrateKbps,
                        channels = it.channels,
                        sizeBytes = it.sizeBytes,
                        replayGainTrack = it.replayGainTrack,
                        replayGainAlbum = it.replayGainAlbum,
                    )
                },
            ),
            replyTo = _playerToast,
        )
    }

    /** Remove the offline copy of whatever is playing, from the player. */
    fun deleteCurrentTrackDownload() {
        val id = localPlayer.current.value?.libraryId ?: return
        if (!downloadManager.isDownloaded(id)) return
        viewModelScope.launch {
            downloadManager.delete(id)
            _playerToast.tryEmit("Offline copy deleted")
        }
    }

    // Launched rather than called straight through: `delete` unlinks a file and
    // writes Room, and every caller of this is a tap handler on the main thread.
    fun deleteDownload(id: String) {
        viewModelScope.launch { downloadManager.delete(id) }
    }

    /** Drop a failed download row once the user has acknowledged it. */
    fun dismissDownload(id: String) = downloadManager.dismissJob(id)

    /**
     * Try a failed download again.
     *
     * Rebuilt from the job rather than from the library, because the library may not
     * be on that screen — or on that server — any more. The job records which provider
     * it was for, so a retry goes back to the library that actually has the file.
     */
    fun retryDownload(id: String) {
        val job = downloadJobs.value.firstOrNull { it.id == id && it.failed } ?: return
        val provider = job.provider ?: SubsonicClient.PROVIDER
        viewModelScope.launch {
            val sc = sourceFor(provider)
            if (sc == null) {
                // Left in place rather than cleared. Dismissing first and failing
                // silently made the row vanish with no explanation, which reads as the
                // retry having worked.
                _playerToast.tryEmit("That library isn't connected")
                return@launch
            }
            // Re-fetched rather than rebuilt from the job. The job carries a title and
            // an artist because that is all a progress row needs; an item built from
            // those alone would be written into the index with no duration, no track
            // number, no album id and no format — a download that plays but sorts
            // wrongly and shows no quality badge. The library still has the real one.
            val item = runCatching { sc.song(id) }.getOrNull()
                ?: MaItem(
                    itemId = id,
                    provider = provider,
                    name = job.title,
                    uri = id,
                    mediaType = "track",
                    subtitle = job.artist,
                    image = job.image,
                    duration = null,
                    album = job.album,
                )
            downloadManager.dismissJob(id)
            runDownload(listOf(item), sc, _playerToast)
        }
    }

    /**
     * Play a downloaded track from the Downloads screen.
     *
     * Goes through [play] with a rebuilt library item rather than straight to the
     * player, so it takes the same download play context the library shelf does — the
     * whole downloads list as the queue, starting here — and plays from disk without
     * reaching for a server that may not be there.
     */
    fun playDownload(track: DownloadedTrack) = play(downloadItem(track))

    /**
     * Play a set of downloads as one queue — a downloaded album, in its own order.
     *
     * The Downloads screen groups by album, and an album's play button that started a
     * queue of one would be an album button that does not play the album.
     */
    fun playDownloads(tracks: List<DownloadedTrack>, option: String = "replace") =
        playAll(tracks.map { downloadItem(it) }, option)

    /**
     * Bytes on disk per downloaded track.
     *
     * Stat-ing every file, so it is the caller's job to keep this off the main thread
     * and to ask only when the list changes — the Downloads screen does both. Missing
     * files count as zero rather than being dropped: a row that has lost its file is
     * exactly what someone opening this screen needs to see.
     */
    fun downloadSizes(): Map<String, Long> = downloadManager.downloads.value.associate {
        it.id to runCatching { java.io.File(it.filePath).length() }.getOrDefault(0L)
    }

    fun deleteAllDownloads() {
        viewModelScope.launch {
            downloadManager.deleteAll()
            _toast.tryEmit("Downloads cleared")
        }
    }

    /** Bytes the downloads take up, for the Settings readout. */
    fun downloadBytes(): Long = downloadManager.bytesUsed()

    fun isDownloaded(id: String): Boolean = downloadManager.isDownloaded(id)

    /**
     * The IME "Search" key: run it now, no debounce.
     *
     * Typing already searches (see the live pipeline in `init`), so by the time this
     * fires the answer is usually on screen — but a user who hits Search expects it to
     * mean something, and a query shorter than [MIN_LIVE_QUERY] has deliberately not
     * been sent yet. Skipped only when this exact query is already the one showing.
     */
    fun doSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) { clearSearch(); return }
        searchDepth = stack.size
        _searchOpen.value = true
        if (q == lastSearched && _search.value != null) return
        viewModelScope.launch { runSearch(q) }
    }

    /**
     * Search as the query is typed.
     *
     * `collectLatest` is the load-bearing half: it cancels the in-flight request when
     * the next keystroke lands, so a slow answer for "bea" can never arrive after the
     * fast one for "beatles" and overwrite it. Without that, results race and the list
     * settles on whichever request happened to finish last.
     */
    private fun startLiveSearch() {
        viewModelScope.launch {
            _query
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { q ->
                    if (q.length < MIN_LIVE_QUERY) return@collectLatest
                    if (q == lastSearched && _search.value != null) return@collectLatest
                    searchDepth = stack.size
                    _searchOpen.value = true
                    runSearch(q)
                }
        }
    }

    /** The body of [doSearch], so [refresh] can await it rather than fire and forget. */
    private suspend fun runSearch(query: String) {
        // A repeat of something already answered is not worth a round trip, and going
        // back a character should feel like undo rather than a fresh search.
        searchCache[query]?.let {
            _search.value = it
            lastSearched = query
            _error.value = null
            return
        }
        _searching.value = true; _error.value = null
        try {
            val r = when {
                _backend.value == Backend.MA -> maRepo.search(query)
                _offline.value -> searchDownloads(query)
                else -> source?.search(query)
            }
            r?.let {
                rememberFavorites(it.artists + it.albums + it.tracks + it.playlists)
                searchCache[query] = it
            }
            _search.value = r
            lastSearched = query
        } catch (e: Exception) {
            // A cancelled request is the next keystroke arriving, not a failure worth
            // showing — collectLatest cancels this coroutine on every new query.
            if (e is kotlinx.coroutines.CancellationException) throw e
            _error.value = e.message ?: "Search failed"
        } finally {
            _searching.value = false
        }
    }

    // --- cross-device resume ----------------------------------------------

    /**
     * A queue another client left on the server, when there is one worth offering.
     *
     * `getPlayQueue` and `savePlayQueue` have been implemented in [SubsonicClient]
     * with no callers at all — the client half of "start on the phone, finish at the
     * desk" was done and the feature did not exist.
     */
    private val _savedQueue = MutableStateFlow<SavedQueue?>(null)
    val savedQueue: StateFlow<SavedQueue?> = _savedQueue

    /** Stop offering this one. Not persisted: a new session may want it again. */
    fun dismissSavedQueue() { _savedQueue.value = null }

    /**
     * Ask the server what was left playing, and offer it if it is worth offering.
     *
     * Silent about failures and silent when there is nothing useful to say. Three
     * things disqualify a saved queue: it is empty, it is what this phone is already
     * playing (resuming what you can hear is not an offer), or it was left in the
     * first few seconds of a track, which is someone starting something rather than
     * stopping mid-listen.
     */
    private suspend fun loadSavedQueue() {
        val sc = source ?: return
        val saved = runCatching { sc.savedQueue() }.getOrNull() ?: return
        val currentId = localPlayer.current.value?.scrobbleId
        val savedId = saved.tracks.getOrNull(saved.index)?.itemId
        if (savedId != null && savedId == currentId) return
        if (saved.positionMs < RESUME_MIN_POSITION_MS) return
        _savedQueue.value = saved
    }

    /** Pick up where the other device left off. */
    fun resumeSavedQueue() {
        val saved = _savedQueue.value ?: return
        _savedQueue.value = null
        viewModelScope.launch {
            runCatching {
                stopMaPlayback()
                localPlayer.setShuffle(false)
                localPlayer.setQueue(saved.tracks.map { localTrack(it) }, saved.index)
                localPlayer.seekTo(saved.positionMs)
            }.onFailure { _toast.tryEmit(it.message ?: "Couldn't resume") }
        }
    }

    /**
     * Hand this phone's queue back to the server, so the desk can pick it up.
     *
     * On track change and on pause, not on a timer: those are the two moments the
     * answer actually changes in a way another device would care about, and
     * `savePlayQueue` rewrites the whole queue every time it is called.
     */
    private fun startQueueSync() {
        viewModelScope.launch {
            combine(localPlayer.current, localPlayer.playing) { track, playing -> track to playing }
                .distinctUntilChanged()
                .collect { (track, playing) ->
                    if (track == null) return@collect
                    // Only ours to save when the bytes are Navidrome's.
                    val sc = source ?: return@collect
                    if (_offline.value) return@collect
                    val ids = localPlayer.queue.value.mapNotNull { it.scrobbleId }
                    if (ids.isEmpty()) return@collect
                    runCatching {
                        sc.saveQueue(
                            songIds = ids,
                            currentId = track.scrobbleId,
                            positionMs = localPlayer.positionMs.value,
                        )
                    }
                    // A queue this phone is actively playing supersedes whatever was
                    // being offered, or the offer would compete with itself.
                    if (playing) _savedQueue.value = null
                }
        }
    }

    // --- continuous play (local) ------------------------------------------

    private val radio = LocalRadio()

    /**
     * The unshuffled answer: the rest of the record, then the next one.
     *
     * Rebuilt whenever the connected library changes — it caches that library's album
     * order, which is not a fact about any other one.
     */
    private var sequential: AlbumContinuation? = null
    private var sequentialKey: String? = null

    private fun sequentialFor(src: MusicSource): AlbumContinuation {
        val key = src.providerId + "|" + src.serverUrl
        val held = sequential
        if (held != null && sequentialKey == key) return held
        return AlbumContinuation(LibraryAlbumWalk(src)).also {
            sequential = it
            sequentialKey = key
        }
    }

    /** True while a top-up is in flight, so a burst of transitions asks once. */
    private var radioFetching = false

    /**
     * Keep the local queue from running out while radio mode is on.
     *
     * The top-up happens **two tracks before the end**, not at it. ExoPlayer is
     * handed the whole queue at once — that is what makes its transitions gapless —
     * so appending while there is still something playing keeps the seam intact.
     * Waiting for the queue to actually empty would mean a silence, a fetch, and then
     * a fresh `prepare`, which is exactly the gap this backend doesn't have.
     *
     * That head start is the *only* trigger there used to be, and it cannot see two
     * cases the listener certainly can. With shuffle on, the index is a position in
     * the list rather than in the play order, so "two from the end" says nothing
     * about what is left. And a skip from the last track produces no transition at
     * all — the player simply stopped, leaving the same song on screen, which is
     * what "pressing next goes back to the same song" was. So the player's own
     * [LocalPlayer.exhausted] is collected alongside, and answered by topping up and
     * carrying on.
     */
    private fun startLocalRadio() {
        viewModelScope.launch {
            combine(localPlayer.index, localPlayer.queue) { at, queue -> at to queue }
                .collect { (at, queue) ->
                    if (!canTopUp()) return@collect
                    if (queue.isEmpty() || at < 0) return@collect
                    // How many are left **in play order** — see
                    // [LocalPlayer.upcomingCount]. This used to be `queue.size - at`,
                    // which is a position in the list, so with shuffle on it fired at
                    // the wrong moment in both directions.
                    if (localPlayer.upcomingCount(RADIO_TOPUP_AT) >= RADIO_TOPUP_AT) return@collect
                    if (radioFetching) return@collect
                    radioFetching = true
                    try {
                        topUpRadio(queue)
                    } finally {
                        radioFetching = false
                    }
                }
        }
        viewModelScope.launch {
            localPlayer.exhausted.collect {
                if (!canTopUp()) return@collect
                val queue = localPlayer.queue.value
                if (queue.isEmpty()) return@collect
                if (radioFetching) return@collect
                radioFetching = true
                val added = try {
                    topUpRadio(queue)
                } finally {
                    radioFetching = false
                }
                // Playback has already stopped by the time this runs, so appending is
                // not enough on its own — something has to start the next one.
                if (added) localPlayer.continueAfterEnd()
            }
        }
    }

    private suspend fun canTopUp(): Boolean =
        settings.radioMode.first() && _backend.value == Backend.SUBSONIC

    /**
     * Append the next batch, and say whether anything was actually added.
     *
     * **What "next" means depends on the shuffle switch**, which is the whole of
     * what this decides:
     *
     *  - **Shuffled** — a random song from the library. That is what the switch on
     *    Now Playing has always claimed and never did past the end of the queue:
     *    ExoPlayer's shuffle mode reorders the list it was given and has nothing to
     *    say once that list is spent.
     *  - **Not shuffled** — the next song on the record, then the next record, and
     *    so on. See [AlbumContinuation]. A listener working through an album means
     *    the next track by "next", not a genre-matched stranger.
     *  - **Neither can answer** — the similarity ladder, and offline the picker over
     *    what is on the phone. A track with no album to walk (a playlist entry, a
     *    search hit) lands here, and so does a library that will not describe one.
     *
     * The seed is what is playing now, not what started the queue: the continuation
     * should follow where the listener has got to.
     */
    private suspend fun topUpRadio(queue: List<com.engabd.sendpin.audio.LocalTrack>): Boolean {
        val playing = localPlayer.current.value
        val seedId = playing?.scrobbleId ?: playing?.id
        val seed = seedId?.let { id -> lastSeeds[id] }
        val exclude = queue.mapNotNull { it.scrobbleId ?: it.id }.toSet()

        val src = source
        // One generator for every library, built from the `MusicSource` interface.
        // It used to be a `when (source)` over two concrete client classes — so
        // Jellyfin, Emby and Plex each fell through to the offline picker over
        // downloaded files, and on a library with nothing downloaded the queue ended
        // silently, exactly as if the setting were off. See [LibraryRadioSource].
        val generator: RadioSource? = src?.let { LibraryRadioSource(it) }

        // Harmonic DJ mode needs TrackScanRepository, which only ever has data for
        // files on this phone — there's no server round trip that could carry BPM/key
        // compatibility for the generator-backed rungs. It re-ranks *within* whichever
        // rung/pool answers on either path, offline or online, rather than being an
        // offline-only concern: a Subsonic/Jellyfin session with a working generator
        // is exactly as real a "keep the music going" session as an offline one.
        //
        // The seed's scan is looked up once, out here: `bonus` is a sort selector,
        // which Kotlin re-invokes on every comparison rather than once per element,
        // and what is playing does not change between two of them. A null seed scan
        // means there is nothing to be compatible *with*, so the whole bonus drops
        // out and the ranking is the plain genre/artist (or generator-order) one.
        //
        // Not applied to the sequential walk: an album's own running order is not a
        // thing to re-rank.
        val seedScan = if (settings.djMode.first()) playing?.let { trackScans.peek(it) } else null
        val bonus: (MaItem) -> Int = if (seedScan == null) ({ 0 }) else djBonus(seedScan)

        val picked = when {
            _offline.value || src == null || generator == null ->
                radio.offline(downloads.value.map { downloadItem(it) }, seed, RADIO_BATCH, exclude, bonus = bonus)
            localPlayer.shuffle.value ->
                radio.random(generator, RADIO_BATCH, exclude, bonus = bonus)
                    .ifEmpty { radio.next(generator, seed, RADIO_BATCH, exclude, bonus = bonus) }
            else ->
                sequentialFor(src).next(seed, RADIO_BATCH, exclude)
                    .ifEmpty { radio.next(generator, seed, RADIO_BATCH, exclude, bonus = bonus) }
        }
        if (picked.isEmpty()) return false
        rememberSeeds(picked)
        localPlayer.addToQueue(picked.map { localTrack(it) })
        return true
    }

    /**
     * Harmonic DJ mode's ranking bonus for one candidate, against the scan of what
     * is playing — key compatibility on the Camelot wheel is worth two, a matching
     * (or half/double) tempo one, on top of [LocalRadio.offline]'s own genre and
     * artist scores.
     *
     * `store.peek`, not the suspending `cached()`: this is a sort selector, called
     * on every comparison, so a disk read is not an option. A candidate with no
     * memory-resident scan contributes nothing and keeps its genre/artist ranking.
     * Keyed by `itemId` because that is what [TrackScanRepository.keyFor] resolves
     * a downloaded track to — `DownloadsIndex.item` sets `itemId` from the
     * download's id and `toLocalTrack` carries the same value into `LocalTrack.id`,
     * which `keyFor` prefers over every other field.
     */
    private fun djBonus(seedScan: TrackScan): (MaItem) -> Int = { item ->
        val candidateScan = trackScans.store.peek(item.itemId)
        val seedKey = seedScan.key
        val candidateKey = candidateScan?.key
        var s = 0
        if (seedKey != null && candidateKey != null && Camelot.compatible(seedKey, candidateKey)) s += 2
        if (candidateScan != null && Camelot.bpmMatch(seedScan.bpm, candidateScan.bpm)) s += 1
        s
    }

    /**
     * Library items for tracks that have played, keyed by id.
     *
     * A [com.engabd.sendpin.audio.LocalTrack] is what the player needs and carries no
     * genre or artist id, so the radio would have nothing but a title to seed from.
     * Kept small — only what has actually been queued.
     */
    private val lastSeeds = LinkedHashMap<String, MaItem>()

    private fun rememberSeeds(items: List<MaItem>) {
        items.forEach { lastSeeds[it.itemId] = it }
        while (lastSeeds.size > 400) lastSeeds.remove(lastSeeds.keys.first())
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
        lastSearched = null
        _searching.value = false
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

    private suspend fun childrenOf(item: MaItem): List<MaItem> =
        if (MusicSources.isLocalProvider(item.provider)) source?.children(item) ?: emptyList()
        else maRepo.children(item)

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
                // Starred is not a page of one media type, so it is answered ahead of
                // the paging table below. Music Assistant has had the filter all
                // along — `favorite = true` on the same `library_items` command every
                // browse uses — and the category simply did not exist on this backend,
                // so a starred library was reachable from Navidrome and Jellyfin and
                // not from MA. Same four types, same order, same sectioned screen.
                //
                // Four calls in parallel: they are independent, and run in series the
                // slowest one would decide how long the screen stayed empty. Each is
                // allowed to fail on its own — a server that cannot answer for
                // playlists should still show the starred albums.
                if (id == "starred") {
                    return@pushNode coroutineScope {
                        val artists = async {
                            // Not album-artists-only: this list is what the user
                            // starred, and a featured artist they starred deliberately
                            // is exactly as starred as a headliner.
                            runCatching { maRepo.favoriteArtists(limit = 200, albumArtistsOnly = false) }
                                .getOrDefault(emptyList())
                        }
                        val albums = async {
                            runCatching { maRepo.favoriteAlbums(limit = 200) }.getOrDefault(emptyList())
                        }
                        val playlists = async {
                            runCatching { maRepo.favoritePlaylists() }.getOrDefault(emptyList())
                        }
                        val tracks = async {
                            runCatching { maRepo.favoriteTracks() }.getOrDefault(emptyList())
                        }
                        artists.await() + albums.await() + playlists.await() + tracks.await()
                    }
                }
                // Paged, and published as the pages land: a large library used to be a
                // single 5000-item request that simply timed out, showing an error
                // instead of a library. Now the first 500 are on screen in a moment and
                // the rest fill in behind them.
                val page: suspend (Int, Int) -> List<MaItem> = when (id) {
                    "artists" -> { o, l -> maRepo.artists(o, l) }
                    "albums" -> { o, l -> maRepo.albums(o, l) }
                    "tracks" -> { o, l -> maRepo.tracks(o, l) }
                    "playlists" -> { o, l -> maRepo.playlists(o, l) }
                    "radios" -> { o, l -> maRepo.radios(o, l) }
                    "podcasts" -> { o, l -> maRepo.podcasts(o, l) }
                    else -> return@pushNode emptyList()
                }
                maRepo.allLibraryItems(page, onPage = onPartial)
            } else {
                val sc = source ?: throw IllegalStateException("${libraryName()} isn't connected")
                when (id) {
                    "artists" -> sc.artists()
                    // Paged, not one 5000-item request: the Subsonic spec caps
                    // `getAlbumList2` at 500 per call, and a server that enforces it
                    // answered the old request with an error rather than a truncated
                    // list. Batches are published as they land, so the wall of art
                    // fills in.
                    "albums" -> allAlbums(sc, onPartial)
                    // Paged and published as the pages land, for the same reason
                    // albums are — "every song" is the largest list a library has, and
                    // asking for it in one request is how a big one times out.
                    "tracks" -> allTracks(sc, onPartial)
                    "newest" -> sc.recentlyAdded(200)
                    "playlists" -> sc.playlists()
                    "genres" -> sc.genres()
                    // Ordered by type, and the screen keeps them that way: a starred
                    // list is the one browse node that genuinely mixes artists,
                    // albums, playlists and tracks, and it used to arrive as one
                    // undifferentiated run of rows. Playlists were dropped outright.
                    "starred" -> sc.favorites().let {
                        it.artists + it.albums + it.playlists + it.tracks
                    }
                    "random" -> sc.randomSongs(100)
                    else -> emptyList()
                }
            }
        }
    }

    /**
     * Every album on the server, a page at a time.
     *
     * Subsonic documents a maximum `size` of 500 on `getAlbumList2` and Jellyfin has
     * its own limits, so the whole library is walked with an offset either way. [cap]
     * backstops a server that ignores the offset and hands back the same page for
     * ever.
     */
    private suspend fun allAlbums(
        sc: MusicSource,
        onPartial: (List<MaItem>) -> Unit,
        cap: Int = 20_000,
    ): List<MaItem> = allPages(onPartial, cap) { offset, limit -> sc.albums(offset, limit) }

    /**
     * Every track on the server, the same way and for the same reasons.
     *
     * The same 20,000 ceiling `MaRepository.allLibraryItems` puts on the Music
     * Assistant backend's Tracks category, so the two behave alike on a library
     * larger than anyone scrolls: the first pages are on screen at once and it stops
     * fetching eventually rather than walking a hundred thousand songs into memory.
     */
    private suspend fun allTracks(
        sc: MusicSource,
        onPartial: (List<MaItem>) -> Unit,
        cap: Int = 20_000,
    ): List<MaItem> = allPages(onPartial, cap) { offset, limit -> sc.tracks(offset, limit) }

    /**
     * Walk a paged listing to its end, publishing each running batch.
     *
     * [cap] backstops a server that ignores the offset and hands back the same page
     * for ever; so does the id check, which stops the moment a page adds nothing new.
     */
    private suspend fun allPages(
        onPartial: (List<MaItem>) -> Unit,
        cap: Int,
        page: suspend (offset: Int, limit: Int) -> List<MaItem>,
    ): List<MaItem> {
        val all = mutableListOf<MaItem>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (offset < cap) {
            val batch = page(offset, SUBSONIC_PAGE)
            if (batch.isEmpty()) break
            val fresh = batch.filterNot { it.itemId in seen }
            fresh.forEach { seen += it.itemId }
            if (fresh.isEmpty()) break          // the server is repeating itself
            all += fresh
            onPartial(all.toList())
            if (batch.size < SUBSONIC_PAGE) break
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

    /**
     * Back to the top of the library, however deep the browse stack is.
     *
     * What tapping the Library tab does when the library is already on screen — the
     * convention every tabbed app has, and the reason it exists here is that the only
     * way out of a nested browse used to be pressing back once per level.
     *
     * A no-op at the root with no search open, so re-tapping the tab does not reload
     * every shelf for nothing.
     */
    fun goToRoot() {
        if (_depth.value == 0 && !_searchOpen.value) return
        _searchOpen.value = false
        _query.value = ""
        showRoot()
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
        loadDiscoverRows()
        loadInProgress()
        loadFrequent()
    }

    /** Best-effort — a shelf is hidden rather than erroring if a server lacks it. */
    private fun loadRecent(): Job? {
        if (_offline.value) { _recent.value = emptyList(); return null }
        return viewModelScope.launch {
            _recent.value = try {
                // A longer list than a single shelf needs, because on MA this one feeds
                // two: [shelves] splits the songs out of it into "Continue listening".
                // At the default twelve, a listener who plays single tracks left
                // "Recently played" with two entries in it and "Continue listening"
                // with ten, which is a worse page than either shelf alone.
                if (_backend.value == Backend.MA) maRepo.recentlyPlayed(MA_RECENT_LIMIT)
                else source?.recentlyPlayed(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            // The only loader that used to skip this, which is why "Recently played"
            // was the one shelf whose hearts never came up filled.
            rememberFavorites(_recent.value)
        }
    }

    /** The pending "this track was actually played" report; cancelled by the next track. */
    private var submissionJob: kotlinx.coroutines.Job? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    /**
     * Keep the server's session alive for as long as [id] is the playing track.
     *
     * A no-op for every provider except Jellyfin, whose session — and therefore its
     * "Now Playing" panel and its resume positions — times out after about a minute
     * of silence from the client. [PROGRESS_REPORT_MS] is well inside that, and far
     * enough apart that it costs one request per ten seconds of listening.
     *
     * Cancelled and restarted by the caller on every track change, so the loop only
     * has to notice the track moving on underneath it, not race with its successor.
     */
    private suspend fun reportProgressWhile(sink: MusicSource, id: String) {
        while (true) {
            delay(PROGRESS_REPORT_MS)
            if (localPlayer.current.value?.scrobbleId != id) return
            runCatching {
                sink.reportProgress(
                    id = id,
                    positionMs = localPlayer.positionMs.value,
                    paused = !localPlayer.playing.value,
                )
            }
        }
    }

    /**
     * Wait until [id] has been listened to, then report the completed play.
     *
     * The conventional threshold, and the one Last.fm and Navidrome both use: half the
     * track, or four minutes, whichever comes first. Bails if the user moved on — a
     * track that was skipped is not a play, which is the whole point of splitting this
     * off from the "now playing" ping.
     */
    private suspend fun submitWhenPlayed(sink: MusicSource, id: String, startedAtMs: Long) {
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
        if (played == true) {
            runCatching {
                sink.scrobble(
                    id,
                    completed = true,
                    startedAtMs = startedAtMs,
                    positionMs = localPlayer.positionMs.value,
                )
            }
        }
    }

    private fun loadFavoriteAlbums(): Job? {
        if (_offline.value) { _favoriteAlbums.value = emptyList(); return null }
        return viewModelScope.launch {
            _favoriteAlbums.value = try {
                if (_backend.value == Backend.MA) maRepo.favoriteAlbums()
                else source?.favorites()?.albums?.take(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_favoriteAlbums.value)
        }
    }

    private fun loadFavoriteArtists(): Job? {
        if (_offline.value) { _favoriteArtists.value = emptyList(); return null }
        return viewModelScope.launch {
            _favoriteArtists.value = try {
                if (_backend.value == Backend.MA) maRepo.favoriteArtists()
                else source?.favorites()?.artists?.take(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_favoriteArtists.value)
        }
    }

    private fun loadRecentlyAdded(): Job? {
        if (_offline.value) { _recentlyAdded.value = emptyList(); return null }
        return viewModelScope.launch {
            _recentlyAdded.value = try {
                if (_backend.value == Backend.MA) maRepo.recentlyAdded()
                else source?.recentlyAdded(12).orEmpty()
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
                else source?.randomAlbums(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_recommendations.value)
        }
    }

    /**
     * Music Assistant's own Discover rows, each fetched and published as it lands.
     *
     * MA 2.10 answers `music/recommendations` with the row *listing* — names, icons,
     * ids — and nothing else; the contents of each row come from a second call. So this
     * is one cheap call followed by a fan-out, rather than the single call the app used
     * to make and then find empty.
     *
     * Three things are deliberate:
     *
     *  - **Rows are published incrementally.** A server with fifteen enabled rows is
     *    fifteen round trips; waiting for all of them would leave the library blank for
     *    as long as the slowest one takes. Each row appears the moment it is filled.
     *  - **Concurrency is capped.** They all share the one WebSocket, and firing fifteen
     *    commands into it at once just queues them behind each other while starving
     *    whatever else the app is doing — including the play command from a tap.
     *  - **Rows the fixed shelves already cover are dropped.** "Recently played",
     *    "Recently added tracks" and "In progress" have their own loaders above and
     *    their own places on screen; listing them twice is worse than not having them
     *    from the server at all.
     */
    private fun loadDiscoverRows(): Job? {
        if (_backend.value != Backend.MA || _offline.value) { _discover.value = emptyList(); return null }
        return viewModelScope.launch {
            val rows = try { maRepo.recommendationRows() } catch (_: Exception) { emptyList() }
            val wanted = rows.filter { it.enabledByDefault && it.itemId !in DISCOVER_COVERED_ELSEWHERE }
            if (wanted.isEmpty()) { _discover.value = emptyList(); return@launch }
            _discover.value = emptyList()
            // Keeps the row order the server chose, however the fetches interleave.
            val filled = LinkedHashMap<String, DiscoverShelf>()
            val gate = Semaphore(DISCOVER_CONCURRENCY)
            coroutineScope {
                wanted.forEach { row ->
                    launch {
                        val items = gate.withPermit {
                            // Already inline on a pre-2.10 server; one call otherwise.
                            row.items.ifEmpty {
                                try { maRepo.recommendationItems(row) } catch (_: Exception) { emptyList() }
                            }
                        }
                        if (items.isEmpty()) return@launch
                        rememberFavorites(items)
                        synchronized(filled) {
                            filled[row.itemId] = DiscoverShelf(
                                key = "${row.provider}:${row.itemId}",
                                title = row.name,
                                icon = row.icon,
                                items = items,
                                circular = items.all { it.mediaType == "artist" },
                            )
                            _discover.value = wanted.mapNotNull { filled[it.itemId] }
                        }
                    }
                }
            }
        }
    }

    /**
     * Load what's unfinished: MA's in-progress audiobooks/podcasts, or — on a
     * Jellyfin library — tracks with a saved resume point. Subsonic/Navidrome have
     * no per-track resume concept at all (see [MusicSource.continueListening]'s
     * default), so this shelf simply doesn't appear there.
     */
    private fun loadInProgress(): Job? {
        if (_offline.value) { _inProgress.value = emptyList(); return null }
        return viewModelScope.launch {
            _inProgress.value = try {
                if (_backend.value == Backend.MA) maRepo.inProgress() else source?.continueListening(12).orEmpty()
            } catch (_: Exception) { emptyList() }
            rememberFavorites(_inProgress.value)
        }
    }

    private fun loadFrequent(): Job? {
        if (_backend.value != Backend.SUBSONIC) { _frequent.value = emptyList(); return null }
        return viewModelScope.launch {
            _frequent.value = try { source?.mostPlayed(12).orEmpty() } catch (_: Exception) { emptyList() }
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
                if (MusicSources.isLocalProvider(item.provider)) {
                    val sc = source ?: throw IllegalStateException("${libraryName()} isn't connected")
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
    //
    // Extracted to [TrackPreviewPlayer]: it is the one part of this class that owns a
    // *player* rather than browse state, so its lifetime is its own. The two entry
    // points stay here so no caller changed.

    /** The track currently being auditioned, if any. */
    val previewing: StateFlow<String?> get() = preview.previewing

    private val preview by lazy {
        TrackPreviewPlayer(
            context = getApplication(),
            repo = maRepo,
            scope = viewModelScope,
            onMessage = { _toast.tryEmit(it) },
        )
    }

    /**
     * Music Assistant is the only backend with previews to hand back.
     *
     * The backend check is deliberately *third*, not first, which is where the
     * original had it: a preview started on MA and still running when the library
     * switches to Navidrome must still be stoppable by tapping it. Checking the
     * backend up front would return before either branch and strand it playing.
     */
    fun togglePreview(item: MaItem) {
        if (previewing.value == item.itemId) { stopPreview(); return }
        stopPreview()
        if (_backend.value != Backend.MA) return
        preview.toggle(item)
    }

    fun stopPreview() = preview.stop()

    private fun rootItems(): List<MaItem> = buildList {
        when {
            // Offline: the server's categories would all dead-end, so the phone's
            // own library is the only thing offered.
            _offline.value -> Unit
            _backend.value == Backend.MA -> {
                add(category("artists", "Artists")); add(category("albums", "Albums"))
                add(category("tracks", "Tracks")); add(category("playlists", "Playlists"))
                // Starred was a self-hosted-only category, for no reason other than
                // that nobody had written the MA query — see [openCategory].
                add(category("starred", "Starred"))
                add(category("radios", "Radio stations")); add(category("podcasts", "Podcasts"))
            }
            // Driven by what the source says it can answer, not by a fixed list.
            // Every self-hosted library got Playlists, Genres and Starred whatever it
            // was — LocalMediaSource declares only SEARCH and still offered all three,
            // each of which dead-ends in an empty screen. See [Capability]: a shelf
            // that is always empty is worse than the feature being absent.
            else -> {
                val src = source
                add(category("artists", "Artists")); add(category("albums", "Albums"))
                // Songs had a category on the Music Assistant backend and on none of
                // the self-hosted ones, so on Jellyfin (and Navidrome, and every other
                // library this phone plays itself) the only routes to a track were
                // through the album holding it or through search. Gated on the
                // capability rather than added unconditionally: a source that cannot
                // enumerate songs would otherwise offer an empty screen.
                if (src == null || src.has(Capability.TRACKS)) add(category("tracks", "Tracks"))
                if (src == null || src.has(Capability.PLAYLIST_READ)) add(category("playlists", "Playlists"))
                if (src == null || src.has(Capability.GENRES)) add(category("genres", "Genres"))
                if (src == null || src.has(Capability.FAVORITES)) add(category("starred", "Starred"))
                add(category("newest", "Recently Added"))
                add(category("random", "Shuffle all"))
            }
        }
        // Downloads used to be grafted in here as a flat category with its own
        // rendering branch in the library screen. It is a library of its own now
        // ([com.engabd.sendpin.local.DownloadsSource]), reachable from the switcher
        // like every other one, so there is nothing left to graft. The one case that
        // still wants it inline is the automatic offline fallback, which has no
        // categories of its own to show.
        if (_offline.value) {
            add(category("downloads", "Downloads"))
        }
    }

    private fun category(id: String, name: String) =
        MaItem(id, CATEGORY_PROVIDER, name, null, "category", null, null, null)

    /**
     * Downloads grouped by album and in track order, so a downloaded album reads —
     * and plays — as an album rather than in whatever order the files happened to
     * land.
     */
    private fun downloadItems(list: List<DownloadedTrack>): List<MaItem> =
        com.engabd.sendpin.local.DownloadsIndex.items(list)

    /**
     * One mapping from a downloaded file to a library item, shared with
     * [com.engabd.sendpin.local.DownloadsSource]. Two of them drift.
     */
    private fun downloadItem(d: DownloadedTrack) = com.engabd.sendpin.local.DownloadsIndex.item(d)

    override fun onCleared() {
        super.onCleared()
        // The MaApiClient and the LocalPlayer are process-scoped and shared — a
        // ViewModel going away (a rotation, a tab swap) must not stop the music.
        stopPreview()
    }

    private companion object {
        const val DOWNLOAD = "__dl__"
        const val DOWNLOADS_TITLE = "Downloads"

        /**
         * Discover rows the app already has a dedicated shelf for.
         *
         * Music Assistant's built-in recommendations provider offers these under its own
         * names, and the app fetches the same three through [loadRecent],
         * [loadRecentlyAdded] and [loadInProgress]. Showing both would be the same
         * albums twice, a few rows apart.
         */
        val DISCOVER_COVERED_ELSEWHERE = setOf(
            "recently_played", "recently_added_tracks", "in_progress",
        )

        /**
         * How many Discover rows to fetch at once.
         *
         * They share the one WebSocket with everything else the app does, including the
         * play command behind a tap. Four keeps the library filling briskly without the
         * library's own background work being what makes a tap feel slow.
         */
        const val DISCOVER_CONCURRENCY = 4

        /** Four minutes in: a play, however long the track. The usual convention. */
        const val SCROBBLE_MAX_MS = 4 * 60 * 1000L

        /**
         * How often to tell a session-based server the track is still playing.
         *
         * Jellyfin times a session out at roughly a minute, so this has plenty of
         * margin while staying cheap — one request per ten seconds of listening, and
         * none at all for providers that don't implement `reportProgress`.
         */
        const val PROGRESS_REPORT_MS = 10_000L

        /**
         * How many recently-played items to ask Music Assistant for.
         *
         * Split across two shelves (see [LibraryShelves.inProgress]), so it is sized
         * to fill both rather than one. Well inside what a carousel will lazily
         * compose and one command either way.
         */
        const val MA_RECENT_LIMIT = 30

        /** `getAlbumList2`'s documented maximum `size`. */
        const val SUBSONIC_PAGE = 500

        /**
         * How long typing has to pause before the query is sent.
         *
         * Short enough that it reads as "while I type" rather than "after I stop",
         * long enough that a whole word is one request instead of six.
         */
        const val SEARCH_DEBOUNCE_MS = 220L

        /**
         * Below this, don't ask. One or two letters match most of a library, so the
         * server does real work to return something nobody wanted; the IME Search key
         * still forces it for anyone who means it.
         */
        const val MIN_LIVE_QUERY = 2

        /** Enough to cover backspacing through a word, and no more. */
        const val SEARCH_CACHE_SIZE = 24

        /**
         * Below this, a saved queue is someone starting something rather than
         * stopping mid-listen, and "resume" is the wrong word for it.
         */
        const val RESUME_MIN_POSITION_MS = 15_000L

        /**
         * Top up once fewer than this many tracks are still ahead of the playhead.
         *
         * Two, not zero: ExoPlayer buffers across a boundary it can already see, so
         * appending early is what keeps the transition gapless. Waiting for the queue
         * to empty would mean silence, a fetch and a fresh prepare.
         */
        const val RADIO_TOPUP_AT = 2

        /** How many to add each time. Enough to cover a fetch failing next round. */
        const val RADIO_BATCH = 10
    }
}
