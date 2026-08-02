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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs [com.engabd.sendpin.ui.screens.ArtistDetailScreen]. Loads the artist's
 * metadata and albums, then exposes the hero's play / shuffle / queue / download
 * actions over the artist's whole catalogue.
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
                    sc.streamFormat = settings.navStreamFormat.first()
                    subsonic = sc
                    if (!resolveRef()) return@launch
                    val (artistMeta, albumList) = sc.artistDetail(ref.itemId)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
                    _albums.value = albumList.distinctBy { it.itemId }
                } else {
                    if (!resolveRef()) return@launch
                    val artistMeta = maRepo.getArtist(ref)
                    if (artistMeta != null) { ref = artistMeta; _artist.value = artistMeta }
                    // De-duplicated by id: MA answers an artist's albums per provider
                    // mapping and concatenates the results, so an artist held by two
                    // providers comes back with every album twice.
                    _albums.value = maRepo.artistAlbums(ref).distinctBy { it.itemId }
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

    /**
     * Everything by this artist, in album order — what the hero's Play / Shuffle /
     * Add to Queue and the download button all act on. Empty until first use, then
     * held: the Subsonic path costs a round-trip per album.
     */
    private val _catalogue = MutableStateFlow<List<MaItem>>(emptyList())

    private suspend fun catalogue(): List<MaItem> {
        _catalogue.value.takeIf { it.isNotEmpty() }?.let { return it }
        val all = try {
            val sc = subsonic
            when {
                // The albums are already loaded, so walk those rather than asking
                // for the artist's albums a second time.
                isSubsonic && sc != null -> _albums.value.flatMap { sc.albumTracks(it.itemId) }
                isSubsonic -> emptyList()
                else -> maRepo.artistTracks(ref)
            }
        } catch (_: Exception) { emptyList() }
        val tracks = all.distinctBy { it.itemId }
        _catalogue.value = tracks
        return tracks
    }

    /** Runs [block] over the artist's catalogue, or says why it can't. */
    private fun onCatalogue(verb: String, block: suspend (List<MaItem>) -> Unit) {
        viewModelScope.launch {
            try {
                val tracks = catalogue()
                if (tracks.isEmpty()) {
                    _toast.tryEmit("No tracks for ${ref.name}")
                    return@launch
                }
                block(tracks)
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't $verb") }
        }
    }

    /** Play everything by this artist, in order. */
    fun playAll() = onCatalogue("play") { tracks ->
        if (isSubsonic) {
            stopMaPlayback()
            localPlayer.setShuffle(false)
            localPlayer.setQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, "replace")
        }
        _toast.tryEmit("Playing ${ref.name}")
    }

    /** Shuffle and play everything by this artist. */
    fun shuffleAll() = onCatalogue("shuffle") { tracks ->
        if (isSubsonic) {
            stopMaPlayback()
            // Shuffle on *before* the queue is set, so the play order is built
            // shuffled rather than starting on track 1 and jumping.
            localPlayer.setShuffle(true)
            localPlayer.setQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }.shuffled(), "replace")
            maRepo.setShuffle(playTarget(), true)
        }
        _toast.tryEmit("Shuffling ${ref.name}")
    }

    /** Add everything by this artist to the end of the queue. */
    fun addToQueue() = onCatalogue("add to queue") { tracks ->
        if (isSubsonic) {
            localPlayer.addToQueue(localTracks(tracks))
        } else {
            maRepo.playOn(playTarget(), tracks.mapNotNull { it.uri }, "add")
        }
        _toast.tryEmit("Added ${tracks.size} tracks to queue")
    }

    /**
     * Play or queue one of this artist's albums, from the long-press sheet.
     *
     * [option] is Music Assistant's own vocabulary — `replace` | `next` | `add` —
     * and MA takes an album uri on `play_media` exactly as it takes a track's. On
     * Navidrome the album has to be resolved to its tracks first, because the local
     * player is a track queue.
     */
    fun playAlbum(album: MaItem, option: String) {
        viewModelScope.launch {
            try {
                if (isSubsonic) {
                    val sc = subsonic ?: throw IllegalStateException("Navidrome isn't connected")
                    val tracks = localTracks(sc.albumTracks(album.itemId))
                    if (tracks.isEmpty()) { _toast.tryEmit("Nothing to play"); return@launch }
                    when (option) {
                        "next" -> localPlayer.playNext(tracks)
                        "add" -> localPlayer.addToQueue(tracks)
                        else -> {
                            stopMaPlayback()
                            localPlayer.setShuffle(false)
                            localPlayer.setQueue(tracks)
                        }
                    }
                } else {
                    val uri = album.uri ?: run { _toast.tryEmit("Couldn't play that"); return@launch }
                    maRepo.playOn(playTarget(), listOf(uri), option)
                }
                _toast.tryEmit(
                    when (option) {
                        "next" -> "Playing next"
                        "add" -> "Added to queue"
                        else -> "Playing ${album.name}"
                    }
                )
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't play") }
        }
    }

    /** Take one album offline, from the long-press sheet. */
    fun downloadAlbum(album: MaItem) {
        val sc = subsonic
        if (!isSubsonic || sc == null) { _toast.tryEmit("Only Navidrome albums can be downloaded"); return }
        viewModelScope.launch {
            try {
                val tracks = sc.albumTracks(album.itemId)
                val pending = tracks.filterNot { downloads.isDownloaded(it.itemId) }
                if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return@launch }
                _toast.tryEmit("Downloading ${pending.size} tracks…")
                val ok = downloads.downloadAll(pending, urlFor = { sc.downloadUrl(it.itemId) })
                _toast.tryEmit(
                    when (ok) {
                        pending.size -> "Downloaded ${album.name}"
                        0 -> "Download failed"
                        else -> "Downloaded $ok of ${pending.size}"
                    }
                )
            } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't download") }
        }
    }

    /**
     * Favourite (or unfavourite) the artist.
     *
     * Flipped locally first — the command is a round-trip and the heart should not
     * sit still while it happens — and rolled back if the server refuses. Both
     * backends already take an artist: MA's `favorites/add_item` accepts any media
     * item's uri, and Subsonic's `star` has an `artistId` parameter.
     */
    fun toggleFavorite() {
        val current = _artist.value ?: return
        val wanted = !current.favorite
        _artist.value = current.copy(favorite = wanted)
        viewModelScope.launch {
            try {
                val sc = subsonic
                when {
                    isSubsonic && sc != null -> sc.setStarred(current, wanted)
                    isSubsonic -> throw IllegalStateException("Navidrome isn't connected")
                    wanted -> maRepo.addFavorite(current)
                    else -> maRepo.removeFavorite(current)
                }
                ref = ref.copy(favorite = wanted)
                _toast.tryEmit(if (wanted) "Added to favorites" else "Removed from favorites")
            } catch (e: Exception) {
                _artist.value = _artist.value?.copy(favorite = !wanted)
                _toast.tryEmit(e.message ?: "Couldn't toggle favorite")
            }
        }
    }

    /** A track list as a local queue, offline copies preferred over the stream. */
    private fun localTracks(list: List<MaItem>) = list.map {
        downloads.toLocalTrack(it, streamUrl = subsonic?.streamUrl(it.itemId))
    }

    // --- offline ----------------------------------------------------------

    /** Only Navidrome hands over the file; MA streams, so there is nothing to keep. */
    val canDownload: Boolean get() = isSubsonic

    /**
     * Every track by this artist is already on the phone.
     *
     * False until the catalogue has been resolved once — the track ids simply aren't
     * known before that, and walking every album of every artist on the off-chance
     * the user might tap Download would be a round-trip per album per screen.
     */
    val allDownloaded: StateFlow<Boolean> =
        combine(_catalogue, downloads.downloads) { tracks, index ->
            tracks.isNotEmpty() && tracks.all { t -> index.any { it.id == t.itemId } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Download the artist's whole discography for offline. */
    fun downloadAll() {
        val sc = subsonic
        if (!isSubsonic || sc == null) { _toast.tryEmit("Only Navidrome albums can be downloaded"); return }
        viewModelScope.launch {
            val tracks = catalogue()
            if (tracks.isEmpty()) { _toast.tryEmit("No tracks for ${ref.name}"); return@launch }
            val pending = tracks.filterNot { downloads.isDownloaded(it.itemId) }
            if (pending.isEmpty()) { _toast.tryEmit("Already downloaded"); return@launch }
            _toast.tryEmit("Downloading ${pending.size} tracks…")
            val ok = downloads.downloadAll(pending, urlFor = { sc.downloadUrl(it.itemId) })
            _toast.tryEmit(
                when (ok) {
                    pending.size -> "Downloaded ${ref.name}"
                    0 -> "Download failed"
                    else -> "Downloaded $ok of ${pending.size}"
                }
            )
        }
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
