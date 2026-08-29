package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.queueFrom
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs [com.engabd.sendpin.ui.screens.PlaylistDetailScreen]. Loads the playlist's
 * tracks and exposes play / shuffle / queue actions.
 *
 * Same dual-backend pattern as [AlbumDetailViewModel], including what that buys on
 * Navidrome: the playlist becomes a real local queue with downloaded copies
 * preferred, so Play plays the whole thing, Shuffle actually shuffles, and Add to
 * queue works with Music Assistant switched off entirely.
 */
class PlaylistDetailViewModel(
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
    /** Process-scoped, not owned by this screen — see the note in [AlbumDetailViewModel]. */
    private val localPlayer = (app as SendpinApp).localPlayer
    private val downloads = (app as SendpinApp).downloads

    /** Built once per load rather than per action. Null until the playlist resolves. */
    /** The library this phone plays itself — see the same field on [AlbumDetailViewModel]. */
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

    private val isLocal get() = MusicSources.isLocalProvider(provider)

    private val _playlist = MutableStateFlow<MaItem?>(null)
    val playlist: StateFlow<MaItem?> = _playlist

    private val _tracks = MutableStateFlow<List<MaItem>>(emptyList())
    val tracks: StateFlow<List<MaItem>> = _tracks

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    init {
        _playlist.value = MaItem(
            itemId = itemId, provider = provider, name = initialName,
            uri = null, mediaType = "playlist", subtitle = null,
            image = initialArt, duration = null,
        )
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        loadPlaylist()
    }

    fun loadPlaylist() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val ref = MaItem(itemId, provider, initialName, null, "playlist", null, initialArt, null)
                if (isLocal) {
                    val sc = source
                    if (sc == null) { _error.value = "That library isn't connected"; return@launch }
                    _tracks.value = sc.playlistTracks(itemId)
                } else {
                    _tracks.value = maRepo.playlistTracks(ref)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load playlist"
            }
            _loading.value = false
        }
    }

    // --- playback actions -------------------------------------------------

    fun playAll() {
        if (_tracks.value.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) {
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playOn(playTarget(), _tracks.value.mapNotNull { it.uri }, "replace")
                }
                _toast.tryEmit("Playing ${_playlist.value?.name ?: "playlist"}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    fun shuffleAll() {
        if (_tracks.value.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) {
                    stopMaPlayback()
                    // Shuffle on *before* the queue is set, so the play order is built
                    // shuffled rather than starting on track 1 and jumping.
                    localPlayer.setShuffle(true)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playOn(playTarget(), _tracks.value.mapNotNull { it.uri }.shuffled(), "replace")
                    maRepo.setShuffleOn(playTarget(), true)
                }
                _toast.tryEmit("Shuffling ${_playlist.value?.name ?: "playlist"}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't shuffle") }
        }
    }

    fun addToQueue() {
        val tracks = _tracks.value
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isLocal) localPlayer.addToQueue(localTracks())
                else maRepo.enqueue(playTarget(), tracks.mapNotNull { it.uri }, "add")
                _toast.tryEmit("Added ${tracks.size} tracks to queue")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't add to queue") }
        }
    }

    /** One track onto the queue — the long-press alternative to "play now". */
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
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't queue that") }
        }
    }

    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (isLocal) {
                    // The playlist is the queue; the tapped track is where it starts.
                    val start = _tracks.value.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks(), start)
                } else {
                    // The rest of the playlist, from the tapped track onwards. Sliced
                    // here rather than named as `start_item` — see [queueFrom].
                    val uris = queueFrom(_tracks.value, track)
                    if (uris.isNotEmpty()) {
                        maRepo.playOn(playTarget(), uris, "replace", radioMode = false)
                    }
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    // --- favorites --------------------------------------------------------

    /**
     * Favourite one of the playlist's tracks.
     *
     * Optimistic flip with rollback, the same shape as
     * [AlbumDetailViewModel.toggleFavorite] — the row's heart shouldn't sit still
     * across a round-trip.
     */
    fun toggleFavorite(track: MaItem) {
        val wanted = !track.favorite
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
     * Favourite the playlist itself.
     *
     * Music Assistant only: Subsonic's `star` takes an `id`, an `albumId` or an
     * `artistId` and has no notion of starring a playlist, so saying so is better
     * than sending something Navidrome will reject.
     */
    fun togglePlaylistFavorite() {
        val current = _playlist.value ?: return
        if (isLocal) { _toast.tryEmit("This library can't star playlists"); return }
        val wanted = !current.favorite
        _playlist.value = current.copy(favorite = wanted)
        viewModelScope.launch {
            try {
                if (wanted) maRepo.addFavorite(current) else maRepo.removeFavorite(current)
                _toast.tryEmit(if (wanted) "Added to favorites" else "Removed from favorites")
            } catch (e: Exception) {
                _playlist.value = _playlist.value?.copy(favorite = !wanted)
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    /** The playlist as a local queue, offline copies preferred over the stream. */
    private fun localTracks() = _tracks.value.map {
        downloads.toLocalTrack(it, streamUrl = source?.streamUrl(it.itemId))
    }

    /**
     * Hand the speaker over before the local player takes it. Fire-and-forget: if MA
     * is down or idle there is nothing on it to stop.
     */
    private fun stopMaPlayback() {
        viewModelScope.launch { runCatching { maRepo.stop(playTarget()) } }
    }

    override fun onCleared() {
        super.onCleared()
        // The local player is process-scoped and shared — leaving the playlist screen
        // must not stop the music the user just started from it.
    }
}

class PlaylistDetailViewModelFactory(
    private val app: Application,
    private val itemId: String,
    private val provider: String,
    private val name: String,
    private val art: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        PlaylistDetailViewModel(app, itemId, provider, name, art) as T
}
