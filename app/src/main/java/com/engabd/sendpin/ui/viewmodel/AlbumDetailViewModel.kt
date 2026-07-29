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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
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
    private val localPlayer = com.engabd.sendpin.audio.LocalPlayer()

    private val _album = MutableStateFlow<MaItem?>(null)
    val album: StateFlow<MaItem?> = _album

    private val _tracks = MutableStateFlow<List<MaItem>>(emptyList())
    val tracks: StateFlow<List<MaItem>> = _tracks

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

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
                val isSubsonic = provider == "subsonic"
                if (isSubsonic) {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
                    val (albumMeta, trackList) = sc.albumDetail(itemId)
                    if (albumMeta != null) _album.value = albumMeta
                    _tracks.value = trackList
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
                if (provider == "subsonic") {
                    // Subsonic: play first track locally (local player has no queue yet)
                    _tracks.value.firstOrNull()?.let { playTrackLocal(it) }
                } else {
                    maRepo.playMedia(playTarget(), uris, "replace")
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
                if (provider == "subsonic") {
                    _tracks.value.firstOrNull()?.let { playTrackLocal(it) }
                } else {
                    maRepo.playMedia(playTarget(), uris, "replace")
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
                if (provider == "subsonic") {
                    _toast.tryEmit("Queue management needs Music Assistant")
                } else {
                    maRepo.playMedia(playTarget(), uris, "add")
                    _toast.tryEmit("Added ${uris.size} tracks to queue")
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't add to queue")
            }
        }
    }

    /** Play a specific track from the album. */
    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (provider == "subsonic") {
                    playTrackLocal(track)
                } else {
                    // Play the whole album starting from this track — MA's play_media
                    // with "replace" + the full URI list starts at index 0; to start
                    // from a specific track, we play just that track, then add the rest.
                    track.uri?.let { maRepo.playMedia(playTarget(), listOf(it), "replace") }
                    val rest = _tracks.value.mapNotNull { it.uri }
                        .filter { it != track.uri }
                    if (rest.isNotEmpty()) maRepo.playMedia(playTarget(), rest, "add")
                    maRepo.play(playTarget())
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't play")
            }
        }
    }

    /** Subsonic direct playback via the local player. */
    private suspend fun playTrackLocal(track: MaItem) {
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) { _toast.tryEmit("No Navidrome server"); return }
        val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
        localPlayer.play(sc.streamUrl(track.itemId), track.name)
        _toast.tryEmit("Playing ${track.name}")
    }

    // --- favorites --------------------------------------------------------

    fun toggleFavorite(track: MaItem) {
        if (provider == "subsonic") return  // Subsonic favorites not wired in this screen
        viewModelScope.launch {
            try {
                val isFav = track.favorite
                if (isFav) maRepo.removeFavorite(track)
                else maRepo.addFavorite(track)
                _tracks.value = _tracks.value.map {
                    if (it.itemId == track.itemId) it.copy(favorite = !isFav) else it
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        localPlayer.stop()
    }
}