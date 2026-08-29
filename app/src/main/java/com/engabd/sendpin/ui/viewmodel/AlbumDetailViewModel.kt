package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.queueFrom
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel.Load
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the [com.engabd.sendpin.ui.screens.AlbumDetailScreen]. Loads the album's
 * tracks + metadata, and exposes play / shuffle / queue actions — the album-level
 * equivalents of the track-level actions in [LibraryViewModel].
 *
 * The album's identity (itemId, provider, name, artwork URL) is passed in from the
 * navigation route rather than re-fetched, so the screen can render the header
 * immediately while tracks load in the background.
 *
 * Both backends are handled: Music Assistant via [MaRepository] and Navidrome via
 * [MusicSource]. Which one is decided by the item's own provider tag.
 */
class AlbumDetailViewModel(
    app: Application,
    val itemId: String,
    val provider: String,
    val initialName: String,
    val initialArt: String?,
) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    /** Live, not captured — see [PlayerIdentity.getPlayerId]. */
    private val myPlayerId: String get() = PlayerIdentity.getPlayerId(getApplication<Application>())
    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    /**
     * The process-scoped player and download index — *not* private instances. A
     * player owned by this screen would be a second one running alongside the real
     * one: invisible to Now Playing and the notification, and silenced the moment
     * the user navigated away from the album they had just started.
     */
    private val localPlayer = (app as SendpinApp).localPlayer
    private val downloads = (app as SendpinApp).downloads

    /**
     * The library this phone plays itself, when this album came from one.
     *
     * Read from the process-scoped holder rather than built here. This screen used to
     * construct its own Subsonic client on every load — a second connection pool
     * against a server the library tab was already talking to, and a second place to
     * edit when Jellyfin arrived.
     */
    private val sourceHolder = (app as SendpinApp).musicSource
    /**
     * Null unless the live source is the one this screen's items came from.
     *
     * The provider check is load-bearing now that there can be two locally-played
     * libraries: without it, switching from Navidrome to Jellyfin while an album was
     * open left the screen building stream URLs out of Jellyfin for Navidrome ids.
     */
    private val source: MusicSource?
        get() = sourceHolder.value?.takeIf { it.providerId == provider }

    /** This album belongs to the library this phone plays, rather than to MA. */
    private val isLocal get() = MusicSources.isLocalProvider(provider)

    private val _album = MutableStateFlow<MaItem?>(null)
    val album: StateFlow<MaItem?> = _album

    private val _tracks = MutableStateFlow<List<MaItem>>(emptyList())
    val tracks: StateFlow<List<MaItem>> = _tracks

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Album notes/liner notes (from Navidrome's getAlbumInfo2), or null. */
    private val _notes = MutableStateFlow<String?>(null)
    val notes: StateFlow<String?> = _notes

    /** A MusicBrainz release id, when the server offered one. */
    private val _musicBrainzId = MutableStateFlow<String?>(null)
    val musicBrainzId: StateFlow<String?> = _musicBrainzId

    /**
     * More to listen to after this record: other albums by the same artist, and
     * failing that something adjacent.
     *
     * Its own load state so it never blocks the track list — the shelf appearing a
     * moment after the songs is fine; the songs waiting on a recommendation is not.
     */
    private val _related = MutableStateFlow<Load<List<MaItem>>>(Load.Idle)
    val related: StateFlow<Load<List<MaItem>>> = _related

    /** What the related shelf ended up being able to offer, for its heading. */
    private val _relatedTitle = MutableStateFlow("More like this")
    val relatedTitle: StateFlow<String> = _relatedTitle

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** The play target — this phone by default, or a selected remote player. */
    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    init {
        // Seed the album from what the navigation route gave us, so the header
        // paints before the network round-trip completes.
        _album.value = MaItem(
            itemId = itemId, provider = provider, name = initialName,
            uri = null, mediaType = "album", subtitle = null,
            image = initialArt, duration = null,
        )
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        loadAlbum()
    }

    fun loadAlbum() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                if (isLocal) {
                    val sc = source
                    if (sc == null) { _error.value = "That library isn't connected"; return@launch }
                    val (albumMeta, trackList) = sc.albumDetail(itemId)
                    if (albumMeta != null) _album.value = albumMeta
                    _tracks.value = trackList
                    // Liner notes, where the server has them. Absent on a source
                    // without METADATA, which is why this never blocks the tracks.
                    runCatching { sc.albumInfo(itemId) }.getOrNull()?.let {
                        _notes.value = it.notes
                        _musicBrainzId.value = it.musicBrainzId
                    }
                } else {
                    // Metadata and tracks together, not one after the other. They are
                    // independent reads and the screen needs both before it is usable,
                    // so running them in series made opening any album cost two serial
                    // round trips for no reason — [ArtistDetailViewModel] already loads
                    // its four shelves this way.
                    val ref = MaItem(itemId, provider, initialName, null, "album", null, initialArt, null)
                    val (albumMeta, trackList) = coroutineScope {
                        val meta = async { maRepo.getAlbum(ref) }
                        val trk = async { maRepo.albumTracks(ref) }
                        meta.await() to trk.await()
                    }
                    if (albumMeta != null) _album.value = albumMeta
                    _tracks.value = trackList
                    // MA's own description has always been parsed — `metadata.description
                    // ?? metadata.review` — and never shown, so the notes section was
                    // Navidrome-only for want of one assignment.
                    _notes.value = albumMeta?.description
                }
                loadRelated()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load album"
            }
            _loading.value = false
        }
    }

    /**
     * What to listen to next, in the order a listener would want it.
     *
     * The artist's other records first — that is what "more like this" means when you
     * have just finished an album. Failing that, records by artists this one is filed
     * next to, and failing *that*, the same genre. Each rung is a weaker claim than
     * the one above, so the shelf is titled for whichever rung answered rather than
     * pretending they are the same thing.
     *
     * Anything empty hides the section. A shelf that says "More like this" over
     * nothing is worse than no shelf.
     */
    private fun loadRelated() {
        viewModelScope.launch {
            _related.value = Load.Loading
            val here = _album.value
            val result = runCatching {
                byArtist(here)?.takeIf { it.isNotEmpty() }?.also { _relatedTitle.value = "More from ${here?.subtitle ?: "this artist"}" }
                    ?: bySimilarArtists(here)?.takeIf { it.isNotEmpty() }?.also { _relatedTitle.value = "Similar artists" }
                    ?: byGenre(here)?.takeIf { it.isNotEmpty() }?.also { _relatedTitle.value = "More ${here?.genres?.firstOrNull() ?: "like this"}" }
                    ?: emptyList()
            }.getOrDefault(emptyList())
            // Never recommend the record you are looking at.
            _related.value = Load.Ready(result.filter { it.itemId != itemId }.distinctBy { it.itemId }.take(20))
        }
    }

    /** The rest of the artist's catalogue. */
    private suspend fun byArtist(album: MaItem?): List<MaItem>? {
        if (isLocal) {
            // A self-hosted library's album carries its artist's id, so no name
            // matching is needed here — unlike the artist screen, which has to
            // resolve one.
            val artistId = album?.parentId ?: return null
            return source?.artistDetail(artistId)?.second
        }
        val name = album?.subtitle?.substringBefore(",")?.trim() ?: return null
        val artist = maRepo.search(name).artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        return maRepo.artistAlbums(artist)
    }

    /** Albums by artists the server files next to this one. */
    private suspend fun bySimilarArtists(album: MaItem?): List<MaItem>? {
        if (isLocal) {
            val artistId = album?.parentId ?: return null
            val similar = source?.artistInfo(artistId, similarCount = 6)?.similar.orEmpty()
            return similar.take(4).flatMap {
                runCatching { source?.artistDetail(it.itemId)?.second.orEmpty() }.getOrDefault(emptyList())
            }
        }
        val name = album?.subtitle?.substringBefore(",")?.trim() ?: return null
        val artist = maRepo.search(name).artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        return maRepo.similarArtists(artist).take(4)
            .flatMap { runCatching { maRepo.artistAlbums(it) }.getOrDefault(emptyList()) }
    }

    /** Anything in the same genre — the weakest claim, and better than nothing. */
    private suspend fun byGenre(album: MaItem?): List<MaItem>? {
        val genre = album?.genres?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return if (isLocal) {
            // Genre browsing returns tracks; the albums behind them are what a shelf
            // of covers wants, so they are folded down by album id.
            source?.songsByGenre(genre, 60)
                ?.distinctBy { it.parentId }
                ?.mapNotNull { song ->
                    song.parentId?.let {
                        MaItem(
                            itemId = it, provider = provider, name = song.album ?: song.name,
                            uri = it, mediaType = "album", subtitle = song.subtitle,
                            image = song.image, duration = null,
                        )
                    }
                }
        } else {
            maRepo.search(genre).albums
        }
    }

    // --- playback actions -------------------------------------------------

    /** Play the whole album (replace queue). */
    fun playAll() {
        val uris = _tracks.value.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) {
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playOn(playTarget(), uris, "replace")
                }
                _toast.tryEmit("Playing ${_album.value?.name ?: "album"}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /** Shuffle and play the whole album. */
    fun shuffleAll() {
        val uris = _tracks.value.mapNotNull { it.uri }.shuffled()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) {
                    stopMaPlayback()
                    // Shuffle on *before* the queue is set, so the play order is
                    // built shuffled rather than starting on track 1 and jumping.
                    localPlayer.setShuffle(true)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playOn(playTarget(), uris, "replace")
                    maRepo.setShuffleOn(playTarget(), true)
                }
                _toast.tryEmit("Shuffling ${_album.value?.name ?: "album"}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't shuffle")
            }
        }
    }

    /** Add all tracks to the end of the queue. */
    fun addToQueue() {
        val uris = _tracks.value.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) {
                    localPlayer.addToQueue(localTracks())
                    _toast.tryEmit("Added ${_tracks.value.size} tracks to queue")
                } else {
                    maRepo.enqueue(playTarget(), uris, "add")
                    _toast.tryEmit("Added ${uris.size} tracks to queue")
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't add to queue")
            }
        }
    }

    /**
     * Queue a single track without disturbing what's playing.
     *
     * [option] is Music Assistant's: `next` or `add`. Tapping a row means "play this
     * now", which replaces the queue — this is the long-press alternative for when
     * that isn't what the user wanted.
     */
    fun enqueueTrack(track: MaItem, option: String) {
        viewModelScope.launch {
            try {
                if (isLocal) {
                    val one = localTracks().filter { it.id == track.itemId }
                    if (one.isEmpty()) { _toast.tryEmit("Couldn't queue that"); return@launch }
                    if (option == "next") localPlayer.playNext(one) else localPlayer.addToQueue(one)
                } else {
                    val uri = track.uri ?: run { _toast.tryEmit("Couldn't queue that"); return@launch }
                    maRepo.enqueue(playTarget(), listOf(uri), option)
                }
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't queue that")
            }
        }
    }

    /** Only Navidrome hands over the file; MA streams, so there is nothing to keep. */
    // Keyed on the item's own provider, not on a live source read during
    // composition — the source is null for a moment around a reconnect, and a plain
    // getter read at that instant dropped the Download action and never brought it
    // back. Every library this phone plays can hand over its files.
    val canDownload: Boolean get() = isLocal

    /** Every track of this album is already on the phone. */
    val allDownloaded: StateFlow<Boolean> = combine(_tracks, downloads.downloads) { tracks, index ->
        tracks.isNotEmpty() && tracks.all { t -> index.any { it.id == t.itemId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Download the whole album for offline. */
    fun downloadAll() {
        val sc = source
        if (!isLocal || sc == null) { _toast.tryEmit("That library isn't connected"); return }
        val pending = _tracks.value.filterNot { downloads.isDownloaded(it.itemId) }
        if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return }
        viewModelScope.launch {
            _toast.tryEmit("Downloading ${pending.size} tracks…")
            val ok = downloads.downloadAll(
                pending,
                urlFor = { sc.downloadUrl(it.itemId) },
                wifiOnly = settings.downloadWifiOnly.first(),
                storageCapMb = settings.downloadStorageCapMb.first(),
            )
            _toast.tryEmit(
                when (ok) {
                    pending.size -> "Downloaded ${_album.value?.name ?: "album"}"
                    0 -> "Download failed"
                    else -> "Downloaded $ok of ${pending.size}"
                }
            )
        }
    }

    /**
     * Play a specific track from the album.
     *
     * [order] is the album as the *screen* has it — multi-disc grouped and, where the
     * server gave track numbers, sorted by them (see [com.engabd.sendpin.ui.screens.discGroups]).
     * It has to be passed in rather than read from [tracks], because those two lists can
     * differ: the screen sorts, the view model held what the server sent, and the queue
     * was built from the latter. Tapping the fourth row then played whatever the server
     * happened to list fourth. Defaults to the unordered list for callers with no screen
     * order of their own.
     */
    fun playTrack(track: MaItem, order: List<MaItem>? = null) {
        val queue = order?.takeIf { it.isNotEmpty() } ?: _tracks.value
        viewModelScope.launch {
            try {
                if (isLocal) {
                    // The album is the queue; the tapped track is where it starts.
                    val start = queue.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    stopMaPlayback()
                    localPlayer.setQueue(localTracks(queue), start)
                    _toast.tryEmit("Playing ${track.name}")
                    return@launch
                } else {
                    // The rest of the album, from the tapped track onwards, as one
                    // "replace". Sliced here rather than named as `start_item`: MA
                    // only honours that parameter when `media` is a container, and
                    // this sends tracks — see [queueFrom].
                    val uris = queueFrom(queue, track)
                    if (uris.isNotEmpty()) {
                        maRepo.playOn(playTarget(), uris, "replace", radioMode = false)
                    }
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /**
     * Hand the speaker over before the local player takes it — the two backends know
     * nothing about each other, so starting an album here while MA is streaming to
     * this phone would play both at once. Fire-and-forget: if MA is down or idle
     * there is nothing on it to stop.
     */
    private fun stopMaPlayback() {
        viewModelScope.launch { runCatching { maRepo.stop(playTarget()) } }
    }

    /** The album as a local queue, offline copies preferred over the stream. */
    private fun localTracks(from: List<MaItem> = _tracks.value) = from.map {
        downloads.toLocalTrack(it, streamUrl = source?.streamUrl(it.itemId))
    }

    // --- favorites --------------------------------------------------------

    fun toggleFavorite(track: MaItem) {
        val wanted = !track.favorite
        // Flip locally first — the command is a round-trip and the heart should not
        // sit still while it happens. Rolled back if the server refuses.
        _tracks.value = _tracks.value.map { if (it.itemId == track.itemId) it.copy(favorite = wanted) else it }
        viewModelScope.launch {
            try {
                val sc = source
                when {
                    isLocal && sc != null -> sc.setStarred(track, wanted)
                    isLocal -> throw IllegalStateException("That library isn't connected")
                    wanted -> maRepo.addFavorite(track)
                    else -> maRepo.removeFavorite(track)
                }
            } catch (e: Exception) {
                _tracks.value = _tracks.value.map {
                    if (it.itemId == track.itemId) it.copy(favorite = !wanted) else it
                }
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    /**
     * Favourite (or unfavourite) the album itself, not one of its tracks.
     *
     * Same optimistic flip as [toggleFavorite]. Both backends already take an album:
     * MA's `favorites/add_item` accepts any media item's uri, and Subsonic's `star`
     * has an `albumId` parameter.
     */
    fun toggleAlbumFavorite() {
        val current = _album.value ?: return
        val wanted = !current.favorite
        _album.value = current.copy(favorite = wanted)
        viewModelScope.launch {
            try {
                val sc = source
                when {
                    isLocal && sc != null -> sc.setStarred(current, wanted)
                    isLocal -> throw IllegalStateException("That library isn't connected")
                    wanted -> maRepo.addFavorite(current)
                    else -> maRepo.removeFavorite(current)
                }
                _toast.tryEmit(if (wanted) "Added to favorites" else "Removed from favorites")
            } catch (e: Exception) {
                _album.value = _album.value?.copy(favorite = !wanted)
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The local player is process-scoped and shared — leaving the album screen
        // must not stop the album the user just started from it.
    }
}