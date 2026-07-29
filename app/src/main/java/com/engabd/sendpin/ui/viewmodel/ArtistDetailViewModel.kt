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
 * Backs [com.engabd.sendpin.ui.screens.ArtistDetailScreen]. Loads the artist's
 * metadata, albums, and top tracks — then exposes play / shuffle / queue actions
 * for both the album grid and the top-tracks list.
 *
 * Mirrors [AlbumDetailViewModel] in structure: the artist's identity is passed
 * in from the navigation route, the header renders immediately, and data loads
 * in the background.
 */
class ArtistDetailViewModel(
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

    private val _artist = MutableStateFlow<MaItem?>(null)
    val artist: StateFlow<MaItem?> = _artist

    private val _albums = MutableStateFlow<List<MaItem>>(emptyList())
    val albums: StateFlow<List<MaItem>> = _albums

    private val _topTracks = MutableStateFlow<List<MaItem>>(emptyList())
    val topTracks: StateFlow<List<MaItem>> = _topTracks

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val _targetPlayer = MutableStateFlow("")
    private fun playTarget() = _targetPlayer.value.ifBlank { myPlayerId }

    init {
        _artist.value = MaItem(
            itemId = itemId, provider = provider, name = initialName,
            uri = null, mediaType = "artist", subtitle = null,
            image = initialArt, duration = null,
        )
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        loadArtist()
    }

    fun loadArtist() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                if (provider == "subsonic") {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
                    val (artistMeta, albumList) = sc.artistDetail(itemId)
                    if (artistMeta != null) _artist.value = artistMeta
                    _albums.value = albumList
                } else {
                    val ref = MaItem(itemId, provider, initialName, null, "artist", null, initialArt, null)
                    val artistMeta = maRepo.getArtist(ref)
                    if (artistMeta != null) _artist.value = artistMeta
                    _albums.value = maRepo.artistAlbums(ref)
                    // Top tracks are best-effort — not all MA providers support it.
                    _topTracks.value = try { maRepo.topTracks(ref, limit = 10) } catch (_: Exception) { emptyList() }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load artist"
            }
            _loading.value = false
        }
    }

    // --- playback actions -------------------------------------------------

    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (provider == "subsonic") playTrackLocal(track)
                else track.uri?.let { maRepo.playMedia(playTarget(), listOf(it), "replace") }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    fun shuffleTopTracks() {
        val uris = _topTracks.value.mapNotNull { it.uri }.shuffled()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (provider == "subsonic") {
                    _topTracks.value.firstOrNull()?.let { playTrackLocal(it) }
                } else {
                    maRepo.playMedia(playTarget(), uris, "replace")
                    maRepo.setShuffle(playTarget(), true)
                }
                _toast.tryEmit("Shuffling top tracks")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't shuffle") }
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
        // The local player is process-scoped and shared — leaving the artist screen
        // must not stop the music the user just started from it.
    }
}

class ArtistDetailViewModelFactory(
    private val app: Application,
    private val itemId: String,
    private val provider: String,
    private val name: String,
    private val art: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ArtistDetailViewModel(app, itemId, provider, name, art) as T
}