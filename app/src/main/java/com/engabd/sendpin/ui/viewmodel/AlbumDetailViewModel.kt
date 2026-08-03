package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
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
 * [SubsonicClient]. The backend is chosen from the saved setting at load time.
 */
class AlbumDetailViewModel(
    app: Application,
    val itemId: String,
    val provider: String,
    val initialName: String,
    val initialArt: String?,
) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val myPlayerId: String = PlayerIdentity.getPlayerId(app)
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

    /** Built once per load rather than per action. Null until the album resolves. */
    private var subsonic: SubsonicClient? = null

    private val isSubsonic get() = provider == SubsonicClient.PROVIDER

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
                if (isSubsonic) {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
                    sc.streamFormat = settings.navStreamFormat.first()
                    subsonic = sc
                    val (albumMeta, trackList) = sc.albumDetail(itemId)
                    if (albumMeta != null) _album.value = albumMeta
                    _tracks.value = trackList
                    // Fetch album notes from Navidrome's getAlbumInfo2.
                    _notes.value = runCatching { sc.getAlbumInfo2(itemId) }.getOrNull()
                } else {
                    // Fetch full album metadata, then tracks.
                    val ref = MaItem(itemId, provider, initialName, null, "album", null, initialArt, null)
                    val albumMeta = maRepo.getAlbum(ref)
                    if (albumMeta != null) _album.value = albumMeta
                    _tracks.value = maRepo.albumTracks(ref)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load album"
            }
            _loading.value = false
        }
    }

    // --- playback actions -------------------------------------------------

    /** Play the whole album (replace queue). */
    fun playAll() {
        val uris = _tracks.value.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isSubsonic) {
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
                if (isSubsonic) {
                    stopMaPlayback()
                    // Shuffle on *before* the queue is set, so the play order is
                    // built shuffled rather than starting on track 1 and jumping.
                    localPlayer.setShuffle(true)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playOn(playTarget(), uris, "replace")
                    maRepo.setShuffle(playTarget(), true)
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
                if (isSubsonic) {
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
                if (isSubsonic) {
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
    val canDownload: Boolean get() = isSubsonic

    /** Every track of this album is already on the phone. */
    val allDownloaded: StateFlow<Boolean> = combine(_tracks, downloads.downloads) { tracks, index ->
        tracks.isNotEmpty() && tracks.all { t -> index.any { it.id == t.itemId } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Download the whole album for offline. */
    fun downloadAll() {
        val sc = subsonic
        if (!isSubsonic || sc == null) { _toast.tryEmit("Only Navidrome albums can be downloaded"); return }
        val pending = _tracks.value.filterNot { downloads.isDownloaded(it.itemId) }
        if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return }
        viewModelScope.launch {
            _toast.tryEmit("Downloading ${pending.size} tracks…")
            val ok = downloads.downloadAll(pending, urlFor = { sc.downloadUrl(it.itemId) })
            _toast.tryEmit(
                when (ok) {
                    pending.size -> "Downloaded ${_album.value?.name ?: "album"}"
                    0 -> "Download failed"
                    else -> "Downloaded $ok of ${pending.size}"
                }
            )
        }
    }

    /** Play a specific track from the album. */
    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    // The album is the queue; the tapped track is where it starts.
                    val start = _tracks.value.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    stopMaPlayback()
                    localPlayer.setQueue(localTracks(), start)
                    _toast.tryEmit("Playing ${track.name}")
                    return@launch
                } else {
                    // Play the whole album starting from this track — MA's play_media
                    // with "replace" + the full URI list starts at index 0; to start
                    // from a specific track, we play just that track, then add the rest.
                    track.uri?.let { maRepo.playOn(playTarget(), listOf(it), "replace") }
                    val rest = _tracks.value.mapNotNull { it.uri }
                        .filter { it != track.uri }
                    if (rest.isNotEmpty()) maRepo.playOn(playTarget(), rest, "add")
                    maRepo.play(playTarget())
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
    private fun localTracks() = _tracks.value.map {
        downloads.toLocalTrack(it, streamUrl = subsonic?.streamUrl(it.itemId))
    }

    // --- favorites --------------------------------------------------------

    fun toggleFavorite(track: MaItem) {
        val wanted = !track.favorite
        // Flip locally first — the command is a round-trip and the heart should not
        // sit still while it happens. Rolled back if the server refuses.
        _tracks.value = _tracks.value.map { if (it.itemId == track.itemId) it.copy(favorite = wanted) else it }
        viewModelScope.launch {
            try {
                val sc = subsonic
                when {
                    isSubsonic && sc != null -> sc.setStarred(track, wanted)
                    isSubsonic -> throw IllegalStateException("Navidrome isn't connected")
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
                val sc = subsonic
                when {
                    isSubsonic && sc != null -> sc.setStarred(current, wanted)
                    isSubsonic -> throw IllegalStateException("Navidrome isn't connected")
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