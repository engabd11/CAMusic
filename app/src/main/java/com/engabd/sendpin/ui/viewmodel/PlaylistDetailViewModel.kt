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
import com.engabd.sendpin.subsonic.SubsonicClient
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
    private val myPlayerId: String = PlayerIdentity.getPlayerId(app)
    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    /** Process-scoped, not owned by this screen — see the note in [AlbumDetailViewModel]. */
    private val localPlayer = (app as SendpinApp).localPlayer
    private val downloads = (app as SendpinApp).downloads

    /** Built once per load rather than per action. Null until the playlist resolves. */
    private var subsonic: SubsonicClient? = null

    private val isSubsonic get() = provider == SubsonicClient.PROVIDER

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
                if (isSubsonic) {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
                    sc.streamFormat = settings.navStreamFormat.first()
                    subsonic = sc
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
                if (isSubsonic) {
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playMedia(playTarget(), _tracks.value.mapNotNull { it.uri }, "replace")
                }
                _toast.tryEmit("Playing ${_playlist.value?.name ?: "playlist"}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    fun shuffleAll() {
        if (_tracks.value.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    stopMaPlayback()
                    // Shuffle on *before* the queue is set, so the play order is built
                    // shuffled rather than starting on track 1 and jumping.
                    localPlayer.setShuffle(true)
                    localPlayer.setQueue(localTracks())
                } else {
                    maRepo.playMedia(playTarget(), _tracks.value.mapNotNull { it.uri }.shuffled(), "replace")
                    maRepo.setShuffle(playTarget(), true)
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
                if (isSubsonic) localPlayer.addToQueue(localTracks())
                else maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }, "add")
                _toast.tryEmit("Added ${tracks.size} tracks to queue")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't add to queue") }
        }
    }

    /** One track onto the queue — the long-press alternative to "play now". */
    fun enqueueTrack(track: MaItem, option: String) {
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    val one = localTracks().filter { it.id == track.itemId }
                    if (one.isEmpty()) { _toast.tryEmit("Couldn't queue that"); return@launch }
                    if (option == "next") localPlayer.playNext(one) else localPlayer.addToQueue(one)
                } else {
                    val uri = track.uri ?: run { _toast.tryEmit("Couldn't queue that"); return@launch }
                    maRepo.playMedia(playTarget(), listOf(uri), option)
                }
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't queue that") }
        }
    }

    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    // The playlist is the queue; the tapped track is where it starts.
                    val start = _tracks.value.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks(), start)
                } else {
                    // MA's play_media + "replace" always starts at index 0, so play the
                    // tapped track and append the rest of the playlist behind it.
                    track.uri?.let { maRepo.playMedia(playTarget(), listOf(it), "replace") }
                    val rest = _tracks.value.mapNotNull { it.uri }.filter { it != track.uri }
                    if (rest.isNotEmpty()) maRepo.playMedia(playTarget(), rest, "add")
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    /** The playlist as a local queue, offline copies preferred over the stream. */
    private fun localTracks() = _tracks.value.map {
        downloads.toLocalTrack(it, streamUrl = subsonic?.streamUrl(it.itemId))
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
