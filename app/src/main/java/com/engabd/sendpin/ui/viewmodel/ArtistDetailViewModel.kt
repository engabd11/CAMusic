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
 * Mirrors [AlbumDetailViewModel] in structure: the artist's identity is passed in
 * from the navigation route, the header renders immediately, and data loads in the
 * background. Navidrome plays through the process-scoped
 * [com.engabd.sendpin.audio.LocalPlayer] as a *queue*, downloaded copies preferred —
 * firing a single URL at it would make "shuffle" a lie and leave the offline files
 * unplayable from this screen.
 *
 * [resolveByName] covers the one caller that cannot know the artist's id: the album
 * screen. [MaItem] carries artist *names* and no artist ids, so the album header
 * hands over a name and the real id is looked up here.
 */
class ArtistDetailViewModel(
    app: Application,
    val itemId: String,
    val provider: String,
    val initialName: String,
    val initialArt: String?,
    private val resolveByName: Boolean = false,
) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val myPlayerId: String = PlayerIdentity.getPlayerId(app)
    private val maApi = (app as SendpinApp).maApi
    private val maRepo = MaRepository(maApi)
    /** Process-scoped, not owned by this screen — see the note in [AlbumDetailViewModel]. */
    private val localPlayer = (app as SendpinApp).localPlayer
    private val downloads = (app as SendpinApp).downloads

    /** Built once per load rather than per action. Null until the artist resolves. */
    private var subsonic: SubsonicClient? = null

    private val isSubsonic get() = provider == SubsonicClient.PROVIDER

    /**
     * What API calls are made against. Replaced by the real library item once the
     * load (or a by-name lookup) resolves, so later calls carry the resolved id and
     * provider rather than the placeholder the route arrived with.
     */
    private var ref = MaItem(
        itemId = itemId, provider = provider, name = initialName,
        uri = null, mediaType = "artist", subtitle = null,
        image = initialArt, duration = null,
    )

    /** A name-only route has been turned into a real id, so don't search again. */
    private var resolved = false

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
        // Seed from the route so the header paints before the round-trip lands.
        _artist.value = ref
        viewModelScope.launch { settings.targetPlayer.collect { _targetPlayer.value = it } }
        loadArtist()
    }

    fun loadArtist() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                if (isSubsonic) {
                    val url = settings.navUrl.first().trim()
                    if (url.isBlank()) { _error.value = "No Navidrome server configured"; return@launch }
                    val sc = SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
                    subsonic = sc
                    if (!resolveRef()) return@launch
                    val (artistMeta, albumList) = sc.artistDetail(ref.itemId)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
                    _albums.value = albumList
                    // Subsonic's getTopSongs needs Last.fm wired into Navidrome, so the
                    // top-tracks section simply doesn't appear on this backend.
                } else {
                    if (!resolveRef()) return@launch
                    val artistMeta = maRepo.getArtist(ref)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
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

    /**
     * Turn a name-only [ref] into a real library item, by searching for it.
     *
     * Returns false — with the error already set — when the artist isn't in the
     * library, so the caller stops rather than asking the API for an artist whose
     * id is actually a display name.
     */
    private suspend fun resolveRef(): Boolean {
        if (!resolveByName || resolved) return true
        // A joined multi-artist credit ("Wilco, Billy Bragg") links to the first name.
        val wanted = initialName.substringBefore(",").trim().ifBlank { initialName }
        val hits =
            if (isSubsonic) subsonic?.search(wanted, limit = 10)?.artists.orEmpty()
            else maRepo.search(wanted, limit = 10).artists
        val hit = hits.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: hits.firstOrNull()
        if (hit == null) {
            _error.value = "Couldn't find $wanted in your library"
            return false
        }
        ref = hit
        resolved = true
        // The route's art is usually the album cover the user tapped from, which is
        // better than nothing while the artist's own image loads.
        _artist.value = hit.copy(image = hit.image ?: initialArt)
        return true
    }

    // --- playback actions -------------------------------------------------

    fun playTrack(track: MaItem) {
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    // The list the track was tapped in *is* the queue; the tapped
                    // track is only where it starts.
                    val list = _topTracks.value.ifEmpty { listOf(track) }
                    val start = list.indexOfFirst { it.itemId == track.itemId }.coerceAtLeast(0)
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks(list), start)
                } else {
                    // MA's play_media + "replace" always starts at index 0, so play the
                    // tapped track and append the rest of the list behind it.
                    track.uri?.let { maRepo.playMedia(playTarget(), listOf(it), "replace") }
                    val rest = _topTracks.value.mapNotNull { it.uri }.filter { it != track.uri }
                    if (rest.isNotEmpty()) maRepo.playMedia(playTarget(), rest, "add")
                }
                _toast.tryEmit("Playing ${track.name}")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    fun shuffleTopTracks() = shuffleAll()

    /** Play all top tracks in order. */
    fun playAll() {
        val tracks = _topTracks.value
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    stopMaPlayback()
                    localPlayer.setShuffle(false)
                    localPlayer.setQueue(localTracks(tracks))
                } else {
                    maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }, "replace")
                }
                _toast.tryEmit("Playing top tracks")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    /** Shuffle and play all top tracks. */
    fun shuffleAll() {
        val tracks = _topTracks.value
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    stopMaPlayback()
                    // Shuffle on *before* the queue is set, so the play order is built
                    // shuffled rather than starting on track 1 and jumping.
                    localPlayer.setShuffle(true)
                    localPlayer.setQueue(localTracks(tracks))
                } else {
                    maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }.shuffled(), "replace")
                    maRepo.setShuffle(playTarget(), true)
                }
                _toast.tryEmit("Shuffling top tracks")
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't shuffle") }
        }
    }

    /** Add all top tracks to the end of the queue. */
    fun addToQueue() {
        val tracks = _topTracks.value
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    localPlayer.addToQueue(localTracks(tracks))
                    _toast.tryEmit("Added ${tracks.size} tracks to queue")
                } else {
                    maRepo.playMedia(playTarget(), tracks.mapNotNull { it.uri }, "add")
                    _toast.tryEmit("Added ${tracks.size} tracks to queue")
                }
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't add to queue") }
        }
    }

    /** A track list as a local queue, offline copies preferred over the stream. */
    private fun localTracks(list: List<MaItem>) = list.map {
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
    private val resolveByName: Boolean = false,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        ArtistDetailViewModel(app, itemId, provider, name, art, resolveByName) as T
}
