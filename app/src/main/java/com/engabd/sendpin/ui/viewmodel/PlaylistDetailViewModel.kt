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
 * Backs [com.engabd.sendpin.ui.screens.PlaylistDetailScreen]. Loads the
 * playlist's tracks and exposes play / shuffle / queue actions.
 *
 * Same dual-backend pattern as [AlbumDetailViewModel].
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
    private val localPlayer = (app as SendpinApp).localPlayer

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
                if (provider == "subsonic") {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
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

    fun playAll() {
        val uris = _tracks.value.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (provider == "subsonic") {
                    _tracks.value.firstOrNull()?.let { playTrackLocal(it) }
                } else {
                    maRepo.playMedia(playTarget(), uris, "replace")
                }
                _toast.tryEmit("Playing ${_playlist.value?.name ?: "playlist"}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    fun shuffleAll() {
        val uris = _tracks.value.mapNotNull { it.uri }.shuffled()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (provider == "subsonic") {
                    _tracks.value.firstOrNull()?.let { playTrackLocal(it) }
                } else {
                    maRepo.playMedia(playTarget(), uris, "replace")
                    maRepo.setShuffle(playTarget(), true)
                }
                _toast.tryEmit("Shuffling ${_playlist.value?.name ?: "playlist"}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't shuffle") }
        }
    }

    fun addToQueue() {
        val uris = _tracks.value.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (provider == "subsonic") {
                    _toast.tryEmit("Queue management needs Music Assistant")
                } else {
                    maRepo.playMedia(playTarget(), uris, "add")
                    _toast.tryEmit("Added ${uris.size} tracks to queue")
                }
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't add to queue") }
        }
    }

    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (provider == "subsonic") playTrackLocal(track)
                else track.uri?.let { maRepo.playMedia(playTarget(), listOf(it), "replace") }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    private suspend fun playTrackLocal(track: MaItem) {
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) { _toast.tryEmit("No Navidrome server"); return }
        // Stop MA before playing locally — both can't own the speaker.
        runCatching { maRepo.stop(playTarget()) }
        val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
        localPlayer.play(sc.streamUrl(track.itemId), track.name)
        _toast.tryEmit("Playing ${track.name}")
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